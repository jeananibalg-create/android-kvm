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
    val peers = MutableStateFlow>(emptyMap())
    val edges = MutableStateFlow>(emptyMap())

    @Volatile var armed = true
}
