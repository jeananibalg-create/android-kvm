package com.kvm.mouse

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private val edgeButtons = mutableMapOf<Edge, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Android KVM v2.0 (Zero-Latency)"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "IP local: ${KvmService.getLocalIpAddress()}"
            textSize = 14f
            setTextColor(Color.GREEN)
            setPadding(0, 12, 0, 24)
        }
        root.addView(statusText)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnMaster = Button(this).apply { text = "MESTRE (Mouse aqui)" }
        val btnSlave = Button(this).apply { text = "ESCRAVO (Recebe seta)" }
        modeRow.addView(btnMaster, LinearLayout.LayoutParams(0, -2, 1f))
        modeRow.addView(btnSlave, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(modeRow)

        val edgesLabel = TextView(this).apply {
            text = "\nPosição dos celulares escravos (Modo Mestre):"
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 8)
        }
        root.addView(edgesLabel)

        Edge.values().forEach { edge ->
            val btn = Button(this).apply {
                text = "${edge.label}: Nenhum"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { showPeerSelector(edge) }
            }
            edgeButtons[edge] = btn
            root.addView(btn)
        }

        val speedLabel = TextView(this).apply {
            text = "\nVelocidade da seta no Escravo: 1.35x"
            setTextColor(Color.LTGRAY)
        }
        root.addView(speedLabel)

        val speedBar = SeekBar(this).apply {
            max = 250
            progress = 85
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = 0.5f + (progress / 100f)
                    KvmState.pointerSpeed = speed
                    speedLabel.text = "Velocidade da seta no Escravo: ${String.format("%.2f", speed)}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(speedBar)

        val btnShizuku = Button(this).apply {
            text = "1. Autorizar Shizuku (Obrigatório no Escravo)"
            setOnClickListener { requestShizukuPerm() }
        }
        root.addView(btnShizuku)

        val btnOverlay = Button(this).apply {
            text = "2. Permitir Sobrepor Apps (Obrigatório em ambos)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Permissão já concedida!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnOverlay)

        val btnToggleService = Button(this).apply {
            text = "INICIAR SERVIÇO KVM"
            setBackgroundColor(Color.parseColor("#1B5E20"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Ative 'Sobrepor Apps' primeiro!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!KvmState.isRunning) {
                    startForegroundService(Intent(this@MainActivity, KvmService::class.java))
                    KvmState.isRunning = true
                    text = "PARAR SERVIÇO KVM"
                    setBackgroundColor(Color.parseColor("#B71C1C"))
                    statusText.text = if (KvmState.isMasterMode)
                        "MESTRE ATIVO (IP: ${KvmService.getLocalIpAddress()})"
                    else
                        "ESCRAVO ATIVO - IP: ${KvmService.getLocalIpAddress()}"
                } else {
                    stopService(Intent(this@MainActivity, KvmService::class.java))
                    KvmState.isRunning = false
                    text = "INICIAR SERVIÇO KVM"
                    setBackgroundColor(Color.parseColor("#1B5E20"))
                    statusText.text = "IP local: ${KvmService.getLocalIpAddress()}"
                }
            }
        }
        root.addView(btnToggleService)

        btnMaster.setOnClickListener {
            KvmState.isMasterMode = true
            statusText.text = "Modo: MESTRE | IP: ${KvmService.getLocalIpAddress()}"
        }
        btnSlave.setOnClickListener {
            KvmState.isMasterMode = false
            statusText.text = "Modo: ESCRAVO | IP: ${KvmService.getLocalIpAddress()}"
        }

        setContentView(root)
    }

    private fun showPeerSelector(edge: Edge) {
        val peers = KvmState.discoveredPeers.values.toList()
        val items = (listOf("Nenhum", "+ Digitar IP do Escravo manualmente") + peers.map { it.name }).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Celular na borda: ${edge.label}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        KvmState.edgeAssignments[edge] = null
                        edgeButtons[edge]?.text = "${edge.label}: Nenhum"
                    }
                    1 -> showManualIpDialog(edge)
                    else -> {
                        val chosen = peers[which - 2]
                        KvmState.edgeAssignments[edge] = chosen
                        edgeButtons[edge]?.text = "${edge.label}: ${chosen.name}"
                    }
                }
            }
            .show()
    }

    private fun showManualIpDialog(edge: Edge) {
        val input = EditText(this).apply {
            hint = "Ex: 192.168.1.101"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("IP do celular Escravo (${edge.label})")
            .setMessage("Olhe o IP exibido em verde na tela do celular Escravo:")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val ipStr = input.text.toString().trim()
                if (ipStr.isNotEmpty()) {
                    thread {
                        try {
                            val addr = InetAddress.getByName(ipStr)
                            val peer = PeerDevice("Manual ($ipStr)", addr, KvmState.UDP_PORT)
                            KvmState.discoveredPeers[peer.name] = peer
                            KvmState.edgeAssignments[edge] = peer
                            runOnUiThread {
                                edgeButtons[edge]?.text = "${edge.label}: $ipStr"
                                Toast.makeText(this, "Conectado ao IP $ipStr!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "IP inválido!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestShizukuPerm() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Abra o app Shizuku e inicie o serviço primeiro.", Toast.LENGTH_LONG).show()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            } else {
                Toast.makeText(this, "Shizuku já autorizado!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro Shizuku: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
