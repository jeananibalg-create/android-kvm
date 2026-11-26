package com.kvm.mouse

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

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
            text = "Android KVM (Shizuku)"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "Selecione o modo deste aparelho:"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 32)
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
            setPadding(0, 32, 0, 16)
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
                    statusText.text = if (KvmState.isMasterMode) "MESTRE ATIVO - Encoste o mouse na borda!" else "ESCRAVO ATIVO - Visível no Wi-Fi!"
                } else {
                    stopService(Intent(this@MainActivity, KvmService::class.java))
                    KvmState.isRunning = false
                    text = "INICIAR SERVIÇO KVM"
                    setBackgroundColor(Color.parseColor("#1B5E20"))
                    statusText.text = "Serviço parado."
                }
            }
        }
        root.addView(btnToggleService)

        btnMaster.setOnClickListener {
            KvmState.isMasterMode = true
            statusText.text = "Modo selecionado: MESTRE"
        }
        btnSlave.setOnClickListener {
            KvmState.isMasterMode = false
            statusText.text = "Modo selecionado: ESCRAVO"
        }

        setContentView(root)
    }

    private fun showPeerSelector(edge: Edge) {
        val peers = KvmState.discoveredPeers.values.toList()
        if (peers.isEmpty()) {
            Toast.makeText(this, "Inicie o serviço no Mestre e no Escravo no mesmo Wi-Fi primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val names = (listOf("Nenhum") + peers.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Celular na borda: ${edge.label}")
            .setItems(names) { _, which ->
                if (which == 0) {
                    KvmState.edgeAssignments[edge] = null
                    edgeButtons[edge]?.text = "${edge.label}: Nenhum"
                } else {
                    val chosen = peers[which - 1]
                    KvmState.edgeAssignments[edge] = chosen
                    edgeButtons[edge]?.text = "${edge.label}: ${chosen.name}"
                }
            }
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
