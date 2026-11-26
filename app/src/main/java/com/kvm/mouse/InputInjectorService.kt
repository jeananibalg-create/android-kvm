package com.kvm.mouse

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.FileInputStream
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
    private var downTime = 0L
    private var isLeftPressed = false
    private var isRightPressed = false
    private var rightLongPressTriggered = false
    private var lastX = 0f
    private var lastY = 0f

    @Volatile private var monitoringHardware = false
    private var hwCallback: IMouseEventCallback? = null
    private val activeReaders = mutableSetOf<String>()

    private val rightLongPressRunnable = Runnable {
        if (isRightPressed) {
            rightLongPressTriggered = true
            val now = SystemClock.uptimeMillis()
            downTime = now
            dispatchTouch(downTime, now, MotionEvent.ACTION_DOWN, lastX, lastY, 1.0f)
        }
    }

    private val rescanRunnable = object : Runnable {
        override fun run() {
            if (monitoringHardware) {
                scanInputDevices()
                handler.postDelayed(this, 4000)
            }
        }
    }

    override fun startHardwareMouseMonitor(callback: IMouseEventCallback?) {
        hwCallback = callback
        if (monitoringHardware) return
        monitoringHardware = true
        scanInputDevices()
        handler.postDelayed(rescanRunnable, 4000)
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
                    val read = fis.read(buf)
                    if (read < 24) break
                    bb.rewind()
                    bb.long // tv_sec
                    bb.long // tv_usec
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int

                    when (type) {
                        0x02 -> { // EV_REL
                            when (code) {
                                0x00 -> accX += value.toFloat() // REL_X
                                0x01 -> accY += value.toFloat() // REL_Y
                                0x08 -> {
                                    try { hwCallback?.onMouseScroll(value.toFloat()) } catch (_: Exception) {}
                                }
                            }
                        }
                        0x01 -> { // EV_KEY
                            val action = if (value == 1) MotionEvent.ACTION_DOWN else if (value == 0) MotionEvent.ACTION_UP else -1
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

    override fun injectMove(x: Float, y: Float) {
        lastX = x
        lastY = y
        val now = SystemClock.uptimeMillis()
        when {
            isLeftPressed -> dispatchTouch(downTime, now, MotionEvent.ACTION_MOVE, x, y, 1.0f)
            rightLongPressTriggered -> dispatchTouch(downTime, now, MotionEvent.ACTION_MOVE, x, y, 1.0f)
            else -> dispatchMouseHover(now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0f, 0f)
        }
    }

    override fun injectButton(action: Int, x: Float, y: Float, buttonState: Int) {
        lastX = x
        lastY = y
        val now = SystemClock.uptimeMillis()

        if (buttonState == MotionEvent.BUTTON_SECONDARY) {
            if (action == MotionEvent.ACTION_DOWN) {
                isRightPressed = true
                rightLongPressTriggered = false
                handler.removeCallbacks(rightLongPressRunnable)
                handler.postDelayed(rightLongPressRunnable, 320)
            } else if (action == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(rightLongPressRunnable)
                if (rightLongPressTriggered) {
                    val dTime = if (downTime > 0L) downTime else now
                    dispatchTouch(dTime, now, MotionEvent.ACTION_UP, x, y, 0.0f)
                    downTime = 0L
                } else if (isRightPressed) {
                    injectKey(KeyEvent.KEYCODE_BACK)
                }
                isRightPressed = false
                rightLongPressTriggered = false
            }
            return
        }

        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
            isLeftPressed = true
            dispatchTouch(downTime, now, MotionEvent.ACTION_DOWN, x, y, 1.0f)
        } else if (action == MotionEvent.ACTION_UP) {
            val dTime = if (downTime > 0L) downTime else now
            dispatchTouch(dTime, now, MotionEvent.ACTION_UP, x, y, 0.0f)
            isLeftPressed = false
            downTime = 0L
        }
    }

    override fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float) {
        lastX = x
        lastY = y
        val now = SystemClock.uptimeMillis()
        dispatchMouseHover(now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0f, 0f)
        dispatchMouseHover(now, MotionEvent.ACTION_SCROLL, x, y, vScroll * 2.5f, hScroll * 2.5f)
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

    private fun dispatchTouch(
        dTime: Long,
        eTime: Long,
        action: Int,
        x: Float,
        y: Float,
        pressureVal: Float
    ) {
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = pressureVal
                size = 0.5f
            })
            val event = MotionEvent.obtain(
                dTime, eTime, action, 1, props, coords,
                0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            injectMethod.invoke(inputManager, event, 0)
            event.recycle()
        } catch (_: Exception) {
        }
    }

    private fun dispatchMouseHover(
        eTime: Long,
        action: Int,
        x: Float,
        y: Float,
        vScroll: Float,
        hScroll: Float
    ) {
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 0.0f
                size = 1.0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            })
            val event = MotionEvent.obtain(
                eTime, eTime, action, 1, props, coords,
                0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectMethod.invoke(inputManager, event, 0)
            event.recycle()
        } catch (_: Exception) {
        }
    }

    override fun destroy() {
        monitoringHardware = false
        handler.removeCallbacksAndMessages(null)
        exitProcess(0)
    }
}
