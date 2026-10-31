package com.botsync.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Mesma paleta do painel web (index.html). */
object Cor {
    const val BG = 0xFF070509.toInt()
    const val SURFACE = 0xFF0E0A15.toInt()
    const val SURFACE2 = 0xFF161022.toInt()
    const val LINE = 0xFF251B36.toInt()
    const val FG = 0xFFF1ECF9.toInt()
    const val MUTED = 0xFF9A8FB0.toInt()
    const val ACCENT = 0xFF7C3AED.toInt()
    const val ACCENT_TXT = 0xFFA78BFA.toInt()
    const val ACCENT_SOFT = 0xFF1D1233.toInt()
    const val OK = 0xFF34D399.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val BAD = 0xFFF87171.toInt()
    const val OK_SOFT = 0xFF0D2A21.toInt()
    const val WARN_SOFT = 0xFF2E2208.toInt()
    const val BAD_SOFT = 0xFF2F1218.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.fundo(cor: Int, raioDp: Int, borda: Int? = null): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(cor)
    g.cornerRadius = dp(raioDp).toFloat()
    if (borda != null) g.setStroke(dp(1), borda)
    return g
}

fun Context.texto(t: String, tam: Float = 15f, cor: Int = Cor.FG, negrito: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = tam
    v.setTextColor(cor)
    if (negrito) v.setTypeface(v.typeface, Typeface.BOLD)
    return v
}

fun Context.coluna(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.cartao(): LinearLayout {
    val l = coluna()
    l.setPadding(dp(16), dp(16), dp(16), dp(16))
    l.background = fundo(Cor.SURFACE, 12, Cor.LINE)
    return l
}

fun Context.botao(t: String, primario: Boolean, acao: () -> Unit): Button {
    val b = Button(this)
    b.text = t
    b.isAllCaps = false
    b.textSize = 15f
    b.setTypeface(b.typeface, Typeface.BOLD)
    b.setTextColor(if (primario) 0xFFFFFFFF.toInt() else Cor.FG)
    val base = if (primario) gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 12, 0xFF9F67FF.toInt()) else fundo(Cor.SURFACE2, 12, Cor.LINE)
    b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
    b.stateListAnimator = null
    b.minHeight = dp(46)
    b.setPadding(dp(16), dp(8), dp(16), dp(8))
    b.setOnClickListener { acao() }
    return b
}

/** tom: "ok" | "warn" | "bad" | "" (neutro/roxo) */
fun Context.chip(t: String, tom: String = ""): TextView {
    val (bg, fg) = when (tom) {
        "ok" -> Pair(Cor.OK_SOFT, Cor.OK)
        "warn" -> Pair(Cor.WARN_SOFT, Cor.WARN)
        "bad" -> Pair(Cor.BAD_SOFT, Cor.BAD)
        else -> Pair(Cor.ACCENT_SOFT, Cor.ACCENT_TXT)
    }
    val v = texto(t, 12f, fg, true)
    v.background = fundo(bg, 20)
    v.setPadding(dp(10), dp(4), dp(10), dp(4))
    return v
}

fun Context.linha(vararg views: View): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    for (v in views) l.addView(v)
    return l
}

/** Adiciona com margem superior. */
fun LinearLayout.add(v: View, topoDp: Int = 0): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = (topoDp * resources.displayMetrics.density).toInt()
    addView(v, lp)
    return v
}

fun LinearLayout.addWrap(v: View, topoDp: Int = 0, esqDp: Int = 0): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = (topoDp * resources.displayMetrics.density).toInt()
    lp.leftMargin = (esqDp * resources.displayMetrics.density).toInt()
    addView(v, lp)
    return v
}


// ── visual novo: gradientes, logo e icones vetoriais ─────────────────────────
fun Context.gradiente(c1: Int, c2: Int, raioDp: Int, borda: Int? = null): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
    g.cornerRadius = dp(raioDp).toFloat()
    if (borda != null) g.setStroke(dp(1), borda)
    return g
}

/** Fundo da tela de entrada: preto com brilho roxo no topo. */
fun Context.brilhoFundo(): LayerDrawable {
    val glow = GradientDrawable()
    glow.gradientType = GradientDrawable.RADIAL_GRADIENT
    glow.gradientRadius = dp(420).toFloat()
    glow.setGradientCenter(0.5f, 0.1f)
    glow.colors = intArrayOf(0x66A21CD0, 0x225B0B7A, 0x00070509)
    return LayerDrawable(arrayOf(ColorDrawable(Cor.BG), glow))
}

/** O MESMO logo do app (Bs / BOTSYNC), recortado com cantos arredondados. */
fun Context.logo(tamDp: Int, raioDp: Int): ImageView {
    val v = ImageView(this)
    v.setImageResource(R.drawable.logo_botsync)
    v.scaleType = ImageView.ScaleType.CENTER_CROP
    val r = dp(raioDp).toFloat()
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) }
    }
    v.clipToOutline = true
    v.layoutParams = LinearLayout.LayoutParams(dp(tamDp), dp(tamDp))
    return v
}

fun Context.icone(res: Int, cor: Int = Cor.ACCENT_TXT): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.imageTintList = ColorStateList.valueOf(cor)
    return v
}

fun Context.rotulo(t: String, cor: Int = Cor.ACCENT_TXT): TextView {
    val v = texto(t.uppercase(), 11f, cor, true)
    v.letterSpacing = 0.2f
    return v
}

/** Caixa quadrada com icone (usada em listas e cartoes). */
fun Context.caixaIcone(res: Int, tamDp: Int = 40, cor: Int = Cor.ACCENT_TXT, fundoCor: Int = Cor.ACCENT_SOFT): FrameLayout {
    val f = FrameLayout(this)
    f.background = fundo(fundoCor, 12)
    f.addView(icone(res, cor), FrameLayout.LayoutParams(dp(tamDp / 2 + 2), dp(tamDp / 2 + 2), Gravity.CENTER))
    f.layoutParams = LinearLayout.LayoutParams(dp(tamDp), dp(tamDp))
    return f
}

fun Context.botaoIcone(res: Int, acao: () -> Unit): FrameLayout {
    val f = FrameLayout(this)
    f.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fundo(Cor.SURFACE2, 12, Cor.LINE), null)
    f.addView(icone(res, Cor.ACCENT_TXT), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    f.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
    f.isClickable = true
    f.setOnClickListener { acao() }
    return f
}

/** Botao largo com icone + texto. */
fun Context.botaoAcao(t: String, iconeRes: Int?, primario: Boolean, acao: () -> Unit): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER
    l.minimumHeight = dp(52)
    l.setPadding(dp(16), dp(10), dp(16), dp(10))
    val base = if (primario) gradiente(0xFF8B3DF0.toInt(), 0xFF6D28D9.toInt(), 14, 0xFF9F67FF.toInt()) else fundo(Cor.SURFACE2, 14, Cor.LINE)
    l.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
    if (iconeRes != null) {
        val lp = LinearLayout.LayoutParams(dp(20), dp(20))
        lp.rightMargin = dp(10)
        l.addView(icone(iconeRes, if (primario) 0xFFFFFFFF.toInt() else Cor.ACCENT_TXT), lp)
    }
    l.addView(texto(t, 15f, if (primario) 0xFFFFFFFF.toInt() else Cor.FG, true))
    l.isClickable = true
    l.setOnClickListener { acao() }
    return l
}

/** Caixinha de numero (ex.: Filas hoje / Faturamento). */
fun Context.estatistica(rotuloTxt: String, valor: String): LinearLayout {
    val c = coluna()
    c.setPadding(dp(12), dp(10), dp(12), dp(10))
    c.background = fundo(Cor.SURFACE2, 12, Cor.LINE)
    c.addView(texto(rotuloTxt, 11f, Cor.MUTED))
    c.add(texto(valor, 17f, Cor.FG, true), 2)
    return c
}

/** Barra de progresso (0..100). */
fun Context.barra(pct: Int): LinearLayout {
    val p = pct.coerceIn(0, 100)
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.weightSum = 100f
    l.background = fundo(Cor.LINE, 4)
    val cheio = View(this)
    cheio.background = gradiente(0xFF8B3DF0.toInt(), 0xFFC084FC.toInt(), 4)
    l.addView(cheio, LinearLayout.LayoutParams(0, dp(7), p.toFloat()))
    l.addView(View(this), LinearLayout.LayoutParams(0, dp(7), (100 - p).toFloat()))
    return l
}
