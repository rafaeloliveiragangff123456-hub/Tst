package com.botsync.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Anti Cheat (Free Fire). Por enquanto analisa só os aplicativos instalados e a ORIGEM de cada um
 * (quem instalou: Play Store, loja do fabricante, instalador de APK, ADB...). A leitura vem do próprio
 * aparelho pela conexão ADB que o app já faz em Configuração (shell: pm list packages -3 -i).
 */
object AntiCheat {
    const val OK = 0
    const val ALERTA = 1
    const val SUSPEITO = 2

    class Item(val pacote: String, val nome: String, val instalador: String?, val origem: String, val risco: Int)

    /** Lojas oficiais (aplicativo vindo de uma delas = origem confiável). */
    private val lojas = mapOf(
        "com.android.vending" to "Google Play",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.xiaomi.mipicks" to "Xiaomi GetApps",
        "com.xiaomi.market" to "Xiaomi Market",
        "com.huawei.appmarket" to "Huawei AppGallery",
        "com.heytap.market" to "HeyTap / OPPO",
        "com.oppo.market" to "OPPO Store",
        "com.vivo.appstore" to "vivo V-Appstore",
        "com.amazon.venezia" to "Amazon Appstore"
    )

    private val instaladoresDeApk = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    fun classificar(instalador: String?): Triple<String, Int, String?> {
        val i = instalador?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return when {
            i == null -> Triple("Origem desconhecida (APK solto)", SUSPEITO, null)
            lojas.containsKey(i) -> Triple(lojas.getValue(i), OK, i)
            i == "com.android.shell" -> Triple("Instalado por ADB", SUSPEITO, i)
            i in instaladoresDeApk -> Triple("Instalado de arquivo APK", SUSPEITO, i)
            else -> Triple("Loja não oficial ($i)", ALERTA, i)
        }
    }

    /** Roda um comando no shell do aparelho. O ADB fecha o canal ao terminar: isso não é erro, vale o que já chegou. */
    private fun executar(m: BotAdb, comando: String): String {
        val s = m.openStream("shell:$comando")
        val out = java.io.ByteArrayOutputStream()
        try {
            val entrada = s.openInputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = entrada.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (e: java.io.IOException) {
            // "Stream closed": o aparelho terminou de responder
        } finally {
            try { s.close() } catch (e: Exception) { /* já fechou */ }
        }
        return String(out.toByteArray())
    }

    private fun nomeDe(ctx: Context, pacote: String): String =
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pacote, 0)).toString() } catch (e: Exception) { pacote }

    @Suppress("DEPRECATION")
    private fun instaladorDe(ctx: Context, pacote: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pacote).installingPackageName
        else ctx.packageManager.getInstallerPackageName(pacote)
    } catch (e: Exception) { null }

    /** Lê os apps instalados pelo usuário e a origem de cada um. Bloqueia: chame fora da thread principal. */
    fun ler(ctx: Context): List<Item> {
        val m = BotAdb.existente() ?: throw Exception("O ADB não está conectado.")
        val lista = ArrayList<Item>()

        // 1) pelo ADB: pacote + instalador numa tacada só (tenta até 3 vezes)
        var saida = ""
        for (t in 1..3) {
            saida = try { executar(m, "pm list packages -3 -i") } catch (e: Exception) { "" }
            if (saida.contains("package:")) break
            Thread.sleep(600)
        }
        val regex = Regex("^package:(\\S+)\\s+installer=(\\S*)")
        for (linha in saida.lineSequence()) {
            val r = regex.find(linha.trim()) ?: continue
            val pacote = r.groupValues[1]
            if (pacote == ctx.packageName) continue
            val (origem, risco, inst) = classificar(r.groupValues[2])
            lista.add(Item(pacote, nomeDe(ctx, pacote), inst, origem, risco))
        }

        // 2) plano B: só a lista de pacotes pelo ADB e a origem lida pelo sistema do aparelho
        if (lista.isEmpty()) {
            var so = ""
            for (t in 1..2) {
                so = try { executar(m, "pm list packages -3") } catch (e: Exception) { "" }
                if (so.contains("package:")) break
                Thread.sleep(600)
            }
            for (linha in so.lineSequence()) {
                val pacote = linha.trim().removePrefix("package:").trim()
                if (!linha.trim().startsWith("package:") || pacote.isEmpty() || pacote == ctx.packageName) continue
                val (origem, risco, inst) = classificar(instaladorDe(ctx, pacote))
                lista.add(Item(pacote, nomeDe(ctx, pacote), inst, origem, risco))
            }
        }

        if (lista.isEmpty()) throw Exception("o aparelho não devolveu a lista de apps. Reconecte o ADB em Configuração e tente de novo.")
        return lista.sortedWith(compareByDescending<Item> { it.risco }.thenBy { it.nome.lowercase() })
    }
}

/** Estado do chat do Anti Cheat. Fica fora da tela: a análise continua se a pessoa trocar de aba. */
object AntiCheatEstado {
    const val TEXTO = 0
    const val LISTA_SIMPLES = 1
    const val LISTA_DETALHE = 2
    const val RESUMO = 3

    class Msg(val tipo: Int, val texto: String, val itens: List<AntiCheat.Item> = emptyList(), val extra: Int = 0)

    val msgs = mutableListOf<Msg>()
    var rodando = false
    var digitando = false
    var ouvinte: (() -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var geracao = 0

    fun limpar() {
        geracao++
        msgs.clear(); rodando = false; digitando = false
        ouvinte?.invoke()
    }

    private fun dizer(m: Msg, g: Int) {
        ui.post { if (g == geracao) { digitando = false; msgs.add(m); ouvinte?.invoke() } }
    }

    private fun pensando(g: Int) {
        ui.post { if (g == geracao) { digitando = true; ouvinte?.invoke() } }
    }

    fun iniciar(ctx: Context) {
        if (rodando) return
        val app = ctx.applicationContext
        msgs.clear()
        rodando = true
        val g = ++geracao
        ouvinte?.invoke()
        Thread {
            try {
                pensando(g); Thread.sleep(700)
                dizer(Msg(TEXTO, "ADB conectado. Vou analisar o ${WifiDebug.modelo()} para o Free Fire."), g)
                pensando(g); Thread.sleep(900)
                dizer(Msg(TEXTO, "Primeiro, os aplicativos instalados por você…"), g)
                pensando(g)
                val itens = AntiCheat.ler(app)
                Thread.sleep(700)
                dizer(Msg(TEXTO, "Encontrei ${itens.size} aplicativos instalados:"), g)
                dizer(Msg(LISTA_SIMPLES, "", itens), g)
                pensando(g); Thread.sleep(1100)
                dizer(Msg(TEXTO, "Agora vou checar a origem de cada um (quem instalou)…"), g)
                pensando(g); Thread.sleep(1400)
                val suspeitos = itens.filter { it.risco != AntiCheat.OK }
                if (suspeitos.isEmpty()) {
                    dizer(Msg(TEXTO, "Todos os aplicativos vieram de lojas oficiais."), g)
                } else {
                    dizer(Msg(TEXTO, "${suspeitos.size} de origem suspeita:"), g)
                    dizer(Msg(LISTA_DETALHE, "", suspeitos), g)
                }
                pensando(g); Thread.sleep(700)
                dizer(Msg(RESUMO, "", suspeitos, itens.size), g)
            } catch (e: Exception) {
                dizer(Msg(TEXTO, "Não consegui concluir a análise: " + (e.message ?: "falha na leitura do aparelho.")), g)
            } finally {
                ui.post { if (g == geracao) { rodando = false; digitando = false; ouvinte?.invoke() } }
            }
        }.start()
    }
}

// ── tela ──────────────────────────────────────────────────────────────────────
private fun Context.balao(conteudo: View): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.TOP
    l.addView(caixaIcone(R.drawable.ic_anticheat, 34))
    val b = coluna()
    b.setPadding(dp(14), dp(12), dp(14), dp(12))
    b.background = fundo(Cor.SURFACE, 14, Cor.LINE)
    b.addView(conteudo, LinearLayout.LayoutParams(-1, -2))
    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    lp.leftMargin = dp(8)
    l.addView(b, lp)
    return l
}

/** Abre o app pelo pacote (o mesmo que o usuário usaria, não via ADB). */
private fun Context.abrirApp(pacote: String) {
    val intent = packageManager.getLaunchIntentForPackage(pacote)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } else {
        Toast.makeText(this, "Não consegui abrir esse aplicativo.", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.botaoAbrir(pacote: String): FrameLayout {
    val b = botaoIcone(R.drawable.ic_entrar) { abrirApp(pacote) }
    (b.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = dp(34); it.height = dp(34); it.leftMargin = dp(8) }
    return b
}

private fun tomDe(risco: Int) = when (risco) {
    AntiCheat.OK -> "ok"
    AntiCheat.ALERTA -> "warn"
    else -> "bad"
}

private fun corDe(risco: Int) = when (risco) {
    AntiCheat.OK -> Cor.OK
    AntiCheat.ALERTA -> Cor.WARN
    else -> Cor.BAD
}

private fun Context.listaSimples(itens: List<AntiCheat.Item>): View {
    val c = coluna()
    for ((n, it) in itens.withIndex()) {
        val l = linha()
        l.addView(icone(R.drawable.ic_ponto, corDe(it.risco)), LinearLayout.LayoutParams(dp(12), dp(12)))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(8)
        l.addView(texto(it.nome, 13f, Cor.FG), lp)
        l.addView(botaoAbrir(it.pacote))
        c.add(l, if (n == 0) 0 else 6)
    }
    return c
}

private fun Context.listaDetalhe(itens: List<AntiCheat.Item>): View {
    val c = coluna()
    for ((n, it) in itens.withIndex()) {
        val caixa = coluna()
        caixa.setPadding(dp(12), dp(10), dp(12), dp(10))
        caixa.background = fundo(Cor.SURFACE2, 12, Cor.LINE)
        val topo = linha()
        val txtCol = coluna()
        txtCol.addView(texto(it.nome, 14f, Cor.FG, true))
        txtCol.add(texto(it.pacote, 11f, Cor.MUTED), 2)
        topo.addView(txtCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topo.addView(botaoAbrir(it.pacote))
        caixa.addView(topo)
        val l = linha()
        l.addView(chip(it.origem, tomDe(it.risco)))
        caixa.add(l, 8)
        c.add(caixa, if (n == 0) 0 else 8)
    }
    return c
}

private fun Context.resumo(suspeitos: List<AntiCheat.Item>, total: Int): View {
    val c = coluna()
    val ruins = suspeitos.count { it.risco == AntiCheat.SUSPEITO }
    val avisos = suspeitos.count { it.risco == AntiCheat.ALERTA }
    c.addView(texto("Análise concluída", 15f, Cor.FG, true))
    val l = linha()
    fun cel(rot: String, v: String, esq: Int, dir: Int): View {
        val e = estatistica(rot, v)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(esq); lp.rightMargin = dp(dir)
        e.layoutParams = lp
        return e
    }
    l.addView(cel("Analisados", total.toString(), 0, 4))
    l.addView(cel("Suspeitos", ruins.toString(), 4, 4))
    l.addView(cel("Atenção", avisos.toString(), 4, 0))
    c.add(l, 10)
    val tom = if (ruins > 0) Cor.BAD else if (avisos > 0) Cor.WARN else Cor.OK
    val msg = when {
        ruins > 0 -> "Há apps instalados fora das lojas oficiais. Confira se algum deles interfere no Free Fire."
        avisos > 0 -> "Alguns apps vieram de lojas não oficiais. Vale conferir."
        else -> "Nenhuma origem suspeita encontrada nos aplicativos."
    }
    c.add(texto(msg, 13f, tom), 10)
    return c
}

/** Aba Anti Cheat: com o ADB conectado, a análise aparece em formato de chat. */
fun Activity.telaAntiCheat(irConfig: () -> Unit): View {
    val act = this
    val c = coluna()
    val cab = linha()
    val tit = coluna()
    tit.addView(texto("Anti Cheat", 19f, Cor.FG, true))
    tit.addView(rotulo("Free Fire"))
    cab.addView(tit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    cab.addView(caixaIcone(R.drawable.ic_anticheat, 42))
    c.add(cab)

    val chat = coluna()
    c.add(chat, 20)

    val sv = ScrollView(this)
    sv.isFillViewport = true
    c.setPadding(dp(16), dp(28), dp(16), dp(24))
    sv.addView(c, FrameLayout.LayoutParams(-1, -2))
    sv.background = brilhoFundo()

    fun desenhar() {
        chat.removeAllViews()
        val e = WifiDebug.estado(act)
        val est = AntiCheatEstado
        if (!e.conectada && est.msgs.isEmpty()) {
            chat.add(balao(texto("Conecte o ADB para eu analisar o seu celular.", 14f, Cor.FG)))
            chat.add(balao(texto("Em Configuração, ligue a Depuração por Wi-Fi, pareie com o código e volte aqui: a análise começa sozinha.", 13f, Cor.MUTED)), 10)
            chat.add(botaoAcao("Ir para Configuração", R.drawable.ic_config, true) { irConfig() }, 16)
            return
        }
        for (m in est.msgs) {
            val v: View = when (m.tipo) {
                AntiCheatEstado.LISTA_SIMPLES -> listaSimples(m.itens)
                AntiCheatEstado.LISTA_DETALHE -> listaDetalhe(m.itens)
                AntiCheatEstado.RESUMO -> resumo(m.itens, m.extra)
                else -> texto(m.texto, 14f, Cor.FG)
            }
            chat.add(balao(v), 10)
        }
        if (est.digitando) chat.add(balao(texto("analisando…", 13f, Cor.MUTED)), 10)
        if (!est.rodando && est.msgs.isNotEmpty() && e.conectada) {
            chat.add(botaoAcao("Analisar de novo", R.drawable.ic_atualizar, false) { est.iniciar(act) }, 16)
        }
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    fun verificar() {
        val e = WifiDebug.estado(act)
        val est = AntiCheatEstado
        if (e.conectada && est.msgs.isEmpty() && !est.rodando) est.iniciar(act)
        else if (!e.conectada && !est.rodando && est.msgs.isNotEmpty()) est.limpar()   // ADB caiu: some a análise antiga
        desenhar()
    }

    val ouv: () -> Unit = { verificar() }
    sv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            AdbWifi.observar(ouv)
            AntiCheatEstado.ouvinte = { desenhar() }
            verificar()
        }
        override fun onViewDetachedFromWindow(v: View) {
            AdbWifi.parar(ouv)
            AntiCheatEstado.ouvinte = null
        }
    })
    desenhar()
    return sv
}
