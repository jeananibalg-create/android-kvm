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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var isGrabbed = false
    private var hwCallback: IMouseEventCallback? = null
    private val openNativeFds = ConcurrentHashMap<String, Int>()

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

    private fun hasKernelBit(hexWords: String, bit: Int): Boolean {
        val parts = hexWords.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val wordBits = 64
        val wordIndexFromEnd = bit / wordBits
        val bitInWord = bit % wordBits
        val idx = parts.size - 1 - wordIndexFromEnd
        if (idx < 0 || idx >= parts.size) return false
        val wordVal = java.lang.Long.parseUnsignedLong(parts[idx], 16)
        return ((wordVal ushr bitInWord) and 1L) == 1L
    }

    // Identifica qualquer mouse USB/Bluetooth real: precisa ter REL_X (0), REL_Y (1) e BTN_LEFT (0x110 = 272)
    private fun isPhysicalMouseDevice(eventName: String): Boolean {
        try {
            val sysBase = File("/sys/class/input/$eventName/device")
            val nameFile = File(sysBase, "name")
            val devName = if (nameFile.exists()) nameFile.readText().trim().lowercase() else ""
            if (devName.contains("kvm") || devName.contains("multi control") || devName.contains("touchscreen")) {
                return false
            }

            val relFile = File(sysBase, "capabilities/rel")
            val keyFile = File(sysBase, "capabilities/key")
            if (!relFile.exists() || !keyFile.exists()) return false

            val relHex = relFile.readText().trim()
            val keyHex = keyFile.readText().trim()

            val hasRelX = hasKernelBit(relHex, 0x00)
            val hasRelY = hasKernelBit(relHex, 0x01)
            val hasBtnLeft = hasKernelBit(keyHex, 0x110)

            return hasRelX && hasRelY && hasBtnLeft
        } catch (_: Exception) {
            return false
        }
    }

    override fun setKernelMouseGrab(grab: Boolean) {
        isGrabbed = grab
        openNativeFds.values.forEach { rawFd ->
            try {
                NativeEvdev.grabDevice(rawFd, grab)
            } catch (_: Exception) {
            }
        }
    }

    override fun applyMultiControlSystemPolicy(enableLockscreenMode: Boolean) {
        try {
            Runtime.getRuntime().exec(arrayOf("dumpsys", "deviceidle", "whitelist", "+com.kvm.mouse"))
            Runtime.getRuntime().exec(arrayOf("cmd", "appops", "set", "com.kvm.mouse", "SYSTEM_ALERT_WINDOW", "allow"))
            Runtime.getRuntime().exec(arrayOf("cmd", "appops", "set", "com.kvm.mouse", "RUN_IN_BACKGROUND", "allow"))
            Runtime.getRuntime().exec(arrayOf("cmd", "appops", "set", "com.kvm.mouse", "RUN_ANY_IN_BACKGROUND", "allow"))
            if (enableLockscreenMode) {
                Runtime.getRuntime().exec(arrayOf("settings", "put", "secure", "wake_gesture_enabled", "0"))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    override fun createVirtualMouse() {
        if (uhidOut != null) return
        try {
            val fos = FileOutputStream(File("/dev/uhid"))
            val totalSize = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

            buf.putInt(11) // UHID_CREATE2
            val nameBytes = "Samsung Multi Control Mouse".toByteArray(Charsets.UTF_8)
            buf.put(nameBytes)
            buf.position(4 + 128 + 64 + 64)

            buf.putShort(mouseHidDescriptor.size.toShort())
            buf.putShort(0x05) // BUS_BLUETOOTH
            buf.putInt(0x04E8)
            buf.putInt(0x7021)
            buf.putInt(0x0100)
            buf.putInt(0)
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
    override fun isVirtualMouseActive(): Boolean = uhidOut != null

    @Synchronized
    private fun writeHidReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val fos = uhidOut ?: return
        try {
            var remX = dx
            var remY = dy
            var first = true
            while (first || remX != 0 || remY != 0) {
                first = false
                val stepX = remX.coerceIn(-127, 127)
                val stepY = remY.coerceIn(-127, 127)
                remX -= stepX
                remY -= stepY

                val buf = ByteBuffer.allocate(4 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(12) // UHID_INPUT2
                buf.putShort(4)
                buf.put((buttons and 0x07).toByte())
                buf.put(stepX.toByte())
                buf.put(stepY.toByte())
                buf.put(wheel.coerceIn(-127, 127).toByte())
                fos.write(buf.array())
            }
            fos.flush()
        } catch (_: Exception) {
            try { fos.close() } catch (_: Exception) {}
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
                scanMouseDevicesOnly()
                handler.postDelayed(this, 2000)
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
        }
    }

    override fun startHardwareMouseMonitor(callback: IMouseEventCallback?) {
        hwCallback = callback
        if (monitoringHardware) return
        monitoringHardware = true
        scanMouseDevicesOnly()
        handler.postDelayed(rescanRunnable, 2000)
    }

    private fun scanMouseDevicesOnly() {
        val inputDir = File("/dev/input")
        val files = inputDir.listFiles { f -> f.name.startsWith("event") } ?: return
        files.forEach { evFile ->
            if (!openNativeFds.containsKey(evFile.name) && isPhysicalMouseDevice(evFile.name)) {
                thread(isDaemon = true, name = "NativeMouse_${evFile.name}") {
                    readMouseLoop(evFile)
                }
            }
        }
    }

    private fun readMouseLoop(file: File) {
        val rawFd = NativeEvdev.openDevice(file.absolutePath)
        if (rawFd < 0) return
        openNativeFds[file.name] = rawFd
        if (isGrabbed) {
            NativeEvdev.grabDevice(rawFd, true)
        }

        val evData = LongArray(3)
        var accX = 0f
        var accY = 0f

        try {
            while (monitoringHardware) {
                val res = NativeEvdev.readEvent(rawFd, evData)
                if (res <= 0) break

                val type = evData[0].toInt()
                val code = evData[1].toInt()
                val value = evData[2].toInt()

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
        } catch (_: Exception) {
        } finally {
            openNativeFds.remove(file.name)
            try { NativeEvdev.closeDevice(rawFd) } catch (_: Exception) {}
        }
    }

    override fun stopHardwareMouseMonitor() {
        monitoringHardware = false
        isGrabbed = false
        handler.removeCallbacks(rescanRunnable)
        openNativeFds.values.forEach { rawFd ->
            try { NativeEvdev.closeDevice(rawFd) } catch (_: Exception) {}
        }
        openNativeFds.clear()
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
        stopHardwareMouseMonitor()
        destroyVirtualMouse()
        handler.removeCallbacksAndMessages(null)
        exitProcess(0)
    }
}
