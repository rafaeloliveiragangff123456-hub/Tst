package com.botsync.app

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

class GmailErro(val tipo: String, mensagem: String, val intent: Intent? = null) : Exception(mensagem)

/**
 * Lê o Gmail pela conta Google do aparelho (OAuth, escopo gmail.readonly) — sem senha de app.
 * Só procura e-mails dos remetentes pedidos pela rota (o banco) e só dos últimos dias.
 */
class GmailLeitor(private val ctx: Context, private val conta: Account) {
    private var tokenAtual: String? = null
    private val escopo = "oauth2:https://www.googleapis.com/auth/gmail.readonly"

    fun token(renovar: Boolean = false): String {
        if (renovar) {
            val t = tokenAtual
            if (t != null) {
                try { GoogleAuthUtil.clearToken(ctx, t) } catch (e: Exception) { /* segue */ }
            }
        }
        try {
            val t = GoogleAuthUtil.getToken(ctx, conta, escopo)
            tokenAtual = t
            return t
        } catch (e: UserRecoverableAuthException) {
            throw GmailErro("auth", "Autorização do Gmail necessária.", e.intent)
        } catch (e: GoogleAuthException) {
            throw GmailErro("auth", e.message ?: "Falha de autorização.")
        }
    }

    private fun get(url: String, jaTentou: Boolean = false): JSONObject {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer " + token())
            c.setRequestProperty("Accept", "application/json")
            val cod = c.responseCode
            if (cod == 401 && !jaTentou) {
                token(true)
                return get(url, true)
            }
            if (cod == 401) throw GmailErro("auth", "Gmail recusou a autorização.")
            if (cod == 403) throw GmailErro("permissao", "Sem permissão para ler o Gmail (API não habilitada ou escopo negado).")
            if (cod !in 200..299) throw IOException("gmail $cod")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun buscar(froms: List<String>, dias: Int, max: Int, conhecidos: Set<String>): List<JSONObject> {
        val seguros = froms.filter { it.matches(Regex("[A-Za-z0-9._@-]{2,60}")) }.take(4)
        if (seguros.isEmpty()) return emptyList()
        val q = "(" + seguros.joinToString(" OR ") { "from:$it" } + ") newer_than:" + dias.coerceIn(1, 3) + "d"
        val lista = get("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=" + max.coerceIn(1, 40) + "&q=" + URLEncoder.encode(q, "UTF-8"))
        val msgs = lista.optJSONArray("messages") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until msgs.length()) {
            val id = msgs.getJSONObject(i).getString("id")
            if (!conhecidos.contains(id)) ids.add(id)
        }
        val novos = ids.take(25)
        if (novos.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futuros = novos.map { id ->
                pool.submit(Callable { montar(get("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=full")) })
            }
            return futuros.map { it.get() }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            pool.shutdown()
        }
    }

    private fun montar(m: JSONObject): JSONObject {
        val payload = m.getJSONObject("payload")
        val hs = payload.optJSONArray("headers") ?: JSONArray()
        fun cab(nome: String): String {
            for (i in 0 until hs.length()) {
                val o = hs.getJSONObject(i)
                if (o.optString("name").equals(nome, true)) return o.optString("value")
            }
            return ""
        }
        val auth = JSONArray()
        for (i in 0 until hs.length()) {
            val o = hs.getJSONObject(i)
            if (o.optString("name").equals("Authentication-Results", true)) auth.put(o.optString("value").take(2000))
        }
        val texto = StringBuilder()
        val html = StringBuilder()
        coletar(payload, texto, html)
        return JSONObject()
            .put("id", m.getString("id"))
            .put("data", m.optString("internalDate", "0").toLongOrNull() ?: 0L)
            .put("de", cab("From").take(400))
            .put("para", cab("To").take(800))
            .put("assunto", cab("Subject").take(500))
            .put("messageId", cab("Message-ID").take(400))
            .put("dataHeader", cab("Date").take(100))
            .put("auth", auth)
            .put("texto", texto.toString().take(80000))
            .put("html", html.toString().take(250000))
    }

    private fun charsetDe(p: JSONObject): Charset {
        val hs = p.optJSONArray("headers") ?: return Charsets.UTF_8
        for (i in 0 until hs.length()) {
            val o = hs.getJSONObject(i)
            if (o.optString("name").equals("Content-Type", true)) {
                val m = Regex("charset=\"?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(o.optString("value"))
                if (m != null) {
                    try { return Charset.forName(m.groupValues[1]) } catch (e: Exception) { /* usa UTF-8 */ }
                }
            }
        }
        return Charsets.UTF_8
    }

    private fun coletar(p: JSONObject, t: StringBuilder, h: StringBuilder) {
        val partes = p.optJSONArray("parts")
        if (partes != null) {
            for (i in 0 until partes.length()) coletar(partes.getJSONObject(i), t, h)
            return
        }
        if (p.optString("filename").isNotEmpty()) return
        val dados = p.optJSONObject("body")?.optString("data") ?: ""
        if (dados.isEmpty()) return
        val txt = String(Base64.decode(dados, Base64.URL_SAFE), charsetDe(p))
        val mime = p.optString("mimeType")
        if (mime.equals("text/plain", true)) t.append(txt).append('\n')
        else if (mime.equals("text/html", true)) h.append(txt).append('\n')
    }
}
