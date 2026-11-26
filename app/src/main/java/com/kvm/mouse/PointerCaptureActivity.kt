package com.kvm.mouse

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class PointerCaptureActivity : Activity() {
    private lateinit var captureView: View
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var lastButtonState = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KvmState.captureActivity = this
        KvmState.isRemoteActive = true

        setShowWhenLocked(true)

        // Sem FLAG_KEEP_SCREEN_ON para a tela apagar normalmente
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDimAmount(0f)

        // Totalmente transparente e limpo, sem textos no topo
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        captureView = object : View(this) {
            override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
                handleCapturedEvent(event)
                return true
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        root.addView(captureView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        captureView.requestFocus()
        ensurePointerCapture()
    }

    private fun ensurePointerCapture() {
        handler.postDelayed({
            if (!isFinishing) {
                captureView.requestFocus()
                captureView.requestPointerCapture()
                if (!captureView.hasPointerCapture() && retryCount < 20) {
                    retryCount++
                    ensurePointerCapture()
                }
            }
        }, 16)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            captureView.requestFocus()
            captureView.requestPointerCapture()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            releaseAndClose()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleCapturedEvent(event: MotionEvent) {
        val peer = KvmState.activePeer ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = 0f
                var dy = 0f
                for (i in 0 until event.historySize) {
                    dx += event.getHistoricalX(i)
                    dy += event.getHistoricalY(i)
                }
                dx += event.x
                dy += event.y
                if (dx != 0f || dy != 0f) {
                    UdpFastSender.sendMove(peer, dx * KvmState.pointerSpeed, dy * KvmState.pointerSpeed)
                }
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                val pressed = event.buttonState and lastButtonState.inv()
                lastButtonState = event.buttonState
                when {
                    (pressed and MotionEvent.BUTTON_SECONDARY) != 0 -> {
                        UdpFastSender.sendButton(peer, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY)
                    }
                    (pressed and MotionEvent.BUTTON_TERTIARY) != 0 -> {
                        UdpFastSender.sendKey(peer, KeyEvent.KEYCODE_HOME)
                    }
                    (pressed and MotionEvent.BUTTON_PRIMARY) != 0 || event.buttonState == 0 -> {
                        UdpFastSender.sendButton(peer, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val released = lastButtonState and event.buttonState.inv()
                lastButtonState = event.buttonState
                if ((released and MotionEvent.BUTTON_SECONDARY) != 0) {
                    UdpFastSender.sendButton(peer, MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY)
                } else {
                    UdpFastSender.sendButton(peer, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY)
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v != 0f || h != 0f) {
                    UdpFastSender.sendScroll(peer, v, h)
                }
            }
        }
    }

    fun releaseAndClose() {
        runOnUiThread {
            try { captureView.releasePointerCapture() } catch (_: Exception) {}
            KvmState.captureActivity = null
            KvmState.isRemoteActive = false
            KvmState.activePeer = null
            KvmState.activeEdge = null
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        KvmState.captureActivity = null
        KvmState.isRemoteActive = false
        super.onDestroy()
    }
}
