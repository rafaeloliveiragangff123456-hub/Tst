package com.botsync.app

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.gms.auth.GoogleAuthUtil
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val REQ_CONTA = 11
    private val REQ_AUTH = 12
    private val REQ_NOTIF = 13

    private lateinit var raiz: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rnd = SecureRandom()

    private var contaAtual: JSONObject? = null     // conta aberta na tela do IMAP v2
    private var gmailEscolhido: String? = null
    private var statusRunnable: Runnable? = null
    private var aguardandoPermissao = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // impede captura de tela, gravação e miniatura em "recentes"
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        raiz = FrameLayout(this)
        raiz.setBackgroundColor(Cor.BG)
        setContentView(raiz)
        val bloqueio = Seguranca.verificar(this)
        if (bloqueio != null) {
            mostrar(telaBloqueio(bloqueio))
            return
        }
        if (intent?.data != null) tratarLink(intent) else inicio()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (Seguranca.verificar(this) != null) return
        if (intent?.data != null) tratarLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Seguranca.verificar(this) != null) return
        if (aguardandoPermissao) {
            aguardandoPermissao = false
            if (Settings.canDrawOverlays(this)) {
                if (Sessao.imapAtivo) Imap2Service.iniciar(this)      // reaplica para a bolinha aparecer
                pedirBateria()
            }
        }
        if (contaAtual != null) iniciarStatus()
    }

    override fun onPause() {
        super.onPause()
        pararStatus()
    }

    // ── utilidades ────────────────────────────────────────────────────────────
    private fun mostrar(v: View) {
        pararStatus()
        raiz.removeAllViews()
        raiz.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    private fun rolagem(conteudo: LinearLayout): ScrollView {
        val s = ScrollView(this)
        s.isFillViewport = true
        conteudo.setPadding(dp(16), dp(28), dp(16), dp(40))
        s.addView(conteudo, FrameLayout.LayoutParams(-1, -2))
        return s
    }

    private fun trabalhar(tarefa: () -> Unit) {
        io.execute {
            try {
                tarefa()
            } catch (e: Throwable) {
                ui.post { erroGeral(e) }
            }
        }
    }

    private fun erroGeral(e: Throwable) {
        if (e is ApiErro && e.codigo == 401 && !Sessao.logado()) {
            Imap2Service.parar(this)
            mostrar(telaLogin("Sessão expirada. Entre novamente."))
            return
        }
        val msg = when (e) {
            is ApiErro -> e.message ?: "Erro"
            is IOException -> "Sem conexão com o servidor. Verifique a internet e tente de novo."
            else -> "Algo deu errado. Tente novamente."
        }
        val c = coluna()
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.add(texto("Não foi possível continuar", 20f, Cor.FG, true), 40)
        c.add(texto(msg, 15f, Cor.MUTED), 12)
        c.add(botao("Tentar de novo", true) { inicio() }, 20)
        if (Sessao.logado()) c.add(botao("Sair deste aparelho", false) { sair() }, 10)
        mostrar(rolagem(c))
    }

    private fun carregando(t: String): View {
        val c = coluna()
        c.gravity = Gravity.CENTER
        c.add(texto(t, 16f, Cor.MUTED), 0)
        return c
    }

    private fun inicio() {
        if (!Sessao.logado()) {
            mostrar(telaLogin(null))
            return
        }
        carregarHome()
    }

    // ── bloqueio de segurança ─────────────────────────────────────────────────
    private fun telaBloqueio(motivo: String): View {
        val c = coluna()
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.add(texto("Ambiente não seguro", 22f, Cor.BAD, true), 60)
        c.add(texto(motivo, 15f, Cor.MUTED), 14)
        c.add(texto("Por proteção das suas contas, nenhum dado é carregado.", 13f, Cor.MUTED), 10)
        return rolagem(c)
    }

    // ── login (Discord + PKCE) ────────────────────────────────────────────────
    private fun telaLogin(aviso: String?): View {
        val c = coluna()
        c.gravity = Gravity.CENTER_HORIZONTAL
        val marca = texto("B", 26f, 0xFFFFFFFF.toInt(), true)
        marca.gravity = Gravity.CENTER
        marca.background = fundo(Cor.ACCENT, 16)
        val lp = LinearLayout.LayoutParams(dp(72), dp(72))
        lp.topMargin = dp(70)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(marca, lp)
        val t = texto("BotSync System", 26f, Cor.FG, true)
        t.gravity = Gravity.CENTER
        c.add(t, 18)
        val s = texto("Pagamentos em tempo real, configurações e IMAP v2 no seu celular.", 14f, Cor.MUTED)
        s.gravity = Gravity.CENTER
        c.add(s, 8)
        if (aviso != null) c.add(texto(aviso, 14f, Cor.WARN), 18)
        if (BuildConfig.SERVER_URL.contains("CONFIGURE")) {
            c.add(texto("Este APK foi gerado sem a URL do servidor (BOTSYNC_URL). Gere de novo pelo GitHub Actions.", 14f, Cor.BAD), 18)
        } else {
            c.add(botao("Entrar com Discord", true) { iniciarLogin() }, 32)
            c.add(texto("O login abre no navegador. A senha do Discord nunca passa pelo aplicativo.", 12f, Cor.MUTED), 14)
        }
        return rolagem(c)
    }

    private fun b64u(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun iniciarLogin() {
        val verifier = b64u(ByteArray(32).also { rnd.nextBytes(it) })
        val state = b64u(ByteArray(18).also { rnd.nextBytes(it) })
        val desafio = b64u(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        Sessao.pkceVerifier = verifier
        Sessao.pkceState = state
        Sessao.pkceExpira = System.currentTimeMillis() + 10 * 60_000L
        val url = BuildConfig.SERVER_URL + "/app/auth/start?c=" + desafio + "&s=" + state
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_LONG).show()
        }
    }

    private fun tratarLink(i: Intent?) {
        val u = i?.data
        if (u == null || u.scheme != "botsyncapp" || u.host != "auth") {
            inicio()
            return
        }
        val code = u.getQueryParameter("code")
        val state = u.getQueryParameter("state")
        val verifier = Sessao.pkceVerifier
        val esperado = Sessao.pkceState
        val valido = code != null && state != null && verifier != null && esperado != null &&
            state == esperado && System.currentTimeMillis() < Sessao.pkceExpira
        Sessao.pkceVerifier = null
        Sessao.pkceState = null
        if (!valido) {
            mostrar(telaLogin("Login inválido ou expirado. Tente de novo."))
            return
        }
        mostrar(carregando("Entrando…"))
        trabalhar {
            Api.trocarCodigo(code!!, verifier!!, Build.MODEL ?: "Android")
            ui.post { carregarHome() }
        }
    }

    private fun sair() {
        mostrar(carregando("Saindo…"))
        trabalhar {
            Api.sair()
            Sessao.sair()
            ui.post {
                Imap2Service.parar(this)
                mostrar(telaLogin(null))
            }
        }
    }

    // ── painel ────────────────────────────────────────────────────────────────
    private fun carregarHome() {
        contaAtual = null
        mostrar(carregando("Verificando plano e configurações no bot…"))
        trabalhar {
            val me = Api.me()
            ui.post { desenharHome(me) }
        }
    }

    private fun dataBR(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))
    private fun brl(centavos: Long): String = "R$ " + String.format(Locale("pt", "BR"), "%,.2f", centavos / 100.0)

    private fun desenharHome(me: JSONObject) {
        val c = coluna()
        val nome = me.optJSONObject("usuario")?.optString("nome") ?: ""
        val marca = texto("B", 14f, 0xFFFFFFFF.toInt(), true)
        marca.gravity = Gravity.CENTER
        marca.background = fundo(Cor.ACCENT, 9)
        val cab = linha(marca, texto("  BotSync", 18f, Cor.FG, true))
        (marca.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = dp(30); it.height = dp(30) }
        marca.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        c.add(cab)
        c.add(texto("Olá, $nome", 14f, Cor.MUTED), 4)

        // plano
        val plano = me.getJSONObject("plano")
        val ativo = plano.optBoolean("ativo")
        val pc = cartao()
        pc.add(linha(chip(if (ativo) "Plano ativo" else "Sem plano", if (ativo) "ok" else "bad")))
        val exp = if (plano.isNull("expira")) 0L else plano.optLong("expira", 0L)
        val txtPlano = when {
            plano.optBoolean("admin") -> "Acesso de administrador"
            ativo && exp > 0 -> (plano.optString("nome").ifEmpty { "Plano" }) + " · expira em " + dataBR(exp)
            else -> "Compre ou renove um plano no site para liberar o bot."
        }
        pc.add(texto(txtPlano, 14f, Cor.FG), 10)
        val bloqueio = if (me.isNull("bloqueio")) "" else me.optString("bloqueio")
        if (bloqueio.isNotEmpty()) pc.add(texto(bloqueio, 13f, Cor.WARN), 8)
        c.add(pc, 18)

        // contas
        val contas = me.getJSONArray("contas")
        if (contas.length() == 0) {
            val vc = cartao()
            vc.add(texto("Nenhuma conta conectada", 16f, Cor.FG, true))
            vc.add(texto("Conecte o token da sua conta pelo site; ela aparece aqui automaticamente.", 14f, Cor.MUTED), 6)
            c.add(vc, 14)
        }
        for (i in 0 until contas.length()) {
            val ct = contas.getJSONObject(i)
            val im = ct.getJSONObject("imap")
            val card = cartao()
            val modo = if (!im.optBoolean("configurado")) null else im.optString("modo")
            val tit = linha(texto(ct.optString("username"), 17f, Cor.FG, true))
            card.add(tit)
            val chips = linha(chip(if (ct.optBoolean("online")) "Conta online" else "Conta offline", if (ct.optBoolean("online")) "ok" else "bad"))
            val chipModo = chip(when (modo) { "v2" -> "IMAP v2"; "normal" -> "IMAP normal"; else -> "Sem IMAP" }, if (modo == null) "warn" else "")
            chips.addWrap(chipModo, 0, 8)
            card.add(chips, 10)

            // verificação do bot (plano + configurações), vinda da rota
            val lista = ct.getJSONArray("checklist")
            for (k in 0 until lista.length()) {
                val it = lista.getJSONObject(k)
                val ok = it.optBoolean("ok")
                val opcional = it.optString("k") == "meta"
                card.add(texto((if (ok) "✓  " else if (opcional) "•  " else "✗  ") + it.optString("txt"), 13f, if (ok) Cor.OK else if (opcional) Cor.MUTED else Cor.BAD), if (k == 0) 12 else 4)
            }
            val cfg = ct.getJSONObject("config")
            val hoje = ct.getJSONObject("hoje")
            card.add(texto("Hoje: " + hoje.optInt("filas") + " filas · " + brl(hoje.optLong("fat")) +
                (if (cfg.optLong("metaDia") > 0) " de meta " + brl(cfg.optLong("metaDia")) else ""), 13f, Cor.MUTED), 12)

            if (modo == "v2") {
                val appOn = im.optJSONObject("app")?.optBoolean("online") ?: false
                card.add(texto("Aplicativo: " + (if (appOn) "online neste/em outro aparelho" else "offline") + " · Gmail " + im.optString("gmail"), 13f, Cor.MUTED), 6)
                card.add(botao("Abrir IMAP v2", true) { abrirImap2(ct) }, 14)
            } else if (modo == "normal") {
                card.add(texto("Esta conta usa IMAP normal (senha de app). Para o aplicativo ler o Gmail, troque para IMAP v2 no site, em Pagamento.", 13f, Cor.MUTED), 12)
            } else {
                card.add(texto("Configure o IMAP pelo site (Pagamento) e escolha IMAP v2 para usar o aplicativo.", 13f, Cor.MUTED), 12)
            }
            c.add(card, 14)
        }

        c.add(botao("Atualizar", false) { carregarHome() }, 18)
        c.add(botao("Sair deste aparelho", false) { sair() }, 10)
        val pinado = BuildConfig.TLS_PINS.isNotEmpty()
        c.add(texto("v" + BuildConfig.VERSION_NAME + " · conexão " + (if (pinado) "com certificado fixado" else "TLS padrão") + " · dados criptografados no aparelho", 11f, Cor.MUTED), 18)
        mostrar(rolagem(c))
    }

    // ── IMAP v2 ───────────────────────────────────────────────────────────────
    private lateinit var tvEstado: android.widget.TextView
    private lateinit var tvLog: android.widget.TextView
    private lateinit var blocoResultado: LinearLayout

    private fun abrirImap2(conta: JSONObject) {
        contaAtual = conta
        gmailEscolhido = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (Sessao.imapAtivo && !Imap2Service.rodando && !Sessao.imapGmail.isNullOrEmpty()) Imap2Service.iniciar(this)
        desenharImap2()
    }

    /** 1º permite a bolinha flutuante (sobrepor outros apps); ao voltar, pede a liberação de bateria. */
    private fun prepararSegundoPlano() {
        if (!Settings.canDrawOverlays(this)) {
            aguardandoPermissao = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                aguardandoPermissao = false
                pedirBateria()
            }
            return
        }
        pedirBateria()
    }

    private fun pedirBateria() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { /* sem tela */ }
        }
    }

    private fun desenharImap2() {
        val conta = contaAtual ?: return
        val im = conta.getJSONObject("imap")
        val c = coluna()
        c.add(botao("← Voltar", false) { carregarHome() })
        c.add(texto("IMAP v2", 24f, Cor.FG, true), 16)
        c.add(texto("Conta " + conta.optString("username") + " · Gmail esperado " + im.optString("gmail"), 14f, Cor.MUTED), 4)

        val p1 = cartao()
        p1.add(texto("1. Escolha o Gmail do aparelho", 16f, Cor.FG, true))
        p1.add(texto("O aplicativo mostra o Gmail selecionado e avisa se não for o configurado no site.", 13f, Cor.MUTED), 6)
        val vinculado = Sessao.imapConta == conta.optString("id") && !Sessao.imapGmail.isNullOrEmpty()
        if (vinculado) p1.add(texto("Vinculado: " + Sessao.imapGmail, 14f, Cor.OK), 10)
        p1.add(botao(if (vinculado) "Trocar Gmail" else "Escolher Gmail", true) { escolherGmail() }, 12)
        c.add(p1, 16)

        blocoResultado = coluna()
        c.add(blocoResultado, 12)

        val p2 = cartao()
        p2.add(texto("2. Monitoramento em tempo real", 16f, Cor.FG, true))
        tvEstado = texto("", 14f, Cor.MUTED)
        p2.add(tvEstado, 8)
        tvLog = texto("", 12f, Cor.MUTED)
        p2.add(tvLog, 10)
        if (vinculado) {
            p2.add(botao(if (Imap2Service.rodando) "Parar monitoramento" else "Iniciar monitoramento", !Imap2Service.rodando) {
                if (Imap2Service.rodando) Imap2Service.parar(this) else { Imap2Service.iniciar(this); prepararSegundoPlano() }
                ui.postDelayed({ desenharImap2() }, 600)
            }, 12)
        }
        val semRestricao = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        p2.add(texto(if (semRestricao) "Bateria: sem restrição ✓ (roda com o app fechado)" else "Bateria: RESTRITA ✗ — o Android pode derrubar a conexão", 13f, if (semRestricao) Cor.OK else Cor.WARN), 12)
        if (!semRestricao) p2.add(botao("Liberar uso de bateria", true) { pedirBateria() }, 8)
        val bolinha = Settings.canDrawOverlays(this)
        p2.add(texto(if (bolinha) "Bolinha flutuante: ativa ✓ (toque nela para abrir o app)" else "Bolinha flutuante: permissão necessária ✗", 13f, if (bolinha) Cor.OK else Cor.WARN), 10)
        if (!bolinha) p2.add(botao("Permitir bolinha flutuante", true) { prepararSegundoPlano() }, 8)
        p2.add(texto("Pode fechar o app: o monitoramento continua pela notificação fixa. Em Xiaomi/Samsung/Huawei, ative também \"Início automático\" e \"Sem restrições\" nas configurações do app.", 11f, Cor.MUTED), 8)
        c.add(p2, 12)
        mostrar(rolagem(c))
        iniciarStatus()
    }

    private fun iniciarStatus() {
        pararStatus()
        val r = object : Runnable {
            override fun run() {
                if (contaAtual == null || !::tvEstado.isInitialized) return
                tvEstado.text = "Estado: " + Imap2Service.estado
                tvLog.text = if (Imap2Service.registro.isEmpty()) "Sem atividade ainda." else Imap2Service.registro.take(8).joinToString("\n")
                ui.postDelayed(this, 1000)
            }
        }
        statusRunnable = r
        ui.post(r)
    }

    private fun pararStatus() {
        statusRunnable?.let { ui.removeCallbacks(it) }
        statusRunnable = null
    }

    private fun escolherGmail() {
        val i = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        try {
            startActivityForResult(i, REQ_CONTA)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a lista de contas Google.", Toast.LENGTH_LONG).show()
        }
    }

    private fun mensagem(titulo: String, corpo: String, tom: String): LinearLayout {
        val card = cartao()
        val cor = when (tom) { "ok" -> Cor.OK; "bad" -> Cor.BAD; else -> Cor.WARN }
        card.background = fundo(when (tom) { "ok" -> Cor.OK_SOFT; "bad" -> Cor.BAD_SOFT; else -> Cor.WARN_SOFT }, 12, cor)
        card.add(texto(titulo, 15f, cor, true))
        card.add(texto(corpo, 13f, Cor.FG), 6)
        return card
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONTA && resultCode == RESULT_OK) {
            val nome = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!nome.isNullOrEmpty()) verificarGmail(nome)
        } else if (requestCode == REQ_AUTH) {
            val nome = gmailEscolhido
            if (resultCode == RESULT_OK && nome != null) concluirVinculo(nome)
            else {
                blocoResultado.removeAllViews()
                blocoResultado.add(mensagem("Autorização negada", "Sem permitir a leitura do Gmail o IMAP v2 não funciona. Toque em Escolher Gmail para tentar de novo.", "bad"))
            }
        }
    }

    /** Passo 1: compara o Gmail escolhido com o cadastrado no site (a comparação é feita pela rota). */
    private fun verificarGmail(nome: String) {
        val conta = contaAtual ?: return
        gmailEscolhido = nome
        blocoResultado.removeAllViews()
        blocoResultado.add(texto("Gmail atual selecionado: $nome", 14f, Cor.FG, true))
        blocoResultado.add(texto("Conferindo com o bot…", 13f, Cor.MUTED), 6)
        trabalhar {
            val r = Api.vincular(conta.optString("id"), nome)
            ui.post {
                blocoResultado.removeAllViews()
                if (!r.optBoolean("combina")) {
                    blocoResultado.add(texto("Gmail atual selecionado: $nome", 14f, Cor.FG, true))
                    blocoResultado.add(mensagem("Esse não é o Gmail configurado", r.optString("mensagem") + "\n\nEsperado: " + r.optString("esperado") + "\nSelecionado: " + nome, "bad"), 8)
                    blocoResultado.add(botao("Escolher outro Gmail", true) { escolherGmail() }, 10)
                    blocoResultado.add(botao("Abrir contas do aparelho", false) {
                        try { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) } catch (e: Exception) { /* sem tela */ }
                    }, 8)
                } else {
                    blocoResultado.add(texto("Gmail atual selecionado: $nome", 14f, Cor.FG, true))
                    blocoResultado.add(mensagem("É este Gmail ✓", "Falta só autorizar a leitura do Gmail (somente leitura).", "ok"), 8)
                    autorizar(nome)
                }
            }
        }
    }

    /** Passo 2: autoriza o Gmail (somente leitura) pela conta Google do aparelho. */
    private fun autorizar(nome: String) {
        trabalhar {
            try {
                GmailLeitor(applicationContext, android.accounts.Account(nome, "com.google")).token()
                ui.post { concluirVinculo(nome) }
            } catch (e: GmailErro) {
                val intent = e.intent
                ui.post {
                    if (intent != null) startActivityForResult(intent, REQ_AUTH)
                    else {
                        blocoResultado.removeAllViews()
                        blocoResultado.add(mensagem("Não consegui autorizar o Gmail", (e.message ?: "") + "\nConfirme que o app foi cadastrado no Google Cloud (veja o README).", "bad"))
                    }
                }
            }
        }
    }

    private fun concluirVinculo(nome: String) {
        val conta = contaAtual ?: return
        Sessao.imapConta = conta.optString("id")
        Sessao.imapGmail = nome
        Imap2Service.iniciar(this)
        prepararSegundoPlano()
        ui.postDelayed({ desenharImap2() }, 400)
        Toast.makeText(this, "IMAP v2 conectado. Deixe o aplicativo ativo em segundo plano.", Toast.LENGTH_LONG).show()
    }
}
