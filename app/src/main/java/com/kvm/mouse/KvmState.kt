package com.kvm.mouse

import java.net.InetAddress

enum class Edge(val label: String) {
    LEFT("Esquerda"),
    RIGHT("Direita"),
    TOP("Em cima"),
    BOTTOM("Embaixo")
}

data class PeerDevice(
    val name: String,
    val host: InetAddress,
    val port: Int
)

object KvmState {
    const val UDP_PORT = 45678
    const val SERVICE_TYPE = "_androidkvm._udp."

    var isMasterMode = true
    var isRunning = false
    val discoveredPeers = mutableMapOf<String, PeerDevice>()
    val edgeAssignments = mutableMapOf<Edge, PeerDevice?>()

    var activeEdge: Edge? = null
    var activePeer: PeerDevice? = null
    var captureActivity: PointerCaptureActivity? = null
}
