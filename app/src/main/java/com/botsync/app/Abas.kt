package com.botsync.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout

const val ABA_HOME = 0
const val ABA_CHAT = 1
const val ABA_CONFIG = 2
const val ABA_ANTICHEAT = 3

/**
 * Envolve uma tela com a barra de baixo (Home, Chat, Anti Cheat, Configuração).
 * A barra some sozinha quando o teclado abre, para o campo de digitar ter espaço.
 */
fun Context.comAbas(conteudo: View, ativa: Int, ir: (Int) -> Unit): View {
    val raiz = LinearLayout(this)
    raiz.orientation = LinearLayout.VERTICAL
    raiz.addView(conteudo, LinearLayout.LayoutParams(-1, 0, 1f))

    val barra = LinearLayout(this)
    barra.orientation = LinearLayout.VERTICAL
    val linha = View(this)
    linha.setBackgroundColor(Cor.LINE)
    barra.addView(linha, LinearLayout.LayoutParams(-1, dp(1)))

    val itens = LinearLayout(this)
    itens.orientation = LinearLayout.HORIZONTAL
    itens.setBackgroundColor(0xFF0A0710.toInt())
    itens.setPadding(dp(8), dp(8), dp(8), dp(8))

    val abas = listOf(
        Triple("Home", R.drawable.ic_home, ABA_HOME),
        Triple("Chat", R.drawable.ic_chat, ABA_CHAT),
        Triple("Anti Cheat", R.drawable.ic_anticheat, ABA_ANTICHEAT),
        Triple("Configuração", R.drawable.ic_config, ABA_CONFIG)
    )
    for ((nome, ic, id) in abas) {
        val on = id == ativa
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val pilula = FrameLayout(this)
        if (on) pilula.background = fundo(0xFF2A1A4D.toInt(), 16, 0xFF3F2A66.toInt())
        pilula.addView(icone(ic, if (on) Cor.ACCENT_TXT else Cor.MUTED), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        col.addView(pilula, LinearLayout.LayoutParams(dp(60), dp(32)))
        val tv = texto(nome, 11f, if (on) Cor.FG else Cor.MUTED, on)
        tv.gravity = Gravity.CENTER
        col.addWrap(tv, 2)

        col.background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, fundo(0xFFFFFFFF.toInt(), 16))
        col.isClickable = true
        col.setOnClickListener { if (!on) ir(id) }
        itens.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    barra.addView(itens, LinearLayout.LayoutParams(-1, -2))
    raiz.addView(barra, LinearLayout.LayoutParams(-1, -2))

    // esconde a barra enquanto o teclado está aberto
    val ouvinte = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect()
        raiz.getWindowVisibleDisplayFrame(r)
        val teclado = raiz.rootView.height - r.bottom
        barra.visibility = if (teclado > dp(120)) View.GONE else View.VISIBLE
    }
    raiz.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { raiz.viewTreeObserver.addOnGlobalLayoutListener(ouvinte) }
        override fun onViewDetachedFromWindow(v: View) { raiz.viewTreeObserver.removeOnGlobalLayoutListener(ouvinte) }
    })
    return raiz
}
