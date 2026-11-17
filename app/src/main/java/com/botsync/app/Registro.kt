package com.botsync.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Um comprovante lido da notificação do banco: só nome e valor (mais a hora e o app, para a lista). */
class Comprovante(val nome: String, val valor: String, val hora: Long, val app: String)

/** Guarda os últimos comprovantes no aparelho e avisa a tela quando chega um novo. */
object Registro {
    private const val MAX = 100
    private lateinit var sp: SharedPreferences

    /** A tela se registra aqui enquanto está aberta. */
    @Volatile var ouvinte: (() -> Unit)? = null

    fun iniciar(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("comprovantes", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun lista(): List<Comprovante> {
        val arr = try { JSONArray(sp.getString("lista", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Comprovante(o.optString("n"), o.optString("v"), o.optLong("h"), o.optString("a"))
        }
    }

    /** Devolve false se já tinha (a mesma notificação costuma ser entregue mais de uma vez). */
    @Synchronized
    fun adicionar(chave: String, c: Comprovante): Boolean {
        if (sp.getString("ultima", null) == chave) return false
        val atual = lista().toMutableList()
        atual.add(0, c)
        val arr = JSONArray()
        atual.take(MAX).forEach {
            arr.put(JSONObject().put("n", it.nome).put("v", it.valor).put("h", it.hora).put("a", it.app))
        }
        sp.edit().putString("lista", arr.toString()).putString("ultima", chave).apply()
        ouvinte?.invoke()
        return true
    }

    @Synchronized
    fun limpar() {
        sp.edit().remove("lista").remove("ultima").apply()
        ouvinte?.invoke()
    }
}
