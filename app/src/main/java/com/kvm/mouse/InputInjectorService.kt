package com.kvm.mouse

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
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

    override fun injectMove(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val action = if (downTime > 0L) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
        val dTime = if (downTime > 0L) downTime else now
        dispatch(dTime, now, action, x, y, if (downTime > 0L) MotionEvent.BUTTON_PRIMARY else 0, 0f, 0f)
    }

    override fun injectButton(action: Int, x: Float, y: Float, buttonState: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
            dispatch(downTime, now, MotionEvent.ACTION_DOWN, x, y, buttonState, 0f, 0f)
            dispatch(downTime, now, MotionEvent.ACTION_BUTTON_PRESS, x, y, buttonState, 0f, 0f)
        } else if (action == MotionEvent.ACTION_UP) {
            val dTime = if (downTime > 0L) downTime else now
            dispatch(dTime, now, MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, 0f, 0f)
            dispatch(dTime, now, MotionEvent.ACTION_UP, x, y, 0, 0f, 0f)
            downTime = 0L
        }
    }

    override fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float) {
        val now = SystemClock.uptimeMillis()
        dispatch(now, now, MotionEvent.ACTION_SCROLL, x, y, 0, vScroll, hScroll)
    }

    private fun dispatch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
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
                pressure = if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) 1.0f else 0.0f
                size = 1.0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            })
            val event = MotionEvent.obtain(
                downTime, eventTime, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0
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
