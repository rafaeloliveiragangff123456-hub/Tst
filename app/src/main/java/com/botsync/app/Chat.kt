package com.botsync.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Estado do chat de IA. Fica fora da tela para a conversa (e a resposta que ainda está chegando)
 * sobreviver à troca de aba. A IA roda NO SERVIDOR: o app só manda a pergunta e o modelo
 * (basic | max | extreme) para as rotas de IA do servidor (/app/api/ia/...) e recebe a resposta já filtrada.
 */
object ChatEstado {
    class Fonte(val dominio: String, val url: String)
    class Msg(
        val papel: String,                       // "user" | "ia"
        val texto: String,
        val modelo: String = "basic",
        val erro: Boolean = false,
        val fontes: List<Fonte> = emptyList(),
        val opcoes: List<String> = emptyList(),
        val verificada: Boolean = false
    )

    val mensagens = mutableListOf<Msg>()
    var modelo = "basic"
    var pendente = false
    var progresso = ""
    var cotaTxt: String? = null
    var ouvinte: (() -> Unit)? = null             // a tela aberta se registra aqui para redesenhar
    var aoErroFatal: ((Throwable) -> Unit)? = null // versão antiga / sessão expirada: quem manda é a MainActivity

    private var geracao = 0                       // sobe ao limpar: respostas atrasadas são descartadas
    private var chatId = novoId()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private fun novoId(): String {
        val b = ByteArray(5); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }      // 10 letras/números: cada conversa tem histórico próprio no servidor
    }

    /** Conversa nova (ou saiu da conta): apaga tudo daqui e descarta qualquer resposta em andamento. */
    fun limpar(zerarCota: Boolean = false) {
        geracao++
        mensagens.clear(); pendente = false; progresso = ""
        if (zerarCota) cotaTxt = null
        chatId = novoId()
        ouvinte?.invoke()
    }

    fun cotaTexto(j: JSONObject?): String? {
        if (j == null) return null
        if (j.optBoolean("ilimitado")) return "Tokens ilimitados"
        if (j.isNull("disponivel")) return null
        return String.format(Locale("pt", "BR"), "%,d tokens hoje", j.optLong("disponivel"))
    }

    fun carregarCota() {
        if (!Sessao.logado()) return
        val g = geracao
        io.execute {
            val t = try { cotaTexto(Api.iaCota()) } catch (e: Exception) { null }
            if (t != null) ui.post { if (g == geracao) { cotaTxt = t; ouvinte?.invoke() } }
        }
    }

    fun enviar(texto: String) {
        if (pendente || texto.isBlank()) return
        mensagens.add(Msg("user", texto))
        pendente = true; progresso = ""
        ouvinte?.invoke()
        val g = geracao; val m = modelo; val chat = chatId
        io.execute {
            var fatal: Throwable? = null
            val resposta: Msg = try {
                val job = Api.iaEnviar(texto, m, chat)
                val inicio = System.currentTimeMillis()
                var falhas = 0
                var pronta: Msg? = null
                laco@ while (true) {
                    if (g != geracao) return@execute
                    Thread.sleep(if (m == "extreme") 2500L else 1200L)
                    if (g != geracao) return@execute
                    if (System.currentTimeMillis() - inicio > 15 * 60_000L) { pronta = Msg("ia", "A IA demorou demais para responder. Tente de novo.", m, true); break@laco }
                    val j = try { Api.iaStatus(job) } catch (e: Exception) {
                        val fatalApi = e is ApiErro && e.codigo in intArrayOf(401, 403, 404, 426)
                        if (fatalApi || ++falhas > 5) throw e
                        continue@laco
                    }
                    falhas = 0
                    when (j.optString("estado")) {
                        "andando" -> {
                            val pr = j.optJSONObject("progresso")
                            var t = pr?.optString("texto") ?: ""
                            if (pr != null && !pr.isNull("feitos") && !pr.isNull("total") && pr.optInt("total") > 0) t += "  (" + pr.optInt("feitos") + "/" + pr.optInt("total") + ")"
                            ui.post { if (g == geracao) { progresso = t; ouvinte?.invoke() } }
                        }
                        "pronto" -> {
                            val fs = ArrayList<Fonte>()
                            val fa = j.optJSONObject("meta")?.optJSONArray("fontes")
                            if (fa != null) for (i in 0 until minOf(fa.length(), 4)) {
                                val o = fa.optJSONObject(i) ?: continue
                                val u = o.optString("url")
                                if (u.startsWith("https://") || u.startsWith("http://")) fs.add(Fonte(o.optString("dominio").ifEmpty { u }, u))
                            }
                            val oa = j.optJSONObject("meta")?.optJSONArray("opcoes")
                            val ops = ArrayList<String>()
                            if (oa != null) for (i in 0 until minOf(oa.length(), 4)) oa.optString(i).takeIf { it.isNotBlank() }?.let { ops.add(it) }
                            val cota = cotaTexto(j.optJSONObject("cota"))
                            if (cota != null) ui.post { cotaTxt = cota }
                            var tx = j.optString("texto")
                            if (j.optBoolean("projeto")) tx += "\n\nEsse pedido gerou um projeto em ZIP. O download dos arquivos só está disponível no site."
                            pronta = Msg("ia", tx, j.optString("modelo", m), false, fs, ops, j.optJSONObject("meta")?.optBoolean("verificada") ?: false)
                            break@laco
                        }
                        else -> { pronta = Msg("ia", j.optString("erro").ifEmpty { "A IA não conseguiu responder agora. Tente de novo em instantes." }, m, true); break@laco }
                    }
                }
                pronta!!
            } catch (e: Exception) {
                if (e is ApiErro && (e.codigo == 426 || (e.codigo == 401 && !Sessao.logado()))) fatal = e
                val txt = when (e) {
                    is ApiErro -> e.message ?: "Erro"
                    is java.io.IOException -> "Sem conexão com o servidor. Verifique a internet e tente de novo."
                    else -> "Algo deu errado. Tente novamente."
                }
                Msg("ia", txt, m, true)
            }
            ui.post {
                if (g != geracao) return@post
                pendente = false; progresso = ""
                mensagens.add(resposta)
                val f = fatal
                if (f != null) aoErroFatal?.invoke(f) else ouvinte?.invoke()
            }
        }
    }
}

private val NOMES_MODELO = mapOf("basic" to "Basic", "max" to "Max", "extreme" to "Extreme")
private val DESC_MODELO = mapOf(
    "basic" to "Respostas rápidas e leves.",
    "max" to "4 IAs pesquisam em fontes confiáveis e conferem a resposta. Gasta o dobro de tokens.",
    "extreme" to "20+ IAs em equipe. Pode levar vários minutos e gasta muitos tokens."
)

/** Markdown simples que a IA devolve: **negrito**, `código`, blocos ```, listas e títulos. */
private fun md(src: String): CharSequence {
    val sb = SpannableStringBuilder()
    val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    val fundoCodigo = 0xFF1D1233.toInt()
    val rxTitulo = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")
    val rxItem = Regex("^\\s*[-*+]\\s+(.*)$")
    val rxInline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var codigo = false
    val linhas = src.replace("\r", "").split("\n")
    for ((i, bruta) in linhas.withIndex()) {
        if (bruta.trimStart().startsWith("```")) { codigo = !codigo; continue }
        val ini = sb.length
        if (codigo) {
            sb.append(bruta)
            sb.setSpan(TypefaceSpan("monospace"), ini, sb.length, f)
            sb.setSpan(BackgroundColorSpan(fundoCodigo), ini, sb.length, f)
        } else {
            var l = bruta
            var titulo = false
            val h = rxTitulo.find(l)
            if (h != null) { l = h.groupValues[1]; titulo = true }
            else rxItem.find(l)?.let { l = "•  " + it.groupValues[1] }
            var pos = 0
            for (mm in rxInline.findAll(l)) {
                sb.append(l.substring(pos, mm.range.first))
                val a = sb.length
                if (mm.groups[1] != null) {
                    sb.append(mm.groupValues[1]); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, f)
                } else {
                    sb.append(mm.groupValues[2])
                    sb.setSpan(TypefaceSpan("monospace"), a, sb.length, f)
                    sb.setSpan(BackgroundColorSpan(fundoCodigo), a, sb.length, f)
                    sb.setSpan(ForegroundColorSpan(Cor.ACCENT_TXT), a, sb.length, f)
                }
                pos = mm.range.last + 1
            }
            sb.append(l.substring(pos))
            if (titulo) { sb.setSpan(StyleSpan(Typeface.BOLD), ini, sb.length, f); sb.setSpan(RelativeSizeSpan(1.1f), ini, sb.length, f) }
        }
        if (i < linhas.size - 1) sb.append("\n")
    }
    while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
    return sb
}

/**
 * Tela do chat de IA: escolha do modelo (Basic / Max / Extreme), mensagens e campo de digitar.
 * A resposta vem do servidor (ver ChatEstado.enviar).
 */
fun Context.telaChat(nome: String): View {
    val raiz = LinearLayout(this)
    raiz.orientation = LinearLayout.VERTICAL
    raiz.background = brilhoFundo()

    // ── cabeçalho ──
    val cab = linha()
    cab.setPadding(dp(16), dp(14), dp(16), dp(10))
    cab.addView(RoboView(this), LinearLayout.LayoutParams(dp(40), dp(48)))
    val tit = coluna()
    tit.addView(texto("BotSync IA", 19f, Cor.FG, true))
    val tvCota = rotulo("Assistente")
    tit.addView(tvCota)
    val lpT = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    lpT.leftMargin = dp(12)
    cab.addView(tit, lpT)
    cab.addView(botaoIcone(R.drawable.ic_atualizar) { ChatEstado.limpar() })      // conversa nova
    raiz.addView(cab, LinearLayout.LayoutParams(-1, -2))

    // ── corpo: tela inicial (robô + sugestões) ou lista de mensagens ──
    val corpo = FrameLayout(this)
    raiz.addView(corpo, LinearLayout.LayoutParams(-1, 0, 1f))

    val vazioScroll = ScrollView(this)
    vazioScroll.isFillViewport = true
    val vazio = coluna()
    vazio.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
    vazio.setPadding(dp(20), dp(8), dp(20), dp(16))
    vazio.addView(RoboView(this), LinearLayout.LayoutParams(dp(150), dp(180)))
    val ola = texto("Olá" + (if (nome.isNotBlank()) ", $nome" else "") + "!", 24f, Cor.FG, true)
    ola.gravity = Gravity.CENTER
    vazio.add(ola, 6)
    val sub = texto("Como posso ajudar você hoje?", 14f, Cor.MUTED)
    sub.gravity = Gravity.CENTER
    vazio.add(sub, 4)
    vazioScroll.addView(vazio, FrameLayout.LayoutParams(-1, -2))

    val listaScroll = ScrollView(this)
    val msgs = coluna()
    msgs.setPadding(dp(16), dp(8), dp(16), dp(12))
    listaScroll.addView(msgs, FrameLayout.LayoutParams(-1, -2))

    corpo.addView(vazioScroll, FrameLayout.LayoutParams(-1, -1))
    corpo.addView(listaScroll, FrameLayout.LayoutParams(-1, -1))

    // ── escolha do modelo + campo de digitar ──
    val rodape = coluna()
    rodape.setPadding(dp(12), dp(6), dp(12), dp(12))

    val linhaModelos = LinearLayout(this)
    linhaModelos.orientation = LinearLayout.HORIZONTAL
    rodape.addView(linhaModelos, LinearLayout.LayoutParams(-1, -2))
    val tvDesc = texto("", 11f, Cor.MUTED)
    rodape.add(tvDesc, 6)

    val caixa = LinearLayout(this)
    caixa.orientation = LinearLayout.HORIZONTAL
    caixa.gravity = Gravity.BOTTOM
    caixa.setPadding(dp(18), dp(4), dp(6), dp(6))
    caixa.background = fundo(Cor.SURFACE, 26, Cor.LINE)
    rodape.add(caixa, 8)

    val campo = EditText(this)
    campo.background = null
    campo.hint = "Pergunte qualquer coisa…"
    campo.setHintTextColor(Cor.MUTED)
    campo.setTextColor(Cor.FG)
    campo.textSize = 15f
    campo.maxLines = 5
    campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    campo.setPadding(0, dp(10), 0, dp(10))
    caixa.addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val enviar = FrameLayout(this)
    enviar.addView(icone(R.drawable.ic_enviar, 0xFFFFFFFF.toInt()), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    val lpE = LinearLayout.LayoutParams(dp(42), dp(42))
    lpE.leftMargin = dp(8)
    lpE.gravity = Gravity.BOTTOM
    caixa.addView(enviar, lpE)
    raiz.addView(rodape, LinearLayout.LayoutParams(-1, -2))

    fun atualizarBotao() {
        val pode = campo.text.isNotBlank() && !ChatEstado.pendente
        enviar.background = if (pode) gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 21, 0xFF9F67FF.toInt()) else fundo(Cor.SURFACE2, 21, Cor.LINE)
        enviar.alpha = if (pode) 1f else 0.6f
        enviar.isClickable = pode
    }

    fun desenharModelos() {
        linhaModelos.removeAllViews()
        for (id in listOf("basic", "max", "extreme")) {
            val on = ChatEstado.modelo == id
            val t = texto(NOMES_MODELO[id] ?: id, 13f, if (on) 0xFFFFFFFF.toInt() else Cor.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(8), dp(8), dp(8), dp(8))
            t.background = if (on) gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 18, 0xFF9F67FF.toInt()) else fundo(Cor.SURFACE2, 18, Cor.LINE)
            t.isClickable = true
            t.setOnClickListener { if (!ChatEstado.pendente) { ChatEstado.modelo = id; desenharModelos() } }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (id != "basic") lp.leftMargin = dp(8)
            linhaModelos.addView(t, lp)
        }
        tvDesc.text = DESC_MODELO[ChatEstado.modelo] ?: ""
    }

    fun rolar() { listaScroll.post { listaScroll.fullScroll(View.FOCUS_DOWN) } }

    fun bolhaUsuario(t: String) {
        val tv = texto(t, 15f, 0xFFFFFFFF.toInt())
        tv.background = gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 18)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        tv.setTextIsSelectable(true)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.topMargin = dp(12)
        msgs.addView(tv, lp)
    }

    fun abrir(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* sem navegador */ }
    }

    fun mandar(t: String) {
        val limpo = t.trim()
        if (limpo.isEmpty() || ChatEstado.pendente) return
        campo.setText("")
        ChatEstado.enviar(limpo)
    }

    fun bolhaIA(m: ChatEstado.Msg, ultima: Boolean) {
        val col = coluna()
        val cabIA = linha()
        cabIA.addView(texto("BOTSYNC IA · " + (NOMES_MODELO[m.modelo] ?: "Basic").uppercase(), 10f, Cor.ACCENT_TXT, true))
        if (m.verificada && !m.erro) {
            val ch = chip("Auditada", "ok")
            ch.textSize = 10f
            val lpc = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lpc.leftMargin = dp(8)
            cabIA.addView(ch, lpc)
        }
        col.addView(cabIA)
        val tv = texto("", 15f, if (m.erro) Cor.BAD else Cor.FG)
        tv.text = if (m.erro) m.texto else md(m.texto)
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        tv.setTextIsSelectable(true)
        if (m.erro) { tv.background = fundo(Cor.BAD_SOFT, 12); tv.setPadding(dp(12), dp(10), dp(12), dp(10)) }
        col.add(tv, 6)
        for (f in m.fontes) {
            val r = linha()
            r.addView(icone(R.drawable.ic_seta, Cor.ACCENT_TXT), LinearLayout.LayoutParams(dp(14), dp(14)))
            val lt = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lt.leftMargin = dp(6)
            r.addView(texto(f.dominio, 12f, Cor.ACCENT_TXT), lt)
            r.setPadding(0, dp(4), 0, dp(4))
            r.isClickable = true
            r.setOnClickListener { abrir(f.url) }
            col.add(r, 4)
        }
        if (ultima) for (o in m.opcoes) {
            val b = texto(o, 13f, Cor.FG)
            b.setPadding(dp(14), dp(9), dp(14), dp(9))
            b.background = fundo(Cor.SURFACE2, 18, Cor.LINE)
            b.isClickable = true
            b.setOnClickListener { mandar(o) }
            col.addWrap(b, 8)
        }
        msgs.add(col, 16)
    }

    fun bolhaPensando() {
        val r = linha()
        r.addView(RoboView(this), LinearLayout.LayoutParams(dp(34), dp(41)))
        val c = coluna()
        val t = texto("Pensando…", 14f, Cor.MUTED)
        c.addView(t)
        if (ChatEstado.progresso.isNotEmpty()) c.add(texto(ChatEstado.progresso, 12f, Cor.ACCENT_TXT), 2)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(10)
        r.addView(c, lp)
        val pulso = ObjectAnimator.ofFloat(t, "alpha", 1f, 0.35f)
        pulso.duration = 700
        pulso.repeatMode = ValueAnimator.REVERSE
        pulso.repeatCount = ValueAnimator.INFINITE
        pulso.start()
        r.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { pulso.cancel() }
        })
        msgs.add(r, 16)
    }

    // redesenha tudo a partir do ChatEstado (chamado quando chega resposta, progresso ou conversa nova)
    fun desenhar() {
        val tem = ChatEstado.mensagens.isNotEmpty() || ChatEstado.pendente
        vazioScroll.visibility = if (tem) View.GONE else View.VISIBLE
        listaScroll.visibility = if (tem) View.VISIBLE else View.GONE
        tvCota.text = (ChatEstado.cotaTxt ?: "Assistente").uppercase()
        msgs.removeAllViews()
        val ms = ChatEstado.mensagens
        for ((i, m) in ms.withIndex()) {
            if (m.papel == "user") bolhaUsuario(m.texto) else bolhaIA(m, i == ms.size - 1 && !ChatEstado.pendente)
        }
        if (ChatEstado.pendente) bolhaPensando()
        atualizarBotao()
        if (tem) rolar()
    }

    // sugestões da tela inicial
    for (sug in listOf("Resumir meu faturamento de hoje", "Como configurar o IMAP v2?", "Ver o status das minhas contas")) {
        val card = linha()
        card.setPadding(dp(16), dp(14), dp(14), dp(14))
        card.background = fundo(Cor.SURFACE, 14, Cor.LINE)
        card.addView(texto(sug, 14f, Cor.FG), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(icone(R.drawable.ic_seta, Cor.ACCENT_TXT), LinearLayout.LayoutParams(dp(18), dp(18)))
        card.isClickable = true
        card.setOnClickListener {
            campo.setText(sug)
            campo.setSelection(sug.length)
            campo.requestFocus()
        }
        vazio.add(card, 10)
    }

    campo.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { atualizarBotao() }
        override fun afterTextChanged(s: Editable?) {}
    })
    enviar.setOnClickListener { mandar(campo.text.toString()) }

    // a tela se registra para ser avisada; ao sair da tela, solta o registro
    val aviso: () -> Unit = { desenhar() }
    raiz.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { ChatEstado.ouvinte = aviso; desenhar() }
        override fun onViewDetachedFromWindow(v: View) { if (ChatEstado.ouvinte === aviso) ChatEstado.ouvinte = null }
    })
    desenharModelos()
    desenhar()
    ChatEstado.carregarCota()
    return raiz
}
