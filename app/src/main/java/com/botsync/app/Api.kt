package com.botsync.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

class ApiErro(val codigo: Int, mensagem: String, val corpo: JSONObject? = null) : Exception(mensagem)

class Resposta(val codigo: Int, val json: JSONObject?)

/**
 * Cliente das rotas da API do servidor (caminho /app/api).
 *  - somente HTTPS; certificado conferido pelo sistema E (se configurado) fixado por pin SPKI
 *  - cada chamada é assinada com HMAC-SHA256 usando a chave exclusiva deste aparelho (anti-adulteração e anti-replay)
 *  - token de acesso curto (15 min) renovado com refresh rotativo
 */
object Api {
    private val rnd = SecureRandom()
    @Volatile private var skew = 0L
    private val lockRefresh = Any()

    private fun b64u(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun sha256hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun agora(): Long = System.currentTimeMillis() + skew

    private fun pinOk(c: HttpsURLConnection): Boolean {
        val pins = BuildConfig.TLS_PINS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (pins.isEmpty()) return true
        for (x in c.serverCertificates) {
            val h = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(x.publicKey.encoded), Base64.NO_WRAP)
            if (pins.contains("sha256/$h") || pins.contains(h)) return true
        }
        return false
    }

    private fun executar(metodo: String, caminho: String, corpo: JSONObject?, comAcesso: Boolean, assinar: Boolean, espera: Int): Resposta {
        val dados = (corpo?.toString() ?: "").toByteArray(Charsets.UTF_8)
        val c = URL(BuildConfig.SERVER_URL + caminho).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 10000
            c.readTimeout = espera
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "BotSyncApp/" + BuildConfig.VERSION_NAME + " (Android)")
            c.setRequestProperty("X-BS-App", "1")
            c.setRequestProperty("X-BS-Ver", BuildConfig.VERSION_CODE.toString())
            c.setRequestProperty("Accept", "application/json")
            if (metodo == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(dados.size)
            }
            if (assinar) {
                val dev = Sessao.dispositivo ?: throw ApiErro(401, "Sem sessão.")
                val chaveB64 = Sessao.chave ?: throw ApiErro(401, "Sem sessão.")
                val ts = agora().toString()
                val nonce = b64u(ByteArray(18).also { rnd.nextBytes(it) })
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(Base64.decode(chaveB64, Base64.NO_WRAP), "HmacSHA256"))
                val texto = metodo + "\n" + caminho + "\n" + ts + "\n" + nonce + "\n" + sha256hex(dados)
                c.setRequestProperty("X-BS-Dev", dev)
                c.setRequestProperty("X-BS-Ts", ts)
                c.setRequestProperty("X-BS-Nonce", nonce)
                c.setRequestProperty("X-BS-Sig", b64u(mac.doFinal(texto.toByteArray(Charsets.UTF_8))))
                if (comAcesso) c.setRequestProperty("Authorization", "Bearer " + (Sessao.acesso ?: ""))
            }
            c.connect()
            if (!pinOk(c)) throw ApiErro(-2, "Conexão não confiável: o certificado do servidor não confere.")
            if (metodo == "POST") c.outputStream.use { it.write(dados) }

            val cod = c.responseCode
            val dataServidor = c.getHeaderFieldDate("Date", 0L)
            if (dataServidor > 0L) skew = dataServidor - System.currentTimeMillis()
            val fluxo = if (cod in 200..399) c.inputStream else c.errorStream
            val txt = if (fluxo == null) "" else fluxo.use { s ->
                val saida = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > 2_000_000) throw ApiErro(-3, "Resposta grande demais.")
                    saida.write(buf, 0, n)
                }
                saida.toString("UTF-8")
            }
            val js = if (txt.isNotEmpty()) try { JSONObject(txt) } catch (e: Exception) { null } else null
            return Resposta(cod, js)
        } finally {
            c.disconnect()
        }
    }

    private fun guardarTokens(j: JSONObject) {
        Sessao.acesso = j.getString("acesso")
        Sessao.refresh = j.getString("refresh")
        Sessao.acessoExpira = agora() + j.optLong("expiraEm", 900000L)
    }

    fun renovar(forcar: Boolean) {
        synchronized(lockRefresh) {
            if (!forcar && Sessao.acessoExpira - 30_000L > agora()) return
            val r = executar("POST", "/app/api/refresh", JSONObject().put("refresh", Sessao.refresh ?: ""), false, true, 15000)
            val j = r.json
            if (r.codigo == 200 && j != null) {
                guardarTokens(j)
            } else if (r.codigo == 401 || r.codigo == 404) {
                Sessao.sair()
                throw ApiErro(401, "Sessão expirada. Entre novamente.")
            } else {
                throw ApiErro(r.codigo, "Não consegui renovar a sessão.")
            }
        }
    }

    fun chamar(metodo: String, caminho: String, corpo: JSONObject? = null, espera: Int = 15000): Resposta {
        renovar(false)
        var r = executar(metodo, caminho, corpo, true, true, espera)
        val erro = r.json?.optString("erro") ?: ""
        if (r.codigo == 401 && erro == "expirado") {
            renovar(true)
            r = executar(metodo, caminho, corpo, true, true, espera)
        } else if (r.codigo == 401 && erro == "relogio") {
            r = executar(metodo, caminho, corpo, true, true, espera)       // o relógio já foi corrigido pelo cabeçalho Date
        }
        return r
    }

    private fun exigir(r: Resposta): JSONObject {
        val j = r.json
        if (r.codigo == 200 && j != null) return j
        val msg = j?.optString("mensagem")?.takeIf { it.isNotEmpty() } ?: j?.optString("erro")?.takeIf { it.isNotEmpty() } ?: "Erro ${r.codigo}"
        throw ApiErro(r.codigo, msg, j)
    }

    // ── endpoints ────────────────────────────────────────────────────────────
    fun trocarCodigo(code: String, verifier: String, nomeAparelho: String) {
        val corpo = JSONObject().put("code", code).put("verifier", verifier).put("nome", nomeAparelho).put("versao", BuildConfig.VERSION_CODE)
        val j = exigir(executar("POST", "/app/api/token", corpo, false, false, 15000))
        Sessao.dispositivo = j.getString("dispositivo")
        Sessao.chave = j.getString("chave")
        Sessao.usuario = j.optJSONObject("usuario")?.optString("nome") ?: ""
        guardarTokens(j)
    }

    fun me(): JSONObject = exigir(chamar("GET", "/app/api/me"))

    fun vincular(conta: String, gmail: String): JSONObject =
        exigir(chamar("POST", "/app/api/imap2/vincular", JSONObject().put("conta", conta).put("gmail", gmail)))

    fun poll(): JSONArray {
        val r = chamar("GET", "/app/api/imap2/poll?wait=20", null, 30000)
        if (r.codigo == 204) return JSONArray()
        return exigir(r).optJSONArray("jobs") ?: JSONArray()
    }

    fun enviarEmails(jobId: String, itens: List<JSONObject>, fim: Boolean, erro: String?) {
        val arr = JSONArray()
        for (i in itens) arr.put(i)
        val corpo = JSONObject().put("jobId", jobId).put("fim", fim).put("itens", arr)
        if (erro != null) corpo.put("erro", erro)
        exigir(chamar("POST", "/app/api/imap2/emails", corpo, 20000))
    }

    fun estado(e: String) {
        exigir(chamar("POST", "/app/api/imap2/estado", JSONObject().put("estado", e)))
    }

    fun sair() {
        try {
            chamar("POST", "/app/api/sair", JSONObject())
        } catch (e: Exception) {
            // sai mesmo sem rede: o aparelho é apagado localmente
        }
    }
}
