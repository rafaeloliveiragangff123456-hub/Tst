package com.botsync.app

import android.content.Context
import android.content.SharedPreferences

/** Estado persistente do app. Cada valor é selado no Keystore antes de ir para o disco. */
object Sessao {
    private lateinit var sp: SharedPreferences

    fun iniciar(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("bs", Context.MODE_PRIVATE)
    }

    private fun ler(k: String): String? = Cofre.abrir(sp.getString(k, null))
    private fun gravar(k: String, v: String?) {
        val e = sp.edit()
        if (v == null) e.remove(k) else e.putString(k, Cofre.selar(v))
        e.apply()
    }

    var acesso: String?
        get() = ler("acc")
        set(v) = gravar("acc", v)
    var refresh: String?
        get() = ler("ref")
        set(v) = gravar("ref", v)
    var dispositivo: String?
        get() = ler("dev")
        set(v) = gravar("dev", v)
    var chave: String?
        get() = ler("key")
        set(v) = gravar("key", v)
    var usuario: String?
        get() = ler("usr")
        set(v) = gravar("usr", v)
    var acessoExpira: Long
        get() = ler("accexp")?.toLongOrNull() ?: 0L
        set(v) = gravar("accexp", v.toString())

    // login (PKCE) em andamento
    var pkceVerifier: String?
        get() = ler("pv")
        set(v) = gravar("pv", v)
    var pkceState: String?
        get() = ler("ps")
        set(v) = gravar("ps", v)
    var pkceExpira: Long
        get() = ler("pe")?.toLongOrNull() ?: 0L
        set(v) = gravar("pe", v.toString())

    // IMAP v2 vinculado neste aparelho
    var imapConta: String?
        get() = ler("ic")
        set(v) = gravar("ic", v)
    var imapGmail: String?
        get() = ler("ig")
        set(v) = gravar("ig", v)

    // monitoramento ligado pelo usuário: o app religa sozinho (boot, atualização, serviço morto pelo sistema)
    var imapAtivo: Boolean
        get() = ler("ia") == "1"
        set(v) = gravar("ia", if (v) "1" else "0")

    // posição da bolinha flutuante ("x,y")
    var bolha: String?
        get() = ler("bl")
        set(v) = gravar("bl", v)

    fun logado(): Boolean = !dispositivo.isNullOrEmpty() && !chave.isNullOrEmpty() && !refresh.isNullOrEmpty()

    fun sair() {
        sp.edit().clear().apply()
    }
}
