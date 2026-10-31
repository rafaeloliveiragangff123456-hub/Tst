package com.botsync.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.widget.TextView
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

    // atualização obrigatória
    private var versaoNova: Versao? = null
    private var naChat = false                  // true enquanto a aba Chat está aberta (voltar leva ao painel)
    private var ultimoMe: JSONObject? = null    // último painel carregado: trocar de aba não precisa buscar tudo de novo
    private var naAntiCheat = false             // true enquanto a aba Anti Cheat está aberta (voltar leva ao painel)
    private var naConfig = false                // true enquanto a tela de Configurações está aberta (voltar leva ao painel)
    private var telaBloqueada = false           // true enquanto a tela de atualização está aberta (voltar não sai dela)
    private var aguardandoInstalar = false      // foi levado às configurações para liberar "instalar apps desta fonte"
    private var pollAtual: Runnable? = null
    private var blocoAcao: View? = null
    private var blocoProgresso: View? = null
    private var btnAtualizar: LinearLayout? = null
    private var barraAtual: LinearLayout? = null
    private var tvFase: TextView? = null
    private var tvPct: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // anti-gravação (padrão: ativado): impede captura de tela, gravação e miniatura em "recentes". A pessoa pode desligar em Configurações
        aplicarAntiGravacao()
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
        else if (intent?.getBooleanExtra("atualizar", false) == true) inicio()      // veio da notificação de atualização
    }

    override fun onBackPressed() {
        if (telaBloqueada) return       // atualização obrigatória: não dá para sair da tela
        if (naConfig || naChat || naAntiCheat) { irHome(); return }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (Seguranca.verificar(this) != null) return
        WifiDebug.ligarGuardiao(this)    // sessão de 40 min e "manter a tela ligada" valem em qualquer tela
        if (aguardandoPermissao) {
            aguardandoPermissao = false
            if (Settings.canDrawOverlays(this)) {
                if (Sessao.imapAtivo) Imap2Service.iniciar(this)      // reaplica para a bolinha aparecer
                pedirBateria()
            }
        }
        if (contaAtual != null) iniciarStatus()
        if (aguardandoInstalar && Atualizador.podeInstalar(this)) {
            aguardandoInstalar = false
            iniciarAtualizacao()
        }
        if (telaBloqueada) iniciarPollAtualizacao()
    }

    override fun onPause() {
        super.onPause()
        WifiDebug.soltarGuardiao(this)
        pararStatus()
    }

    // ── utilidades ────────────────────────────────────────────────────────────
    private fun mostrar(v: View) {
        pararStatus()
        telaBloqueada = false
        naConfig = false
        naChat = false
        naAntiCheat = false
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
        if (e is ApiErro && e.codigo == 426) {      // o servidor recusou esta versão: só resta atualizar
            exigirAtualizacao()
            return
        }
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
        val lg = logo(84, 22)
        c.addView(lg)
        val pulso = ObjectAnimator.ofFloat(lg, "alpha", 1f, 0.4f)
        pulso.duration = 800
        pulso.repeatMode = ValueAnimator.REVERSE
        pulso.repeatCount = ValueAnimator.INFINITE
        pulso.start()
        lg.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { pulso.cancel() }
        })
        val tv = texto(t, 15f, Cor.MUTED)
        tv.gravity = Gravity.CENTER
        c.add(tv, 22)
        c.setPadding(dp(24), 0, dp(24), 0)
        return c
    }

    private fun inicio() {
        mostrar(carregando("Verificando versão…"))
        io.execute {
            // sem rede ou servidor fora: segue normal (o servidor ainda barra versão antiga com 426)
            val v = try { Atualizador.verificar(applicationContext) } catch (e: Exception) { null }
            ui.post {
                when {
                    v != null -> mostrarAtualizacao(v)
                    !Sessao.logado() -> mostrar(telaLogin(null))
                    else -> carregarHome()
                }
            }
        }
    }

    // ── atualização obrigatória ───────────────────────────────────────────────
    private fun exigirAtualizacao() {
        mostrar(carregando("Verificando atualização…"))
        io.execute {
            val v = try { Atualizador.verificar(applicationContext) } catch (e: Exception) { null }
            ui.post {
                if (v != null) { mostrarAtualizacao(v); return@post }
                val c = coluna()
                c.gravity = Gravity.CENTER_HORIZONTAL
                c.add(texto("Atualize o aplicativo", 22f, Cor.FG, true), 60)
                c.add(texto("O servidor pediu uma versão mais nova do BotSync, mas não consegui buscá-la agora. Verifique a internet e tente de novo.", 14f, Cor.MUTED), 12)
                c.add(botaoAcao("Tentar de novo", R.drawable.ic_atualizar, true) { inicio() }, 20)
                mostrar(rolagem(c))
            }
        }
    }

    private fun mostrarAtualizacao(v: Versao) {
        versaoNova = v
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        Atualizador.notificarObrigatoria(applicationContext, v)
        mostrar(telaAtualizacao(v))
        telaBloqueada = true
        iniciarPollAtualizacao()
    }

    private fun linhaInfo(rot: String, valor: String, cor: Int): View {
        val l = linha()
        l.addView(texto(rot, 13f, Cor.MUTED), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(texto(valor, 14f, cor, true))
        return l
    }

    private fun telaAtualizacao(v: Versao): View {
        val c = coluna()
        c.gravity = Gravity.CENTER_HORIZONTAL

        val moldura = FrameLayout(this)
        moldura.background = gradiente(0xFF8B3DF0.toInt(), 0xFF3B0A5C.toInt(), 26)
        moldura.setPadding(dp(3), dp(3), dp(3), dp(3))
        moldura.addView(logo(84, 23))
        val lpM = LinearLayout.LayoutParams(dp(90), dp(90))
        lpM.topMargin = dp(36)
        lpM.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(moldura, lpM)

        c.addWrap(chip("Atualização obrigatória", "warn"), 18)
        val t = texto("Nova versão disponível", 25f, Cor.FG, true)
        t.gravity = Gravity.CENTER
        c.add(t, 14)
        val s = texto("O BotSync " + v.nome + " já está no ar. Para continuar usando o aplicativo, atualize agora.", 14f, Cor.MUTED)
        s.gravity = Gravity.CENTER
        c.add(s, 8)

        val info = cartao()
        info.add(linhaInfo("Versão instalada", "v" + BuildConfig.VERSION_NAME, Cor.MUTED))
        info.add(linhaInfo("Nova versão", "v" + v.nome, Cor.OK), 10)
        info.add(linhaInfo("Tamanho", Atualizador.tamanhoLegivel(v.tamanho), Cor.FG), 10)
        c.add(info, 22)

        val notas = Atualizador.linhasNotas(v.notas)
        if (notas.isNotEmpty()) {
            val nc = cartao()
            nc.addView(rotulo("O que há de novo"))
            for ((k, n) in notas.withIndex()) {
                val l = linha()
                l.addView(icone(R.drawable.ic_certo, Cor.ACCENT_TXT), LinearLayout.LayoutParams(dp(16), dp(16)))
                val tx = texto(n, 13f, Cor.FG)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = dp(10)
                l.addView(tx, lp)
                nc.add(l, if (k == 0) 12 else 8)
            }
            c.add(nc, 12)
        }

        // andamento (aparece depois de tocar em Atualizar)
        val prog = coluna()
        val fase = texto("", 13f, Cor.MUTED)
        fase.gravity = Gravity.CENTER
        prog.add(fase)
        val barraProg = barra(0)
        prog.add(barraProg, 10)
        val pct = texto("", 12f, Cor.ACCENT_TXT, true)
        pct.gravity = Gravity.CENTER
        prog.add(pct, 8)
        prog.visibility = View.GONE
        c.add(prog, 22)

        val btn = botaoAcao("Atualizar agora", R.drawable.ic_atualizar, true) { iniciarAtualizacao() }
        val acao = coluna()
        acao.add(btn)
        val nota = linha()
        nota.gravity = Gravity.CENTER
        nota.addView(icone(R.drawable.ic_cadeado, Cor.MUTED), LinearLayout.LayoutParams(dp(14), dp(14)))
        val nt = texto("Baixa e instala sozinho. O arquivo é conferido (SHA-256 e assinatura) antes de instalar.", 12f, Cor.MUTED)
        val lpn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpn.leftMargin = dp(8)
        nota.addView(nt, lpn)
        acao.add(nota, 14)
        c.add(acao, 22)

        blocoProgresso = prog
        blocoAcao = acao
        btnAtualizar = btn
        barraAtual = barraProg
        tvFase = fase
        tvPct = pct
        val sv = rolagem(c)
        sv.background = brilhoFundo()
        return sv
    }

    private fun iniciarAtualizacao() {
        val v = versaoNova ?: return
        if (Atualizador.fase == Atualizador.BAIXANDO || Atualizador.fase == Atualizador.INSTALANDO) return
        if (!Atualizador.podeInstalar(this)) {
            aguardandoInstalar = true
            Toast.makeText(this, "Ative \"Permitir desta fonte\" para o BotSync e volte aqui.", Toast.LENGTH_LONG).show()
            Atualizador.pedirPermissao(this)
            return
        }
        // thread própria: o download não pode travar a fila das outras tarefas do app
        Thread({ Atualizador.atualizar(applicationContext, v) }, "update").also { it.isDaemon = true }.start()
    }

    private fun iniciarPollAtualizacao() {
        pararStatus()
        val r = object : Runnable {
            override fun run() {
                if (!telaBloqueada) return
                desenharAndamento()
                ui.postDelayed(this, 300)
            }
        }
        pollAtual = r
        ui.post(r)
    }

    private fun desenharAndamento() {
        val f = Atualizador.fase
        val andando = f == Atualizador.BAIXANDO || f == Atualizador.INSTALANDO
        blocoAcao?.visibility = if (andando) View.GONE else View.VISIBLE
        blocoProgresso?.visibility = if (f == Atualizador.PARADO) View.GONE else View.VISIBLE
        tvFase?.text = Atualizador.mensagem
        tvFase?.setTextColor(if (f == Atualizador.ERRO) Cor.BAD else Cor.MUTED)
        val p = Atualizador.progresso.coerceIn(0, 100)
        tvPct?.text = if (f == Atualizador.BAIXANDO) "$p%" else ""
        barraAtual?.let { b ->
            val a = b.getChildAt(0).layoutParams as LinearLayout.LayoutParams
            val z = b.getChildAt(1).layoutParams as LinearLayout.LayoutParams
            if (a.weight != p.toFloat()) {
                a.weight = p.toFloat()
                z.weight = (100 - p).toFloat()
                b.getChildAt(0).layoutParams = a
                b.getChildAt(1).layoutParams = z
            }
        }
        if (f == Atualizador.ERRO) ((btnAtualizar?.getChildAt(1)) as? TextView)?.text = "Tentar de novo"
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
    private fun linhaRecurso(icone: Int, titulo: String, sub: String): View {
        val l = linha()
        l.addView(caixaIcone(icone, 38))
        val col = coluna()
        col.addView(texto(titulo, 14f, Cor.FG, true))
        col.addView(texto(sub, 12f, Cor.MUTED))
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(12)
        l.addView(col, lp)
        return l
    }

    private fun telaLogin(aviso: String?): View {
        val c = coluna()
        c.gravity = Gravity.CENTER_HORIZONTAL

        // logo (a mesma imagem Bs / BOTSYNC) com moldura
        val moldura = FrameLayout(this)
        moldura.background = gradiente(0xFF8B3DF0.toInt(), 0xFF3B0A5C.toInt(), 30)
        moldura.setPadding(dp(3), dp(3), dp(3), dp(3))
        moldura.addView(logo(104, 27))
        val lpM = LinearLayout.LayoutParams(dp(110), dp(110))
        lpM.topMargin = dp(56)
        lpM.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(moldura, lpM)

        val t = texto("BotSync", 32f, Cor.FG, true)
        t.gravity = Gravity.CENTER
        c.add(t, 20)
        val sys = rotulo("System")
        sys.letterSpacing = 0.4f
        sys.gravity = Gravity.CENTER
        c.add(sys, 2)
        val s = texto("Pagamentos em tempo real, configurações e IMAP v2 direto do seu celular.", 14f, Cor.MUTED)
        s.gravity = Gravity.CENTER
        c.add(s, 12)

        // o que o app faz
        val rec = cartao()
        rec.add(linhaRecurso(R.drawable.ic_raio, "Tempo real", "Consulta o Gmail na hora que o bot pedir."))
        rec.add(linhaRecurso(R.drawable.ic_email, "IMAP v2", "Lê o Gmail do aparelho, sem senha de app."), 14)
        rec.add(linhaRecurso(R.drawable.ic_escudo, "Protegido", "Conexão assinada e dados criptografados."), 14)
        c.add(rec, 28)

        if (aviso != null) {
            val av = linha()
            av.setPadding(dp(14), dp(12), dp(14), dp(12))
            av.background = fundo(Cor.WARN_SOFT, 12, 0xFF5A4410.toInt())
            av.addView(icone(R.drawable.ic_alerta, Cor.WARN), LinearLayout.LayoutParams(dp(20), dp(20)))
            val tx = texto(aviso, 13f, Cor.WARN)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(10)
            av.addView(tx, lp)
            c.add(av, 16)
        }
        if (BuildConfig.SERVER_URL.contains("CONFIGURE")) {
            c.add(texto("Este APK foi gerado sem a URL do servidor (BOTSYNC_URL). Gere de novo pelo GitHub Actions.", 14f, Cor.BAD), 18)
        } else {
            c.add(botaoAcao("Entrar com Discord", R.drawable.ic_entrar, true) { iniciarLogin() }, 20)
            val nota = linha()
            nota.gravity = Gravity.CENTER
            nota.addView(icone(R.drawable.ic_cadeado, Cor.MUTED), LinearLayout.LayoutParams(dp(14), dp(14)))
            val nt = texto("O login abre no navegador. A senha do Discord nunca passa pelo app.", 12f, Cor.MUTED)
            val lpn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpn.leftMargin = dp(8)
            nota.addView(nt, lpn)
            c.add(nota, 14)
        }
        val sv = rolagem(c)
        sv.background = brilhoFundo()
        return sv
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
                ultimoMe = null
                ChatEstado.limpar(true)
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

    // ── abas de baixo: Home, Chat, Configuração ───────────────────────────────
    private fun irAba(aba: Int) {
        when (aba) {
            ABA_HOME -> irHome()
            ABA_CHAT -> abrirChat()
            ABA_ANTICHEAT -> abrirAntiCheat()
            ABA_CONFIG -> abrirConfig()
        }
    }

    /** Volta ao painel usando o que já foi carregado; só busca no servidor se ainda não tiver nada. */
    private fun irHome() {
        contaAtual = null
        val me = ultimoMe
        if (me != null) desenharHome(me) else carregarHome()
    }

    private fun abrirChat() {
        val nome = ultimoMe?.optJSONObject("usuario")?.optString("nome") ?: ""
        ChatEstado.aoErroFatal = { e -> erroGeral(e) }      // versão antiga ou sessão expirada durante o chat
        mostrar(comAbas(telaChat(nome), ABA_CHAT) { irAba(it) })
        naChat = true
    }

    private fun abrirAntiCheat() {
        mostrar(comAbas(telaAntiCheat { irAba(ABA_CONFIG) }, ABA_ANTICHEAT) { irAba(it) })
        naAntiCheat = true
    }

    private fun dataBR(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))
    private fun brl(centavos: Long): String = "R$ " + String.format(Locale("pt", "BR"), "%,.2f", centavos / 100.0)

    private fun desenharHome(me: JSONObject) {
        ultimoMe = me
        val c = coluna()
        val nome = me.optJSONObject("usuario")?.optString("nome") ?: ""

        // cabecalho: logo + nome + atualizar / sair
        val cab = linha()
        cab.addView(logo(44, 13))
        val tit = coluna()
        tit.addView(texto("BotSync", 19f, Cor.FG, true))
        tit.addView(rotulo("System"))
        val lpT = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpT.leftMargin = dp(12)
        cab.addView(tit, lpT)
        cab.addView(botaoIcone(R.drawable.ic_atualizar) { carregarHome() })
        val sairBtn = botaoIcone(R.drawable.ic_sair) { sair() }
        (sairBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        cab.addView(sairBtn)
        c.add(cab)

        c.add(texto("Olá, $nome", 24f, Cor.FG, true), 22)
        c.add(texto("Acompanhe o plano, as contas e o monitoramento.", 13f, Cor.MUTED), 4)

        // plano
        val plano = me.getJSONObject("plano")
        val ativo = plano.optBoolean("ativo")
        val pc = coluna()
        pc.setPadding(dp(16), dp(16), dp(16), dp(16))
        pc.background = gradiente(0xFF2B1552.toInt(), 0xFF140B26.toInt(), 18, 0xFF3F2A66.toInt())
        val topo = linha()
        topo.addView(caixaIcone(R.drawable.ic_estrela, 40, if (ativo) Cor.ACCENT_TXT else Cor.BAD, if (ativo) 0xFF2A1A4D.toInt() else Cor.BAD_SOFT))
        val col = coluna()
        col.addView(rotulo("Seu plano"))
        val exp = if (plano.isNull("expira")) 0L else plano.optLong("expira", 0L)
        val nomePlano = if (plano.optBoolean("admin")) "Administrador" else (if (ativo) plano.optString("nome").ifEmpty { "Plano ativo" } else "Sem plano")
        col.add(texto(nomePlano, 18f, Cor.FG, true), 2)
        val lpc = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpc.leftMargin = dp(12)
        topo.addView(col, lpc)
        topo.addView(chip(if (ativo) "Ativo" else "Inativo", if (ativo) "ok" else "bad"))
        pc.add(topo)
        val txtPlano = when {
            plano.optBoolean("admin") -> "Acesso de administrador."
            ativo && exp > 0 -> "Expira em " + dataBR(exp)
            else -> "Compre ou renove um plano no site para liberar o bot."
        }
        pc.add(texto(txtPlano, 13f, Cor.MUTED), 12)
        val bloqueio = if (me.isNull("bloqueio")) "" else me.optString("bloqueio")
        if (bloqueio.isNotEmpty()) pc.add(texto(bloqueio, 13f, Cor.WARN), 8)
        c.add(pc, 18)

        // contas
        val contas = me.getJSONArray("contas")
        val secao = linha()
        secao.addView(texto("Suas contas", 16f, Cor.FG, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        secao.addView(chip(contas.length().toString()))
        c.add(secao, 24)

        if (contas.length() == 0) {
            val vc = cartao()
            vc.add(texto("Nenhuma conta conectada", 16f, Cor.FG, true))
            vc.add(texto("Conecte o token da sua conta pelo site; ela aparece aqui automaticamente.", 13f, Cor.MUTED), 6)
            c.add(vc, 12)
        }
        for (i in 0 until contas.length()) {
            val ct = contas.getJSONObject(i)
            val im = ct.getJSONObject("imap")
            val card = cartao()
            val modo = if (!im.optBoolean("configurado")) null else im.optString("modo")
            val online = ct.optBoolean("online")

            // topo da conta: avatar com inicial + nome + status
            val user = ct.optString("username")
            val ini = texto(user.take(1).uppercase().ifEmpty { "?" }, 17f, Cor.ACCENT_TXT, true)
            ini.gravity = Gravity.CENTER
            val av = FrameLayout(this)
            val ag = android.graphics.drawable.GradientDrawable()
            ag.shape = android.graphics.drawable.GradientDrawable.OVAL
            ag.setColor(Cor.ACCENT_SOFT)
            ag.setStroke(dp(2), if (online) Cor.OK else Cor.LINE)
            av.background = ag
            av.addView(ini, FrameLayout.LayoutParams(-1, -1))
            val head = linha()
            head.addView(av, LinearLayout.LayoutParams(dp(44), dp(44)))
            val nc = coluna()
            nc.addView(texto(user, 16f, Cor.FG, true))
            nc.addView(texto(if (online) "Online no Discord" else "Offline", 12f, if (online) Cor.OK else Cor.BAD))
            val lpn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpn.leftMargin = dp(12)
            head.addView(nc, lpn)
            head.addView(chip(when (modo) { "v2" -> "IMAP v2"; "normal" -> "IMAP normal"; else -> "Sem IMAP" }, if (modo == null) "warn" else ""))
            card.add(head)

            // verificacao do bot (plano + configuracoes), vinda da rota
            val lista = ct.getJSONArray("checklist")
            for (k in 0 until lista.length()) {
                val it = lista.getJSONObject(k)
                val ok = it.optBoolean("ok")
                val opcional = it.optString("k") == "meta"
                val cor = if (ok) Cor.OK else if (opcional) Cor.MUTED else Cor.BAD
                val ic = if (ok) R.drawable.ic_certo else if (opcional) R.drawable.ic_ponto else R.drawable.ic_fechar
                val l = linha()
                l.addView(icone(ic, cor), LinearLayout.LayoutParams(dp(16), dp(16)))
                val tx = texto(it.optString("txt"), 13f, if (ok) Cor.FG else cor)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = dp(10)
                l.addView(tx, lp)
                card.add(l, if (k == 0) 16 else 8)
            }

            // numeros de hoje
            val cfg = ct.getJSONObject("config")
            val hoje = ct.getJSONObject("hoje")
            val par = LinearLayout(this)
            par.orientation = LinearLayout.HORIZONTAL
            val e1 = estatistica("Filas hoje", hoje.optInt("filas").toString())
            val e2 = estatistica("Faturamento", brl(hoje.optLong("fat")))
            val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp1.rightMargin = dp(5)
            val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp2.leftMargin = dp(5)
            par.addView(e1, lp1)
            par.addView(e2, lp2)
            card.add(par, 16)
            val meta = cfg.optLong("metaDia")
            if (meta > 0) {
                val pct = ((hoje.optLong("fat") * 100) / meta).toInt()
                val rot = linha()
                rot.addView(texto("Meta do dia", 12f, Cor.MUTED), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                rot.addView(texto(pct.coerceAtMost(999).toString() + "% de " + brl(meta), 12f, Cor.ACCENT_TXT, true))
                card.add(rot, 12)
                card.add(barra(pct), 6)
            }

            if (modo == "v2") {
                val appOn = im.optJSONObject("app")?.optBoolean("online") ?: false
                val st = linha()
                val dot = View(this)
                val dg = android.graphics.drawable.GradientDrawable()
                dg.shape = android.graphics.drawable.GradientDrawable.OVAL
                dg.setColor(if (appOn) Cor.OK else Cor.WARN)
                dot.background = dg
                st.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
                val lps = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lps.leftMargin = dp(8)
                st.addView(texto("App " + (if (appOn) "online" else "offline") + " - Gmail " + im.optString("gmail"), 12f, Cor.MUTED), lps)
                card.add(st, 16)
                card.add(botaoAcao("Abrir IMAP v2", R.drawable.ic_email, true) { abrirImap2(ct) }, 12)
            } else if (modo == "normal") {
                card.add(texto("Esta conta usa IMAP normal (senha de app). Para o aplicativo ler o Gmail, troque para IMAP v2 no site, em Pagamento.", 13f, Cor.MUTED), 14)
            } else {
                card.add(texto("Configure o IMAP pelo site (Pagamento) e escolha IMAP v2 para usar o aplicativo.", 13f, Cor.MUTED), 14)
            }
            c.add(card, 12)
        }

        val pinado = BuildConfig.TLS_PINS.isNotEmpty()
        val rod = texto("v" + BuildConfig.VERSION_NAME + " - conexão " + (if (pinado) "com certificado fixado" else "TLS padrão") + " - dados criptografados no aparelho", 11f, Cor.MUTED)
        rod.gravity = Gravity.CENTER
        c.add(rod, 24)
        val sv = rolagem(c)
        sv.background = brilhoFundo()
        mostrar(comAbas(sv, ABA_HOME) { irAba(it) })
    }

    // ── configurações ─────────────────────────────────────────────────────────
    private fun aplicarAntiGravacao() {
        if (Sessao.antiGravacao) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Chave liga/desliga desenhada à mão, no mesmo estilo do app. */
    private fun interruptor(ligado: Boolean, acao: (Boolean) -> Unit): FrameLayout {
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

    private fun abrirConfig() {
        mostrar(telaConfig())
        naConfig = true
    }

    private fun telaConfig(): View {
        val c = coluna()

        val cab = linha()
        val tit = coluna()
        tit.addView(texto("Configurações", 19f, Cor.FG, true))
        tit.addView(rotulo("BotSync System"))
        val lpT = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        cab.addView(tit, lpT)
        cab.addView(caixaIcone(R.drawable.ic_config, 42))
        c.add(cab)

        c.add(rotulo("Privacidade e segurança"), 26)

        val ligado = Sessao.antiGravacao
        val card = cartao()
        val topo = linha()
        topo.addView(caixaIcone(R.drawable.ic_escudo, 42, if (ligado) Cor.OK else Cor.MUTED, if (ligado) Cor.OK_SOFT else Cor.SURFACE2))
        val col = coluna()
        col.addView(texto("Anti-gravação", 16f, Cor.FG, true))
        val st = linha()
        st.addView(chip(if (ligado) "Ativado" else "Desativado", if (ligado) "ok" else "warn"))
        col.add(st, 4)
        val lpc = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpc.leftMargin = dp(12)
        topo.addView(col, lpc)
        topo.addView(interruptor(ligado) { novo ->
            Sessao.antiGravacao = novo
            aplicarAntiGravacao()
            abrirConfig()
        })
        card.add(topo)
        card.add(
            texto(
                if (ligado) "O BotSync bloqueia print, gravação e espelhamento de tela, e esconde o conteúdo na lista de apps recentes. Vale para o app e para o painel flutuante."
                else "Print, gravação e espelhamento de tela estão liberados. Suas contas e o painel flutuante podem aparecer em capturas e vídeos.",
                13f, Cor.MUTED
            ), 14
        )
        c.add(card, 10)

        val nota = linha()
        nota.gravity = Gravity.CENTER
        nota.addView(icone(R.drawable.ic_cadeado, Cor.MUTED), LinearLayout.LayoutParams(dp(14), dp(14)))
        val nt = texto("Vem ativado por padrão. A escolha é sua e fica salva neste aparelho.", 12f, Cor.MUTED)
        val lpn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpn.leftMargin = dp(8)
        nota.addView(nt, lpn)
        c.add(nota, 16)

        c.add(rotulo("Conexão com o computador"), 26)
        c.add(cartaoWifi(), 10)

        val rod = texto("v" + BuildConfig.VERSION_NAME, 11f, Cor.MUTED)
        rod.gravity = Gravity.CENTER
        c.add(rod, 28)

        val sv = rolagem(c)
        sv.background = brilhoFundo()
        return comAbas(sv, ABA_CONFIG) { irAba(it) }
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
        c.add(botao("Voltar", false) { carregarHome() })
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
        p2.add(texto(if (semRestricao) "Bateria: sem restrição (roda com o app fechado)" else "Bateria: restrita - o Android pode derrubar a conexão", 13f, if (semRestricao) Cor.OK else Cor.WARN), 12)
        if (!semRestricao) p2.add(botao("Liberar uso de bateria", true) { pedirBateria() }, 8)
        val bolinha = Settings.canDrawOverlays(this)
        p2.add(texto(if (bolinha) "Flutuante: ativo (toque nele para abrir o menu)" else "Flutuante: permissão necessária", 13f, if (bolinha) Cor.OK else Cor.WARN), 10)
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
        pollAtual?.let { ui.removeCallbacks(it) }
        pollAtual = null
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
                    blocoResultado.add(mensagem("É este Gmail", "Falta só autorizar a leitura do Gmail (somente leitura).", "ok"), 8)
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
