package com.kvm.mouse

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class InputInjectorService : IInputInjector.Stub() {
    private val inputManager: InputManager by lazy {
        InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
    }
    private val injectMethod by lazy {
        InputManager::class.java.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var uhidOut: FileOutputStream? = null
    private var currentButtonsMask = 0

    @Volatile private var monitoringHardware = false
    private var hwCallback: IMouseEventCallback? = null
    private val activeReaders = mutableSetOf<String>()

    // Descritor HID padrao de Mouse 3 botoes + Rodinha (reconhecido nativamente pelo Android/One UI/Samsung DeX)
    private val mouseHidDescriptor = byteArrayOf(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x09, 0x01,       //   Usage (Pointer)
        0xA1.toByte(), 0x00, //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Button)
        0x19, 0x01,       //     Usage Minimum (Button 1)
        0x29, 0x03,       //     Usage Maximum (Button 3)
        0x15, 0x00,       //     Logical Minimum (0)
        0x25, 0x01,       //     Logical Maximum (1)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01, //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81.toByte(), 0x03, //     Input (Constant)
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)
        0xC0.toByte(),       //   End Collection
        0xC0.toByte()        // End Collection
    )

    @Synchronized
    override fun createVirtualMouse() {
        if (uhidOut != null) return
        try {
            val fos = FileOutputStream(File("/dev/uhid"))
            // struct uhid_event para UHID_CREATE2 (type = 11):
            // uint32 type (4) + name[128] + phys[64] + uniq[64] + uint16 rd_size (2) + uint16 bus (2)
            // + uint32 vendor (4) + uint32 product (4) + uint32 version (4) + uint32 country (4) + rd_data[4096]
            val totalSize = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

            buf.putInt(11) // UHID_CREATE2
            val nameBytes = "Samsung Bluetooth Mouse KVM".toByteArray(Charsets.UTF_8)
            buf.put(nameBytes)
            buf.position(4 + 128 + 64 + 64)

            buf.putShort(mouseHidDescriptor.size.toShort())
            buf.putShort(0x05) // BUS_BLUETOOTH (Android/Samsung trata como mouse Bluetooth nativo!)
            buf.putInt(0x04E8) // Samsung Vendor ID
            buf.putInt(0x7021) // Samsung Bluetooth Mouse Product ID
            buf.putInt(0x0100) // Version
            buf.putInt(0)      // Country
            buf.put(mouseHidDescriptor)

            fos.write(buf.array())
            fos.flush()
            uhidOut = fos
            currentButtonsMask = 0
        } catch (_: Exception) {
            uhidOut = null
        }
    }

    @Synchronized
    override fun destroyVirtualMouse() {
        val fos = uhidOut ?: return
        try {
            // UHID_DESTROY = 1
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()
            fos.write(buf)
            fos.flush()
            fos.close()
        } catch (_: Exception) {
        } finally {
            uhidOut = null
            currentButtonsMask = 0
        }
    }

    @Synchronized
    private fun writeHidReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val fos = uhidOut ?: return
        try {
            // Quebra movimentos maiores que 127px em multiplos pacotes HID de 8 bits com sinal
            var remX = dx
            var remY = dy
            var first = true
            while (first || remX != 0 || remY != 0) {
                first = false
                val stepX = remX.coerceIn(-127, 127)
                val stepY = remY.coerceIn(-127, 127)
                remX -= stepX
                remY -= stepY

                // struct uhid_event para UHID_INPUT2 (type = 12):
                // uint32 type (4) + uint16 size (2) + uint8 data[4096]
                val buf = ByteBuffer.allocate(4 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(12) // UHID_INPUT2
                buf.putShort(4) // 4 bytes de relatorio HID: [buttons, dx, dy, wheel]
                buf.put((buttons and 0x07).toByte())
                buf.put(stepX.toByte())
                buf.put(stepY.toByte())
                buf.put(wheel.coerceIn(-127, 127).toByte())
                fos.write(buf.array())
            }
            fos.flush()
        } catch (_: Exception) {
            uhidOut = null
        }
    }

    override fun sendVirtualMouseDelta(dx: Int, dy: Int) {
        if (uhidOut == null) createVirtualMouse()
        writeHidReport(currentButtonsMask, dx, dy, 0)
    }

    override fun sendVirtualMouseButton(buttonMask: Int) {
        if (uhidOut == null) createVirtualMouse()
        currentButtonsMask = buttonMask
        writeHidReport(currentButtonsMask, 0, 0, 0)
    }

    override fun sendVirtualMouseScroll(wheel: Int) {
        if (uhidOut == null) createVirtualMouse()
        writeHidReport(currentButtonsMask, 0, 0, wheel)
    }

    private val rescanRunnable = object : Runnable {
        override fun run() {
            if (monitoringHardware) {
                scanInputDevices()
                handler.postDelayed(this, 2500)
            }
        }
    }

    override fun setScreenPower(on: Boolean) {
        val now = SystemClock.uptimeMillis()
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApp = activityThreadClass.getMethod("currentApplication").invoke(null) as android.content.Context
            val pm = currentApp.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (on) {
                val wakeMethod = PowerManager::class.java.getMethod("wakeUp", Long::class.javaPrimitiveType)
                wakeMethod.invoke(pm, now)
            } else {
                val sleepMethod = PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
                sleepMethod.invoke(pm, now)
            }
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", if (on) "224" else "223"))
            } catch (_: Exception) {
            }
        }
    }

    override fun startHardwareMouseMonitor(callback: IMouseEventCallback?) {
        hwCallback = callback
        if (monitoringHardware) return
        monitoringHardware = true
        scanInputDevices()
        handler.postDelayed(rescanRunnable, 2500)
    }

    private fun scanInputDevices() {
        val inputDir = File("/dev/input")
        val files = inputDir.listFiles { f -> f.name.startsWith("event") } ?: return
        files.forEach { evFile ->
            synchronized(activeReaders) {
                if (!activeReaders.contains(evFile.name)) {
                    activeReaders.add(evFile.name)
                    thread(isDaemon = true, name = "Evdev_${evFile.name}") {
                        readEvdevLoop(evFile)
                        synchronized(activeReaders) {
                            activeReaders.remove(evFile.name)
                        }
                    }
                }
            }
        }
    }

    private fun readEvdevLoop(file: File) {
        try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(24)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                var accX = 0f
                var accY = 0f

                while (monitoringHardware) {
                    var offset = 0
                    while (offset < 24) {
                        val r = fis.read(buf, offset, 24 - offset)
                        if (r <= 0) return
                        offset += r
                    }
                    bb.rewind()
                    bb.long // tv_sec
                    bb.long // tv_usec
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int

                    when (type) {
                        0x02 -> { // EV_REL
                            when (code) {
                                0x00 -> accX += value.toFloat()
                                0x01 -> accY += value.toFloat()
                                0x08 -> {
                                    try { hwCallback?.onMouseScroll(value.toFloat()) } catch (_: Exception) {}
                                }
                            }
                        }
                        0x01 -> { // EV_KEY
                            val action = when (value) {
                                1 -> MotionEvent.ACTION_DOWN
                                0 -> MotionEvent.ACTION_UP
                                else -> -1
                            }
                            if (action != -1) {
                                val btn = when (code) {
                                    0x110 -> MotionEvent.BUTTON_PRIMARY
                                    0x111 -> MotionEvent.BUTTON_SECONDARY
                                    0x112 -> MotionEvent.BUTTON_TERTIARY
                                    else -> 0
                                }
                                if (btn != 0) {
                                    try { hwCallback?.onMouseButton(action, btn) } catch (_: Exception) {}
                                }
                            }
                        }
                        0x00 -> { // EV_SYN
                            if (accX != 0f || accY != 0f) {
                                try { hwCallback?.onMouseDelta(accX, accY) } catch (_: Exception) {}
                                accX = 0f
                                accY = 0f
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun stopHardwareMouseMonitor() {
        monitoringHardware = false
        handler.removeCallbacks(rescanRunnable)
        hwCallback = null
    }

    override fun injectKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        try {
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            val up = KeyEvent(now, now + 15, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            injectMethod.invoke(inputManager, down, 0)
            injectMethod.invoke(inputManager, up, 0)
        } catch (_: Exception) {
        }
    }

    override fun destroy() {
        monitoringHardware = false
        destroyVirtualMouse()
        handler.removeCallbacksAndMessages(null)
        exitProcess(0)
    }
}
