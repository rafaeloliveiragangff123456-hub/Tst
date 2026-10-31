package com.botsync.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * IMAP v2: serviço em primeiro plano que segura uma conexão (long-poll) com a rota. Quando alguém digita "pg Nome" na fila,
 * a rota acorda este serviço, ele procura no Gmail e devolve os e-mails do banco para o bot.js conferir.
 *
 * Só para quando VOCÊ toca em "Parar monitoramento" ou sai do app. Erros e quedas de rede apenas reconectam.
 * Se o sistema matar o processo, ele volta sozinho (START_STICKY + alarme de vigia + religar no boot).
 */
class Imap2Service : Service() {
    companion object {
        @Volatile var rodando = false
        @Volatile var estado = "Parado"
        val registro = CopyOnWriteArrayList<String>()
        private const val CANAL = "botsync_imap2"
        private const val VIGIA_MS = 5 * 60_000L

        fun registrar(m: String) {
            val h = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())
            registro.add(0, "$h  $m")
            while (registro.size > 30) registro.removeAt(registro.size - 1)
        }

        private fun intentServico(ctx: Context) = Intent(ctx, Imap2Service::class.java)

        fun iniciar(ctx: Context) {
            Sessao.imapAtivo = true
            ctx.startForegroundService(intentServico(ctx))
        }

        fun parar(ctx: Context) {
            Sessao.imapAtivo = false
            rodando = false
            cancelarVigia(ctx)
            ctx.stopService(intentServico(ctx))
        }

        private fun pendenteVigia(ctx: Context): PendingIntent =
            PendingIntent.getForegroundService(ctx, 7, intentServico(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Alarme que religa o serviço se o sistema o matar (reagendado a cada volta do laço). */
        fun agendarVigia(ctx: Context) {
            try {
                val am = ctx.getSystemService(AlarmManager::class.java)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + VIGIA_MS, pendenteVigia(ctx))
            } catch (e: Exception) {
                // sem alarme: segue só com START_STICKY
            }
        }

        fun cancelarVigia(ctx: Context) {
            try { ctx.getSystemService(AlarmManager::class.java).cancel(pendenteVigia(ctx)) } catch (e: Exception) { /* ok */ }
        }
    }

    private var fio: Thread? = null
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var ultimoTextoNotif = ""
    private var bolha: View? = null
    private val principal = Handler(Looper.getMainLooper())

    /** Bolinha flutuante de 32dp com o símbolo do app: mantém o app "aberto" para o Android. Toque abre o app; arraste para mover. */
    private fun mostrarBolha() {
        principal.post {
            if (bolha != null || !Settings.canDrawOverlays(this)) return@post
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val d = resources.displayMetrics.density
                val tam = (32 * d).toInt()
                val v = ImageView(this)
                val fundo = GradientDrawable()
                fundo.shape = GradientDrawable.OVAL
                fundo.setColor(0xFF7C3AED.toInt())
                fundo.setStroke((2 * d).toInt(), 0xFFFBBF24.toInt())
                v.background = fundo
                v.setImageResource(R.drawable.ic_stat)
                val pad = (6 * d).toInt()
                v.setPadding(pad, pad, pad, pad)
                v.alpha = 0.92f
                val lp = WindowManager.LayoutParams(
                    tam, tam, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                val salva = Sessao.bolha?.split(",")
                lp.x = salva?.getOrNull(0)?.toIntOrNull() ?: (8 * d).toInt()
                lp.y = salva?.getOrNull(1)?.toIntOrNull() ?: (140 * d).toInt()
                v.setOnTouchListener(object : View.OnTouchListener {
                    var ix = 0
                    var iy = 0
                    var tx = 0f
                    var ty = 0f
                    var moveu = false
                    override fun onTouch(view: View, e: MotionEvent): Boolean {
                        when (e.action) {
                            MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moveu = false; return true }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (e.rawX - tx).toInt()
                                val dy = (e.rawY - ty).toInt()
                                if (abs(dx) > 8 || abs(dy) > 8) moveu = true
                                lp.x = ix + dx
                                lp.y = iy + dy
                                try { wm.updateViewLayout(view, lp) } catch (x: Exception) { /* ok */ }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (moveu) Sessao.bolha = lp.x.toString() + "," + lp.y.toString()
                                else abrirApp()
                                return true
                            }
                        }
                        return false
                    }
                })
                wm.addView(v, lp)
                bolha = v
            } catch (e: Exception) {
                // sem permissão de sobreposição: segue sem a bolinha
            }
        }
    }

    private fun esconderBolha() {
        val v = bolha ?: return
        bolha = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { /* ok */ }
    }

    /** Anel verde = online; amarelo = reconectando. */
    private fun corBolha(online: Boolean) {
        principal.post {
            val g = bolha?.background as? GradientDrawable ?: return@post
            g.setStroke((2 * resources.displayMetrics.density).toInt(), if (online) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())
        }
    }

    private fun abrirApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { /* sem permissão para abrir agora */ }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // religado pelo sistema depois de ser morto: só continua se o monitoramento estava ligado
        if (intent == null && !Sessao.imapAtivo) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Pagamentos em tempo real", NotificationManager.IMPORTANCE_LOW))
        val n = notificacao("Conectando…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        segurarEnergia()
        agendarVigia(this)
        mostrarBolha()
        if (fio?.isAlive != true) {
            rodando = true
            fio = Thread({ laco() }, "imap2").also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    /** Usuário fechou o app pelos recentes: o serviço continua e um alarme garante a volta se o sistema o derrubar. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Sessao.imapAtivo) agendarVigia(this)
    }

    override fun onDestroy() {
        rodando = false
        esconderBolha()
        estado = "Parado"
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { /* ok */ }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Exception) { /* ok */ }
        super.onDestroy()
    }

    private fun segurarEnergia() {
        try {
            if (wl == null) {
                wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "botsync:imap2").also { it.setReferenceCounted(false) }
            }
            if (wl?.isHeld != true) wl?.acquire()
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val modo = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(modo, "botsync:wifi").also { it.setReferenceCounted(false) }
            }
            if (wifi?.isHeld != true) wifi?.acquire()
        } catch (e: Exception) {
            // sem lock: segue mesmo assim
        }
    }

    private fun notificacao(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BotSync · IMAP v2")
            .setContentText(texto)
            .setOngoing(true)
            .setContentIntent(abrir)
            .build()
    }

    private fun definirEstado(texto: String) {
        estado = texto
        corBolha(texto.startsWith("Online"))
        if (texto != ultimoTextoNotif) {
            ultimoTextoNotif = texto
            try { getSystemService(NotificationManager::class.java).notify(1, notificacao(texto)) } catch (e: Exception) { /* ok */ }
        }
    }

    private fun dormir(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* acorda */ }
    }

    private fun laco() {
        var espera = 1000L
        registrar("Serviço iniciado")
        while (rodando) {
            val gmail = Sessao.imapGmail
            if (!Sessao.logado() || gmail.isNullOrEmpty()) {
                definirEstado("Sessão encerrada — entre de novo no app")
                break
            }
            try {
                agendarVigia(applicationContext)
                val leitor = GmailLeitor(applicationContext, android.accounts.Account(gmail, "com.google"))
                val jobs = Api.poll()
                definirEstado("Online — aguardando consultas")
                espera = 1000L
                for (i in 0 until jobs.length()) processar(leitor, jobs.getJSONObject(i))
            } catch (e: ApiErro) {
                when (e.codigo) {
                    409 -> { definirEstado("Vínculo perdido — abra o app e selecione o Gmail de novo"); dormir(15_000L) }
                    426 -> { definirEstado("Atualize o aplicativo"); dormir(60_000L) }
                    -2 -> { definirEstado("Certificado do servidor não confere"); dormir(30_000L) }
                    404 -> {
                        // aparelho desconhecido pelo servidor: confirma pela renovação (ela mesma desloga se foi revogado)
                        try { Api.renovar(true) } catch (x: Exception) { /* verificado no topo do laço */ }
                        definirEstado("Reconectando…")
                        dormir(15_000L)
                    }
                    else -> { definirEstado("Reconectando…"); dormir(espera); espera = minOf(espera * 2, 5_000L) }
                }
            } catch (e: IOException) {
                definirEstado("Sem conexão — tentando de novo")
                dormir(espera)
                espera = minOf(espera * 2, 5_000L)
            } catch (e: Exception) {
                definirEstado("Reconectando…")
                dormir(espera)
                espera = minOf(espera * 2, 5_000L)
            }
        }
        rodando = false
        stopSelf()
    }

    private fun processar(leitor: GmailLeitor, job: JSONObject) {
        val id = job.getString("id")
        val froms = ArrayList<String>()
        job.optJSONArray("froms")?.let { a -> for (i in 0 until a.length()) froms.add(a.getString(i)) }
        val conhecidos = HashSet<String>()
        job.optJSONArray("conhecidos")?.let { a -> for (i in 0 until a.length()) conhecidos.add(a.getString(i)) }
        try {
            val itens = leitor.buscar(froms, job.optInt("dias", 2), job.optInt("max", 40), conhecidos)
            Api.enviarEmails(id, itens, true, null)
            registrar("Consulta da fila: " + itens.size + " e-mail(s) novo(s) do banco")
        } catch (e: GmailErro) {
            registrar("Gmail: " + e.message)
            try { Api.estado(if (e.tipo == "auth") "auth" else "erro") } catch (x: Exception) { /* segue */ }
            try { Api.enviarEmails(id, emptyList(), true, e.tipo) } catch (x: Exception) { /* segue */ }
        } catch (e: IOException) {
            registrar("Falha de rede ao ler o Gmail")
            try { Api.enviarEmails(id, emptyList(), true, "rede") } catch (x: Exception) { /* segue */ }
        }
    }
}
