package com.kvm.mouse

import android.app.AlarmManager
import android.app.KeyguardManager
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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class KvmService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val edgeViews = mutableListOf<View>()

    private var relX = 0f
    private var relY = 0f
    private var totalSlaveWidth = 1080f
    private var totalSlaveHeight = 2340f
    private var slaveButtonsMask = 0

    private var virtualMasterX = 500f
    private var virtualMasterY = 1000f
    private var entryEdge: Edge? = null
    private var masterAddress: InetAddress? = null
    private var lastSlaveTrafficMs = 0L
    private var remoteSessionStartMs = 0L

    private var injector: IInputInjector? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = true

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.kvm.mouse", InputInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("kvm_injector").debuggable(false).version(14)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (KvmState.isMasterMode) {
                        if (KvmState.allowLockscreenControl || KvmState.isRemoteActive) {
                            acquireCpuOnlyWakeLock()
                        }
                        if (KvmState.isRemoteActive) {
                            // Garante que o mouse fica exclusivamente preso no Shizuku enquanto a tela esta apagada
                            try { injector?.setExclusiveMouseGrab(true) } catch (_: Exception) {}
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (KvmState.isMasterMode && KvmState.isRemoteActive) {
                        if (KvmState.turnOffMasterScreenOnRemote) {
                            // Se o Android tentou acordar a tela do Mestre durante controle remoto, apaga de novo
                            mainHandler.postDelayed({
                                if (KvmState.isRemoteActive && KvmState.turnOffMasterScreenOnRemote) {
                                    try { injector?.setScreenPower(false) } catch (_: Exception) {}
                                }
                            }, 60)
                        } else if (KvmState.captureActivity == null && KvmState.allowLockscreenControl) {
                            launchMultiControlCapture()
                        }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (KvmState.isMasterMode && KvmState.isRemoteActive && !KvmState.turnOffMasterScreenOnRemote && KvmState.captureActivity == null) {
                        launchMultiControlCapture()
                    }
                }
            }
        }
    }

    private fun launchMultiControlCapture() {
        val intent = Intent(this, PointerCaptureActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        startActivity(intent)
    }

    private fun enterRemoteSession(edge: Edge, peer: PeerDevice, normRatio: Float) {
        KvmState.activeEdge = edge
        KvmState.activePeer = peer
        KvmState.isRemoteActive = true
        remoteSessionStartMs = SystemClock.elapsedRealtime()
        KvmState.lastHeartbeatMs = remoteSessionStartMs

        acquireCpuOnlyWakeLock()
        HybridTransport.sendEnter(peer, edge, normRatio)

        // Sequestra o mouse Bluetooth via EVIOCGRAB no Shizuku:
        // impede o sistema Android do Mestre de acordar com movimentos/cliques enquanto o cursor esta no Escravo
        try { injector?.setExclusiveMouseGrab(true) } catch (_: Exception) {}

        if (KvmState.turnOffMasterScreenOnRemote) {
            KvmState.captureActivity?.releaseAndClose()
            // Mantem isRemoteActive = true pois releaseAndClose zera o estado
            KvmState.activeEdge = edge
            KvmState.activePeer = peer
            KvmState.isRemoteActive = true

            if (powerManager.isInteractive) {
                mainHandler.postDelayed({
                    if (KvmState.isRemoteActive && KvmState.turnOffMasterScreenOnRemote) {
                        try { injector?.setScreenPower(false) } catch (_: Exception) {}
                    }
                }, 80)
            }
        } else if (powerManager.isInteractive) {
            launchMultiControlCapture()
        }
    }

    private fun exitRemoteSession(wakeMasterScreen: Boolean) {
        val (w, h) = getFullScreenBounds()
        when (KvmState.activeEdge) {
            Edge.RIGHT -> virtualMasterX = w - 40f
            Edge.LEFT -> virtualMasterX = 40f
            Edge.TOP -> virtualMasterY = 40f
            Edge.BOTTOM -> virtualMasterY = h - 40f
            null -> {}
        }
        KvmState.isRemoteActive = false
        KvmState.activePeer = null
        KvmState.activeEdge = null
        releaseCpuWakeLock()

        // Devolve o mouse Bluetooth ao sistema operacional do Mestre
        try { injector?.setExclusiveMouseGrab(false) } catch (_: Exception) {}

        KvmState.captureActivity?.releaseAndClose()

        if (wakeMasterScreen && !powerManager.isInteractive) {
            try { injector?.setScreenPower(true) } catch (_: Exception) {}
        }
    }

    private fun acquireCpuOnlyWakeLock() {
        try {
            if (cpuWakeLock == null) {
                cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidKVM:MultiControlLock")
            }
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun isScreenOffOrLocked(): Boolean {
        return !powerManager.isInteractive || keyguardManager.isKeyguardLocked
    }

    private val hwCallback = object : IMouseEventCallback.Stub() {
        override fun onMouseDelta(dx: Float, dy: Float) {
            if (!KvmState.isMasterMode) return
            if (isScreenOffOrLocked() && !KvmState.allowLockscreenControl && !KvmState.isRemoteActive) return

            // Se PointerCaptureActivity esta ativa e com foco (quando apagar tela estiver desligado), ela envia sem duplicar
            if (KvmState.captureActivity != null && powerManager.isInteractive && !KvmState.turnOffMasterScreenOnRemote) return

            val (w, h) = getFullScreenBounds()
            if (!KvmState.isRemoteActive) {
                virtualMasterX = (virtualMasterX + dx).coerceIn(0f, w)
                virtualMasterY = (virtualMasterY + dy).coerceIn(0f, h)

                val crossedEdge = when {
                    virtualMasterX >= w - 2f && KvmState.edgeAssignments[Edge.RIGHT] != null -> Edge.RIGHT
                    virtualMasterX <= 2f && KvmState.edgeAssignments[Edge.LEFT] != null -> Edge.LEFT
                    virtualMasterY <= 2f && KvmState.edgeAssignments[Edge.TOP] != null -> Edge.TOP
                    virtualMasterY >= h - 2f && KvmState.edgeAssignments[Edge.BOTTOM] != null -> Edge.BOTTOM
                    else -> null
                }
                if (crossedEdge != null) {
                    val peer = KvmState.edgeAssignments[crossedEdge] ?: return
                    val ratio = when (crossedEdge) {
                        Edge.LEFT, Edge.RIGHT -> (virtualMasterY / h).coerceIn(0f, 1f)
                        Edge.TOP, Edge.BOTTOM -> (virtualMasterX / w).coerceIn(0f, 1f)
                    }
                    enterRemoteSession(crossedEdge, peer, ratio)
                }
            } else {
                val peer = KvmState.activePeer ?: return
                HybridTransport.sendMove(peer, dx * KvmState.pointerSpeed, dy * KvmState.pointerSpeed)
            }
        }

        override fun onMouseButton(action: Int, button: Int) {
            if (!KvmState.isMasterMode || !KvmState.isRemoteActive) return
            if (KvmState.captureActivity != null && powerManager.isInteractive && !KvmState.turnOffMasterScreenOnRemote) return
            val peer = KvmState.activePeer ?: return
            HybridTransport.sendButton(peer, action, button)
        }

        override fun onMouseScroll(vScroll: Float) {
            if (!KvmState.isMasterMode || !KvmState.isRemoteActive) return
            if (KvmState.captureActivity != null && powerManager.isInteractive && !KvmState.turnOffMasterScreenOnRemote) return
            val peer = KvmState.activePeer ?: return
            HybridTransport.sendScroll(peer, vScroll, 0f)
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            injector = IInputInjector.Stub.asInterface(service)
            try {
                injector?.applyMultiControlSystemPolicy(KvmState.allowLockscreenControl)
            } catch (_: Exception) {}

            if (KvmState.isMasterMode) {
                try {
                    injector?.startHardwareMouseMonitor(hwCallback)
                    injector?.setExclusiveMouseGrab(KvmState.isRemoteActive)
                } catch (_: Exception) {}
            } else {
                try { injector?.createVirtualMouse() } catch (_: Exception) {}
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
        }
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                for (intf in NetworkInterface.getNetworkInterfaces()) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return "Desconhecido"
        }
    }

    override fun onCreate() {
        super.onCreate()
        KvmState.loadConfig(this)
        KvmState.isRunning = true

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("kvm_lock").apply {
            setReferenceCounted(true)
            acquire()
        }
        wifiLowLatencyLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "kvm_low_latency").apply {
            setReferenceCounted(false)
            acquire()
        }

        startForegroundNotification()
        startTcpControlServer()
        startFastUdpReceiver()
        bindShizuku()
        startWatchdogLoop()

        if (KvmState.isMasterMode) {
            setupMasterEdgeTriggers()
            startDiscoveryLoop()
        } else {
            startBeaconLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KvmState.loadConfig(this)
        try {
            injector?.applyMultiControlSystemPolicy(KvmState.allowLockscreenControl)
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (KvmState.isRunning) {
            val restartIntent = Intent(applicationContext, KvmService::class.java)
            val pending = PendingIntent.getForegroundService(
                applicationContext, 1002, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pending)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startWatchdogLoop() {
        thread(isDaemon = true, name = "KvmWatchdog") {
            while (running) {
                try {
                    if (injector == null && Shizuku.pingBinder()) {
                        mainHandler.post { bindShizuku() }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (KvmState.isMasterMode && KvmState.isRemoteActive) {
                        val peer = KvmState.activePeer
                        if (peer != null) {
                            HybridTransport.sendPing(peer)
                        }
                        // Garante que a tela do Mestre permaneca apagada enquanto o cursor estiver no Escravo
                        if (KvmState.turnOffMasterScreenOnRemote && powerManager.isInteractive && (now - remoteSessionStartMs) > 250L) {
                            try { injector?.setScreenPower(false) } catch (_: Exception) {}
                        }
                        if (KvmState.lastHeartbeatMs > 0L && (now - KvmState.lastHeartbeatMs) > 2500L) {
                            exitRemoteSession(wakeMasterScreen = true)
                        }
                    } else if (!KvmState.isMasterMode) {
                        if (lastSlaveTrafficMs > 0L && (now - lastSlaveTrafficMs) > 2800L) {
                            lastSlaveTrafficMs = 0L
                            try { injector?.destroyVirtualMouse() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                }
                Thread.sleep(450)
            }
        }
    }

    private fun getFullScreenBounds(): Pair<Float, Float> {
        val bounds = wm.maximumWindowMetrics.bounds
        return Pair(bounds.width().toFloat(), bounds.height().toFloat())
    }

    private fun updateTotalSlaveWorkspace() {
        var sumW = 0f
        var maxH = 0f
        val displays = displayManager.displays
        for (d in displays) {
            if (d.state != Display.STATE_OFF) {
                val mode = d.mode
                sumW += mode.physicalWidth.toFloat()
                if (mode.physicalHeight > maxH) {
                    maxH = mode.physicalHeight.toFloat()
                }
            }
        }
        if (sumW > 0f && maxH > 0f) {
            totalSlaveWidth = sumW
            totalSlaveHeight = maxH
        } else {
            val (w, h) = getFullScreenBounds()
            totalSlaveWidth = w
            totalSlaveHeight = h
        }
    }

    private fun startForegroundNotification() {
        val channelId = "kvm_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Android KVM", NotificationManager.IMPORTANCE_LOW)
        )
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android KVM v5.1")
            .setContentText(if (KvmState.isMasterMode) "Mestre (${getLocalIpAddress()})" else "Escravo (${getLocalIpAddress()})")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(1, notification)
    }

    private fun bindShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.bindUserService(userServiceArgs, shizukuConnection)
            }
        } catch (_: Exception) {
        }
    }

    private fun setupMasterEdgeTriggers() {
        val thickness = 4
        Edge.values().forEach { edge ->
            val view = View(this)
            val params = WindowManager.LayoutParams(
                if (edge == Edge.LEFT || edge == Edge.RIGHT) thickness else WindowManager.LayoutParams.MATCH_PARENT,
                if (edge == Edge.TOP || edge == Edge.BOTTOM) thickness else WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                gravity = when (edge) {
                    Edge.LEFT -> Gravity.START or Gravity.TOP
                    Edge.RIGHT -> Gravity.END or Gravity.TOP
                    Edge.TOP -> Gravity.TOP or Gravity.START
                    Edge.BOTTOM -> Gravity.BOTTOM or Gravity.START
                }
            }

            view.setOnHoverListener { _, event ->
                if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                    if (isScreenOffOrLocked() && !KvmState.allowLockscreenControl) return@setOnHoverListener false
                    val targetPeer = KvmState.edgeAssignments[edge]
                    if (targetPeer != null && KvmState.captureActivity == null && !KvmState.isRemoteActive) {
                        val (w, h) = getFullScreenBounds()
                        val normRatio = when (edge) {
                            Edge.LEFT, Edge.RIGHT -> (event.rawY / h).coerceIn(0f, 1f)
                            Edge.TOP, Edge.BOTTOM -> (event.rawX / w).coerceIn(0f, 1f)
                        }
                        enterRemoteSession(edge, targetPeer, normRatio)
                    }
                }
                false
            }
            wm.addView(view, params)
            edgeViews.add(view)
        }
    }

    private fun startBeaconLoop() {
        thread(isDaemon = true, name = "KvmSlaveBeacon") {
            val modelBytes = Build.MODEL.toByteArray()
            val len = modelBytes.size.coerceAtMost(60)
            val pktData = ByteBuffer.allocate(2 + len).put(KvmState.PKT_HELLO).put(len.toByte()).put(modelBytes, 0, len).array()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (running) {
                HybridTransport.sendBroadcastRaw(pktData, broadcastAddr)
                Thread.sleep(2000)
            }
        }
    }

    private fun startDiscoveryLoop() {
        thread(isDaemon = true, name = "KvmMasterDiscover") {
            val pktData = byteArrayOf(KvmState.PKT_DISCOVER)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (running) {
                HybridTransport.sendBroadcastRaw(pktData, broadcastAddr)
                Thread.sleep(2000)
            }
        }
    }

    private fun startTcpControlServer() {
        thread(isDaemon = true, name = "KvmTcpControlServer") {
            try {
                tcpServerSocket = ServerSocket(KvmState.TCP_PORT).apply {
                    reuseAddress = true
                }
                while (running) {
                    val client = tcpServerSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    thread(isDaemon = true, name = "KvmTcpClient") {
                        handleTcpClient(client)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleTcpClient(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val sender = client.inetAddress
            while (running && !client.isClosed) {
                val len = input.readUnsignedShort()
                if (len <= 0 || len > 512) break
                val data = ByteArray(len)
                input.readFully(data)
                processBinaryPacket(data, len, sender)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun startFastUdpReceiver() {
        thread(isDaemon = true, name = "KvmUdpReceiver") {
            try {
                udpSocket = DatagramSocket(KvmState.UDP_PORT).apply {
                    broadcast = true
                    receiveBufferSize = 65536
                }
                val buf = ByteArray(256)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    if (packet.length > 0) {
                        processBinaryPacket(packet.data, packet.length, packet.address)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun processBinaryPacket(data: ByteArray, length: Int, sender: InetAddress) {
        val buf = ByteBuffer.wrap(data, 0, length)
        when (buf.get()) {
            KvmState.PKT_DISCOVER -> {
                if (!KvmState.isMasterMode) {
                    val modelBytes = Build.MODEL.toByteArray()
                    val len = modelBytes.size.coerceAtMost(60)
                    val reply = ByteBuffer.allocate(2 + len).put(KvmState.PKT_HELLO).put(len.toByte()).put(modelBytes, 0, len).array()
                    HybridTransport.sendBroadcastRaw(reply, sender)
                }
            }
            KvmState.PKT_HELLO -> {
                if (KvmState.isMasterMode && length >= 2) {
                    val strLen = buf.get().toInt() and 0xFF
                    if (length >= 2 + strLen) {
                        val strBytes = ByteArray(strLen)
                        buf.get(strBytes)
                        val modelName = String(strBytes)
                        val label = "$modelName (${sender.hostAddress})"
                        KvmState.discoveredPeers[label] = PeerDevice(label, sender, KvmState.UDP_PORT)
                    }
                }
            }
            KvmState.PKT_PING -> {
                if (!KvmState.isMasterMode) {
                    lastSlaveTrafficMs = SystemClock.elapsedRealtime()
                    HybridTransport.sendPong(sender)
                }
            }
            KvmState.PKT_PONG -> {
                if (KvmState.isMasterMode) {
                    KvmState.lastHeartbeatMs = SystemClock.elapsedRealtime()
                }
            }
            KvmState.PKT_ENTER -> {
                if (length >= 6) {
                    masterAddress = sender
                    lastSlaveTrafficMs = SystemClock.elapsedRealtime()
                    val fromEdge = Edge.fromId(buf.get()) ?: Edge.RIGHT
                    val ratio = buf.getFloat().coerceIn(0f, 1f)
                    updateTotalSlaveWorkspace()

                    entryEdge = when (fromEdge) {
                        Edge.RIGHT -> Edge.LEFT
                        Edge.LEFT -> Edge.RIGHT
                        Edge.TOP -> Edge.BOTTOM
                        Edge.BOTTOM -> Edge.TOP
                    }

                    try { injector?.createVirtualMouse() } catch (_: Exception) {}

                    when (entryEdge) {
                        Edge.LEFT -> {
                            relX = 16f
                            relY = ratio * totalSlaveHeight
                            try {
                                injector?.sendVirtualMouseDelta(-4000, -4000)
                                injector?.sendVirtualMouseDelta(16, (ratio * totalSlaveHeight).roundToInt())
                            } catch (_: Exception) {}
                        }
                        Edge.RIGHT -> {
                            relX = totalSlaveWidth - 16f
                            relY = ratio * totalSlaveHeight
                            try {
                                injector?.sendVirtualMouseDelta(4000, -4000)
                                injector?.sendVirtualMouseDelta(-16, (ratio * totalSlaveHeight).roundToInt())
                            } catch (_: Exception) {}
                        }
                        Edge.TOP -> {
                            relX = ratio * totalSlaveWidth
                            relY = 16f
                            try {
                                injector?.sendVirtualMouseDelta(-4000, -4000)
                                injector?.sendVirtualMouseDelta((ratio * totalSlaveWidth).roundToInt(), 16)
                            } catch (_: Exception) {}
                        }
                        Edge.BOTTOM -> {
                            relX = ratio * totalSlaveWidth
                            relY = totalSlaveHeight - 16f
                            try {
                                injector?.sendVirtualMouseDelta(-4000, 4000)
                                injector?.sendVirtualMouseDelta((ratio * totalSlaveWidth).roundToInt(), -16)
                            } catch (_: Exception) {}
                        }
                        null -> {}
                    }
                    HybridTransport.sendPong(sender)
                }
            }
            KvmState.PKT_MOVE -> {
                if (length >= 9) {
                    lastSlaveTrafficMs = SystemClock.elapsedRealtime()
                    val dx = buf.getFloat()
                    val dy = buf.getFloat()

                    relX += dx
                    relY += dy

                    val crossedBack = when (entryEdge) {
                        Edge.LEFT -> relX < -15f
                        Edge.RIGHT -> relX > totalSlaveWidth + 15f
                        Edge.TOP -> relY < -15f
                        Edge.BOTTOM -> relY > totalSlaveHeight + 15f
                        null -> false
                    }

                    if (crossedBack && masterAddress != null) {
                        lastSlaveTrafficMs = 0L
                        try { injector?.destroyVirtualMouse() } catch (_: Exception) {}
                        HybridTransport.sendReturn(masterAddress!!)
                        return
                    }

                    relX = relX.coerceIn(0f, totalSlaveWidth)
                    relY = relY.coerceIn(0f, totalSlaveHeight)

                    try {
                        injector?.sendVirtualMouseDelta(dx.roundToInt(), dy.roundToInt())
                    } catch (_: Exception) {}
                }
            }
            KvmState.PKT_BTN -> {
                if (length >= 9) {
                    lastSlaveTrafficMs = SystemClock.elapsedRealtime()
                    val action = buf.getInt()
                    val btn = buf.getInt()
                    val bit = when (btn) {
                        MotionEvent.BUTTON_PRIMARY -> 0x01
                        MotionEvent.BUTTON_SECONDARY -> 0x02
                        MotionEvent.BUTTON_TERTIARY -> 0x04
                        else -> 0x01
                    }
                    slaveButtonsMask = if (action == MotionEvent.ACTION_DOWN) {
                        slaveButtonsMask or bit
                    } else {
                        slaveButtonsMask and bit.inv()
                    }
                    try {
                        injector?.sendVirtualMouseButton(slaveButtonsMask)
                    } catch (_: Exception) {}
                }
            }
            KvmState.PKT_SCROLL -> {
                if (length >= 9) {
                    lastSlaveTrafficMs = SystemClock.elapsedRealtime()
                    val v = buf.getFloat()
                    val wheelStep = if (v > 0f) 1 else if (v < 0f) -1 else 0
                    if (wheelStep != 0) {
                        try {
                            injector?.sendVirtualMouseScroll(wheelStep)
                        } catch (_: Exception) {}
                    }
                }
            }
            KvmState.PKT_KEY -> {
                if (length >= 5) {
                    val keyCode = buf.getInt()
                    try { injector?.injectKey(keyCode) } catch (_: Exception) {}
                }
            }
            KvmState.PKT_RETURN -> {
                if (!KvmState.isRemoteActive) return
                exitRemoteSession(wakeMasterScreen = true)
            }
        }
    }

    override fun onDestroy() {
        running = false
        releaseCpuWakeLock()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServerSocket?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        try { wifiLowLatencyLock?.release() } catch (_: Exception) {}
        edgeViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        try { injector?.destroyVirtualMouse() } catch (_: Exception) {}
        try { injector?.stopHardwareMouseMonitor() } catch (_: Exception) {}
        try { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
