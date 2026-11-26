package com.kvm.mouse

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
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

    private var injector: IInputInjector? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = true

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.kvm.mouse", InputInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("kvm_injector").debuggable(false).version(9)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    KvmState.captureActivity?.releaseCaptureWindowOnly()
                    if (KvmState.isMasterMode && KvmState.isRemoteActive) {
                        acquireCpuOnlyWakeLock()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (KvmState.isMasterMode && KvmState.isRemoteActive) {
                        mainHandler.postDelayed({
                            if (KvmState.isRemoteActive) {
                                try { injector?.setScreenPower(false) } catch (_: Exception) {}
                            }
                        }, 40)
                    }
                }
            }
        }
    }

    private fun acquireCpuOnlyWakeLock() {
        try {
            if (cpuWakeLock == null) {
                cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidKVM:CpuBluetoothLock")
            }
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(4 * 60 * 60 * 1000L)
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

    private fun isScreenLockedOrOff(): Boolean {
        return !powerManager.isInteractive || keyguardManager.isKeyguardLocked
    }

    private val hwCallback = object : IMouseEventCallback.Stub() {
        override fun onMouseDelta(dx: Float, dy: Float) {
            if (!KvmState.isMasterMode) return

            if (isScreenLockedOrOff() || (KvmState.isRemoteActive && KvmState.captureActivity == null)) {
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
                        KvmState.activeEdge = crossedEdge
                        KvmState.activePeer = peer
                        KvmState.isRemoteActive = true
                        KvmState.lastHeartbeatMs = SystemClock.elapsedRealtime()
                        acquireCpuOnlyWakeLock()
                        UdpFastSender.sendEnter(peer, crossedEdge, ratio)
                    }
                } else {
                    val peer = KvmState.activePeer ?: return
                    UdpFastSender.sendMove(peer, dx * KvmState.pointerSpeed, dy * KvmState.pointerSpeed)
                }
            }
        }

        override fun onMouseButton(action: Int, button: Int) {
            if (!KvmState.isMasterMode || !KvmState.isRemoteActive) return
            if (isScreenLockedOrOff() || KvmState.captureActivity == null) {
                val peer = KvmState.activePeer ?: return
                UdpFastSender.sendButton(peer, action, button)
            }
        }

        override fun onMouseScroll(vScroll: Float) {
            if (!KvmState.isMasterMode || !KvmState.isRemoteActive) return
            if (isScreenLockedOrOff() || KvmState.captureActivity == null) {
                val peer = KvmState.activePeer ?: return
                UdpFastSender.sendScroll(peer, vScroll, 0f)
            }
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            injector = IInputInjector.Stub.asInterface(service)
            if (KvmState.isMasterMode) {
                try { injector?.startHardwareMouseMonitor(hwCallback) } catch (_: Exception) {}
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
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("kvm_lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        startForegroundNotification()
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

    // Watchdog de recuperacao de falhas: reconecta Shizuku e libera cursor preso se o Wi-Fi cair
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
                            UdpFastSender.sendPing(peer)
                        }
                        // Se o Escravo ficar >1.4s sem responder ao ping, devolve controle para nunca travar
                        if (KvmState.lastHeartbeatMs > 0L && (now - KvmState.lastHeartbeatMs) > 1400L) {
                            KvmState.isRemoteActive = false
                            releaseCpuWakeLock()
                            KvmState.captureActivity?.releaseAndClose()
                        }
                    } else if (!KvmState.isMasterMode) {
                        // Se o Mestre parar de enviar sinais por >1.6s, remove o mouse virtual da tela escrava
                        if (lastSlaveTrafficMs > 0L && (now - lastSlaveTrafficMs) > 1600L) {
                            lastSlaveTrafficMs = 0L
                            try { injector?.destroyVirtualMouse() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                }
                Thread.sleep(400)
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
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android KVM v3.1 (HID + Watchdog)")
            .setContentText(if (KvmState.isMasterMode) "Mestre (${getLocalIpAddress()})" else "Escravo (${getLocalIpAddress()})")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
                    val targetPeer = KvmState.edgeAssignments[edge]
                    if (targetPeer != null && KvmState.captureActivity == null && !KvmState.isRemoteActive) {
                        val (w, h) = getFullScreenBounds()
                        val normRatio = when (edge) {
                            Edge.LEFT, Edge.RIGHT -> (event.rawY / h).coerceIn(0f, 1f)
                            Edge.TOP, Edge.BOTTOM -> (event.rawX / w).coerceIn(0f, 1f)
                        }
                        KvmState.activeEdge = edge
                        KvmState.activePeer = targetPeer
                        KvmState.isRemoteActive = true
                        KvmState.lastHeartbeatMs = SystemClock.elapsedRealtime()
                        UdpFastSender.sendEnter(targetPeer, edge, normRatio)

                        if (!isScreenLockedOrOff()) {
                            val intent = Intent(this, PointerCaptureActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                                )
                            }
                            startActivity(intent)
                        } else {
                            acquireCpuOnlyWakeLock()
                        }
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
                UdpFastSender.sendRaw(pktData, broadcastAddr)
                Thread.sleep(2000)
            }
        }
    }

    private fun startDiscoveryLoop() {
        thread(isDaemon = true, name = "KvmMasterDiscover") {
            val pktData = byteArrayOf(KvmState.PKT_DISCOVER)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (running) {
                UdpFastSender.sendRaw(pktData, broadcastAddr)
                Thread.sleep(2000)
            }
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
                    UdpFastSender.sendRaw(reply, sender)
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
                    UdpFastSender.sendPong(sender)
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
                    UdpFastSender.sendPong(sender)
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
                        UdpFastSender.sendReturn(masterAddress!!)
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
                val (w, h) = getFullScreenBounds()
                when (KvmState.activeEdge) {
                    Edge.RIGHT -> virtualMasterX = w - 40f
                    Edge.LEFT -> virtualMasterX = 40f
                    Edge.TOP -> virtualMasterY = 40f
                    Edge.BOTTOM -> virtualMasterY = h - 40f
                    null -> {}
                }
                KvmState.isRemoteActive = false
                releaseCpuWakeLock()
                KvmState.captureActivity?.releaseAndClose()

                if (!powerManager.isInteractive) {
                    try { injector?.setScreenPower(true) } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        releaseCpuWakeLock()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        udpSocket?.close()
        try { multicastLock?.release() } catch (_: Exception) {}
        edgeViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        try { injector?.destroyVirtualMouse() } catch (_: Exception) {}
        try { injector?.stopHardwareMouseMonitor() } catch (_: Exception) {}
        try { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
