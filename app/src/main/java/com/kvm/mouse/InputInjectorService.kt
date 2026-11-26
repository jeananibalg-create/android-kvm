package com.kvm.mouse

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
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

    private val rightLongPressRunnable = Runnable {
        if (isRightPressed) {
            rightLongPressTriggered = true
            val now = SystemClock.uptimeMillis()
            downTime = now
            // Injeta toque longo nativo na coordenada exata para abrir menu/opções do atalho na One UI
            dispatchTouch(downTime, now, MotionEvent.ACTION_DOWN, lastX, lastY, 1.0f)
        }
    }

    override fun injectMove(x: Float, y: Float) {
        lastX = x
        lastY = y
        val now = SystemClock.uptimeMillis()
        when {
            isLeftPressed -> {
                dispatchTouch(downTime, now, MotionEvent.ACTION_MOVE, x, y, 1.0f)
            }
            rightLongPressTriggered -> {
                dispatchTouch(downTime, now, MotionEvent.ACTION_MOVE, x, y, 1.0f)
            }
            else -> {
                dispatchMouseHover(now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0f, 0f)
            }
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
                // Se segurar o botão direito por 320ms, abre o menu do atalho/ícone
                handler.postDelayed(rightLongPressRunnable, 320)
            } else if (action == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(rightLongPressRunnable)
                if (rightLongPressTriggered) {
                    // Solta o toque longo do menu de contexto
                    val dTime = if (downTime > 0L) downTime else now
                    dispatchTouch(dTime, now, MotionEvent.ACTION_UP, x, y, 0.0f)
                    downTime = 0L
                } else if (isRightPressed) {
                    // Clique rápido com o botão direito = Voltar
                    injectKey(KeyEvent.KEYCODE_BACK)
                }
                isRightPressed = false
                rightLongPressTriggered = false
            }
            return
        }

        // Botão Esquerdo: emula toque capacitivo direto para abrir apps, links e gestos sem falhar na One UI
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
        handler.removeCallbacksAndMessages(null)
        exitProcess(0)
    }
}
