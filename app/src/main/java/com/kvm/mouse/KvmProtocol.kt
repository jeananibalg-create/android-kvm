package com.kvm.mouse

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

enum class Edge(val id: Byte, val label: String) {
    LEFT(1, "Esquerda"),
    RIGHT(2, "Direita"),
    TOP(3, "Em cima"),
    BOTTOM(4, "Embaixo");

    companion object {
        fun fromId(id: Byte): Edge? = values().find { it.id == id }
    }
}

data class PeerDevice(
    val name: String,
    val host: InetAddress,
    val port: Int
)

object KvmState {
    const val UDP_PORT = 45678

    const val PKT_MOVE: Byte = 1
    const val PKT_BTN: Byte = 2
    const val PKT_SCROLL: Byte = 3
    const val PKT_ENTER: Byte = 4
    const val PKT_RETURN: Byte = 5
    const val PKT_KEY: Byte = 6
    const val PKT_DISCOVER: Byte = 7
    const val PKT_HELLO: Byte = 8
    const val PKT_PING: Byte = 9
    const val PKT_PONG: Byte = 10
    const val PKT_ACK: Byte = 11

    var isMasterMode = true
    var isRunning = false
    var allowLockscreenControl = true
    var pointerSpeed = 1.35f

    val discoveredPeers = mutableMapOf<String, PeerDevice>()
    val edgeAssignments = mutableMapOf<Edge, PeerDevice?>()

    @Volatile var activeEdge: Edge? = null
    @Volatile var activePeer: PeerDevice? = null
    @Volatile var isRemoteActive = false
    @Volatile var lastHeartbeatMs = 0L
    @Volatile var pendingReturnAck = false

    @Volatile var shizukuConnected = false
    @Volatile var detectedMouseCount = 0

    fun saveConfig(context: Context) {
        val prefs = context.getSharedPreferences("kvm_prefs", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        ed.putBoolean("isMasterMode", isMasterMode)
        ed.putBoolean("isRunning", isRunning)
        ed.putBoolean("allowLockscreenControl", allowLockscreenControl)
        ed.putFloat("pointerSpeed", pointerSpeed)
        Edge.values().forEach { edge ->
            val p = edgeAssignments[edge]
            if (p != null) {
                ed.putString("edge_${edge.name}_name", p.name)
                ed.putString("edge_${edge.name}_ip", p.host.hostAddress)
            } else {
                ed.remove("edge_${edge.name}_name")
                ed.remove("edge_${edge.name}_ip")
            }
        }
        ed.apply()
    }

    fun loadConfig(context: Context) {
        val prefs = context.getSharedPreferences("kvm_prefs", Context.MODE_PRIVATE)
        isMasterMode = prefs.getBoolean("isMasterMode", true)
        isRunning = prefs.getBoolean("isRunning", false)
        allowLockscreenControl = prefs.getBoolean("allowLockscreenControl", true)
        pointerSpeed = prefs.getFloat("pointerSpeed", 1.35f)
        Edge.values().forEach { edge ->
            val ip = prefs.getString("edge_${edge.name}_ip", null)
            val name = prefs.getString("edge_${edge.name}_name", null)
            if (!ip.isNullOrEmpty() && !name.isNullOrEmpty()) {
                try {
                    val peer = PeerDevice(name, InetAddress.getByName(ip), UDP_PORT)
                    edgeAssignments[edge] = peer
                    discoveredPeers[name] = peer
                } catch (_: Exception) {
                }
            }
        }
    }
}

object ReliableUdpTransport {
    private data class OutPacket(val data: ByteArray, val host: InetAddress, val port: Int)
    private val queue = LinkedBlockingQueue<OutPacket>(1024)
    private var socket: DatagramSocket? = null

    init {
        thread(isDaemon = true, name = "KvmNonBlockingSender") {
            while (true) {
                try {
                    val pkt = queue.take()
                    val s = socket ?: DatagramSocket().apply {
                        broadcast = true
                        trafficClass = 0x10
                    }.also { socket = it }
                    s.send(DatagramPacket(pkt.data, pkt.data.size, pkt.host, pkt.port))
                } catch (_: Exception) {
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }
            }
        }
    }

    fun sendMove(peer: PeerDevice, dx: Float, dy: Float) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_MOVE).putFloat(dx).putFloat(dy).array()
        queue.offer(OutPacket(buf, peer.host, peer.port))
    }

    fun sendButton(peer: PeerDevice, action: Int, button: Int) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_BTN).putInt(action).putInt(button).array()
        queue.offer(OutPacket(buf, peer.host, peer.port))
    }

    fun sendScroll(peer: PeerDevice, vScroll: Float, hScroll: Float) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_SCROLL).putFloat(vScroll).putFloat(hScroll).array()
        queue.offer(OutPacket(buf, peer.host, peer.port))
    }

    fun sendKey(peer: PeerDevice, keyCode: Int) {
        val buf = ByteBuffer.allocate(5).put(KvmState.PKT_KEY).putInt(keyCode).array()
        queue.offer(OutPacket(buf, peer.host, peer.port))
    }

    fun sendEnter(peer: PeerDevice, edge: Edge, ratio: Float) {
        val buf = ByteBuffer.allocate(6).put(KvmState.PKT_ENTER).put(edge.id).putFloat(ratio).array()
        queue.offer(OutPacket(buf, peer.host, peer.port))
        thread(isDaemon = true) {
            Thread.sleep(25)
            if (KvmState.isRemoteActive) {
                queue.offer(OutPacket(buf, peer.host, peer.port))
            }
        }
    }

    fun sendReturnWithRetry(host: InetAddress) {
        KvmState.pendingReturnAck = true
        val buf = byteArrayOf(KvmState.PKT_RETURN)
        thread(isDaemon = true, name = "KvmReturnRetry") {
            var attempts = 0
            while (KvmState.pendingReturnAck && attempts < 15) {
                queue.offer(OutPacket(buf, host, KvmState.UDP_PORT))
                attempts++
                Thread.sleep(35)
            }
            KvmState.pendingReturnAck = false
        }
    }

    fun sendAck(host: InetAddress) {
        queue.offer(OutPacket(byteArrayOf(KvmState.PKT_ACK), host, KvmState.UDP_PORT))
    }

    fun sendPing(peer: PeerDevice) {
        queue.offer(OutPacket(byteArrayOf(KvmState.PKT_PING), peer.host, peer.port))
    }

    fun sendPong(host: InetAddress) {
        queue.offer(OutPacket(byteArrayOf(KvmState.PKT_PONG), host, KvmState.UDP_PORT))
    }

    fun sendRaw(data: ByteArray, host: InetAddress, port: Int = KvmState.UDP_PORT) {
        queue.offer(OutPacket(data, host, port))
    }
}
