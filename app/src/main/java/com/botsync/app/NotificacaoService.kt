package com.botsync.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Fica de olho nas notificações dos aplicativos de banco. Quando cai um comprovante de dinheiro recebido,
 * extrai o nome de quem enviou e o valor e guarda no Registro. Nada sai do aparelho (o app nem tem internet).
 */
class NotificacaoService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        val banco = BANCOS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val ex = n.extras
        val titulo = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val corpo = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val r = Leitor.ler(titulo, corpo) ?: return

        Registro.adicionar("${sbn.key}|${sbn.postTime}", Comprovante(r.first, r.second, sbn.postTime, banco))
    }

    companion object {
        /** Pacote do app → nome para mostrar. Para monitorar outro banco, é só acrescentar uma linha. */
        val BANCOS = mapOf(
            "com.picpay" to "PicPay",
            "com.nu.production" to "Nubank",
            "br.com.intermedium" to "Inter",
            "com.itau" to "Itaú",
            "com.bradesco" to "Bradesco",
            "br.com.bb.android" to "Banco do Brasil",
            "com.santander.app" to "Santander",
            "br.com.gabba.Caixa" to "Caixa",
            "com.mercadopago.wallet" to "Mercado Pago",
            "com.c6bank.app" to "C6 Bank",
            "br.com.neon" to "Neon",
            "br.com.original.bank" to "Original",
            "br.com.uol.ps.myaccount" to "PagBank",
            "com.xp.carteira" to "XP",
            "br.com.sicredi.mobile" to "Sicredi",
            "br.com.sicoobnet.sicoob" to "Sicoob"
        )
    }
}

/** Interpreta o texto da notificação. */
object Leitor {
    private val valorRx = Regex("""R\$\s*((?:\d{1,3}(?:\.\d{3})+|\d+)(?:,\d{1,2})?)""")

    /** Só dinheiro que ENTROU. Pagamentos e Pix enviados por você são ignorados. */
    private val entrou = Regex(
        """(?i)\b(recebeu|recebido|recebida|recebemos|te\s+enviou|te\s+transferiu|enviou\s+(?:um|uma|r\$)|caiu|creditad[oa]|depósito|deposito)\b"""
    )
    private val saiu = Regex("""(?i)\b(você\s+(?:enviou|pagou|transferiu)|voce\s+(?:enviou|pagou|transferiu)|pagamento\s+(?:realizado|efetuado)|compra\s+aprovada|débito|debito)\b""")

    private val nomeRx = listOf(
        // "Maria Silva te enviou um Pix de R$ 10,00"
        Regex("""^\s*(.+?)\s+(?:te\s+enviou|te\s+transferiu|enviou)\b""", RegexOption.IGNORE_CASE),
        // "Você recebeu um Pix de Maria Silva no valor de R$ 10,00" / "Pix recebido de Maria Silva"
        Regex("""\b(?:recebeu|recebido|recebida|recebemos)\b.*?\bde\s+(?!R\$)(.+?)(?:\s+no\s+valor|\s+via|\s+às|\s+as\s|\s+R\$|\s*[.,!]|\s*$)""", RegexOption.IGNORE_CASE),
        // "R$ 10,00 de Maria Silva"
        Regex("""R\$\s*[\d.,]+\s+de\s+(.+?)(?:\s+via|\s+às|\s*[.,!]|\s*$)""", RegexOption.IGNORE_CASE)
    )
    private val generico = Regex("""(?i)^(pix|pix recebido|transfer[eê]ncia.*|pagamento.*|voc[eê] recebeu.*|dinheiro.*|nova.*|notifica.*)$""")

    fun ler(titulo: String, corpo: String): Pair<String, String>? {
        val texto = "$titulo. $corpo".replace('\n', ' ').trim()
        val m = valorRx.find(texto) ?: return null
        if (!entrou.containsMatchIn(texto) || saiu.containsMatchIn(texto)) return null
        val valor = "R$ " + m.groupValues[1].let { if (it.contains(',')) it else "$it,00" }

        var nome: String? = null
        for (alvo in listOf(corpo.replace('\n', ' '), texto)) {
            for (rx in nomeRx) {
                val g = rx.find(alvo)?.groupValues?.get(1)?.trim(' ', '.', ',', '!', ':')
                if (!g.isNullOrEmpty() && g.length <= 60 && !g.contains("R$") && !g.matches(Regex("(?i)voc[eê]|um pix|uma transfer.*"))) { nome = g; break }
            }
            if (nome != null) break
        }
        if (nome == null && titulo.isNotBlank() && !generico.matches(titulo.trim()) && !valorRx.containsMatchIn(titulo)) nome = titulo.trim()
        return Pair(nome ?: "Não identificado", valor)
    }
}
