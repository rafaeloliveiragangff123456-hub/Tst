package com.botsync.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * Verificações de integridade feitas ao abrir o app. Nenhuma defesa no celular é absoluta (quem controla o aparelho
 * sempre pode tentar burlar), por isso a segurança de verdade está no servidor: tokens curtos, assinatura por aparelho,
 * anti-replay e vínculo com o Gmail. Estas checagens só aumentam muito o custo de um ataque.
 */
object Seguranca {
    /** Devolve o motivo do bloqueio, ou null se o ambiente está íntegro. */
    fun verificar(ctx: Context): String? {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return "Depurador detectado."
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return "Versão de depuração não é permitida."
        if (hookDetectado()) return "Ferramenta de injeção de código detectada."
        if (!assinaturaOk(ctx)) return "O aplicativo foi modificado ou não é a versão original."
        if (BuildConfig.BLOQUEAR_ROOT && rootDetectado(ctx)) return "Aparelho com root detectado. Por segurança o BotSync não roda em aparelhos com root."
        return null
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun assinaturaOk(ctx: Context): Boolean {
        val esperado = BuildConfig.CERT_SHA256.replace(":", "").lowercase()
        if (esperado.isEmpty()) return true
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val assinaturas = if (Build.VERSION.SDK_INT >= 28) {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = pi.signingInfo ?: return false
                if (si.hasMultipleSigners()) return false
                si.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            if (assinaturas == null || assinaturas.size != 1) return false
            hex(md.digest(assinaturas[0].toByteArray())) == esperado
        } catch (e: Exception) {
            false
        }
    }

    private fun hookDetectado(): Boolean {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            return true
        } catch (e: Throwable) {
            // não há Xposed
        }
        return try {
            File("/proc/self/maps").useLines { linhas ->
                linhas.any { l ->
                    val x = l.lowercase()
                    x.contains("frida") || x.contains("gum-js-loop") || x.contains("xposed") || x.contains("substrate")
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun rootDetectado(ctx: Context): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        val caminhos = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk", "/system/xbin/daemonsu"
        )
        if (caminhos.any { File(it).exists() }) return true
        return try {
            ctx.packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
