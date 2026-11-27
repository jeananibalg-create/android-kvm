package com.kvm.mouse

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Protocolo binário sobre UDP. Byte 0 = tipo do pacote. */
object Proto {
    const val REPORT: Byte = 1 // Mestre -> Escravo (8B): u8 botões, i16 dx, i16 dy, i8 wheel, i8 pan
    const val ENTER: Byte = 2  // Mestre -> Escravo (10B): u8 borda, f32 razão, i32 sessão
    const val ACK: Byte = 3    // Escravo -> Mestre (5B): i32 sessão
    const val LEAVE: Byte = 4  // Mestre -> Escravo (1B)
    const val RETURN: Byte = 5 // Escravo -> Mestre (1B)
    const val PING: Byte = 6   // Escravo -> Mestre (1B)
    const val HELLO: Byte = 7  // Escravo -> Broadcast: nome do modelo UTF-8
}

object Net {
    private val tx = Executors.newSingleThreadExecutor { r -> Thread(r, "kvm-tx").apply { isDaemon = true } }
    private val globalBroadcast: InetAddress by lazy { InetAddress.getByName("255.255.255.255") }

    @Volatile private var socket: DatagramSocket? = null

    fun open(): DatagramSocket {
        val s = DatagramSocket(null as SocketAddress?)
        s.reuseAddress = true
        s.broadcast = true
        runCatching { s.trafficClass = 0x10 } // IPTOS_LOWDELAY
        s.bind(InetSocketAddress(KvmState.PORT))
        socket = s
        return s
    }

    fun close() {
        socket?.close()
        socket = null
    }

    private fun post(host: InetAddress, data: ByteArray, copies: Int = 1) {
        tx.execute {
            val s = socket ?: return@execute
            val p = DatagramPacket(data, data.size, host, KvmState.PORT)
            try {
                repeat(copies) { s.send(p) }
            } catch (_: Exception) {
            }
        }
    }

    private fun frame(type: Byte, size: Int, fill: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(1 + size)
        b.put(type)
        b.fill()
        return b.array()
    }

    fun enter(host: InetAddress, edge: Edge, ratio: Float, session: Int) =
        post(host, frame(Proto.ENTER, 9) { put(edge.ordinal.toByte()); putFloat(ratio); putInt(session) }, 3)

    fun ack(host: InetAddress, session: Int) = post(host, frame(Proto.ACK, 4) { putInt(session) }, 2)
    fun leave(host: InetAddress) = post(host, frame(Proto.LEAVE, 0) {}, 2)
    fun ret(host: InetAddress) = post(host, frame(Proto.RETURN, 0) {}, 3)
    fun ping(host: InetAddress) = post(host, frame(Proto.PING, 0) {})

    fun hello(model: String) {
        val name = model.toByteArray(Charsets.UTF_8)
        val payload = frame(Proto.HELLO, name.size) { put(name) }
        val targets = subnetBroadcasts() + globalBroadcast
        targets.distinct().forEach { addr -> post(addr, payload) }
    }

    private fun subnetBroadcasts(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .toList()
    }.getOrDefault(emptyList())

    fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull() ?: "Desconhecido"
}
