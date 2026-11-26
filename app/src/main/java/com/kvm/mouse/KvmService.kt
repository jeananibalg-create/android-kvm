package com.kvm.mouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class KvmService : Service() {

    companion object {
        const val ACTION_ABORT = "com.kvm.mouse.ABORT"
        private const val CHANNEL = "kvm"
        private const val EXIT_PUSH = 30f // empurrão (em unidades do mouse) contra a borda para voltar ao Mestre
    }

    private val isMaster = KvmState.isMaster.value
    private lateinit var wm: WindowManager
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var priv: IPrivileged? = null
    private var socket: DatagramSocket? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null

    // ---- Mestre ----
    private val strips = mutableListOf<View>()
    private var screenW = 1080
    private var screenH = 2400
    @Volatile private var remotePeer: PeerDevice? = null
    @Volatile private var remoteActive = false
    @Volatile private var grabbed = false
    @Volatile private var pendingSession = 0
    @Volatile private var lastPing = 0L

    // ---- Escravo ----
    @Volatile private var active = false
    private var entry: Edge? = null
    private var masterAddr: InetAddress? = null
    private var lastSession = 0
    private var px = 0f
    private var py = 0f
    private var push = 0f
    @Volatile private var spanW = 1080f
    @Volatile private var spanH = 2400f

    // ---- Shizuku ----
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PrivilegedService::class.java.name))
            .daemon(false).processNameSuffix("kvm").debuggable(false).version(31)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            priv = IPrivileged.Stub.asInterface(service)
            KvmState.shizuku.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            priv = null
            KvmState.shizuku.value = false
            if (isMaster) abortRemote("Shizuku desconectou") else deactivate()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { bindPrivileged() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bindPrivileged()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            onDisplaysChanged()
        }

        override fun onDisplayRemoved(displayId: Int) {
            onDisplaysChanged()
        }

        override fun onDisplayChanged(displayId: Int) {
            onDisplaysChanged()
        }
    }

    // Fallback (só se a captura exclusiva falhar): volta a apagar a tela se o mouse a acender.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (remoteActive && !grabbed) runCatching { priv?.sleepScreen() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        KvmState.armed = true
        measure()
        startInForeground()
        acquireLocks()

        val s = try {
            Net.open()
        } catch (_: Exception) {
            KvmState.status.value = "Porta ${KvmState.PORT} ocupada"
            stopSelf()
            return
        }
        socket = s
        startReceiver(s)

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        if (isMaster) {
            registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
            ui.launch { KvmState.edges.collect { rebuildStrips() } }
            bg.launch {
                while (isActive) {
                    delay(2000)
                    if (remoteActive && SystemClock.elapsedRealtime() - lastPing > 7000) {
                        abortRemote("Escravo parou de responder")
                    }
                }
            }
        } else {
            bg.launch {
                while (isActive) {
                    Net.hello(Build.MODEL)
                    val m = masterAddr
                    if (active && m != null) Net.ping(m)
                    delay(2000)
                }
            }
        }
        KvmState.running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ABORT) {
            if (isMaster) abortRemote("Mouse recuperado") else deactivate()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onDisplaysChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KvmState.running.value = false
        if (isMaster) abortRemote("Serviço parado") else deactivate()
        ui.cancel()
        bg.cancel()
        Net.close()
        strips.forEach { runCatching { wm.removeView(it) } }
        runCatching { unregisterReceiver(screenOn) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        runCatching { cpuLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { mcastLock?.release() }
        runCatching {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ infraestrutura

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Android KVM", NotificationManager.IMPORTANCE_LOW))
        val abort = PendingIntent.getService(
            this, 1, Intent(this, KvmService::class.java).setAction(ACTION_ABORT), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Android KVM ativo")
            .setContentText("${if (isMaster) "Mestre" else "Escravo"} · ${Net.localIp()}")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Recuperar mouse", abort)
            .build()
        startForeground(1, n)
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kvm:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "kvm:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        mcastLock = wifi.createMulticastLock("kvm:mc").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun bindPrivileged() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.bindUserService(userServiceArgs, connection)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun measure(): Boolean {
        val b = wm.maximumWindowMetrics.bounds
        val sizeChanged = screenW != b.width() || screenH != b.height()
        screenW = b.width()
        screenH = b.height()

        // Escravo: soma das larguras das telas ativas (celular + monitor DeX)
        val dm = getSystemService(DisplayManager::class.java)
        val list = dm.displays.filter { it.state == Display.STATE_ON }.ifEmpty { dm.displays.toList() }
        var w = 0
        var h = 0
        for (d in list) {
            val p = Point()
            d.getRealSize(p)
            w += p.x
            h = maxOf(h, p.y)
        }
        if (w > 0) {
            spanW = w.toFloat()
            spanH = h.toFloat()
        }
        return sizeChanged
    }

    private fun onDisplaysChanged() {
        if (measure() && isMaster) rebuildStrips()
    }

    private fun startReceiver(s: DatagramSocket) {
        thread(name = "kvm-rx", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val buf = ByteArray(256)
            val packet = DatagramPacket(buf, buf.size)
            while (!s.isClosed) {
                try {
                    packet.length = buf.size
                    s.receive(packet)
                    onPacket(ByteBuffer.wrap(buf, 0, packet.length), packet.address)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun onPacket(b: ByteBuffer, from: InetAddress) {
        when (b.get()) {
            Proto.REPORT -> if (!isMaster && active) onReport(b)
            Proto.ENTER -> if (!isMaster) onEnter(from, Edge.entries[b.get().toInt()], b.getFloat(), b.getInt())
            Proto.LEAVE -> if (!isMaster) deactivate()
            Proto.ACK -> if (isMaster) onAck(b.getInt())
            Proto.RETURN -> if (isMaster) finishRemote()
            Proto.PING -> if (isMaster) lastPing = SystemClock.elapsedRealtime()
            Proto.HELLO -> if (isMaster) {
                val name = String(b.array(), b.position(), b.remaining(), Charsets.UTF_8)
                val key = from.hostAddress ?: return
                val peer = PeerDevice("$name ($key)", from)
                KvmState.peers.update { if (it[key] == peer) it else it + (key to peer) }
            }
        }
    }

    // ------------------------------------------------------------------ MESTRE

    private fun rebuildStrips() {
        strips.forEach { runCatching { wm.removeView(it) } }
        strips.clear()
        val t = (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
        val hidden = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        for ((edge, peer) in KvmState.edges.value) {
            val horizontal = edge == Edge.LEFT || edge == Edge.RIGHT
            val v = View(this)
            v.pointerIcon = hidden // seta do Mestre invisível enquanto está parada na borda
            v.setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_EXIT -> KvmState.armed = true
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> enterPeer(edge, peer, ev)
                }
                false
            }
            val lp = WindowManager.LayoutParams(
                if (horizontal) t else screenW,
                if (horizontal) screenH else t,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = when (edge) {
                    Edge.LEFT, Edge.TOP -> Gravity.TOP or Gravity.START
                    Edge.RIGHT -> Gravity.TOP or Gravity.END
                    Edge.BOTTOM -> Gravity.BOTTOM or Gravity.START
                }
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }
            runCatching {
                wm.addView(v, lp)
                strips += v
            }
        }
    }

    private fun enterPeer(edge: Edge, peer: PeerDevice, ev: MotionEvent) {
        if (!KvmState.armed || remotePeer != null) return
        if (priv == null) {
            KvmState.status.value = "Mestre: Shizuku não conectado"
            return
        }
        KvmState.armed = false
        val horizontal = edge == Edge.LEFT || edge == Edge.RIGHT
        val ratio = (if (horizontal) ev.rawY / screenH else ev.rawX / screenW).coerceIn(0f, 1f)
        remotePeer = peer
        remoteActive = false
        pendingSession = SystemClock.elapsedRealtime().toInt() or 1
        Net.enter(peer.host, edge, ratio, pendingSession)
        // Se o escravo não confirmar, o mouse nunca é preso.
        ui.launch {
            delay(900)
            if (remotePeer === peer && !remoteActive) abortRemote("Escravo não respondeu")
        }
    }

    private fun onAck(session: Int) {
        val p = remotePeer ?: return
        if (remoteActive || session != pendingSession) return
        remoteActive = true
        lastPing = SystemClock.elapsedRealtime()
        bg.launch {
            val mode = try {
                priv?.startRemote(p.host.hostAddress ?: "", KvmState.PORT) ?: 0
            } catch (_: Throwable) {
                0
            }
            when (mode) {
                0 -> abortRemote("Mestre: nenhum mouse encontrado ou sem acesso")
                1 -> {
                    grabbed = false
                    KvmState.status.value = "⚠ Sem captura exclusiva: o mouse também mexe no Mestre"
                }
                else -> {
                    grabbed = true
                    KvmState.status.value = "Seta no escravo · captura exclusiva ativa"
                }
            }
        }
    }

    /** O escravo devolveu a seta: solta o mouse e acende a tela do Mestre. */
    private fun finishRemote() {
        if (remotePeer == null) return
        remotePeer = null
        remoteActive = false
        grabbed = false
        runCatching {
            priv?.stopRemote()
            priv?.wakeScreen()
        }
        KvmState.status.value = "Seta de volta no Mestre"
    }

    private fun abortRemote(reason: String) {
        val p = remotePeer ?: return
        remotePeer = null
        remoteActive = false
        grabbed = false
        runCatching { priv?.stopRemote() }
        Net.leave(p.host)
        KvmState.status.value = reason
    }

    // ------------------------------------------------------------------ ESCRAVO

    private fun onEnter(from: InetAddress, masterEdge: Edge, ratio: Float, session: Int) {
        if (session == lastSession) {
            Net.ack(from, session)
            return
        }
        val p = priv
        if (p == null) {
            KvmState.status.value = "Escravo: Shizuku não conectado"
            Net.ret(from)
            return
        }
        val created = try {
            p.mouseCreate()
        } catch (_: Throwable) {
            false
        }
        if (!created) {
            KvmState.status.value = "Escravo: /dev/uhid indisponível"
            Net.ret(from)
            return
        }
        lastSession = session
        masterAddr = from
        val e = masterEdge.opposite
        entry = e
        val w = spanW
        val h = spanH
        val (sx, sy, mx, my) = when (e) {
            Edge.LEFT -> listOf(-1, -1, 0, (ratio * h).toInt())
            Edge.RIGHT -> listOf(1, -1, 0, (ratio * h).toInt())
            Edge.TOP -> listOf(-1, -1, (ratio * w).toInt(), 0)
            Edge.BOTTOM -> listOf(-1, 1, (ratio * w).toInt(), 0)
        }
        when (e) {
            Edge.LEFT -> { px = 0f; py = ratio * h }
            Edge.RIGHT -> { px = w; py = ratio * h }
            Edge.TOP -> { px = ratio * w; py = 0f }
            Edge.BOTTOM -> { px = ratio * w; py = h }
        }
        push = 0f
        active = true
        try {
            p.mousePlace(sx, sy, mx, my)
        } catch (_: Throwable) {
        }
        Net.ack(from, session)
        KvmState.status.value = "Seta do Mestre ativa"
    }

    private fun onReport(b: ByteBuffer) {
        val buttons = b.get().toInt() and 0xFF
        val dx = b.getShort().toInt()
        val dy = b.getShort().toInt()
        val wheel = b.get().toInt()
        val pan = b.get().toInt()
        val e = entry ?: return

        // Estimativa da posição (o Android aplica aceleração, então é aproximada)
        val nx = px + dx
        val ny = py + dy
        val beyond = when (e) {
            Edge.LEFT -> -nx
            Edge.RIGHT -> nx - spanW
            Edge.TOP -> -ny
            Edge.BOTTOM -> ny - spanH
        }
        push = if (beyond > 0f) push + beyond else 0f
        if (push >= EXIT_PUSH) {
            leaveToMaster()
            return
        }
        px = nx.coerceIn(0f, spanW)
        py = ny.coerceIn(0f, spanH)
        try {
            priv?.mouseReport(buttons, dx, dy, wheel, pan)
        } catch (_: Throwable) {
        }
    }

    private fun leaveToMaster() {
        val m = masterAddr
        deactivate()
        if (m != null) Net.ret(m)
    }

    /** Solta botões e destrói o mouse virtual (o cursor nativo some na hora). */
    private fun deactivate() {
        if (!active) return
        active = false
        try {
            priv?.mouseReport(0, 0, 0, 0, 0)
            priv?.mouseDestroy()
        } catch (_: Throwable) {
        }
        KvmState.status.value = "Seta devolvida ao Mestre"
    }
}
