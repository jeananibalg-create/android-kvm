package com.kvm.mouse

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.net.InetAddress

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ipText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnMaster: Button
    private lateinit var btnSlave: Button
    private lateinit var edgesBox: LinearLayout
    private lateinit var slaveHint: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnToggle: Button
    private val edgeButtons = mutableMapOf<Edge, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.put(v: View, top: Int = 12) {
        addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        root.put(text("Android KVM 3.3", 24f), 0)
        ipText = text("", 14f, Color.rgb(102, 187, 106))
        root.put(ipText, 4)
        statusText = text("", 13f, Color.LTGRAY)
        root.put(statusText, 8)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMaster = button("Mestre (mouse aqui)") { KvmState.isMaster.value = true }
        btnSlave = button("Escravo (recebe a seta)") { KvmState.isMaster.value = false }
        modeRow.addView(btnMaster, LinearLayout.LayoutParams(0, -2, 1f))
        modeRow.addView(btnSlave, LinearLayout.LayoutParams(0, -2, 1f))
        root.put(modeRow, 16)

        edgesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgesBox.put(text("Celulares escravos nas bordas:", 14f), 0)
        Edge.entries.forEach { edge ->
            val b = button("") { pickPeer(edge) }
            edgeButtons[edge] = b
            edgesBox.put(b, 4)
        }
        root.put(edgesBox, 16)

        slaveHint = text(
            "Botão esquerdo, direito (menu/voltar), meio e roda funcionam nativamente: " +
                "o escravo enxerga um mouse Bluetooth real. No DeX ative \"Fluxo do ponteiro para a tela do telefone\".",
            12f, Color.GRAY
        )
        root.put(slaveHint, 16)

        root.put(button("1. Autorizar Shizuku (Mestre e Escravo)") { requestShizuku() }, 16)
        btnOverlay = button("2. Permitir sobrepor apps (Mestre)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.put(btnOverlay, 8)

        btnToggle = button("") {
            if (!KvmState.running.value && !Settings.canDrawOverlays(this) && KvmState.isMaster.value) {
                toast("Ative \"Sobrepor apps\" primeiro")
                return@button
            }
            val i = Intent(this, KvmService::class.java)
            if (KvmState.running.value) stopService(i) else startForegroundService(i)
        }
        btnToggle.setTextColor(Color.WHITE)
        root.put(btnToggle, 20)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })

        scope.launch {
            combine(
                KvmState.isMaster, KvmState.running, KvmState.edges, KvmState.status, KvmState.shizuku
            ) { _, _, _, _, _ -> }.collect { render() }
        }
    }

    override fun onResume() {
        super.onResume()
        ipText.text = "IP deste aparelho: ${Net.localIp()}"
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val master = KvmState.isMaster.value
        val running = KvmState.running.value
        btnMaster.isEnabled = !running
        btnSlave.isEnabled = !running
        btnMaster.alpha = if (master) 1f else 0.45f
        btnSlave.alpha = if (master) 0.45f else 1f
        edgesBox.visibility = if (master) View.VISIBLE else View.GONE
        slaveHint.visibility = if (master) View.GONE else View.VISIBLE
        Edge.entries.forEach {
            edgeButtons[it]?.text = "${it.label}: ${KvmState.edges.value[it]?.name ?: "Nenhum"}"
        }
        btnToggle.text = if (running) "PARAR SERVIÇO" else "INICIAR SERVIÇO"
        btnToggle.setBackgroundColor(if (running) 0xFFB71C1C.toInt() else 0xFF1B5E20.toInt())
        btnOverlay.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val shizuku = if (KvmState.shizuku.value) "Shizuku: conectado" else "Shizuku: desconectado"
        statusText.text = listOf(shizuku, KvmState.status.value).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun pickPeer(edge: Edge) {
        val peers = KvmState.peers.value.values.toList()
        val items = (listOf("Nenhum", "Digitar IP manualmente…") + peers.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Celular na borda: ${edge.label}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> KvmState.edges.update { it - edge }
                    1 -> typeIp(edge)
                    else -> KvmState.edges.update { it + (edge to peers[which - 2]) }
                }
            }
            .show()
    }

    private fun typeIp(edge: Edge) {
        val input = EditText(this).apply {
            hint = "Ex: 192.168.1.45"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("IP do escravo (${edge.label})")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val ip = input.text.toString().trim()
                if (Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(ip)) {
                    val peer = PeerDevice("Manual ($ip)", InetAddress.getByName(ip))
                    KvmState.edges.update { it + (edge to peer) }
                } else {
                    toast("IP inválido")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestShizuku() {
        try {
            when {
                !Shizuku.pingBinder() -> toast("Abra o app Shizuku e inicie o serviço primeiro.")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> toast("Shizuku já autorizado!")
                else -> Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            toast("Erro Shizuku: ${e.message}")
        }
    }
}
