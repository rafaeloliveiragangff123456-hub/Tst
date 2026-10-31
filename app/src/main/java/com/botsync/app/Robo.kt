package com.botsync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Robozinho feliz em "3D": desenhado à mão com gradientes, brilho e sombra.
 * Flutua, pisca, balança a cabeça, acena e olha na direção do dedo. Toque nele para ele ficar feliz.
 * Sem imagens e sem bibliotecas: é só um View.
 */
class RoboView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val t0 = SystemClock.uptimeMillis()

    private var alvoX = 0f
    private var alvoY = 0f
    private var olhaX = 0f
    private var olhaY = 0f
    private var festaIni = -100f

    private fun cor(v: Long) = v.toInt()
    private val claro = cor(0xFFE9DDFF)

    // Todos os gradientes usam as coordenadas do desenho (200 x 240), então acompanham cada peça quando ela se mexe.
    private val shCabeca = LinearGradient(35f, 48f, 165f, 142f, intArrayOf(cor(0xFFC9A8FF), cor(0xFF8B3DF0), cor(0xFF3A1280)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val shBrilho = RadialGradient(78f, 68f, 78f, intArrayOf(cor(0x66FFFFFF), cor(0x00FFFFFF)), null, Shader.TileMode.CLAMP)
    private val shBrilhoCorpo = RadialGradient(82f, 162f, 64f, intArrayOf(cor(0x55FFFFFF), cor(0x00FFFFFF)), null, Shader.TileMode.CLAMP)
    private val shBorda = LinearGradient(0f, 48f, 0f, 142f, intArrayOf(cor(0xAAFFFFFF), cor(0x11FFFFFF), cor(0x44000000)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val shOrelha = LinearGradient(0f, 76f, 0f, 114f, cor(0xFF6D28D9), cor(0xFF2A0F57), Shader.TileMode.CLAMP)
    private val shVidro = LinearGradient(0f, 60f, 0f, 130f, cor(0xFF1B1030), cor(0xFF06040B), Shader.TileMode.CLAMP)
    private val shCorpo = LinearGradient(62f, 150f, 138f, 206f, intArrayOf(cor(0xFFB48CFF), cor(0xFF7C3AED), cor(0xFF2E0F63)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val shBracoE = LinearGradient(46f, 0f, 58f, 0f, cor(0xFFA78BFA), cor(0xFF4C1D95), Shader.TileMode.CLAMP)
    private val shBracoD = LinearGradient(142f, 0f, 154f, 0f, cor(0xFFA78BFA), cor(0xFF4C1D95), Shader.TileMode.CLAMP)
    private val shMao = RadialGradient(0f, -3f, 10f, cor(0xFFE9DDFF), cor(0xFF8B3DF0), Shader.TileMode.CLAMP)
    private val shSombra = RadialGradient(0f, 0f, 50f, intArrayOf(cor(0x887C3AED), cor(0x00000000)), null, Shader.TileMode.CLAMP)
    private val shOlho = LinearGradient(0f, -12f, 0f, 12f, cor(0xFFFFFFFF), cor(0xFFC4B5FD), Shader.TileMode.CLAMP)
    private val shBrilhoOlho = RadialGradient(0f, 0f, 24f, intArrayOf(cor(0x77A78BFA), cor(0x00A78BFA)), null, Shader.TileMode.CLAMP)
    private val shAntena = RadialGradient(-3f, -3f, 11f, intArrayOf(cor(0xFFFFFFFF), cor(0xFFC084FC), cor(0xFF7C3AED)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    private val shAntenaGlow = RadialGradient(0f, 0f, 24f, intArrayOf(cor(0x99C084FC), cor(0x00C084FC)), null, Shader.TileMode.CLAMP)
    private val shPeito = RadialGradient(0f, 0f, 16f, intArrayOf(cor(0xFFFFFFFF), cor(0xFFC084FC), cor(0x00C084FC)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)

    private fun tempo(): Float = (SystemClock.uptimeMillis() - t0) / 1000f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onMeasure(w: Int, h: Int) {
        // proporção 200:240
        val ws = MeasureSpec.getSize(w)
        val hs = MeasureSpec.getSize(h)
        val wm = MeasureSpec.getMode(w)
        val hm = MeasureSpec.getMode(h)
        var largura = if (wm == MeasureSpec.UNSPECIFIED) context.dp(140) else ws
        var altura = (largura * 1.2f).toInt()
        if (hm != MeasureSpec.UNSPECIFIED && altura > hs) { altura = hs; largura = (altura / 1.2f).toInt() }
        setMeasuredDimension(largura, altura)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                festaIni = tempo()
                olhar(e)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> olhar(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { alvoX = 0f; alvoY = 0f }
        }
        return true
    }

    private fun olhar(e: MotionEvent) {
        alvoX = ((e.x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
        alvoY = ((e.y - height / 2f) / (height / 2f)).coerceIn(-1f, 1f)
    }

    private fun preencher(cv: Canvas, sh: Shader?, cor: Int = 0xFFFFFFFF.toInt()) {
        p.style = Paint.Style.FILL
        p.shader = sh
        p.color = cor
    }

    private fun traco(larg: Float, cor: Int, sh: Shader? = null) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = larg
        p.strokeCap = Paint.Cap.ROUND
        p.shader = sh
        p.color = cor
    }

    override fun onDraw(cv: Canvas) {
        val t = tempo()
        olhaX += (alvoX - olhaX) * 0.12f
        olhaY += (alvoY - olhaY) * 0.12f

        // momentos felizes: a cada 7s sozinho, ou quando alguém toca nele
        val ciclo = t % 7f
        val feliz1 = if (ciclo in 4.2f..5.6f) sin(((ciclo - 4.2f) / 1.4f) * PI).toFloat() else 0f
        val dt = t - festaIni
        val feliz2 = if (dt in 0f..1.6f) sin((dt / 1.6f) * PI).toFloat() else 0f
        val feliz = max(feliz1, feliz2).coerceIn(0f, 1f)
        val bt = t % 3.3f
        val abre = if (bt < 0.16f) max(abs(bt - 0.08f) / 0.08f, 0.08f) else 1f

        val s = min(width / 200f, height / 240f)
        cv.save()
        cv.translate((width - 200f * s) / 2f, (height - 240f * s) / 2f)
        cv.scale(s, s)

        val bob = sin(t * 2.2f) * 5f

        // sombra no chão (encolhe quando ele sobe)
        val sc = 1f - 0.12f * (bob / 5f)
        cv.save()
        cv.translate(100f, 230f)
        cv.scale(sc, 0.2f * sc)
        preencher(cv, shSombra)
        cv.drawCircle(0f, 0f, 50f, p)
        cv.restore()

        cv.translate(0f, bob)

        // ── corpo e braços ──
        val balanco = sin(t * 2.2f + 1f) * 3f
        // braço esquerdo
        cv.save()
        cv.rotate(8f + balanco, 52f, 166f)
        r.set(46f, 160f, 58f, 202f)
        preencher(cv, shBracoE); cv.drawRoundRect(r, 6f, 6f, p)
        cv.save(); cv.translate(52f, 203f)
        preencher(cv, shMao); cv.drawCircle(0f, 0f, 8f, p)
        cv.restore()
        cv.restore()
        // braço direito (acena quando está feliz)
        val angD = -8f - balanco * 0.6f * (1f - feliz) - feliz * 125f + sin(t * 13f) * 12f * feliz
        cv.save()
        cv.rotate(angD, 148f, 166f)
        r.set(142f, 160f, 154f, 202f)
        preencher(cv, shBracoD); cv.drawRoundRect(r, 6f, 6f, p)
        cv.save(); cv.translate(148f, 203f)
        preencher(cv, shMao); cv.drawCircle(0f, 0f, 8f, p)
        cv.restore()
        cv.restore()

        // tronco
        r.set(62f, 150f, 138f, 206f)
        preencher(cv, shCorpo); cv.drawRoundRect(r, 26f, 26f, p)
        preencher(cv, shBrilhoCorpo); cv.drawRoundRect(r, 26f, 26f, p)
        // luz do peito pulsando
        val pulso = 0.75f + 0.25f * sin(t * 4f)
        cv.save(); cv.translate(100f, 178f); cv.scale(pulso, pulso)
        preencher(cv, shPeito); cv.drawCircle(0f, 0f, 16f, p)
        cv.restore()

        // ── cabeça (balança e acompanha o dedo) ──
        cv.save()
        cv.rotate(sin(t * 1.1f) * 2.5f + olhaX * 7f, 100f, 142f)
        cv.translate(olhaX * 2f, 0f)

        // antena
        traco(3f, cor(0xFF6D3FC0)); cv.drawLine(100f, 50f, 100f, 33f, p)
        val ap = 0.8f + 0.2f * sin(t * 5f)
        cv.save(); cv.translate(100f, 25f); cv.scale(ap, ap)
        preencher(cv, shAntenaGlow); cv.drawCircle(0f, 0f, 24f, p)
        cv.restore()
        cv.save(); cv.translate(100f, 25f)
        preencher(cv, shAntena); cv.drawCircle(0f, 0f, 9f, p)
        cv.restore()

        // orelhas
        r.set(23f, 76f, 40f, 114f); preencher(cv, shOrelha); cv.drawRoundRect(r, 8f, 8f, p)
        r.set(160f, 76f, 177f, 114f); preencher(cv, shOrelha); cv.drawRoundRect(r, 8f, 8f, p)
        preencher(cv, null, cor(0xFFC084FC)); cv.drawCircle(31.5f, 95f, 3f, p); cv.drawCircle(168.5f, 95f, 3f, p)

        // casco da cabeça com luz, brilho e borda
        r.set(35f, 48f, 165f, 142f)
        preencher(cv, shCabeca); cv.drawRoundRect(r, 42f, 42f, p)
        preencher(cv, shBrilho); cv.drawRoundRect(r, 42f, 42f, p)
        traco(2f, 0xFFFFFFFF.toInt(), shBorda); cv.drawRoundRect(r, 42f, 42f, p)

        // rosto (vidro escuro) com paralaxe: mexe mais que o casco, dá sensação de profundidade
        cv.save()
        cv.translate(olhaX * 5f, olhaY * 3f)
        r.set(47f, 60f, 153f, 130f)
        preencher(cv, shVidro); cv.drawRoundRect(r, 30f, 30f, p)
        traco(1.5f, cor(0xFF4A2F7F)); cv.drawRoundRect(r, 30f, 30f, p)
        // reflexo no vidro
        path.reset(); path.moveTo(58f, 64f); path.lineTo(92f, 64f); path.lineTo(70f, 126f); path.lineTo(55f, 126f); path.close()
        cv.save(); cv.clipPath(roundClip(47f, 60f, 153f, 130f, 30f))
        preencher(cv, null, cor(0x0FFFFFFF)); cv.drawPath(path, p)
        cv.restore()

        // olhos
        for (ex in floatArrayOf(78f, 122f)) {
            cv.save(); cv.translate(ex + olhaX * 2.5f, 90f + olhaY * 2f)
            preencher(cv, shBrilhoOlho); cv.drawCircle(0f, 0f, 24f, p)
            if (feliz > 0.35f) {
                // olhos sorrindo: ^ ^
                path.reset(); path.moveTo(-9f, 5f); path.quadTo(0f, -11f, 9f, 5f)
                traco(4.5f, claro); cv.drawPath(path, p)
            } else {
                val h = 12f * abre
                r.set(-9f, -h, 9f, h)
                preencher(cv, shOlho); cv.drawRoundRect(r, 9f, 9f, p)
            }
            cv.restore()
        }

        // bochechas
        val ba = (0x40 + (feliz * 0x40).toInt()) shl 24
        preencher(cv, null, ba or 0xF472B6)
        cv.drawCircle(62f, 111f, 6f, p); cv.drawCircle(138f, 111f, 6f, p)

        // boca: sorriso; abre quando está bem feliz
        path.reset()
        if (feliz > 0.35f) {
            path.moveTo(87f, 104f); path.quadTo(100f, 104f + 12f + feliz * 14f, 113f, 104f); path.close()
            preencher(cv, null, claro); cv.drawPath(path, p)
        } else {
            path.moveTo(88f, 105f); path.quadTo(100f, 117f, 112f, 105f)
            traco(3.5f, claro); cv.drawPath(path, p)
        }
        cv.restore() // rosto
        cv.restore() // cabeça

        cv.restore()
        postInvalidateOnAnimation()
    }

    private val clipPath = Path()
    private fun roundClip(l: Float, t: Float, rr: Float, b: Float, raio: Float): Path {
        clipPath.reset()
        clipPath.addRoundRect(l, t, rr, b, raio, raio, Path.Direction.CW)
        return clipPath
    }
}
