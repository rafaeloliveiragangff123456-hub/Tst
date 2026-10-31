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
import java.net.NetworkInterface
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
import java.util.concurrent.CountDownLatch
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

    // ── pareamento e conexão ──────────────────────────────────────────────────
    /** Pareia com o código e conecta. Bloqueia: chame fora da thread principal. */
    fun executarPareamento(ctx: Context, codigo: String) {
        val app = ctx.applicationContext
        val c = codigo.filter { it.isDigit() }
        if (c.length != 6) { mudar(Fase.ERRO, "O código tem 6 dígitos."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        try {
            mudar(Fase.PAREANDO, "Procurando a tela de pareamento…")
            val alvo = descobrir(app, TIPO_PAREAR, 6000)
                ?: throw Exception("Não achei o pareamento. Deixe aberta a tela \"Parear dispositivo com código\" e tente de novo.")
            mudar(Fase.PAREANDO, "Pareando…")
            val r: Any? = BotAdb.obter().pair(alvo.first, alvo.second, c)
            if (r == false) throw Exception("Código recusado. Confira os 6 dígitos: ele muda a cada vez.")
            conectarInterno(app)
        } catch (e: Exception) {
            mudar(Fase.ERRO, e.message ?: "Falha no pareamento.")
        } finally {
            trabalhando.set(false)
        }
    }

    fun parearEConectar(ctx: Context, codigo: String) {
        Thread { executarPareamento(ctx, codigo) }.start()
    }

    /**
     * Pareia e conecta com OUTRO aparelho (longe, numa rede acessível), informando IP e portas na mão —
     * não depende da descoberta por mDNS, que só acha o pareamento deste próprio aparelho.
     * host/portas vêm da tela "Parear dispositivo com código" (porta de pareamento) e do topo da tela
     * "Depuração sem fio" (IP e porta de conexão) do OUTRO celular.
     */
    fun executarPareamentoRemoto(ctx: Context, host: String, portaParear: Int, codigo: String, portaConectar: Int) {
        val app = ctx.applicationContext
        val h = host.trim()
        val c = codigo.filter { it.isDigit() }
        if (h.isEmpty()) { mudar(Fase.ERRO, "Informe o IP do outro aparelho."); return }
        if (c.length != 6) { mudar(Fase.ERRO, "O código tem 6 dígitos."); return }
        if (portaParear <= 0 || portaConectar <= 0) { mudar(Fase.ERRO, "Informe a porta de pareamento e a de conexão."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        try {
            mudar(Fase.PAREANDO, "Pareando com $h…")
            val m = BotAdb.obter()
            val rp: Any? = m.pair(h, portaParear, c)
            if (rp == false) throw Exception("Código recusado. Confira os 6 dígitos: ele muda a cada vez.")
            mudar(Fase.CONECTANDO, "Conectando a $h…")
            val rc: Any? = m.connect(h, portaConectar)
            if (rc == false) throw Exception("Pareou, mas não consegui conectar. Confira a porta de conexão.")
            WifiDebug.iniciarSessao(app)
            liberarDesligarSozinho(app, m)
            mudar(Fase.CONECTADO)
        } catch (e: Exception) {
            mudar(Fase.ERRO, e.message ?: "Falha no pareamento remoto. Confira se os dois aparelhos conseguem se enxergar na rede.")
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
        val h = host.trim()
        if (h.isEmpty()) { mudar(Fase.ERRO, "Informe o IP do outro aparelho."); return }
        if (portaConectar <= 0) { mudar(Fase.ERRO, "Informe a porta de conexão."); return }
        if (!trabalhando.compareAndSet(false, true)) return
        Thread {
            try {
                mudar(Fase.CONECTANDO, "Conectando a $h…")
                val m = BotAdb.obter()
                val r: Any? = m.connect(h, portaConectar)
                if (r == false) throw Exception("Não consegui conectar. Confira o IP e a porta.")
                WifiDebug.iniciarSessao(app)
                liberarDesligarSozinho(app, m)
                mudar(Fase.CONECTADO)
            } catch (e: Exception) {
                mudar(Fase.ERRO, e.message ?: "Não consegui conectar ao aparelho remoto.")
            } finally {
                trabalhando.set(false)
            }
        }.start()
    }

    /** Conecta sem código (aparelho já pareado antes). */
    fun conectar(ctx: Context) {
        val app = ctx.applicationContext
        if (!trabalhando.compareAndSet(false, true)) return
        Thread {
            try { conectarInterno(app) }
            catch (e: Exception) { mudar(Fase.ERRO, e.message ?: "Não consegui conectar. Se for a primeira vez, pareie com o código.") }
            finally { trabalhando.set(false) }
        }.start()
    }

    private fun conectarInterno(app: Context) {
        mudar(Fase.CONECTANDO, "Conectando…")
        val alvo = descobrir(app, TIPO_CONECTAR, 6000)
            ?: throw Exception("Não achei a porta de conexão. Confira se a \"Depuração sem fio\" está ligada.")
        val m = BotAdb.obter()
        val r: Any? = m.connect(alvo.first, alvo.second)
        if (r == false) throw Exception("Não consegui conectar. Se for a primeira vez, pareie com o código.")
        WifiDebug.iniciarSessao(app)
        liberarDesligarSozinho(app, m)
        mudar(Fase.CONECTADO)
    }

    /** Uma vez conectado, o app concede a si mesmo a permissão que permite desligar a depuração sem fio. */
    private fun liberarDesligarSozinho(app: Context, m: BotAdb) {
        if (WifiDebug.podeDesligar(app)) return
        try {
            val s = m.openStream("shell:pm grant " + app.packageName + " android.permission.WRITE_SECURE_SETTINGS")
            try { s.openInputStream().readBytes() } finally { s.close() }
        } catch (e: Exception) { /* sem a permissão o app só avisa na hora de desligar */ }
    }

    /** Fecha a conexão do app (em segundo plano). */
    fun desconectar() {
        mudar(Fase.OCIOSO)
        Thread { fecharSocket() }.start()
    }

    /** Fecha a conexão agora. Bloqueia: chame fora da thread principal. */
    fun desconectarAgora() {
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
