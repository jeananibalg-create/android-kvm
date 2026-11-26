package com.kvm.mouse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import java.util.concurrent.Executors

class KvmService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var nsdManager: NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newSingleThreadExecutor()

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
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

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
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        updateScreenMetrics()
        startForegroundNotification()
        startUdpListener()

        if (KvmState.isMasterMode) {
            setupMasterEdgeTriggers()
            startNsdDiscovery()
        } else {
            bindShizuku()
            setupSlaveCursorOverlay()
            registerNsdService()
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
            .setContentText(if (KvmState.isMasterMode) "Modo Mestre (Aguardando borda)" else "Modo Escravo (Shizuku)")
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

    private fun startUdpListener() {
        netExecutor.execute {
            try {
                udpSocket = DatagramSocket(KvmState.UDP_PORT)
                val buf = ByteArray(512)
                while (!Thread.currentThread().isInterrupted) {
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

    private fun registerNsdService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "KVM_${Build.MODEL.replace(" ", "_")}"
            serviceType = KvmState.SERVICE_TYPE
            port = KvmState.UDP_PORT
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startNsdDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_androidkvm")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host ?: return
                            val cleanName = info.serviceName.removePrefix("KVM_").replace("_", " ")
                            KvmState.discoveredPeers[cleanName] = PeerDevice(cleanName, host, info.port)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices(KvmState.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun onDestroy() {
        udpSocket?.close()
        edgeViews.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        cursorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        registrationListener?.let { try { nsdManager.unregisterService(it) } catch (_: Exception) {} }
        discoveryListener?.let { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        try { Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
