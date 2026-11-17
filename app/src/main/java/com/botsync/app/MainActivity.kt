package com.botsync.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tela única (Home): liga o acesso às notificações e mostra os comprovantes (nome e valor). */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var raiz: FrameLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        raiz = FrameLayout(this)
        raiz.setBackgroundColor(Cor.BG)
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        // se o acesso já está liberado, pede ao sistema para religar o serviço (caso tenha sido encerrado)
        if (acessoLiberado()) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, NotificacaoService::class.java))
            } catch (e: Exception) { }
        }
        Registro.ouvinte = { ui.post { desenhar() } }
        desenhar()
    }

    override fun onPause() {
        super.onPause()
        Registro.ouvinte = null
    }

    private fun acessoLiberado(): Boolean {
        val lista = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return lista.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun abrirAcesso() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun desenhar() {
        val ativo = acessoLiberado()
        val c = coluna()
        c.setPadding(dp(16), dp(28), dp(16), dp(40))

        c.add(texto("Comprovantes", 24f, Cor.FG, true))
        c.add(texto("Monitorando as notificações do banco", 14f, Cor.MUTED), 2)

        val st = cartao()
        st.addView(linha(
            chip(if (ativo) "Monitoramento ativo" else "Acesso desligado", if (ativo) "ok" else "warn")
        ))
        if (ativo) {
            st.add(texto("Cada comprovante de dinheiro recebido aparece aqui com o nome e o valor.", 14f, Cor.MUTED), 10)
        } else {
            st.add(texto("Para monitorar, libere o acesso às notificações para este app e ative a chave \"BotSync\".", 14f, Cor.MUTED), 10)
            st.add(botaoAcao("Ativar notificações", null, true) { abrirAcesso() }, 14)
        }
        c.add(st, 18)

        val itens = Registro.lista()
        val cab = LinearLayout(this)
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(rotulo("Recebidos (${itens.size})"), LinearLayout.LayoutParams(0, -2, 1f))
        if (itens.isNotEmpty()) {
            val limpar = texto("Limpar", 13f, Cor.ACCENT_TXT, true)
            limpar.setPadding(dp(8), dp(4), dp(8), dp(4))
            limpar.setOnClickListener { Registro.limpar() }
            cab.addView(limpar)
        }
        c.add(cab, 22)

        if (itens.isEmpty()) {
            c.add(texto("Nenhum comprovante ainda.", 14f, Cor.MUTED), 10)
        } else {
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
            for (r in itens) {
                val card = cartao()
                val topo = LinearLayout(this)
                topo.gravity = Gravity.CENTER_VERTICAL
                topo.addView(texto(r.nome, 16f, Cor.FG, true), LinearLayout.LayoutParams(0, -2, 1f))
                topo.addView(texto(r.valor, 16f, Cor.OK, true))
                card.addView(topo)
                card.add(texto("${r.app} · ${fmt.format(Date(r.hora))}", 12f, Cor.MUTED), 4)
                c.add(card, 10)
            }
        }

        val s = ScrollView(this)
        s.isFillViewport = true
        s.addView(c, FrameLayout.LayoutParams(-1, -2))
        raiz.removeAllViews()
        raiz.addView(s, FrameLayout.LayoutParams(-1, -1))
    }
}
