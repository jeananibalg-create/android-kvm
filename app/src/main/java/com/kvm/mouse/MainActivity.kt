package com.kvm.mouse

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
        KvmState.loadConfig(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 56, 48, 56)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "Android KVM v5.1 (Multi Control)"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "IP local: ${KvmService.getLocalIpAddress()}"
            textSize = 14f
            setTextColor(Color.GREEN)
            setPadding(0, 10, 0, 20)
        }
        root.addView(statusText)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnMaster = Button(this).apply { text = "MESTRE (Mouse aqui)" }
        val btnSlave = Button(this).apply { text = "ESCRAVO (Mouse BT Nativo)" }
        modeRow.addView(btnMaster, LinearLayout.LayoutParams(0, -2, 1f))
        modeRow.addView(btnSlave, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(modeRow)

        val screenOffSwitch = Switch(this).apply {
            text = "Apagar tela do Mestre ao controlar Escravo"
            setTextColor(Color.YELLOW)
            isChecked = KvmState.turnOffMasterScreenOnRemote
            setPadding(0, 24, 0, 8)
            setOnCheckedChangeListener { _, isChecked ->
                KvmState.turnOffMasterScreenOnRemote = isChecked
                KvmState.saveConfig(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (isChecked) "Tela do Mestre apagará ao ir para o Escravo" else "Tela do Mestre permanecerá acesa",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(screenOffSwitch)

        val lockSwitch = Switch(this).apply {
            text = "Funcionar com Mestre bloqueado/apagado"
            setTextColor(Color.CYAN)
            isChecked = KvmState.allowLockscreenControl
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, isChecked ->
                KvmState.allowLockscreenControl = isChecked
                KvmState.saveConfig(this@MainActivity)
                if (KvmState.isRunning) {
                    startForegroundService(Intent(this@MainActivity, KvmService::class.java))
                }
            }
        }
        root.addView(lockSwitch)

        val edgesLabel = TextView(this).apply {
            text = "\nPosição dos celulares escravos (Modo Mestre):"
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 8)
        }
        root.addView(edgesLabel)

        Edge.values().forEach { edge ->
            val savedPeer = KvmState.edgeAssignments[edge]
            val btn = Button(this).apply {
                text = "${edge.label}: ${savedPeer?.name ?: "Nenhum"}"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { showPeerSelector(edge) }
            }
            edgeButtons[edge] = btn
            root.addView(btn)
        }

        val speedLabel = TextView(this).apply {
            text = "\nVelocidade da seta no Escravo: ${String.format("%.2f", KvmState.pointerSpeed)}x"
            setTextColor(Color.LTGRAY)
        }
        root.addView(speedLabel)

        val speedBar = SeekBar(this).apply {
            max = 250
            progress = ((KvmState.pointerSpeed - 0.5f) * 100f).toInt().coerceIn(0, 250)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val speed = 0.5f + (progress / 100f)
                    KvmState.pointerSpeed = speed
                    KvmState.saveConfig(this@MainActivity)
                    speedLabel.text = "Velocidade da seta no Escravo: ${String.format("%.2f", speed)}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(speedBar)

        val btnShizuku = Button(this).apply {
            text = "1. Autorizar Shizuku (Obrigatório em ambos)"
            setOnClickListener { requestShizukuPerm() }
        }
        root.addView(btnShizuku)

        val btnOverlay = Button(this).apply {
            text = "2. Permitir Sobrepor Apps (Mestre)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Permissão já concedida!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnOverlay)

        val btnBattery = Button(this).apply {
            text = "3. Manter em 2º Plano (Sem restrição bateria)"
            setOnClickListener {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Já está sem restrição de bateria!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnBattery)

        val btnToggleService = Button(this).apply {
            text = if (KvmState.isRunning) "PARAR SERVIÇO KVM" else "INICIAR SERVIÇO KVM"
            setBackgroundColor(Color.parseColor(if (KvmState.isRunning) "#B71C1C" else "#1B5E20"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Ative 'Sobrepor Apps' primeiro!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!KvmState.isRunning) {
                    KvmState.isRunning = true
                    KvmState.saveConfig(this@MainActivity)
                    startForegroundService(Intent(this@MainActivity, KvmService::class.java))
                    text = "PARAR SERVIÇO KVM"
                    setBackgroundColor(Color.parseColor("#B71C1C"))
                    statusText.text = if (KvmState.isMasterMode)
                        "MESTRE ATIVO (IP: ${KvmService.getLocalIpAddress()})"
                    else
                        "ESCRAVO HID ATIVO - IP: ${KvmService.getLocalIpAddress()}"
                } else {
                    KvmState.isRunning = false
                    KvmState.saveConfig(this@MainActivity)
                    stopService(Intent(this@MainActivity, KvmService::class.java))
                    text = "INICIAR SERVIÇO KVM"
                    setBackgroundColor(Color.parseColor("#1B5E20"))
                    statusText.text = "IP local: ${KvmService.getLocalIpAddress()}"
                }
            }
        }
        root.addView(btnToggleService)

        btnMaster.setOnClickListener {
            KvmState.isMasterMode = true
            KvmState.saveConfig(this)
            statusText.text = "Modo: MESTRE | IP: ${KvmService.getLocalIpAddress()}"
        }
        btnSlave.setOnClickListener {
            KvmState.isMasterMode = false
            KvmState.saveConfig(this)
            statusText.text = "Modo: ESCRAVO | IP: ${KvmService.getLocalIpAddress()}"
        }

        setContentView(scroll)
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
                        KvmState.saveConfig(this)
                        edgeButtons[edge]?.text = "${edge.label}: Nenhum"
                    }
                    1 -> showManualIpDialog(edge)
                    else -> {
                        val chosen = peers[which - 2]
                        KvmState.edgeAssignments[edge] = chosen
                        KvmState.saveConfig(this)
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
                            KvmState.saveConfig(this)
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
