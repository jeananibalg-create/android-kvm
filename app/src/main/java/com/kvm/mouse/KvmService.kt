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
import android.util.DisplayMetrics
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
import java.util.concurrent.Executors

class KvmService : Service() {
    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val beaconExecutor = Executors.newSingleThreadExecutor()

    private val edgeViews = mutableListOf<View>()
    private var cursorView: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    private var screenW = 1080f
    private var screenH = 2400f
    private var curX = 540f
    private var curY = 1200f
    private var entryEdge: Edge? = null
    private var masterAddress: InetAddress? = null

    private var injector: IInputInjector? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.kvm.mouse", InputInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("kvm_injector").debuggable(false).version(1)

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            injector = IInputInjector.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
        }
    }

    companion object {
        private val sendExecutor = Executors.newSingleThreadExecutor()
        fun sendUdp(peer: PeerDevice, message: String) {
            sendExecutor.execute {
                try {
                    val data = message.toByteArray()
                    val socket = DatagramSocket()
                    socket.send(DatagramPacket(data, data.size, peer.host, peer.port))
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
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

        updateScreenMetrics()
        startForegroundNotification()
        startUdpListener()

        if (KvmState.isMasterMode) {
            setupMasterEdgeTriggers()
            startDiscoveryPingLoop()
        } else {
            bindShizuku()
            setupSlaveCursorOverlay()
            startSlaveBeaconLoop()
        }
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels.toFloat()
        screenH = metrics.heightPixels.toFloat()
    }

    private fun startForegroundNotification() {
        val channelId = "kvm_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Android KVM", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android KVM Ativo")
            .setContentText(if (KvmState.isMasterMode) "Modo Mestre (IP: ${getLocalIpAddress()})" else "Modo Escravo (IP: ${getLocalIpAddress()})")
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
        val thickness = 6
        Edge.values().forEach { edge ->
            val view = View(this)
            val params = WindowManager.LayoutParams(
                if (edge == Edge.LEFT || edge == Edge.RIGHT) thickness else WindowManager.LayoutParams.MATCH_PARENT,
                if (edge == Edge.TOP || edge == Edge.BOTTOM) thickness else WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = when (edge) {
                Edge.LEFT -> Gravity.START or Gravity.TOP
                Edge.RIGHT -> Gravity.END or Gravity.TOP
                Edge.TOP -> Gravity.TOP or Gravity.START
                Edge.BOTTOM -> Gravity.BOTTOM or Gravity.START
            }

            view.setOnHoverListener { _, event ->
                if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                    val targetPeer = KvmState.edgeAssignments[edge]
                    if (targetPeer != null && KvmState.captureActivity == null) {
                        val normRatio = when (edge) {
                            Edge.LEFT, Edge.RIGHT -> (event.rawY / screenH).coerceIn(0f, 1f)
                            Edge.TOP, Edge.BOTTOM -> (event.rawX / screenW).coerceIn(0f, 1f)
                        }
                        KvmState.activeEdge = edge
                        KvmState.activePeer = targetPeer
                        sendUdp(targetPeer, "ENTER:${edge.name}:$normRatio")

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
            48, 48,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = curX.toInt()
            y = curY.toInt()
        }
        wm.addView(img, params)
        cursorView = img
        cursorParams = params
    }

    private fun startSlaveBeaconLoop() {
        beaconExecutor.execute {
            while (running) {
                try {
                    val socket = DatagramSocket().apply { broadcast = true }
                    val msg = "HELLO_SLAVE:${Build.MODEL}".toByteArray()
                    socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), KvmState.UDP_PORT))
                    socket.close()
                } catch (_: Exception) {
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun startDiscoveryPingLoop() {
        beaconExecutor.execute {
            while (running) {
                try {
                    val socket = DatagramSocket().apply { broadcast = true }
                    val msg = "DISCOVER_KVM".toByteArray()
                    socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), KvmState.UDP_PORT))
                    socket.close()
                } catch (_: Exception) {
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun startUdpListener() {
        netExecutor.execute {
            try {
                udpSocket = DatagramSocket(KvmState.UDP_PORT).apply { broadcast = true }
                val buf = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    handleUdpMessage(msg, packet.address)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleUdpMessage(msg: String, sender: InetAddress) {
        val parts = msg.split(":")
        when (parts[0]) {
            "DISCOVER_KVM" -> {
                if (!KvmState.isMasterMode) {
                    sendExecutor.execute {
                        try {
                            val reply = "HELLO_SLAVE:${Build.MODEL}".toByteArray()
                            val s = DatagramSocket()
                            s.send(DatagramPacket(reply, reply.size, sender, KvmState.UDP_PORT))
                            s.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            "HELLO_SLAVE" -> {
                if (KvmState.isMasterMode) {
                    val modelName = parts.getOrElse(1) { "Android" }
                    val label = "$modelName (${sender.hostAddress})"
                    KvmState.discoveredPeers[label] = PeerDevice(label, sender, KvmState.UDP_PORT)
                }
            }
            "ENTER" -> {
                masterAddress = sender
                val fromMasterEdge = Edge.valueOf(parts[1])
                val ratio = parts[2].toFloatOrNull() ?: 0.5f

                entryEdge = when (fromMasterEdge) {
                    Edge.RIGHT -> Edge.LEFT
                    Edge.LEFT -> Edge.RIGHT
                    Edge.TOP -> Edge.BOTTOM
                    Edge.BOTTOM -> Edge.TOP
                }

                when (entryEdge) {
                    Edge.LEFT -> { curX = 5f; curY = ratio * screenH }
                    Edge.RIGHT -> { curX = screenW - 5f; curY = ratio * screenH }
                    Edge.TOP -> { curX = ratio * screenW; curY = 5f }
                    Edge.BOTTOM -> { curX = ratio * screenW; curY = screenH - 5f }
                    null -> {}
                }
                updateCursorUi(visible = true)
            }
            "MOVE" -> {
                val dx = parts[1].toFloatOrNull() ?: 0f
                val dy = parts[2].toFloatOrNull() ?: 0f
                curX += dx
                curY += dy

                val crossedBack = when (entryEdge) {
                    Edge.LEFT -> curX < 0f
                    Edge.RIGHT -> curX > screenW
                    Edge.TOP -> curY < 0f
                    Edge.BOTTOM -> curY > screenH
                    null -> false
                }

                if (crossedBack && masterAddress != null) {
                    updateCursorUi(visible = false)
                    sendBackToMaster()
                    return
                }

                curX = curX.coerceIn(0f, screenW)
                curY = curY.coerceIn(0f, screenH)
                updateCursorUi(visible = true)
                injector?.injectMove(curX, curY)
            }
            "BTN" -> {
                val action = parts[1].toIntOrNull() ?: return
                val btn = parts[2].toIntOrNull() ?: 0
                injector?.injectButton(action, curX, curY, btn)
            }
            "SCR" -> {
                val v = parts[1].toFloatOrNull() ?: 0f
                val h = parts[2].toFloatOrNull() ?: 0f
                injector?.injectScroll(curX, curY, v, h)
            }
            "RETURN" -> {
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

    private fun sendBackToMaster() {
        val host = masterAddress ?: return
        sendExecutor.execute {
            try {
                val data = "RETURN".toByteArray()
                val socket = DatagramSocket()
                socket.send(DatagramPacket(data, data.size, host, KvmState.UDP_PORT))
                socket.close()
            } catch (_: Exception) {
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
