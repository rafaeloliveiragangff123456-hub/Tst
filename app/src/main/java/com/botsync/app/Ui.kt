package com.botsync.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    val base = fundo(if (primario) Cor.ACCENT else Cor.SURFACE2, 8, if (primario) Cor.ACCENT else Cor.LINE)
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
