package com.kvm.mouse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class KvmService : Service() {
    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val edgeViews = mutableListOf<View>()
    private var cursorView: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var curX = 300f
    private var curY = 600f
    private var entryEdge: Edge? = null
    private var masterAddress: InetAddress? = null

    private var injector: IInputInjector? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.kvm.mouse", InputInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("kvm_injector").debuggable(false).version(3)

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            injector = IInputInjector.Stub.asInterface(service)
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
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("kvm_lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        startForegroundNotification()
        startFastUdpReceiver()

        if (KvmState.isMasterMode) {
            setupMasterEdgeTriggers()
            startDiscoveryLoop()
        } else {
            bindShizuku()
            setupSlaveCursorOverlay()
            startBeaconLoop()
        }
    }

    private fun getFullScreenBounds(): Pair<Float, Float> {
        val bounds = wm.maximumWindowMetrics.bounds
        return Pair(bounds.width().toFloat(), bounds.height().toFloat())
    }

    private fun startForegroundNotification() {
        val channelId = "kvm_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Android KVM", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android KVM v2.1 Ativo")
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
                    if (targetPeer != null && KvmState.captureActivity == null) {
                        val (w, h) = getFullScreenBounds()
                        val normRatio = when (edge) {
                            Edge.LEFT, Edge.RIGHT -> (event.rawY / h).coerceIn(0f, 1f)
                            Edge.TOP, Edge.BOTTOM -> (event.rawX / w).coerceIn(0f, 1f)
                        }
                        KvmState.activeEdge = edge
                        KvmState.activePeer = targetPeer
                        UdpFastSender.sendEnter(targetPeer, edge, normRatio)

                        val intent = Intent(this, PointerCaptureActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                        startActivity(intent)
                    }
                }
                false
            }
            wm.addView(view, params)
            edgeViews.add(view)
        }
    }

    private fun setupSlaveCursorOverlay() {
        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            44, 44,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.TOP or Gravity.START
            x = curX.toInt()
            y = curY.toInt()
        }
        wm.addView(img, params)
        cursorView = img
        cursorParams = params
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
            KvmState.PKT_ENTER -> {
                if (length >= 6) {
                    masterAddress = sender
                    val fromEdge = Edge.fromId(buf.get()) ?: Edge.RIGHT
                    val ratio = buf.getFloat().coerceIn(0f, 1f)
                    val (w, h) = getFullScreenBounds()

                    entryEdge = when (fromEdge) {
                        Edge.RIGHT -> Edge.LEFT
                        Edge.LEFT -> Edge.RIGHT
                        Edge.TOP -> Edge.BOTTOM
                        Edge.BOTTOM -> Edge.TOP
                    }

                    when (entryEdge) {
                        Edge.LEFT -> { curX = 18f; curY = ratio * h }
                        Edge.RIGHT -> { curX = w - 18f; curY = ratio * h }
                        Edge.TOP -> { curX = ratio * w; curY = 18f }
                        Edge.BOTTOM -> { curX = ratio * w; curY = h - 18f }
                        null -> {}
                    }
                    updateCursorUi(visible = true)
                    injector?.injectMove(curX, curY)
                }
            }
            KvmState.PKT_MOVE -> {
                if (length >= 9) {
                    val dx = buf.getFloat()
                    val dy = buf.getFloat()
                    val (w, h) = getFullScreenBounds()

                    curX += dx
                    curY += dy

                    val crossedBack = when (entryEdge) {
                        Edge.LEFT -> curX < -12f
                        Edge.RIGHT -> curX > w + 12f
                        Edge.TOP -> curY < -12f
                        Edge.BOTTOM -> curY > h + 12f
                        null -> false
                    }

                    if (crossedBack && masterAddress != null) {
                        updateCursorUi(visible = false)
                        UdpFastSender.sendReturn(masterAddress!!)
                        return
                    }

                    curX = curX.coerceIn(0f, w - 1f)
                    curY = curY.coerceIn(0f, h - 1f)
                    updateCursorUi(visible = true)
                    injector?.injectMove(curX, curY)
                }
            }
            KvmState.PKT_BTN -> {
                if (length >= 9) {
                    val action = buf.getInt()
                    val btn = buf.getInt()
                    injector?.injectButton(action, curX, curY, btn)
                }
            }
            KvmState.PKT_SCROLL -> {
                if (length >= 9) {
                    val v = buf.getFloat()
                    val h = buf.getFloat()
                    injector?.injectScroll(curX, curY, v, h)
                }
            }
            KvmState.PKT_KEY -> {
                if (length >= 5) {
                    val keyCode = buf.getInt()
                    injector?.injectKey(keyCode)
                }
            }
            KvmState.PKT_RETURN -> {
                KvmState.captureActivity?.releaseAndClose()
            }
        }
    }

    private fun updateCursorUi(visible: Boolean) {
        mainHandler.post {
            cursorView?.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && cursorParams != null) {
                cursorParams?.x = curX.toInt()
                cursorParams?.y = curY.toInt()
                try {
                    wm.updateViewLayout(cursorView, cursorParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        udpSocket?.close()
        try { multicastLock?.release() } catch (_: Exception) {}
        edgeViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        cursorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        try { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
