package com.botsync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o monitoramento do IMAP v2 depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (Sessao.logado() && Sessao.imapAtivo && !Sessao.imapGmail.isNullOrEmpty()) {
            try {
                Imap2Service.iniciar(ctx)
            } catch (e: Exception) {
                // o sistema recusou agora; o alarme de vigia tenta de novo
            }
        }
    }
}
