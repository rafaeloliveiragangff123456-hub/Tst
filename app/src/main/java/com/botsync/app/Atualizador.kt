package com.botsync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

class Versao(val codigo: Int, val nome: String, val notas: String, val tamanho: Long, val sha256: String)

/**
 * Atualização obrigatória do aplicativo.
 *  1. O servidor anuncia a versão mais nova em /app/api/update (e passa a recusar versões antigas com 426).
 *  2. O app baixa o APK (HTTPS + pin), confere SHA-256, pacote, versão e ASSINATURA antes de instalar.
 *  3. A instalação usa o PackageInstaller do Android; o sistema ainda exige a mesma assinatura do app instalado.
 * O caminho do APK é fixo no código: o servidor não consegue mandar o app baixar de outro endereço.
 */
object Atualizador {
    const val PARADO = 0
    const val BAIXANDO = 1
    const val INSTALANDO = 2
    const val ERRO = 3

    private const val CANAL = "botsync_update"
    private const val ID_NOTIF = 20
    private const val CAMINHO_INFO = "/app/api/update"
    private const val CAMINHO_APK = "/app/api/update/apk"
    private val ROXO = 0xFF7C3AED.toInt()

    @Volatile var fase = PARADO
    @Volatile var progresso = 0
    @Volatile var mensagem = ""
    @Volatile private var atual: Versao? = null
    @Volatile private var avisada = 0
    private var receptor: BroadcastReceiver? = null
    private val rnd = SecureRandom()

    // ── consulta ─────────────────────────────────────────────────────────────
    /** Pergunta ao servidor (rodar fora da thread principal). Devolve a versão nova, ou null se este app já está em dia. */
    fun verificar(ctx: Context): Versao? {
        val r = Api.publico(CAMINHO_INFO)
        val j = r.json
        if (r.codigo != 200 || j == null) throw ApiErro(r.codigo, "Não consegui consultar atualizações.")
        val codigo = j.optInt("versao", 0)
        if (codigo <= BuildConfig.VERSION_CODE) {
            avisada = 0
            limparArquivos(ctx)
            cancelarAviso(ctx)
            return null
        }
        return Versao(
            codigo,
            j.optString("nome").take(24).ifEmpty { codigo.toString() },
            j.optString("notas").take(600),
            j.optLong("tamanho", 0L),
            j.optString("sha256").lowercase(Locale.ROOT),
        )
    }

    fun podeInstalar(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Abre a tela do Android onde o usuário libera "instalar apps desta fonte" para o BotSync. */
    fun pedirPermissao(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
        } catch (e: Exception) { /* sem tela: o usuário libera manualmente */ }
    }

    // ── baixar + conferir + instalar ─────────────────────────────────────────
    /** Rodar fora da thread principal. O andamento fica em fase / progresso / mensagem. */
    fun atualizar(ctx: Context, v: Versao) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (fase == BAIXANDO || fase == INSTALANDO) return
            fase = BAIXANDO
        }
        atual = v
        progresso = 0
        mensagem = "Baixando atualização…"
        try {
            val f = baixar(app, v)
            mensagem = "Arquivo verificado. Instalando…"
            progresso = 100
            fase = INSTALANDO
            notificarInstalando(app, v)
            instalar(app, f)
        } catch (e: Exception) {
            falhar(app, when (e) {
                is ApiErro -> e.message ?: "Falha na atualização."
                is IOException -> "Sem conexão com o servidor. Tente de novo."
                else -> "Não foi possível instalar a atualização."
            })
        }
    }

    private fun baixar(app: Context, v: Versao): File {
        if (v.tamanho <= 0L || v.tamanho > 300L * 1024 * 1024 || !Regex("[0-9a-f]{64}").matches(v.sha256)) {
            throw ApiErro(-5, "A atualização anunciada pelo servidor é inválida.")
        }
        limparArquivos(app)
        val dir = File(app.cacheDir, "update")
        dir.mkdirs()
        val f = File(dir, "botsync-" + v.codigo + ".apk")
        var ultimo = -1
        val hash = Api.baixar(CAMINHO_APK, f, v.tamanho) { total ->
            val p = (total * 100 / v.tamanho).toInt()
            if (p != ultimo) {
                ultimo = p
                progresso = p
                notificarProgresso(app, v, p)
            }
        }
        if (hash != v.sha256) {
            f.delete()
            throw ApiErro(-5, "Arquivo corrompido ou adulterado (SHA-256 não confere).")
        }
        validarPacote(app, f, v)
        return f
    }

    /** Confere o APK baixado ANTES de entregar ao instalador: pacote, versão e assinatura iguais às esperadas. */
    @Suppress("DEPRECATION")
    private fun validarPacote(app: Context, f: File, v: Versao) {
        val pm = app.packageManager
        val modo = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val pi = pm.getPackageArchiveInfo(f.path, modo)
        if (pi == null) { f.delete(); throw ApiErro(-5, "O arquivo baixado não é um APK válido.") }
        if (pi.packageName != app.packageName) { f.delete(); throw ApiErro(-5, "O APK baixado é de outro aplicativo.") }
        val codigo = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        if (codigo != v.codigo.toLong()) { f.delete(); throw ApiErro(-5, "A versão do APK não é a anunciada pelo servidor.") }
        if (codigo <= BuildConfig.VERSION_CODE.toLong()) { f.delete(); throw ApiErro(-5, "O APK baixado não é mais novo que o instalado.") }
        if (Build.VERSION.SDK_INT >= 28) {
            val novo = pi.signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }?.toSet()
            val instalado = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }?.toSet()
            if (novo != null && instalado != null && novo != instalado) {
                f.delete()
                throw ApiErro(-5, "A assinatura do APK novo não é a do app instalado. Atualização recusada.")
            }
        }
    }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun instalar(app: Context, f: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        // ação aleatória por instalação: outro app não consegue forjar a resposta do instalador
        val acao = "com.botsync.app.UPDATE." + ByteArray(12).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        registrarReceptor(app, acao, id, f)
        try {
            pi.openSession(id).use { s ->
                f.inputStream().use { ins ->
                    s.openWrite("botsync.apk", 0L, f.length()).use { out ->
                        ins.copyTo(out)
                        s.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(app, id, Intent(acao).setPackage(app.packageName), flags).intentSender)
            }
        } catch (e: Exception) {
            soltarReceptor(app)
            try { pi.abandonSession(id) } catch (x: Exception) { /* ok */ }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun intentDeConfirmacao(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)

    private fun registrarReceptor(app: Context, acao: String, sessao: Int, f: File) {
        soltarReceptor(app)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessao) return
                val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (st) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        mensagem = "Confirme a instalação na tela do Android"
                        val conf = intentDeConfirmacao(i)
                        if (conf != null) {
                            notificarConfirmar(app, conf)
                            try { app.startActivity(Intent(conf).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* a notificação cobre */ }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        mensagem = "Atualizado"
                        fase = PARADO
                        f.delete()
                        soltarReceptor(app)
                        cancelarAviso(app)
                    }
                    else -> {
                        f.delete()
                        soltarReceptor(app)
                        falhar(app, textoFalha(st))
                    }
                }
            }
        }
        val filtro = IntentFilter(acao)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filtro, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, filtro)
        receptor = r
    }

    private fun soltarReceptor(app: Context) {
        val r = receptor ?: return
        receptor = null
        try { app.unregisterReceiver(r) } catch (e: Exception) { /* ok */ }
    }

    private fun textoFalha(st: Int): String = when (st) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada. Toque em Atualizar para tentar de novo."
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "O Android recusou: a assinatura do APK novo não confere com a do app instalado."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Sem espaço no aparelho para instalar."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "A instalação foi bloqueada pelo aparelho."
        else -> "Falha ao instalar (código $st)."
    }

    private fun falhar(app: Context, msg: String) {
        fase = ERRO
        mensagem = msg
        avisada = 0
        atual?.let { notificarObrigatoria(app, it) }
    }

    private fun limparArquivos(ctx: Context) {
        try { File(ctx.cacheDir, "update").listFiles()?.forEach { it.delete() } } catch (e: Exception) { /* ok */ }
    }

    // ── notificações ─────────────────────────────────────────────────────────
    fun tamanhoLegivel(b: Long): String = String.format(Locale("pt", "BR"), "%.1f MB", b / 1048576.0)

    /** Notas do admin em itens curtos (máx. 6), sem marcadores repetidos. */
    fun linhasNotas(notas: String): List<String> =
        notas.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }.take(6).map { it.take(90) }

    private fun gerente(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CANAL, "Atualizações do app", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Avisos de novas versões do BotSync"
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
        return nm
    }

    private fun abrirApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("atualizar", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 31, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(ctx: Context): Notification.Builder =
        Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ROXO)
            .setSubText("BotSync System")
            .setLargeIcon(Icon.createWithResource(ctx, R.drawable.logo_botsync))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

    /** Aviso fixo e organizado: título, versão/tamanho, lista do que mudou e botão "Atualizar agora". */
    fun notificarObrigatoria(ctx: Context, v: Versao) {
        if (fase == BAIXANDO || fase == INSTALANDO) return
        if (avisada == v.codigo) return
        avisada = v.codigo
        try {
            val abrir = abrirApp(ctx)
            val sb = StringBuilder()
            sb.append("Versão ").append(v.nome).append("  •  ").append(tamanhoLegivel(v.tamanho)).append("\n")
            val notas = linhasNotas(v.notas)
            if (notas.isNotEmpty()) {
                sb.append("\nO que há de novo\n")
                for (l in notas) sb.append("•  ").append(l).append("\n")
            }
            sb.append("\nToque em Atualizar agora: o app baixa e instala sozinho.")
            val acao = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_atualizar), "Atualizar agora", abrir).build()
            val n = base(ctx)
                .setContentTitle("Atualização obrigatória")
                .setContentText("BotSync " + v.nome + " está disponível")
                .setStyle(Notification.BigTextStyle().bigText(sb.toString().trim()).setBigContentTitle("BotSync " + v.nome + " disponível"))
                .setContentIntent(abrir)
                .addAction(acao)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setOngoing(true)
                .build()
            gerente(ctx).notify(ID_NOTIF, n)
        } catch (e: Exception) { /* sem permissão de notificação: a tela do app continua avisando */ }
    }

    private fun notificarProgresso(ctx: Context, v: Versao, pct: Int) {
        try {
            val n = base(ctx)
                .setContentTitle("Baixando atualização")
                .setContentText("BotSync " + v.nome + "  •  " + pct + "%")
                .setProgress(100, pct, false)
                .setContentIntent(abrirApp(ctx))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .build()
            gerente(ctx).notify(ID_NOTIF, n)
        } catch (e: Exception) { /* ok */ }
    }

    private fun notificarInstalando(ctx: Context, v: Versao) {
        try {
            val n = base(ctx)
                .setContentTitle("Instalando atualização")
                .setContentText("BotSync " + v.nome + "  •  arquivo verificado")
                .setProgress(0, 0, true)
                .setContentIntent(abrirApp(ctx))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .build()
            gerente(ctx).notify(ID_NOTIF, n)
        } catch (e: Exception) { /* ok */ }
    }

    /** O Android pediu um toque de confirmação: a notificação abre a tela dele caso a tela não suba sozinha. */
    private fun notificarConfirmar(ctx: Context, conf: Intent) {
        try {
            val pi = PendingIntent.getActivity(ctx, 33, Intent(conf).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = base(ctx)
                .setContentTitle("Confirme a instalação")
                .setContentText("Toque para concluir a atualização do BotSync")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            gerente(ctx).notify(ID_NOTIF, n)
        } catch (e: Exception) { /* ok */ }
    }

    private fun cancelarAviso(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(ID_NOTIF) } catch (e: Exception) { /* ok */ }
    }
}
