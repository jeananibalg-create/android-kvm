package com.kvm.mouse

import android.hardware.input.InputManager
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

    private var downTime = 0L
    private var isPressed = false

    override fun injectMove(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (isPressed) {
            dispatchMotion(downTime, now, MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, 0f, 0f)
        } else {
            dispatchMotion(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0f, 0f)
        }
    }

    override fun injectButton(action: Int, x: Float, y: Float, buttonState: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
            isPressed = true
            dispatchMotion(downTime, now, MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, 0f, 0f)
            dispatchMotion(downTime, now, MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY, 0f, 0f)
        } else if (action == MotionEvent.ACTION_UP) {
            val dTime = if (downTime > 0L) downTime else now
            dispatchMotion(dTime, now, MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, 0f, 0f)
            dispatchMotion(dTime, now, MotionEvent.ACTION_UP, x, y, 0, 0f, 0f)
            isPressed = false
            downTime = 0L
        }
    }

    override fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float) {
        val now = SystemClock.uptimeMillis()
        dispatchMotion(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0f, 0f)
        dispatchMotion(now, now, MotionEvent.ACTION_SCROLL, x, y, 0, vScroll * 2.2f, hScroll * 2.2f)
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

    private fun dispatchMotion(
        dTime: Long,
        eTime: Long,
        action: Int,
        x: Float,
        y: Float,
        btnState: Int,
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
                pressure = if (isPressed) 1.0f else 0.0f
                size = 1.0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            })
            val event = MotionEvent.obtain(
                dTime, eTime, action, 1, props, coords,
                0, btnState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectMethod.invoke(inputManager, event, 0)
            event.recycle()
        } catch (_: Exception) {
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
