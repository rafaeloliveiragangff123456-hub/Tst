package com.botsync.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address

/**
 * Depuração por Wi-Fi (Android 11+) feita dentro do app: a pessoa liga a "Depuração sem fio", abre "Parear dispositivo com código"
 * e digita os 6 dígitos no app (campo do cartão ou notificação). O app pareia, conecta e mostra bateria, modelo etc.
 * A conexão dura 40 minutos e cai sozinha, ou ao tocar em Desconectar.
 * O Android não deixa um app ligar a depuração sem fio nem ler o código sozinho. Para desligar a depuração ao final,
 * o app concede a si mesmo, ao conectar, a permissão WRITE_SECURE_SETTINGS; sem ela, ele avisa e abre a tela certa.
 */
object WifiDebug {
    const val DURACAO_MS = 40L * 60L * 1000L
    const val ACAO_EXPIRA = "com.botsync.app.WIFI_ADB_EXPIRA"
    private const val CHAVE = "adb_wifi_enabled"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private const val CANAL = "botsync_wifi_adb"

    class Estado(
        val suportado: Boolean,
        val devOpcoes: Boolean,
        val ip: String?,
        val ligada: Boolean,
        val conectada: Boolean,
        val podeDesligar: Boolean,
        val restanteMs: Long
    )

    class Bateria(val pct: Int, val carregando: Boolean, val tempC: Float)

    private var guardiao: ContentObserver? = null

    fun suportado(): Boolean = Build.VERSION.SDK_INT >= 30

    private fun global(ctx: Context, nome: String): Boolean =
        try { Settings.Global.getInt(ctx.contentResolver, nome, 0) == 1 } catch (e: Exception) { false }

    fun ligada(ctx: Context): Boolean = suportado() && global(ctx, CHAVE)

    fun podeDesligar(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun ipWifi(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val rede = cm?.activeNetwork
        val cap = if (rede != null) cm.getNetworkCapabilities(rede) else null
        val lp = if (rede != null) cm.getLinkProperties(rede) else null
        if (cap != null && lp != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        else null
    } catch (e: Exception) { null }

    fun bateria(ctx: Context): Bateria {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivel = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val pct = if (nivel >= 0 && escala > 0) nivel * 100 / escala else -1
        val carregando = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return Bateria(pct, carregando, temp / 10f)
    }

    fun modelo(): String {
        val m = Build.MODEL ?: ""
        val f = Build.MANUFACTURER ?: ""
        return if (m.startsWith(f, true)) m else "$f $m".trim()
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun estado(ctx: Context): Estado {
        val fim = Sessao.wifiFim
        val resta = if (fim == 0L) 0L else fim - System.currentTimeMillis()
        return Estado(
            suportado(),
            global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            ipWifi(ctx),
            ligada(ctx),
            AdbWifi.fase == AdbWifi.Fase.CONECTADO && resta > 0,
            podeDesligar(ctx),
            resta
        )
    }

    /** Começa os 40 minutos quando o app conecta. */
    fun iniciarSessao(ctx: Context) {
        Sessao.wifiFim = System.currentTimeMillis() + DURACAO_MS
        agendar(ctx, Sessao.wifiFim)
    }

    /** Mantém a sessão coerente: encerra ao vencer, ao cair a conexão do app ou ao desligar a depuração sem fio. */
    fun sincronizar(ctx: Context) {
        if (!suportado()) return
        val fim = Sessao.wifiFim
        if (fim == 0L) return
        val f = AdbWifi.fase
        val vivo = f == AdbWifi.Fase.CONECTADO || f == AdbWifi.Fase.CONECTANDO || f == AdbWifi.Fase.PAREANDO
        if (System.currentTimeMillis() >= fim || !vivo || !ligada(ctx)) encerrar(ctx)
    }

    /** Fecha a conexão do app e desliga a depuração sem fio. Devolve false se não deu para desligar sozinho. */
    fun encerrar(ctx: Context): Boolean {
        AdbWifi.desconectar()
        val desligou = if (ligada(ctx)) desligar(ctx) else true
        limpar(ctx)
        return desligou
    }

    /** Desliga a depuração sem fio. Só funciona com WRITE_SECURE_SETTINGS (o app a concede a si mesmo ao conectar). */
    fun desligar(ctx: Context): Boolean {
        if (!podeDesligar(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, CHAVE, 0)
            true
        } catch (e: Exception) { false }
    }

    private fun limpar(ctx: Context) {
        Sessao.wifiFim = 0L
        try { ctx.getSystemService(AlarmManager::class.java).cancel(pendente(ctx)) } catch (e: Exception) { /* ok */ }
    }

    private fun pendente(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 41,
            Intent(ctx, WifiAdbReceiver::class.java).setAction(ACAO_EXPIRA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun agendar(ctx: Context, quando: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pendente(ctx))
        }
    }

    /** Chamado pelo alarme quando os 40 minutos acabam (app aberto ou fechado). Bloqueia: roda fora da thread principal. */
    fun expirou(ctx: Context) {
        val fim = Sessao.wifiFim
        if (fim > System.currentTimeMillis() + 5000) { agendar(ctx, fim); return }
        AdbWifi.desconectarAgora()
        val desligou = if (ligada(ctx)) desligar(ctx) else true
        limpar(ctx)
        if (!desligou) avisarEsgotado(ctx)
    }

    private fun avisarEsgotado(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Depuração por Wi-Fi", NotificationManager.IMPORTANCE_HIGH))
            val abrir = PendingIntent.getActivity(
                ctx, 42,
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Depuração por Wi-Fi: 40 minutos acabaram")
                .setContentText("A conexão do app foi encerrada. Toque para desligar a Depuração sem fio.")
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            nm.notify(4102, n)
        } catch (e: Exception) { /* sem permissão de notificação */ }
    }

    fun registrar(ctx: Context, r: BroadcastReceiver, f: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)

    fun soltar(ctx: Context, r: BroadcastReceiver?) {
        if (r != null) try { ctx.unregisterReceiver(r) } catch (e: Exception) { /* já solto */ }
    }

    /** Fica de olho na depuração enquanto o app está aberto: a sessão e a opção "manter a tela ligada" valem em qualquer tela. */
    fun ligarGuardiao(a: Activity) {
        soltarGuardiao(a)
        if (!suportado()) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { sincronizar(a); a.manterTelaAgora() }
        }
        guardiao = o
        try { a.contentResolver.registerContentObserver(Settings.Global.getUriFor(CHAVE), false, o) } catch (e: Exception) { /* ok */ }
        sincronizar(a)
        a.manterTelaAgora()
    }

    fun soltarGuardiao(a: Activity) {
        val o = guardiao
        if (o != null) try { a.contentResolver.unregisterContentObserver(o) } catch (e: Exception) { /* ok */ }
        guardiao = null
    }
}

/** Recebe o alarme dos 40 minutos. exported=false no manifesto: só o próprio app dispara. */
class WifiAdbReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent == null || intent.action != WifiDebug.ACAO_EXPIRA) return
        val app = ctx.applicationContext
        val p = goAsync()
        Thread {
            try { WifiDebug.expirou(app) } finally { p.finish() }
        }.start()
    }
}

/** Tela não apaga enquanto a depuração por Wi-Fi estiver ativa (se a pessoa ligou a opção). */
fun Activity.manterTelaAgora() {
    val ligar = Sessao.manterTela && WifiDebug.ligada(this)
    if (ligar) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private fun Context.chaveTela(ligado: Boolean, acao: (Boolean) -> Unit): FrameLayout {
    val f = FrameLayout(this)
    f.background = if (ligado) gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 16) else fundo(Cor.SURFACE2, 16, Cor.LINE)
    val bola = View(this)
    val g = android.graphics.drawable.GradientDrawable()
    g.shape = android.graphics.drawable.GradientDrawable.OVAL
    g.setColor(if (ligado) 0xFFFFFFFF.toInt() else Cor.MUTED)
    bola.background = g
    val lp = FrameLayout.LayoutParams(dp(22), dp(22), (if (ligado) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
    lp.leftMargin = dp(4)
    lp.rightMargin = dp(4)
    f.addView(bola, lp)
    f.layoutParams = LinearLayout.LayoutParams(dp(52), dp(30))
    f.isClickable = true
    f.setOnClickListener { acao(!ligado) }
    return f
}

/** Cartão "Depuração por Wi-Fi" da tela de Configuração. Atualiza sozinho quando algo muda. */
fun Activity.cartaoWifi(): View {
    val act = this
    val card = cartao()
    val dinamico = coluna()
    card.addView(dinamico, LinearLayout.LayoutParams(-1, -2))

    val tela = linha()
    val txtTela = coluna()
    txtTela.addView(texto("Manter a tela ligada", 15f, Cor.FG, true))
    txtTela.add(texto("Evita que a tela apague sozinha enquanto a depuração por Wi-Fi estiver ativa.", 12f, Cor.MUTED), 2)
    val lpTxt = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    lpTxt.rightMargin = dp(12)
    tela.addView(txtTela, lpTxt)
    val caixaChave = LinearLayout(this)
    tela.addView(caixaChave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    card.add(tela, 18)

    // chave "parear com outro aparelho" (longe, diferente deste)
    val remotoRow = linha()
    val remotoTxt = coluna()
    remotoTxt.addView(texto("Parear com outro aparelho", 15f, Cor.FG, true))
    remotoTxt.add(texto("Para um aparelho diferente deste, longe: peça para a pessoa deixar a tela de pareamento aberta e informe o IP e as portas que aparecem nela.", 12f, Cor.MUTED), 2)
    val lpRemotoTxt = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    lpRemotoTxt.rightMargin = dp(12)
    remotoRow.addView(remotoTxt, lpRemotoTxt)
    val caixaRemoto = LinearLayout(this)
    remotoRow.addView(caixaRemoto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    card.add(remotoRow, 18)

    var remoto = false

    fun montarChave() {
        caixaChave.removeAllViews()
        caixaChave.addView(chaveTela(Sessao.manterTela) { novo ->
            Sessao.manterTela = novo
            act.manterTelaAgora()
            montarChave()
        })
    }

    fun campoTexto(dica: String, numerico: Boolean = false, max: Int? = null): EditText {
        val e = EditText(this)
        e.hint = dica
        e.setHintTextColor(Cor.MUTED)
        e.setTextColor(Cor.FG)
        e.textSize = 15f
        if (numerico) e.inputType = InputType.TYPE_CLASS_NUMBER
        if (max != null) e.filters = arrayOf(InputFilter.LengthFilter(max))
        e.background = fundo(Cor.SURFACE2, 12, Cor.LINE)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        return e
    }

    // campos do pareamento remoto: criados uma vez, para não perder o que a pessoa digitou ao redesenhar
    val campoIp = campoTexto("IP do outro aparelho (ex.: 192.168.0.12)")
    val campoPortaParear = campoTexto("Porta de pareamento", numerico = true, max = 5)
    val campoPortaConectar = campoTexto("Porta de conexão", numerico = true, max = 5)

    // campo do código: criado uma vez, para não perder o que a pessoa digitou quando o cartão se redesenha
    val campo = EditText(this)
    campo.hint = "Código de 6 dígitos"
    campo.setHintTextColor(Cor.MUTED)
    campo.setTextColor(Cor.FG)
    campo.textSize = 18f
    campo.gravity = Gravity.CENTER
    campo.inputType = InputType.TYPE_CLASS_NUMBER
    campo.filters = arrayOf(InputFilter.LengthFilter(6))
    campo.background = fundo(Cor.SURFACE2, 12, Cor.LINE)
    campo.setPadding(dp(14), dp(12), dp(14), dp(12))

    fun abrir(acao: String) {
        try { startActivity(Intent(acao)) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) { /* sem tela de ajustes */ } }
    }

    /** Abre direto a tela "Depuração sem fio". Se o aparelho não tiver essa tela, abre as Opções do desenvolvedor. */
    fun abrirSemFio() {
        try { startActivity(Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity")) }
        catch (e: Exception) { abrir(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    }

    fun passo(ok: Boolean, t: String): View {
        val l = linha()
        l.addView(icone(if (ok) R.drawable.ic_certo else R.drawable.ic_ponto, if (ok) Cor.OK else Cor.MUTED), LinearLayout.LayoutParams(dp(16), dp(16)))
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(10)
        l.addView(texto(t, 13f, if (ok) Cor.FG else Cor.MUTED), lp)
        return l
    }

    fun celula(rot: String, valor: String, esq: Int, dir: Int): View {
        val v = estatistica(rot, valor)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(esq)
        lp.rightMargin = dp(dir)
        v.layoutParams = lp
        return v
    }

    fun pedirNotificacao() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    var tvTempo: TextView? = null
    var antes = false

    fun montar() {
        dinamico.removeAllViews()
        (campo.parent as? ViewGroup)?.removeView(campo)
        (campoIp.parent as? ViewGroup)?.removeView(campoIp)
        (campoPortaParear.parent as? ViewGroup)?.removeView(campoPortaParear)
        (campoPortaConectar.parent as? ViewGroup)?.removeView(campoPortaConectar)
        tvTempo = null
        WifiDebug.sincronizar(act)
        val e = WifiDebug.estado(act)
        val fase = AdbWifi.fase
        val ocupado = fase == AdbWifi.Fase.PAREANDO || fase == AdbWifi.Fase.CONECTANDO
        val (rot, tom) = when {
            !e.suportado -> Pair("Indisponível", "warn")
            e.conectada -> Pair("Conectado", "ok")
            ocupado -> Pair("Conectando…", "warn")
            fase == AdbWifi.Fase.ERRO -> Pair("Falhou", "bad")
            e.ligada -> Pair("Pronta para parear", "warn")
            else -> Pair("Desativada", "warn")
        }
        val topo = linha()
        topo.addView(caixaIcone(R.drawable.ic_terminal, 42, if (e.conectada) Cor.OK else Cor.ACCENT_TXT, if (e.conectada) Cor.OK_SOFT else Cor.ACCENT_SOFT))
        val col = coluna()
        col.addView(texto("Depuração por Wi-Fi", 16f, Cor.FG, true))
        val st = linha()
        st.addView(chip(rot, tom))
        if (e.conectada) {
            val t = texto("restam " + WifiDebug.fmt(e.restanteMs), 12f, Cor.MUTED)
            st.addWrap(t, 0, 8)
            tvTempo = t
        }
        col.add(st, 4)
        val lpc = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpc.leftMargin = dp(12)
        topo.addView(col, lpc)
        dinamico.add(topo)

        if (e.conectada) {
            val b = WifiDebug.bateria(act)
            val bat = (if (b.pct >= 0) "${b.pct}%" else "—") + (if (b.carregando) " (carregando)" else "")
            val l1 = linha()
            l1.addView(celula("Modelo", WifiDebug.modelo(), 0, 6))
            l1.addView(celula("Android", Build.VERSION.RELEASE ?: "—", 6, 0))
            dinamico.add(l1, 16)
            val l2 = linha()
            l2.addView(celula("Bateria", bat, 0, 6))
            l2.addView(celula("Temperatura", "%.1f °C".format(b.tempC), 6, 0))
            dinamico.add(l2, 10)
            val l3 = linha()
            l3.addView(celula("IP do celular", e.ip ?: "sem Wi-Fi", 0, 0))
            dinamico.add(l3, 10)
        }

        if (e.suportado) {
            dinamico.add(passo(e.devOpcoes, "Opções do desenvolvedor ativadas"), 16)
            dinamico.add(passo(e.ip != null, "Celular conectado ao Wi-Fi"), 8)
            dinamico.add(passo(e.ligada, "Depuração sem fio ligada"), 8)
            dinamico.add(passo(e.conectada, "App pareado e conectado"), 8)
            dinamico.add(passo(e.podeDesligar, "Desligar sozinho liberado (automático ao conectar)"), 8)
        }

        val dica = when {
            !e.suportado -> "A depuração por Wi-Fi precisa do Android 11 ou mais novo."
            fase == AdbWifi.Fase.ERRO || ocupado -> AdbWifi.mensagem
            e.conectada -> "Tudo certo: o app está conectado por 40 minutos e desconecta sozinho."
            remoto -> "No OUTRO celular: abra \"Depuração sem fio\" e \"Parear dispositivo com código\". Informe abaixo o IP e a porta de pareamento mostrados lá, o código de 6 dígitos, e a porta de conexão (do topo da tela \"Depuração sem fio\" dele). Os dois aparelhos precisam conseguir se enxergar na rede."
            !e.devOpcoes -> "Primeiro libere as Opções do desenvolvedor: em Sobre o telefone, toque 7 vezes em Número da versão."
            e.ip == null -> "Conecte o celular a uma rede Wi-Fi."
            !e.ligada -> "Toque em \"Abrir Depuração sem fio\" e ligue a chave. Depois toque em \"Parear dispositivo com código de pareamento\": o código de 6 dígitos aparece ali."
            else -> "Digite abaixo o código de 6 dígitos da tela \"Parear dispositivo com código\". Com a tela de pareamento aberta, use \"Digitar código pela notificação\" (ou tela dividida)."
        }
        if (dica.isNotEmpty()) dinamico.add(texto(dica, 13f, if (fase == AdbWifi.Fase.ERRO) Cor.BAD else Cor.MUTED), 14)

        if (!e.suportado) {
            // nada para abrir
        } else if (e.conectada) {
            dinamico.add(botaoAcao("Desconectar agora", R.drawable.ic_fechar, true) {
                if (WifiDebug.encerrar(act)) {
                    Toast.makeText(act, "Depuração por Wi-Fi desconectada.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(act, "Desconectado. Desligue a \"Depuração sem fio\" nesta tela.", Toast.LENGTH_LONG).show()
                    abrir(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
                act.manterTelaAgora()
                montar()
            }, 14)
        } else if (remoto && !ocupado) {
            dinamico.add(campoIp, 14)
            val lp = linha()
            lp.addView(campoPortaParear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            lp.addView(campoPortaConectar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            dinamico.add(lp, 10)
            dinamico.add(campo, 10)
            dinamico.add(botaoAcao("Parear e conectar (remoto)", R.drawable.ic_cadeado, true) {
                val c = campo.text.toString().trim()
                val portaP = campoPortaParear.text.toString().trim().toIntOrNull() ?: 0
                val portaC = campoPortaConectar.text.toString().trim().toIntOrNull() ?: 0
                AdbWifi.parearEConectarRemoto(act, campoIp.text.toString(), portaP, c, portaC)
            }, 10)
            dinamico.add(botaoAcao("Já pareado antes: conectar (remoto)", R.drawable.ic_raio, false) {
                val portaC = campoPortaConectar.text.toString().trim().toIntOrNull() ?: 0
                AdbWifi.conectarRemoto(act, campoIp.text.toString(), portaC)
            }, 10)
        } else if (!e.devOpcoes) {
            dinamico.add(botaoAcao("Abrir Sobre o telefone", R.drawable.ic_config, true) { abrir(Settings.ACTION_DEVICE_INFO_SETTINGS) }, 14)
        } else if (!e.ligada) {
            dinamico.add(botaoAcao("Abrir Depuração sem fio", R.drawable.ic_terminal, true) { abrirSemFio() }, 14)
        } else if (!ocupado) {
            dinamico.add(campo, 14)
            dinamico.add(botaoAcao("Parear e conectar", R.drawable.ic_cadeado, true) {
                val c = campo.text.toString().trim()
                if (c.length != 6) Toast.makeText(act, "O código tem 6 dígitos.", Toast.LENGTH_SHORT).show()
                else AdbWifi.parearEConectar(act, c)
            }, 10)
            dinamico.add(botaoAcao("Digitar código pela notificação", R.drawable.ic_chat, false) {
                pedirNotificacao()
                AdbWifi.notificarPareamento(act)
                abrirSemFio()
            }, 10)
            dinamico.add(botaoAcao("Já pareado antes: conectar", R.drawable.ic_raio, false) { AdbWifi.conectar(act) }, 10)
        }

        if (e.conectada && Sessao.antiGravacao) {
            dinamico.add(texto("Anti-gravação está ativado: espelhar a tela do celular mostra tela preta. Desative acima se quiser espelhar.", 12f, Cor.WARN), 12)
        }
        val aviso = linha()
        aviso.addView(icone(R.drawable.ic_alerta, Cor.WARN), LinearLayout.LayoutParams(dp(16), dp(16)))
        val lpa = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpa.leftMargin = dp(8)
        aviso.addView(texto("O código vale só uma vez e a conexão cai em 40 minutos. Desligue a Depuração sem fio quando terminar.", 12f, Cor.MUTED), lpa)
        dinamico.add(aviso, 12)

        if (antes && !e.conectada) Toast.makeText(act, "Depuração por Wi-Fi desconectada.", Toast.LENGTH_SHORT).show()
        antes = e.conectada
    }

    fun montarChaveRemoto() {
        caixaRemoto.removeAllViews()
        caixaRemoto.addView(chaveTela(remoto) { novo -> remoto = novo; montarChaveRemoto(); montar() })
    }

    val handler = Handler(Looper.getMainLooper())
    val ouvinteAdb: () -> Unit = { montar(); act.manterTelaAgora() }
    val tick = object : Runnable {
        override fun run() {
            val e = WifiDebug.estado(act)
            if (e.conectada) tvTempo?.text = "restam " + WifiDebug.fmt(e.restanteMs)
            else if (tvTempo != null) montar()
            handler.postDelayed(this, 1000)
        }
    }
    val recBateria = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (tvTempo != null) montar() }
    }
    val observador = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { montar(); act.manterTelaAgora() }
    }
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            WifiDebug.registrar(act, recBateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            AdbWifi.observar(ouvinteAdb)
            try {
                contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observador)
                contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, observador)
            } catch (e: Exception) { /* sem observador: o cartão ainda atualiza pelo relógio */ }
            montar()
            handler.postDelayed(tick, 1000)
        }
        override fun onViewDetachedFromWindow(v: View) {
            WifiDebug.soltar(act, recBateria)
            AdbWifi.parar(ouvinteAdb)
            try { contentResolver.unregisterContentObserver(observador) } catch (e: Exception) { /* ok */ }
            handler.removeCallbacks(tick)
        }
    })
    montarChave()
    montarChaveRemoto()
    montar()
    return card
}
