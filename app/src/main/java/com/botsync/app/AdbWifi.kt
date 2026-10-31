package com.botsync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.io.EOFException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Gerenciador ADB do app. A chave e o certificado ficam salvos (selados no Cofre): o pareamento vale para esta chave. */
class BotAdb private constructor() : AbsAdbConnectionManager() {
    private val chave: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val par = carregar() ?: gerar()
        chave = par.first
        cert = par.second
    }

    private fun carregar(): Pair<PrivateKey, Certificate>? {
        val k = Sessao.adbChave
        val c = Sessao.adbCert
        if (k.isNullOrEmpty() || c.isNullOrEmpty()) return null
        return try {
            val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
            val certificado = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP)))
            Pair(priv, certificado)
        } catch (e: Exception) { null }
    }

    private fun gerar(): Pair<PrivateKey, Certificate> {
        val gerador = KeyPairGenerator.getInstance("RSA")
        gerador.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val par = gerador.generateKeyPair()
        val pub = par.public
        val priv = par.private
        val x = AdbCert.gerar(pub, priv)
        Sessao.adbChave = Base64.encodeToString(priv.encoded, Base64.NO_WRAP)
        Sessao.adbCert = Base64.encodeToString(x.encoded, Base64.NO_WRAP)
        return Pair(priv, x)
    }

    override fun getPrivateKey(): PrivateKey = chave
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "BotSync"

    companion object {
        @Volatile private var inst: BotAdb? = null
        fun obter(): BotAdb = inst ?: synchronized(this) { inst ?: BotAdb().also { inst = it } }
        fun existente(): BotAdb? = inst
    }
}

/** Mensagem pronta para mostrar à pessoa (diferente de uma falha técnica da biblioteca). */
private class Aviso(msg: String) : Exception(msg)

/**
 * Pareamento por código e conexão do app com a depuração sem fio do próprio aparelho.
 * Fluxo: a pessoa liga a "Depuração sem fio", abre "Parear dispositivo com código" e digita os 6 dígitos no campo do app
 * (ou na notificação, se a tela de pareamento estiver aberta). O app acha a porta sozinho, pareia, conecta e inicia os 40 minutos.
 */
object AdbWifi {
    enum class Fase { OCIOSO, PAREANDO, CONECTANDO, CONECTADO, ERRO }

    const val ACAO_CODIGO = "com.botsync.app.ADB_CODIGO"
    const val CHAVE_CODIGO = "codigo"
    private const val TIPO_PAREAR = "_adb-tls-pairing._tcp"
    private const val TIPO_CONECTAR = "_adb-tls-connect._tcp"
    private const val CANAL = "botsync_adb_codigo"
    private const val ID_NOTIF = 4103

    @Volatile var fase: Fase = Fase.OCIOSO
        private set
    @Volatile var mensagem: String = ""
        private set
    /** true quando a conexão atual é com OUTRO aparelho (aí o estado da depuração sem fio DESTE celular não importa). */
    @Volatile var remoto: Boolean = false
        private set
    @Volatile private var cancelado = false
    @Volatile private var tarefaAtual: FutureTask<*>? = null

    private val principal = Handler(Looper.getMainLooper())
    private val ouvintes = CopyOnWriteArrayList<() -> Unit>()
    private val trabalhando = AtomicBoolean(false)

    fun observar(o: () -> Unit) { ouvintes.add(o) }
    fun parar(o: () -> Unit) { ouvintes.remove(o) }

    private fun mudar(f: Fase, msg: String = "") {
        fase = f
        mensagem = msg
        principal.post { for (o in ouvintes) o() }
    }

    // ── descoberta da porta (mDNS do próprio aparelho) ────────────────────────
    private fun enderecosLocais(): Set<String> {
        val r = HashSet<String>()
        try {
            val it = NetworkInterface.getNetworkInterfaces()
            while (it != null && it.hasMoreElements()) {
                val ends = it.nextElement().inetAddresses
                while (ends.hasMoreElements()) r.add(ends.nextElement().hostAddress?.substringBefore('%') ?: "")
            }
        } catch (e: Exception) { /* sem interfaces */ }
        return r
    }

    /** Procura o serviço do tipo pedido anunciado por ESTE aparelho (ignora outros celulares/computadores na rede). */
    private fun descobrir(ctx: Context, tipo: String, tempoMs: Long): Pair<String, Int>? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        val locais = enderecosLocais()
        val ip = WifiDebug.ipWifi(ctx)
        val trava = CountDownLatch(1)
        val achado = AtomicReference<Pair<String, Int>?>(null)
        val fila = ConcurrentLinkedQueue<NsdServiceInfo>()
        val ocupado = AtomicBoolean(false)

        fun proximo() {
            if (achado.get() != null) return
            if (!ocupado.compareAndSet(false, true)) return
            val alvo = fila.poll()
            if (alvo == null) { ocupado.set(false); return }
            try {
                nsd.resolveService(alvo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo?, c: Int) { ocupado.set(false); proximo() }
                    override fun onServiceResolved(s: NsdServiceInfo?) {
                        val h = s?.host?.hostAddress?.substringBefore('%')
                        if (s != null && h != null && (h in locais || h == "127.0.0.1")) {
                            achado.set(Pair(ip ?: h, s.port))
                            trava.countDown()
                        } else { ocupado.set(false); proximo() }
                    }
                })
            } catch (e: Exception) { ocupado.set(false) }
        }

        val ouvinte = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, c: Int) { trava.countDown() }
            override fun onStopDiscoveryFailed(t: String?, c: Int) {}
            override fun onServiceLost(i: NsdServiceInfo?) {}
            override fun onServiceFound(i: NsdServiceInfo?) {
                if (i != null) { fila.add(i); proximo() }
            }
        }
        try { nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, ouvinte) } catch (e: Exception) { return null }
        try { trava.await(tempoMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { /* segue */ }
        try { nsd.stopServiceDiscovery(ouvinte) } catch (e: Exception) { /* já parou */ }
        return achado.get()
    }

    // ── utilidades: limite de tempo, teste de rede e mensagens claras ────────
    /** Roda o bloco com limite de tempo. Sem isso, a biblioteca pode ficar presa para sempre em "Conectando…". */
    private fun <T> comLimite(ms: Long, etapa: String, bloco: () -> T): T {
        val tarefa = FutureTask(Callable { bloco() })
        tarefaAtual = tarefa
        val t = Thread(tarefa, "adb-$etapa")
        t.isDaemon = true
        t.start()
        try {
            return tarefa.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            tarefa.cancel(true)
            t.interrupt()
            fecharSocket()
            throw TimeoutException(etapa)
        } catch (e: ExecutionException) {
            val causa = e.cause ?: e
            throw (causa as? Exception ?: RuntimeException(causa))
        } finally {
            if (tarefaAtual === tarefa) tarefaAtual = null
        }
    }

    /** Testa só a rede (TCP) até IP:porta. Devolve null se responde, ou o motivo. Não gasta o código de pareamento. */
    private fun semResposta(host: String, porta: Int, ms: Int): String? = try {
        Socket().use { it.connect(InetSocketAddress(host, porta), ms) }
        null
    } catch (e: SocketTimeoutException) { "não respondeu a tempo"
    } catch (e: UnknownHostException) { "IP inválido"
    } catch (e: ConnectException) { if ((e.message ?: "").contains("refused", true)) "porta fechada" else (e.message ?: "sem rota")
    } catch (e: Exception) { e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") }

    /** Separa um "192.168.0.5:37123" colado no campo de IP. */
    private fun separarHost(bruto: String): Pair<String, Int> {
        val t = bruto.trim().removePrefix("http://").removePrefix("https://").trimEnd('/').filterNot { it.isWhitespace() }
        val i = t.lastIndexOf(':')
        return if (i > 0 && t.indexOf(':') == i) Pair(t.substring(0, i), t.substring(i + 1).toIntOrNull() ?: 0) else Pair(t, 0)
    }

    private fun traduzir(e: Throwable, etapa: String): String {
        val raiz = generateSequence(e) { it.cause }.last()
        val msg = raiz.message ?: ""
        return when {
            e is CancellationException -> "Cancelado."
            e is TimeoutException || raiz is SocketTimeoutException ->
                if (etapa == "parear") "O pareamento ficou sem resposta. Confira: a tela \"Parear dispositivo com código\" precisa continuar ABERTA no outro celular, a porta de pareamento é a que aparece nela (não a do topo) e o código é o que está na tela agora."
                else "A conexão ficou sem resposta. Confira a porta de conexão: é a do topo da tela \"Depuração sem fio\" (muda toda vez que liga)."
            raiz is UnknownHostException -> "IP inválido."
            raiz is ConnectException -> "Porta fechada ou inalcançável ($msg). Veja se a porta mudou: ela muda quando a tela de pareamento é reaberta."
            raiz is UnsatisfiedLinkError -> "A biblioteca nativa do ADB não carregou: $msg"
            raiz is SSLException || raiz is EOFException || msg.contains("closed", true) || msg.contains("reset", true) ->
                if (etapa == "parear") "O outro celular encerrou o pareamento. Código errado/vencido, ou a porta de pareamento está trocada com a de conexão. Reabra a tela de pareamento e use o código novo."
                else "O outro celular fechou a conexão. Pareie de novo (o pareamento pode ter sido removido) e confira a porta de conexão."
            else -> "Falha ao $etapa: ${raiz.javaClass.simpleName}" + (if (msg.isNotEmpty()) " — $msg" else "")
        }
    }

    // ── pareamento e conexão ──────────────────────────────────────────────────
    /** Pareia com o código e conecta. Bloqueia: chame fora da thread principal. */
    fun executarPareamento(ctx: Context, codigo: String) {
        val app = ctx.applicationContext
        val c = codigo.filter { it.isDigit() }
        if (c.length != 6) { mudar(Fase.ERRO, "O código tem 6 dígitos."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        cancelado = false
        remoto = false
        try {
            mudar(Fase.PAREANDO, "Procurando a tela de pareamento…")
            val alvo = descobrir(app, TIPO_PAREAR, 6000)
                ?: throw Aviso("Não achei o pareamento. Deixe aberta a tela \"Parear dispositivo com código\" e tente de novo.")
            mudar(Fase.PAREANDO, "Pareando…")
            val m = BotAdb.obter()
            val r: Any? = comLimite(20000, "parear") { m.pair(alvo.first, alvo.second, c) }
            if (r == false) throw Aviso("Código recusado. Confira os 6 dígitos: ele muda a cada vez.")
            conectarInterno(app)
        } catch (e: Exception) {
            if (cancelado) mudar(Fase.OCIOSO) else mudar(Fase.ERRO, if (e is Aviso) (e.message ?: "Falha no pareamento.") else traduzir(e, "parear"))
        } finally {
            trabalhando.set(false)
        }
    }

    fun parearEConectar(ctx: Context, codigo: String) {
        Thread { executarPareamento(ctx, codigo) }.start()
    }

    /** Tenta conectar até 3 vezes: logo após parear, o outro aparelho às vezes demora um instante a aceitar a chave nova. */
    private fun conectarComTentativas(m: BotAdb, h: String, porta: Int) {
        var ultimo: Throwable? = null
        for (i in 1..3) {
            if (cancelado) throw CancellationException()
            try {
                val r: Any? = comLimite(15000, "conectar") { m.connect(h, porta) }
                if (r != false) return
                ultimo = Exception("recusado")
            } catch (e: Exception) {
                if (cancelado) throw e
                ultimo = e
            }
            if (i < 3) {
                mudar(Fase.CONECTANDO, "Conectando a $h:$porta… (tentativa ${i + 1} de 3)")
                try { Thread.sleep(1500) } catch (e: InterruptedException) { /* segue */ }
            }
        }
        throw Aviso(
            if (ultimo != null && ultimo.message != "recusado") traduzir(ultimo, "conectar")
            else "Pareou, mas o outro celular recusou a conexão. Confira a porta de conexão (topo da tela \"Depuração sem fio\")."
        )
    }

    /**
     * Pareia e conecta com OUTRO aparelho (na MESMA rede), informando IP e portas na mão —
     * não depende da descoberta por mDNS, que só acha o pareamento deste próprio aparelho.
     * host/portas vêm da tela "Parear dispositivo com código" (porta de pareamento) e do topo da tela
     * "Depuração sem fio" (IP e porta de conexão) do OUTRO celular.
     */
    fun executarPareamentoRemoto(ctx: Context, host: String, portaParear: Int, codigo: String, portaConectar: Int) {
        val app = ctx.applicationContext
        val (h, portaNoIp) = separarHost(host)
        val pc = if (portaConectar > 0) portaConectar else portaNoIp
        val c = codigo.filter { it.isDigit() }
        if (h.isEmpty()) { mudar(Fase.ERRO, "Informe o IP do outro aparelho."); return }
        if (c.length != 6) { mudar(Fase.ERRO, "O código tem 6 dígitos."); return }
        if (portaParear <= 0 || pc <= 0) { mudar(Fase.ERRO, "Informe a porta de pareamento e a de conexão."); return }
        if (portaParear == pc) { mudar(Fase.ERRO, "A porta de pareamento e a de conexão são diferentes. Confira as duas."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        cancelado = false
        remoto = true
        try {
            // 1) só rede: se nem a porta de conexão responde, o problema é IP/rede, não código nem porta trocada
            mudar(Fase.PAREANDO, "Testando se $h:$pc responde…")
            val falha = semResposta(h, pc, 5000)
            if (falha != null) throw Aviso(
                "Não consegui falar com $h:$pc ($falha). Os dois celulares precisam estar na MESMA rede Wi-Fi (sem \"isolamento de clientes\" no roteador, sem dados móveis nem VPN), a Depuração sem fio do outro precisa estar ligada, e a porta de conexão é a do topo da tela \"Depuração sem fio\" dele."
            )
            if (cancelado) throw CancellationException()
            // 2) pareamento
            mudar(Fase.PAREANDO, "Pareando com $h:$portaParear…")
            val m = BotAdb.obter()
            val rp: Any? = comLimite(20000, "parear") { m.pair(h, portaParear, c) }
            if (rp == false) throw Aviso("Código recusado. Confira os 6 dígitos: ele muda a cada vez.")
            // 3) conexão
            mudar(Fase.CONECTANDO, "Conectando a $h:$pc…")
            try { Thread.sleep(1200) } catch (e: InterruptedException) { /* segue */ }
            conectarComTentativas(m, h, pc)
            WifiDebug.iniciarSessao(app)
            mudar(Fase.CONECTADO)
        } catch (e: Exception) {
            remoto = false
            fecharSocket()
            if (cancelado) mudar(Fase.OCIOSO) else mudar(Fase.ERRO, if (e is Aviso) (e.message ?: "Falha no pareamento remoto.") else traduzir(e, "parear"))
        } finally {
            trabalhando.set(false)
        }
    }

    fun parearEConectarRemoto(ctx: Context, host: String, portaParear: Int, codigo: String, portaConectar: Int) {
        Thread { executarPareamentoRemoto(ctx, host, portaParear, codigo, portaConectar) }.start()
    }

    /** Conecta a um aparelho remoto já pareado antes, com IP e porta de conexão informados na mão. */
    fun conectarRemoto(ctx: Context, host: String, portaConectar: Int) {
        val app = ctx.applicationContext
        val (h, portaNoIp) = separarHost(host)
        val pc = if (portaConectar > 0) portaConectar else portaNoIp
        if (h.isEmpty()) { mudar(Fase.ERRO, "Informe o IP do outro aparelho."); return }
        if (pc <= 0) { mudar(Fase.ERRO, "Informe a porta de conexão."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        cancelado = false
        remoto = true
        Thread {
            try {
                mudar(Fase.CONECTANDO, "Testando se $h:$pc responde…")
                val falha = semResposta(h, pc, 5000)
                if (falha != null) throw Aviso("Não consegui falar com $h:$pc ($falha). Confira o IP, a porta de conexão e se os dois estão na mesma rede Wi-Fi.")
                mudar(Fase.CONECTANDO, "Conectando a $h:$pc…")
                conectarComTentativas(BotAdb.obter(), h, pc)
                WifiDebug.iniciarSessao(app)
                mudar(Fase.CONECTADO)
            } catch (e: Exception) {
                remoto = false
                fecharSocket()
                if (cancelado) mudar(Fase.OCIOSO) else mudar(Fase.ERRO, if (e is Aviso) (e.message ?: "Não consegui conectar ao aparelho remoto.") else traduzir(e, "conectar"))
            } finally {
                trabalhando.set(false)
            }
        }.start()
    }

    /** Conecta sem código (aparelho já pareado antes). */
    fun conectar(ctx: Context) {
        val app = ctx.applicationContext
        if (!trabalhando.compareAndSet(false, true)) return
        cancelado = false
        remoto = false
        Thread {
            try { conectarInterno(app) }
            catch (e: Exception) {
                if (cancelado) mudar(Fase.OCIOSO)
                else mudar(Fase.ERRO, if (e is Aviso) (e.message ?: "Não consegui conectar. Se for a primeira vez, pareie com o código.") else traduzir(e, "conectar"))
            }
            finally { trabalhando.set(false) }
        }.start()
    }

    private fun conectarInterno(app: Context) {
        mudar(Fase.CONECTANDO, "Conectando…")
        val alvo = descobrir(app, TIPO_CONECTAR, 6000)
            ?: throw Aviso("Não achei a porta de conexão. Confira se a \"Depuração sem fio\" está ligada.")
        val m = BotAdb.obter()
        val r: Any? = comLimite(15000, "conectar") { m.connect(alvo.first, alvo.second) }
        if (r == false) throw Aviso("Não consegui conectar. Se for a primeira vez, pareie com o código.")
        WifiDebug.iniciarSessao(app)
        liberarDesligarSozinho(app, m)
        mudar(Fase.CONECTADO)
    }

    /** Uma vez conectado, o app concede a si mesmo a permissão que permite desligar a depuração sem fio (só vale neste aparelho). */
    private fun liberarDesligarSozinho(app: Context, m: BotAdb) {
        if (WifiDebug.podeDesligar(app)) return
        try {
            val s = m.openStream("shell:pm grant " + app.packageName + " android.permission.WRITE_SECURE_SETTINGS")
            try { s.openInputStream().readBytes() } finally { s.close() }
        } catch (e: Exception) { /* sem a permissão o app só avisa na hora de desligar */ }
    }

    /** Cancela o pareamento/conexão em andamento (botão "Cancelar"). */
    fun cancelar() {
        cancelado = true
        try { tarefaAtual?.cancel(true) } catch (e: Exception) { /* ok */ }
        Thread { fecharSocket() }.start()
        mudar(Fase.OCIOSO)
    }

    /** Fecha a conexão do app (em segundo plano). */
    fun desconectar() {
        remoto = false
        mudar(Fase.OCIOSO)
        Thread { fecharSocket() }.start()
    }

    /** Fecha a conexão agora. Bloqueia: chame fora da thread principal. */
    fun desconectarAgora() {
        remoto = false
        mudar(Fase.OCIOSO)
        fecharSocket()
    }

    private fun fecharSocket() {
        try { BotAdb.existente()?.disconnect() } catch (e: Exception) { /* já caiu */ }
    }

    // ── notificação para digitar o código sem sair da tela de pareamento ──────
    fun notificarPareamento(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Código de pareamento", NotificationManager.IMPORTANCE_HIGH))
            val entrada = RemoteInput.Builder(CHAVE_CODIGO).setLabel("Código de 6 dígitos").build()
            val pi = PendingIntent.getBroadcast(
                ctx, 43,
                Intent(ctx, AdbCodigoReceiver::class.java).setAction(ACAO_CODIGO),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val acao = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Digitar código", pi)
                .addRemoteInput(entrada)
                .build()
            val n = Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Digite o código de pareamento")
                .setContentText("Na tela \"Parear dispositivo com código\", toque em Digitar código e informe os 6 dígitos.")
                .addAction(acao)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(180000)
                .build()
            nm.notify(ID_NOTIF, n)
        } catch (e: Exception) { /* sem permissão de notificação: use o campo do cartão */ }
    }

    fun cancelarNotificacao(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(ID_NOTIF) } catch (e: Exception) { /* ok */ }
    }
}

/** Recebe o código digitado na notificação. exported=false no manifesto: só o próprio app dispara. */
class AdbCodigoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent == null || intent.action != AdbWifi.ACAO_CODIGO) return
        val texto = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbWifi.CHAVE_CODIGO)?.toString() ?: return
        val app = ctx.applicationContext
        val p = goAsync()
        Thread {
            try { AdbWifi.executarPareamento(app, texto) }
            finally { AdbWifi.cancelarNotificacao(app); p.finish() }
        }.start()
    }
}
