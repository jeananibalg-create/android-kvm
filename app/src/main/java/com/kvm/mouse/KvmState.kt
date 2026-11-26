package com.kvm.mouse

import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetAddress

enum class Edge(val label: String) {
    LEFT("Esquerda"),
    RIGHT("Direita"),
    TOP("Em cima"),
    BOTTOM("Embaixo");

    val opposite: Edge
        get() = when (this) {
            LEFT -> RIGHT
            RIGHT -> LEFT
            TOP -> BOTTOM
            BOTTOM -> TOP
        }
}

data class PeerDevice(val name: String, val host: InetAddress)

object KvmState {
    const val PORT = 45678

    val isMaster = MutableStateFlow(true)
    val running = MutableStateFlow(false)
    val shizuku = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val peers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    val edges = MutableStateFlow<Map<Edge, PeerDevice>>(emptyMap())

    /** Só dispara a travessia se o ponteiro já saiu da faixa da borda desde a última vez. */
    @Volatile var armed = true
}
