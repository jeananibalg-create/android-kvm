package com.kvm.mouse

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

    var isMasterMode = true
    var isRunning = false
    var pointerSpeed = 1.35f

    val discoveredPeers = mutableMapOf<String, PeerDevice>()
    val edgeAssignments = mutableMapOf<Edge, PeerDevice?>()

    @Volatile var activeEdge: Edge? = null
    @Volatile var activePeer: PeerDevice? = null
    @Volatile var captureActivity: PointerCaptureActivity? = null
    @Volatile var isRemoteActive = false
}

object UdpFastSender {
    private data class OutPacket(val data: ByteArray, val host: InetAddress, val port: Int)
    private val queue = LinkedBlockingQueue<OutPacket>(512)
    private var socket: DatagramSocket? = null

    init {
        thread(isDaemon = true, name = "KvmUdpSender") {
            while (true) {
                try {
                    val pkt = queue.take()
                    val s = socket ?: DatagramSocket().apply { broadcast = true }.also { socket = it }
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
    }

    fun sendReturn(host: InetAddress) {
        val buf = byteArrayOf(KvmState.PKT_RETURN)
        queue.offer(OutPacket(buf, host, KvmState.UDP_PORT))
    }

    fun sendRaw(data: ByteArray, host: InetAddress, port: Int = KvmState.UDP_PORT) {
        queue.offer(OutPacket(data, host, port))
    }
}
