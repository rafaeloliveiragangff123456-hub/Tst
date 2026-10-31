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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

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
        @Volatile var onlineDesde = 0L      // elapsedRealtime em que a conexao atual ficou estavel (0 = sem conexao)
        @Volatile var ciclos = 0            // ciclos de tempo real concluidos
        @Volatile var quedas = 0            // quedas reais apos ficar estavel
        @Volatile var ultimoOk = 0L         // elapsedRealtime do ultimo poll bem-sucedido
        @Volatile private var geracao = 0   // so o laco da geracao atual pode rodar (evita dois lacos disputando o mesmo poll)
        private const val CANAL = "botsync_imap2"
        private const val VIGIA_MS = 5 * 60_000L

        fun registrar(m: String) {
            val h = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())
            registro.add(0, "$h  $m")
            while (registro.size > 200) registro.removeAt(registro.size - 1)
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
    private var flut: Flutuante? = null
    private val principal = Handler(Looper.getMainLooper())

    /** Flutuante com o logo: 1 toque abre o menu (Terminal), 2o toque fecha. Arraste para mover. */
    private fun mostrarBolha() {
        principal.post {
            if (flut == null) flut = Flutuante(this)
            flut?.mostrar()
        }
    }

    private fun esconderBolha() {
        flut?.esconder()
        flut = null
    }

    /** Anel verde = online; amarelo = reconectando. */
    private fun corBolha(online: Boolean) {
        principal.post { flut?.status(online) }
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
        observarRede()
        mostrarBolha()
        iniciarFio()
        iniciarVigilante()
        return START_STICKY
    }

    /** Usuário fechou o app pelos recentes: o serviço continua e um alarme garante a volta se o sistema o derrubar. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Sessao.imapAtivo) agendarVigia(this)
    }

    override fun onDestroy() {
        rodando = false
        geracao++                       // o laco desta instancia deve sair assim que o poll voltar
        Thread { Api.cortar() }.start() // destrava o poll em andamento na hora (fora da thread principal)
        vigilanteAtivo = false
        onlineDesde = 0L
        try { pool.shutdownNow() } catch (e: Exception) { /* ok */ }
        try { redeCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (e: Exception) { /* ok */ }
        redeCb = null
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
        // na tela bloqueada aparece só o texto genérico: o estado detalhado fica oculto
        val publica = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BotSync")
            .setContentText("Monitoramento ativo")
            .build()
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BotSync · IMAP v2")
            .setContentText(texto)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publica)
            .setContentIntent(abrir)
            .build()
    }

    private fun definirEstado(texto: String) {
        estado = texto
        corBolha(texto.startsWith("Online"))
        if (texto != ultimoTextoNotif) {
            ultimoTextoNotif = texto
            registrar(texto)
            try { getSystemService(NotificationManager::class.java).notify(1, notificacao(texto)) } catch (e: Exception) { /* ok */ }
        }
    }

    private var ultimaUnica = ""

    /** Registra so quando a mensagem muda (evita encher o terminal durante queda de rede). */
    private fun registrarUnico(m: String) {
        if (m != ultimaUnica) { ultimaUnica = m; registrar(m) }
    }

    private fun dormir(ms: Long) {
        dormindo = true
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* acorda */ } finally { dormindo = false }
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "imap2-job").also { it.isDaemon = true } }
    private var vigilanteAtivo = false

    /** Thread do tempo real: se algo derrubar o laco (ate erro grave), ele recomeca sozinho. */
    private fun iniciarFio() {
        if (fio?.isAlive == true) return
        val minha = ++geracao          // invalida qualquer laco antigo (inclusive de uma instancia anterior do servico)
        rodando = true
        registrar("Serviço iniciado")
        fio = Thread({
            while (rodando && minha == geracao) {
                try {
                    laco(minha)
                } catch (t: Throwable) {
                    registrar("Erro interno no tempo real: " + t.javaClass.simpleName + " - reiniciando")
                    try { Thread.sleep(1_500L) } catch (x: InterruptedException) { /* ok */ }
                }
            }
        }, "imap2").also { it.isDaemon = true; it.start() }
    }

    /** Vigilante: conexao "online" mas sem nenhum poll concluido ha 45 s = socket travado, derruba e religa; laco morto = recria. */
    private fun iniciarVigilante() {
        if (vigilanteAtivo) return
        vigilanteAtivo = true
        principal.postDelayed(object : Runnable {
            override fun run() {
                if (!vigilanteAtivo) return
                if (rodando && Sessao.imapAtivo) {
                    val agora = SystemClock.elapsedRealtime()
                    if (fio?.isAlive != true) iniciarFio()
                    else if (falhas == 0 && ultimoOk > 0L && agora - ultimoOk > 45_000L) {
                        registrar("Vigilante: conexão travada, religando")
                        ultimoOk = agora
                        Api.cortar()
                    }
                }
                principal.postDelayed(this, 10_000L)
            }
        }, 10_000L)
    }

    // estado da conexao em tempo real
    private var falhas = 0
    private var offlineDesde = 0L
    private var conectadoDesde = 0L
    @Volatile private var dormindo = false

    /** Falha de conexao: tenta de novo quase na hora e so mostra "Reconectando" se a queda for real (3 falhas ou 12 s). */
    private fun falhou(texto: String, minimo: Long = 0L): Long {
        falhas++
        val agora = SystemClock.elapsedRealtime()
        if (conectadoDesde != 0L) { conectadoDesde = 0L; onlineDesde = 0L; quedas++; offlineDesde = agora }
        else if (offlineDesde == 0L) offlineDesde = agora
        if (falhas >= 3 || agora - offlineDesde >= 12_000L) definirEstado(texto)
        return maxOf(minimo, minOf(300L shl minOf(falhas - 1, 4), 4_000L)) + (Math.random() * 250).toLong()
    }

    private fun laco(minha: Int) {
        var leitor: GmailLeitor? = null
        var leitorConta = ""
        var ultimoBatimento = SystemClock.elapsedRealtime()
        while (rodando && minha == geracao) {
            val gmail = Sessao.imapGmail
            if (!Sessao.logado() || gmail.isNullOrEmpty()) {
                definirEstado("Sessão encerrada — entre de novo no app")
                break
            }
            try {
                agendarVigia(applicationContext)
                if (leitor == null || leitorConta != gmail) {
                    leitor = GmailLeitor(applicationContext, android.accounts.Account(gmail, "com.google"))
                    leitorConta = gmail
                }
                val jobs = Api.poll()
                val agora = SystemClock.elapsedRealtime()
                ultimoOk = agora
                if (conectadoDesde == 0L) {
                    conectadoDesde = agora
                    onlineDesde = agora
                    ultimoBatimento = agora
                    val queda = if (offlineDesde > 0L) (agora - offlineDesde) / 1000 else -1L
                    registrar(if (queda >= 3L) "Tempo real: conexão restabelecida após " + queda + "s" else "Tempo real: conectado ao servidor")
                    offlineDesde = 0L
                }
                falhas = 0
                ultimaUnica = ""
                definirEstado("Online — aguardando consultas")
                ciclos++
                if (jobs.length() == 0 && agora - ultimoBatimento >= 5 * 60_000L) {
                    ultimoBatimento = agora
                    registrar("Tempo real: conexão estável há " + ((agora - conectadoDesde) / 60_000L) + " min (" + ciclos + " ciclos)")
                }
                val ativo = leitor!!
                // cada consulta roda em paralelo; o laco ja volta ao poll (nada fica esperando o Gmail)
                for (i in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(i)
                    try {
                        pool.execute {
                            try { processar(ativo, job) } catch (x: Exception) { registrarUnico("Erro ao processar consulta: " + x.javaClass.simpleName) }
                        }
                    } catch (x: Exception) {
                        try { processar(ativo, job) } catch (y: Exception) { registrarUnico("Erro ao processar consulta: " + y.javaClass.simpleName) }
                    }
                }
            } catch (e: ApiErro) {
                when (e.codigo) {
                    409 -> { definirEstado("Vínculo perdido — abra o app e selecione o Gmail de novo"); dormir(15_000L) }
                    426 -> {
                        definirEstado("Atualização obrigatória — toque na notificação")
                        avisarAtualizacao()
                        dormir(60_000L)
                    }
                    -2 -> { definirEstado("Certificado do servidor não confere"); dormir(30_000L) }
                    404 -> {
                        // aparelho desconhecido pelo servidor: confirma pela renovação (ela mesma desloga se foi revogado)
                        try { Api.renovar(true) } catch (x: Exception) { /* verificado no topo do laço */ }
                        // 404 = o servidor nao reconhece o aparelho (cada tentativa conta como suspeita la): espera bem mais
                        registrarUnico("Servidor respondeu código 404")
                        definirEstado("Reconectando…")
                        dormir(falhou("Reconectando…", 15_000L))
                    }
                    else -> {
                        registrarUnico("Servidor respondeu código " + e.codigo)
                        dormir(falhou("Reconectando…", if (e.codigo == 429 || e.codigo == 503) 6_000L else 0L))
                    }
                }
            } catch (e: IOException) {
                registrarUnico("Rede: " + e.javaClass.simpleName)
                dormir(falhou("Sem conexão — tentando de novo"))
            } catch (e: Exception) {
                dormir(falhou("Reconectando…"))
            }
        }
        // so encerra o servico se ESTE ainda e o laco vigente (um laco antigo saindo nao pode derrubar o novo)
        if (minha == geracao) {
            rodando = false
            stopSelf()
        }
    }

    // ── rede: ao trocar Wi-Fi/dados ou voltar de uma queda, derruba o socket morto e religa na hora ──
    private var redeCb: ConnectivityManager.NetworkCallback? = null
    private var redeAtual: Network? = null
    private var perdeuRede = false

    private fun observarRede() {
        if (redeCb != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val mudou = redeAtual != null && redeAtual != network
                    redeAtual = network
                    if (perdeuRede || mudou) {
                        perdeuRede = false
                        registrar("Rede mudou — religando o tempo real")
                        Api.cortar()
                        if (dormindo) fio?.interrupt()
                    }
                }

                override fun onLost(network: Network) {
                    if (redeAtual == network) perdeuRede = true
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            redeCb = cb
        } catch (e: Exception) {
            // sem observador de rede: segue só com as tentativas automáticas
        }
    }

    /** O servidor barrou esta versão (426): busca os dados da nova e mostra a notificação de atualização (uma vez por versão). */
    private fun avisarAtualizacao() {
        try {
            Atualizador.verificar(applicationContext)?.let { Atualizador.notificarObrigatoria(applicationContext, it) }
        } catch (e: Exception) { /* sem rede agora: tenta de novo no próximo ciclo */ }
    }

    private fun processar(leitor: GmailLeitor, job: JSONObject) {
        val id = job.getString("id")
        val froms = ArrayList<String>()
        job.optJSONArray("froms")?.let { a -> for (i in 0 until a.length()) froms.add(a.getString(i)) }
        val conhecidos = HashSet<String>()
        job.optJSONArray("conhecidos")?.let { a -> for (i in 0 until a.length()) conhecidos.add(a.getString(i)) }
        try {
            registrar("Consulta recebida (" + froms.size + " remetente(s)) - lendo Gmail")
            val itens = leitor.buscar(froms, job.optInt("dias", 2), job.optInt("max", 40), conhecidos)
            Api.enviarEmails(id, itens, true, null)
            registrar("Gmail: " + itens.size + " e-mail(s) novo(s) do banco enviados ao servidor")
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
