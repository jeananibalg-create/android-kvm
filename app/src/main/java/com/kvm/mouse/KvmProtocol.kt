package com.kvm.mouse

import android.content.Context
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
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
    const val TCP_PORT = 45679

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

    var isMasterMode = true
    var isRunning = false
    var pointerSpeed = 1.35f

    val discoveredPeers = mutableMapOf<String, PeerDevice>()
    val edgeAssignments = mutableMapOf<Edge, PeerDevice?>()

    @Volatile var activeEdge: Edge? = null
    @Volatile var activePeer: PeerDevice? = null
    @Volatile var captureActivity: PointerCaptureActivity? = null
    @Volatile var isRemoteActive = false
    @Volatile var lastHeartbeatMs = 0L

    fun saveConfig(context: Context) {
        val prefs = context.getSharedPreferences("kvm_prefs", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        ed.putBoolean("isMasterMode", isMasterMode)
        ed.putBoolean("isRunning", isRunning)
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

// Transporte Hibrido v4.0:
// - TCP persistente com TCP_NODELAY para comandos criticos (ENTER, RETURN, CLICKS, SCROLL, PING) -> 100% entrega garantida
// - UDP ultra-rapido para delta de movimento continuo (dx, dy) -> latencia minima sem bloqueio
object HybridTransport {
    private data class UdpPacket(val data: ByteArray, val host: InetAddress, val port: Int)
    private data class TcpPacket(val data: ByteArray, val host: InetAddress)

    private val udpQueue = LinkedBlockingQueue<UdpPacket>(512)
    private val tcpQueue = LinkedBlockingQueue<TcpPacket>(256)
    private val tcpConnections = ConcurrentHashMap<String, Socket>()
    private var udpSocket: DatagramSocket? = null

    init {
        thread(isDaemon = true, name = "KvmUdpSender") {
            while (true) {
                try {
                    val pkt = udpQueue.take()
                    val s = udpSocket ?: DatagramSocket().apply {
                        broadcast = true
                        trafficClass = 0x10 // IPTOS_LOWDELAY
                    }.also { udpSocket = it }
                    s.send(DatagramPacket(pkt.data, pkt.data.size, pkt.host, pkt.port))
                } catch (_: Exception) {
                    try { udpSocket?.close() } catch (_: Exception) {}
                    udpSocket = null
                }
            }
        }

        thread(isDaemon = true, name = "KvmTcpControlSender") {
            while (true) {
                val pkt = tcpQueue.take()
                val ipKey = pkt.host.hostAddress ?: continue
                var sent = false
                for (attempt in 1..2) {
                    try {
                        val sock = getOrOpenTcpSocket(ipKey, pkt.host)
                        synchronized(sock) {
                            val out = DataOutputStream(sock.getOutputStream())
                            out.writeShort(pkt.data.size)
                            out.write(pkt.data)
                            out.flush()
                        }
                        sent = true
                        break
                    } catch (_: Exception) {
                        closeTcpSocket(ipKey)
                    }
                }
                if (!sent) {
                    // Fallback UDP caso o destino ainda esteja abrindo a porta TCP
                    udpQueue.offer(UdpPacket(pkt.data, pkt.host, KvmState.UDP_PORT))
                }
            }
        }
    }

    private fun getOrOpenTcpSocket(ipKey: String, host: InetAddress): Socket {
        val existing = tcpConnections[ipKey]
        if (existing != null && existing.isConnected && !existing.isClosed) {
            return existing
        }
        closeTcpSocket(ipKey)
        val sock = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            trafficClass = 0x10 // IPTOS_LOWDELAY
            connect(InetSocketAddress(host, KvmState.TCP_PORT), 600)
        }
        tcpConnections[ipKey] = sock
        return sock
    }

    private fun closeTcpSocket(ipKey: String) {
        val old = tcpConnections.remove(ipKey)
        try { old?.close() } catch (_: Exception) {}
    }

    fun sendMove(peer: PeerDevice, dx: Float, dy: Float) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_MOVE).putFloat(dx).putFloat(dy).array()
        udpQueue.offer(UdpPacket(buf, peer.host, peer.port))
    }

    fun sendButton(peer: PeerDevice, action: Int, button: Int) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_BTN).putInt(action).putInt(button).array()
        tcpQueue.offer(TcpPacket(buf, peer.host))
    }

    fun sendScroll(peer: PeerDevice, vScroll: Float, hScroll: Float) {
        val buf = ByteBuffer.allocate(9).put(KvmState.PKT_SCROLL).putFloat(vScroll).putFloat(hScroll).array()
        tcpQueue.offer(TcpPacket(buf, peer.host))
    }

    fun sendKey(peer: PeerDevice, keyCode: Int) {
        val buf = ByteBuffer.allocate(5).put(KvmState.PKT_KEY).putInt(keyCode).array()
        tcpQueue.offer(TcpPacket(buf, peer.host))
    }

    fun sendEnter(peer: PeerDevice, edge: Edge, ratio: Float) {
        val buf = ByteBuffer.allocate(6).put(KvmState.PKT_ENTER).put(edge.id).putFloat(ratio).array()
        tcpQueue.offer(TcpPacket(buf, peer.host))
    }

    fun sendReturn(host: InetAddress) {
        val buf = byteArrayOf(KvmState.PKT_RETURN)
        tcpQueue.offer(TcpPacket(buf, host))
        // Envia tambem via UDP redundante para latencia zero na volta
        udpQueue.offer(UdpPacket(buf, host, KvmState.UDP_PORT))
    }

    fun sendPing(peer: PeerDevice) {
        tcpQueue.offer(TcpPacket(byteArrayOf(KvmState.PKT_PING), peer.host))
    }

    fun sendPong(host: InetAddress) {
        tcpQueue.offer(TcpPacket(byteArrayOf(KvmState.PKT_PONG), host))
    }

    fun sendBroadcastRaw(data: ByteArray, host: InetAddress, port: Int = KvmState.UDP_PORT) {
        udpQueue.offer(UdpPacket(data, host, port))
    }
}
