package com.botsync.app

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs


/** Paleta do painel flutuante (menu + terminal). Troca em tempo real pelo comando "color". */
class Tema(
    val nome: String,
    val bg: Int, val barra: Int, val rodape: Int, val borda: Int,
    val destaque: Int, val texto: Int, val forte: Int, val dim: Int, val info: Int,
    val ok: Int, val aviso: Int, val erro: Int,
    val mFundo: Int, val mItem: Int, val mBorda: Int, val mAcento: Int, val mAcentoFundo: Int,
)

object Temas {
    private fun escuro(
        nome: String, bg: Int, barra: Int, rodape: Int, borda: Int, destaque: Int, texto: Int, dim: Int, info: Int,
    ) = Tema(
        nome, bg, barra, rodape, borda, destaque, texto, 0xFFFFFFFF.toInt(), dim, info,
        Cor.OK, Cor.WARN, Cor.BAD,
        rodape, barra, borda, destaque, bg,
    )

    // normal = visual original (terminal azul/Kali e menu roxo do app)
    val NORMAL = Tema(
        "normal",
        0xFF0A0D13.toInt(), 0xFF141925.toInt(), 0xFF0D1119.toInt(), 0xFF2B3552.toInt(),
        0xFF5C8DFF.toInt(), 0xFFD7DCE8.toInt(), 0xFFFFFFFF.toInt(), 0xFF6B7488.toInt(), 0xFF38BDF8.toInt(),
        Cor.OK, Cor.WARN, Cor.BAD,
        Cor.SURFACE, Cor.SURFACE2, 0xFF3B2A5C.toInt(), Cor.ACCENT_TXT, Cor.ACCENT_SOFT,
    )
    val VERMELHO = escuro("red", 0xFF120708.toInt(), 0xFF230C0F.toInt(), 0xFF170A0C.toInt(), 0xFF5E1C23.toInt(),
        0xFFFF4D5E.toInt(), 0xFFF3DCDE.toInt(), 0xFF946A6F.toInt(), 0xFFFF8A94.toInt())
    val ROSA = escuro("pink", 0xFF140A12.toInt(), 0xFF241122.toInt(), 0xFF180C16.toInt(), 0xFF612553.toInt(),
        0xFFFF5FB8.toInt(), 0xFFF7DEED.toInt(), 0xFF98687F.toInt(), 0xFFFF8AD0.toInt())
    val PRETO = escuro("black", 0xFF000000.toInt(), 0xFF0B0B0B.toInt(), 0xFF050505.toInt(), 0xFF2A2A2A.toInt(),
        0xFFFFFFFF.toInt(), 0xFFCFCFCF.toInt(), 0xFF707070.toInt(), 0xFFA8A8A8.toInt())
    // tema claro: verde/amarelo/vermelho mais escuros para ter contraste no fundo branco
    val BRANCO = Tema(
        "white",
        0xFFF8F9FB.toInt(), 0xFFE7EAF0.toInt(), 0xFFEEF0F5.toInt(), 0xFFC5CBD9.toInt(),
        0xFF2563EB.toInt(), 0xFF1F2937.toInt(), 0xFF000000.toInt(), 0xFF6B7280.toInt(), 0xFF0369A1.toInt(),
        0xFF059669.toInt(), 0xFFB45309.toInt(), 0xFFDC2626.toInt(),
        0xFFF8F9FB.toInt(), 0xFFE7EAF0.toInt(), 0xFFC5CBD9.toInt(), 0xFF2563EB.toInt(), 0xFFDCE6FB.toInt(),
    )

    val todos: Map<String, Tema> = linkedMapOf(
        "normal" to NORMAL, "red" to VERMELHO, "white" to BRANCO, "black" to PRETO, "pink" to ROSA,
    )

    @Volatile var atual: Tema = NORMAL

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("botsync_tema", Context.MODE_PRIVATE)

    fun carregar(ctx: Context) {
        atual = todos[prefs(ctx).getString("tema", "normal")] ?: NORMAL
    }

    fun definir(ctx: Context, nome: String) {
        atual = todos[nome] ?: NORMAL
        prefs(ctx).edit().putString("tema", atual.nome).apply()
    }
}

/**
 * Flutuante do BotSync: logo arrastavel. 1 toque abre o menu (Terminal), 2o toque fecha o menu.
 * O terminal (estilo Kali) mostra somente o registro de atividade, sempre filtrado para nao vazar dados.
 * Comandos: help, status, clear, color <red|white|black|pink|normal>, run stop.
 * Todos os metodos devem ser chamados na thread principal.
 */
class Flutuante(
    private val ctx: Context,
) {
    private companion object {
        // FLAG_SECURE (anti-gravacao, ligado por padrao): o painel (menu/terminal) nao aparece em print, gravacao de tela nem espelhamento.
        // A pessoa pode desligar em Configuracoes; o valor e lido toda vez que o painel abre.
        private const val FLAGS_BASE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val FLAGS: Int
            get() = if (Sessao.antiGravacao) FLAGS_BASE or WindowManager.LayoutParams.FLAG_SECURE else FLAGS_BASE

        val REGEX_EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        val REGEX_URL = Regex("https?://\\S+")
        val REGEX_SEGREDO = Regex("(?i)\\b(token|senha|password|secret|chave|key|authorization|bearer)\\b\\s*[:=]\\s*\\S+")
        val REGEX_BEARER = Regex("(?i)\\bbearer\\s+\\S+")
        val REGEX_IP = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
        val REGEX_LONGO = Regex("[A-Za-z0-9_\\-+/=]{20,}")
        val REGEX_NUM = Regex("\\d{6,}")

        /** Remove de qualquer texto: e-mails, links, tokens/chaves/senhas, IPs e numeros longos. */
        fun limpar(s: String): String = s
            .replace(REGEX_URL, "[url]")
            .replace(REGEX_EMAIL, "***@***")
            .replace(REGEX_SEGREDO, "[oculto]")
            .replace(REGEX_BEARER, "[oculto]")
            .replace(REGEX_IP, "[ip]")
            .replace(REGEX_LONGO, "[oculto]")
            .replace(REGEX_NUM, "[oculto]")
    }

    private val tema: Tema get() = Temas.atual

    private class Term(
        val raiz: View, val barra: View, val titulo: TextView, val linha: View,
        val icVoltar: ImageView, val icFechar: ImageView, val prompt: TextView,
        val rolagem: ScrollView, val log: TextView, val entrada: EditText, val faixa: TextView, val rodape: View,
    ) {
        var assinatura = 0
        var encerrando = false
        var primeira = true
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private val tam = (46 * d).toInt()

    init { Temas.carregar(ctx) }

    private var bolha: FrameLayout? = null
    private var lpBolha: WindowManager.LayoutParams? = null
    private var painel: View? = null
    private var term: Term? = null
    private var lpPainel: WindowManager.LayoutParams? = null
    private var yBase = 0
    private var hBase = 0
    private var online = false

    private val tick = object : Runnable {
        override fun run() {
            val t = term ?: return
            atualizarTerminal(t)
            ui.postDelayed(this, 500)
        }
    }

    private fun px(v: Int) = (v * d).toInt()

    // ── bolha ────────────────────────────────────────────────────────────────
    fun mostrar() {
        if (bolha != null || !Settings.canDrawOverlays(ctx)) return
        try {
            val anel = FrameLayout(ctx)
            anel.background = anelFundo(online)
            val img = ImageView(ctx)
            img.setImageResource(R.drawable.logo_botsync)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            img.clipToOutline = true
            val m = px(3)
            anel.addView(img, FrameLayout.LayoutParams(tam - 2 * m, tam - 2 * m, Gravity.CENTER))
            anel.alpha = 0.95f
            anel.filterTouchesWhenObscured = true

            val lp = WindowManager.LayoutParams(tam, tam, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            val salva = Sessao.bolha?.split(",")
            lp.x = salva?.getOrNull(0)?.toIntOrNull() ?: px(8)
            lp.y = salva?.getOrNull(1)?.toIntOrNull() ?: px(140)
            limitar(lp)

            anel.setOnTouchListener(object : View.OnTouchListener {
                var ix = 0
                var iy = 0
                var tx = 0f
                var ty = 0f
                var moveu = false
                override fun onTouch(view: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moveu = false; return true }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - tx).toInt()
                            val dy = (e.rawY - ty).toInt()
                            if (!moveu && (abs(dx) > 8 || abs(dy) > 8)) { moveu = true; removerPainel() }
                            if (moveu) {
                                lp.x = ix + dx
                                lp.y = iy + dy
                                limitar(lp)
                                try { wm.updateViewLayout(view, lp) } catch (x: Exception) { /* ok */ }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (moveu) Sessao.bolha = lp.x.toString() + "," + lp.y.toString() else alternar()
                            return true
                        }
                    }
                    return false
                }
            })
            wm.addView(anel, lp)
            bolha = anel
            lpBolha = lp
        } catch (e: Exception) {
            // sem permissao de sobreposicao: segue sem o flutuante
        }
    }

    fun esconder() {
        removerPainel()
        val v = bolha ?: return
        bolha = null
        lpBolha = null
        try { wm.removeView(v) } catch (e: Exception) { /* ok */ }
    }

    /** Anel verde = online; amarelo = reconectando. */
    fun status(on: Boolean) {
        online = on
        bolha?.background = anelFundo(on)
    }

    private fun anelFundo(on: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Cor.SURFACE)
        g.setStroke(px(2) + px(1) / 2, if (on) Cor.OK else Cor.WARN)
        return g
    }

    private fun limitar(lp: WindowManager.LayoutParams) {
        val dm = ctx.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - tam).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - tam).coerceAtLeast(0))
    }

    private fun alternar() {
        if (painel != null) removerPainel() else abrirMenu()
    }

    // ── painel (menu / terminal) ─────────────────────────────────────────────
    private fun removerPainel() {
        ui.removeCallbacks(tick)
        term = null
        lpPainel = null
        val p = painel ?: return
        painel = null
        try { wm.removeView(p) } catch (e: Exception) { /* ok */ }
    }

    /** Abre o painel ao LADO do flutuante (direita se couber, senao esquerda). altFixa = altura em px, ou null para ajustar ao conteudo. */
    private fun exibir(v: View, largura: Int, altFixa: Int?, focavel: Boolean = false) {
        removerPainel()
        val lb = lpBolha ?: return
        val dm = ctx.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val margem = px(10)
        val gap = px(8)
        val espacoDir = sw - (lb.x + tam + gap) - margem
        val espacoEsq = lb.x - gap - margem
        val minimo = px(200)
        val aDireita = espacoDir >= espacoEsq
        val espaco = if (aDireita) espacoDir else espacoEsq
        val lado = espaco >= minimo
        val w = if (lado) minOf(largura, espaco) else minOf(largura, sw - 2 * margem)

        val h = if (altFixa != null) {
            minOf(altFixa, sh - 2 * margem)
        } else {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.measuredHeight
        }
        val x: Int
        var y: Int
        if (lado) {
            x = if (aDireita) lb.x + tam + gap else lb.x - gap - w
            y = lb.y + tam / 2 - h / 2
        } else {
            // sem espaco lateral: cai para cima/baixo
            x = (lb.x + tam / 2 - w / 2).coerceIn(margem, (sw - w - margem).coerceAtLeast(margem))
            y = if (sh - (lb.y + tam + gap) - margem >= h) lb.y + tam + gap else lb.y - gap - h
        }
        y = y.coerceIn(margem, (sh - h - margem).coerceAtLeast(margem))

        val flags = if (focavel) FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else FLAGS
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        if (focavel) {
            lp.softInputMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            if (Build.VERSION.SDK_INT >= 30) {
                // teclado aberto: encolhe/sobe o painel para ele nao ficar atras do teclado
                v.setOnApplyWindowInsetsListener { _, ins ->
                    ajustarTeclado(ins.getInsets(WindowInsets.Type.ime()).bottom)
                    ins
                }
            }
        }
        v.filterTouchesWhenObscured = true      // ignora toques se outro app sobrepuser o painel (tapjacking)
        lpPainel = lp
        yBase = y
        hBase = h
        v.alpha = 0f
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.pivotX = if (lado) (if (aDireita) 0f else w.toFloat()) else (lb.x + tam / 2 - x).toFloat()
        v.pivotY = (lb.y + tam / 2 - y).toFloat()
        try {
            wm.addView(v, lp)
            painel = v
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        } catch (e: Exception) {
            painel = null
            term = null
            lpPainel = null
        }
    }

    private fun ajustarTeclado(ime: Int) {
        val p = painel ?: return
        val lp = lpPainel ?: return
        val sh = ctx.resources.displayMetrics.heightPixels
        val margem = px(10)
        if (ime <= 0) {
            lp.height = hBase
            lp.y = yBase
        } else {
            val livre = sh - ime - 2 * margem
            val h = minOf(hBase, livre.coerceAtLeast(px(160)))
            lp.height = h
            lp.y = (sh - ime - h - margem).coerceAtLeast(margem)
        }
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) { /* ok */ }
    }

    private fun abrirMenu() {
        exibir(montarMenu(), px(236), null)
    }

    private fun abrirTerminal() {
        val dm = ctx.resources.displayMetrics
        val alt = minOf(px(340), (dm.heightPixels * 0.6f).toInt())
        val (v, novo) = montarTerminal()
        exibir(v, px(360), alt, true)
        if (painel == null) return
        term = novo
        atualizarTerminal(novo)
        ui.postDelayed(tick, 500)
    }

    // ── menu ─────────────────────────────────────────────────────────────────
    private fun montarMenu(): View {
        val m = tema
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = ctx.fundo(m.mFundo, 18, m.mBorda)
        c.setPadding(px(12), px(12), px(12), px(12))

        val cab = LinearLayout(ctx)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val ponto = View(ctx)
        val pg = GradientDrawable()
        pg.shape = GradientDrawable.OVAL
        pg.setColor(if (online) m.ok else m.aviso)
        ponto.background = pg
        cab.addView(ponto, LinearLayout.LayoutParams(px(8), px(8)))
        val titulo = ctx.texto("BOTSYNC", 11f, m.mAcento, true)
        titulo.letterSpacing = 0.16f
        cab.addView(titulo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = px(8) })
        c.add(cab)

        val est = ctx.texto(limpar(Imap2Service.estado), 11f, m.dim)
        est.maxLines = 1
        est.ellipsize = TextUtils.TruncateAt.END
        c.add(est, 4)

        val div = View(ctx)
        div.setBackgroundColor(m.mBorda)
        c.addView(div, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).also { it.topMargin = px(10) })

        c.add(item(R.drawable.ic_terminal, "Terminal", "Registro do monitoramento") { abrirTerminal() }, 10)
        return c
    }

    private fun item(icone: Int, titulo: String, sub: String, acao: () -> Unit): View {
        val m = tema
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        l.setPadding(px(10), px(10), px(10), px(10))
        l.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), ctx.fundo(m.mItem, 12, m.mBorda), null)

        val caixa = FrameLayout(ctx)
        caixa.background = ctx.fundo(m.mAcentoFundo, 10)
        val ic = ImageView(ctx)
        ic.setImageResource(icone)
        ic.imageTintList = ColorStateList.valueOf(m.mAcento)
        caixa.addView(ic, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        l.addView(caixa, LinearLayout.LayoutParams(px(40), px(40)))

        val tx = LinearLayout(ctx)
        tx.orientation = LinearLayout.VERTICAL
        tx.addView(ctx.texto(titulo, 14f, m.forte, true))
        tx.addView(ctx.texto(sub, 11f, m.dim))
        l.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = px(12) })

        val seta = ImageView(ctx)
        seta.setImageResource(R.drawable.ic_seta)
        seta.imageTintList = ColorStateList.valueOf(m.dim)
        l.addView(seta, LinearLayout.LayoutParams(px(18), px(18)))

        l.setOnClickListener { acao() }
        return l
    }

    // ── terminal (estilo Kali) ───────────────────────────────────────────────
    private fun montarTerminal(): Pair<View, Term> {
        val raiz = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (e.action == KeyEvent.ACTION_UP) removerPainel()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }
        raiz.orientation = LinearLayout.VERTICAL

        // barra de titulo
        val barra = LinearLayout(ctx)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        barra.setPadding(px(4), px(2), px(4), px(2))
        val icVoltar = botaoIcone(R.drawable.ic_voltar) { abrirMenu() }
        barra.addView(icVoltar, LinearLayout.LayoutParams(px(36), px(36)))
        val titulo = ctx.texto("botsync@kali: ~/monitor", 11.5f, 0xFFB8C1D9.toInt(), true)
        titulo.typeface = Typeface.MONOSPACE
        titulo.maxLines = 1
        titulo.ellipsize = TextUtils.TruncateAt.END
        titulo.gravity = Gravity.CENTER
        barra.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val icFechar = botaoIcone(R.drawable.ic_fechar) { removerPainel() }
        barra.addView(icFechar, LinearLayout.LayoutParams(px(36), px(36)))
        raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val linhaAzul = View(ctx)
        raiz.addView(linhaAzul, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))

        // faixa de status em tempo real (online ha quanto tempo, ciclos, quedas, ultimo poll)
        val faixa = TextView(ctx)
        faixa.typeface = Typeface.MONOSPACE
        faixa.textSize = 9.5f
        faixa.maxLines = 1
        faixa.ellipsize = TextUtils.TruncateAt.END
        faixa.setPadding(px(10), px(5), px(10), px(5))
        raiz.addView(faixa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // corpo com o registro
        val rolagem = ScrollView(ctx)
        rolagem.isVerticalScrollBarEnabled = false
        rolagem.overScrollMode = View.OVER_SCROLL_NEVER
        val log = TextView(ctx)
        log.typeface = Typeface.MONOSPACE
        log.textSize = 10.5f
        log.setLineSpacing(px(2).toFloat(), 1f)
        log.setPadding(px(10), px(8), px(10), px(8))
        rolagem.addView(log, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // rodape: prompt com campo para digitar comandos
        val rodape = LinearLayout(ctx)
        rodape.orientation = LinearLayout.HORIZONTAL
        rodape.gravity = Gravity.CENTER_VERTICAL
        rodape.setPadding(px(10), px(2), px(10), px(2))
        val prompt = TextView(ctx)
        prompt.textSize = 10.5f
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        prompt.text = "└─$ "
        rodape.addView(prompt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val entrada = EditText(ctx)
        entrada.typeface = Typeface.MONOSPACE
        entrada.textSize = 10.5f
        entrada.hint = "digite help"
        entrada.background = null
        entrada.setPadding(px(2), px(8), px(2), px(8))
        entrada.isSingleLine = true
        entrada.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        entrada.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        entrada.setOnEditorActionListener { _, _, _ ->
            val c = entrada.text.toString()
            entrada.setText("")
            executar(c)
            true
        }
        rodape.addView(entrada, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(rodape, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val novo = Term(raiz, barra, titulo, linhaAzul, icVoltar, icFechar, prompt, rolagem, log, entrada, faixa, rodape)
        aplicarTema(novo)
        return Pair(raiz, novo)
    }

    /** Pinta todo o terminal com o tema atual (usado ao abrir e quando o comando "color" troca o tema). */
    private fun aplicarTema(t: Term) {
        val m = tema
        val r = px(14).toFloat()
        t.raiz.background = ctx.fundo(m.bg, 14, m.borda)
        val bgBarra = GradientDrawable()
        bgBarra.setColor(m.barra)
        bgBarra.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        t.barra.background = bgBarra
        val bgRodape = GradientDrawable()
        bgRodape.setColor(m.rodape)
        bgRodape.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        t.rodape.background = bgRodape
        t.linha.setBackgroundColor(m.borda)
        t.titulo.setTextColor(m.texto)
        t.icVoltar.imageTintList = ColorStateList.valueOf(m.texto)
        t.icFechar.imageTintList = ColorStateList.valueOf(m.texto)
        t.faixa.setTextColor(m.dim)
        t.faixa.setBackgroundColor(m.rodape)
        t.log.setTextColor(m.texto)
        t.prompt.setTextColor(m.destaque)
        t.entrada.setTextColor(m.forte)
        t.entrada.setHintTextColor(m.dim)
        t.primeira = true       // remonta o registro com as cores novas
    }

    private fun botaoIcone(icone: Int, acao: () -> Unit): ImageView {
        val v = ImageView(ctx)
        v.setImageResource(icone)
        v.imageTintList = ColorStateList.valueOf(0xFFB8C1D9.toInt())
        v.setPadding(px(9), px(9), px(9), px(9))
        v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ctx.fundo(0xFFFFFFFF.toInt(), 18))
        v.setOnClickListener { acao() }
        return v
    }

    private fun executar(bruto: String) {
        val t = term ?: return
        if (t.encerrando) return
        val cmd = bruto.trim()
        if (cmd.isEmpty()) return
        val c = cmd.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (c == "clear" || c == "cls") {
            Imap2Service.registro.clear()
        } else {
            Imap2Service.registrar("$ " + limpar(cmd).take(60))
            val partes = c.split(" ")
            if (partes[0] == "color" || partes[0] == "cor") {
                comandoCor(t, partes.getOrNull(1))
            } else when (c) {
                "run stop", "stop" -> encerrar(t)
                "status" -> {
                    Imap2Service.registrar("estado: " + Imap2Service.estado)
                    Imap2Service.registrar("monitoramento: " + (if (Imap2Service.rodando) "ativo" else "parado"))
                    Imap2Service.registrar("tema: " + tema.nome)
                }
                "help", "ajuda", "?" -> {
                    Imap2Service.registrar("color <tema> - muda o tema: red white black pink normal")
                    Imap2Service.registrar("run stop     - para o tempo real e fecha o aplicativo")
                    Imap2Service.registrar("status       - mostra o estado atual")
                    Imap2Service.registrar("clear        - limpa o terminal")
                }
                else -> Imap2Service.registrar("comando não encontrado: " + limpar(c).take(30) + " (digite help)")
            }
        }
        t.primeira = true
        atualizarTerminal(t)
    }

    /** "color red | white | black | pink | normal": troca e guarda o tema do painel. */
    private fun comandoCor(t: Term, nome: String?) {
        val alvo = when (nome) {
            "red", "vermelho" -> "red"
            "white", "branco" -> "white"
            "black", "preto" -> "black"
            "pink", "rosa" -> "pink"
            "normal", "default", "padrao", "padrão" -> "normal"
            else -> null
        }
        if (alvo == null) {
            Imap2Service.registrar("uso: color <red|white|black|pink|normal>  (atual: " + tema.nome + ")")
            return
        }
        Temas.definir(ctx, alvo)
        aplicarTema(t)
        Imap2Service.registrar("tema alterado para " + alvo)
    }

    /** "run stop": para o monitoramento em tempo real e fecha o aplicativo. */
    private fun encerrar(t: Term) {
        t.encerrando = true
        t.entrada.isEnabled = false
        Imap2Service.registrar("Encerrando monitoramento em tempo real...")
        Imap2Service.registrar("Fechando o aplicativo...")
        val app = ctx.applicationContext
        ui.postDelayed({
            Imap2Service.parar(app)
            Imap2Service.registro.clear()       // nada do registro fica na memoria depois de encerrar
            try {
                app.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
            } catch (e: Exception) { /* ok */ }
            ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }, 1200)
    }

    private fun hms(seg: Long): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d", seg / 3600, (seg % 3600) / 60, seg % 60)

    private fun montarFaixa(): SpannableStringBuilder {
        val m = tema
        val agora = SystemClock.elapsedRealtime()
        val sb = SpannableStringBuilder()
        val desde = Imap2Service.onlineDesde
        if (!Imap2Service.rodando) {
            anexar(sb, "PARADO", m.erro, true)
        } else if (desde > 0L) {
            anexar(sb, "ONLINE ", m.ok, true)
            anexar(sb, hms((agora - desde) / 1000), m.forte, true)
        } else {
            anexar(sb, "CONECTANDO", m.aviso, true)
        }
        anexar(sb, "  ciclos " + Imap2Service.ciclos + "  quedas " + Imap2Service.quedas, m.dim, false)
        val ok = Imap2Service.ultimoOk
        if (ok > 0L && Imap2Service.rodando) anexar(sb, "  poll " + ((agora - ok) / 1000) + "s", m.dim, false)
        return sb
    }

    private fun atualizarTerminal(t: Term) {
        t.faixa.text = montarFaixa()
        // so remonta o texto quando o registro ou o estado mudou
        val ultimo = Imap2Service.registro.firstOrNull() ?: ""
        val assin = Imap2Service.registro.size * 31 + ultimo.hashCode() + Imap2Service.estado.hashCode()
        if (!t.primeira && assin == t.assinatura) return
        val perto = t.log.height - (t.rolagem.height + t.rolagem.scrollY) < px(48)
        t.assinatura = assin
        t.log.text = montarLog()
        if (t.primeira || perto) {
            t.rolagem.post { t.rolagem.scrollTo(0, t.log.height) }
        }
        t.primeira = false
    }

    private fun montarLog(): SpannableStringBuilder {
        val m = tema
        val sb = SpannableStringBuilder()
        anexar(sb, "┌──(", m.destaque, false)
        anexar(sb, "botsync@kali", m.destaque, true)
        anexar(sb, ")-[", m.destaque, false)
        anexar(sb, "~/monitor", m.forte, true)
        anexar(sb, "]\n", m.destaque, false)
        anexar(sb, "└─$ ", m.destaque, true)
        anexar(sb, "tail -f monitor.log\n", m.texto, false)

        val linhas = Imap2Service.registro.toList().reversed()
        if (linhas.isEmpty()) {
            anexar(sb, "(sem atividade ainda)\n", m.dim, false)
        }
        for (l in linhas) {
            val hora = if (l.length > 10 && l[8] == ' ') l.substring(0, 8) else ""
            val msg = limpar((if (hora.isEmpty()) l else l.substring(10)).trim())
            val nv = nivel(msg)
            if (hora.isNotEmpty()) anexar(sb, "[$hora] ", m.dim, false)
            if (msg.startsWith("$ ")) {
                anexar(sb, "└─$ ", m.destaque, true)
                anexar(sb, msg.substring(2) + "\n", m.forte, true)
                continue
            }
            when (nv) {
                1 -> anexar(sb, "[ OK ] ", m.ok, true)
                2 -> anexar(sb, "[WARN] ", m.aviso, true)
                3 -> anexar(sb, "[ERRO] ", m.erro, true)
                else -> anexar(sb, "[INFO] ", m.info, true)
            }
            anexar(sb, msg + "\n", if (nv == 3) m.erro else m.texto, false)
        }
        return sb
    }

    /** 0 info, 1 ok, 2 aviso, 3 erro. */
    private fun nivel(m: String): Int {
        val x = m.lowercase(Locale.ROOT)
        return when {
            x.contains("não confere") || x.contains("não encontrado") || x.contains("encerrada") || x.contains("perdido") ||
                x.contains("recusou") || x.contains("sem permissão") || x.contains("atualize") || x.contains("atualização obrigatória") -> 3
            x.contains("reconectando") || x.contains("rede mudou") || x.startsWith("rede:") || x.contains("sem conexão") || x.contains("falha") ||
                x.startsWith("gmail") || x.startsWith("uso:") -> 2
            x.contains("online") || x.contains("iniciado") || x.contains("encerrando") || x.contains("conexão ativa") || x.contains("conectado ao") || x.contains("restabelecida") || x.contains("estável") || x.contains("novo(s)") || x.contains("tema alterado") -> 1
            else -> 0
        }
    }

    private fun anexar(sb: SpannableStringBuilder, texto: String, cor: Int, negrito: Boolean) {
        val ini = sb.length
        sb.append(texto)
        sb.setSpan(ForegroundColorSpan(cor), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (negrito) sb.setSpan(StyleSpan(Typeface.BOLD), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
