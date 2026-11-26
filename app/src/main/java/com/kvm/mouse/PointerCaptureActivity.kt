package com.kvm.mouse

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class PointerCaptureActivity : Activity() {
    private lateinit var captureView: View
    private val handler = Handler(Looper.getMainLooper())
    private var captureRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KvmState.captureActivity = this

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setDimAmount(0f)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(1, 0, 0, 0))
        }

        val hint = TextView(this).apply {
            text = "Mouse no Escravo (Voltar = resgatar cursor)"
            textSize = 11f
            setTextColor(Color.argb(140, 255, 255, 255))
            setPadding(24, 48, 24, 16)
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

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
        scheduleCaptureAttempt()
    }

    private fun scheduleCaptureAttempt() {
        handler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isFinishing) {
                captureView.requestFocus()
                captureView.requestPointerCapture()
                if (!captureView.hasPointerCapture() && captureRetryCount < 15) {
                    captureRetryCount++
                    scheduleCaptureAttempt()
                }
            }
        }, 40)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureView.requestFocus()
            captureView.requestPointerCapture()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
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
                if (event.historySize > 0) {
                    for (i in 0 until event.historySize) {
                        dx += event.getHistoricalX(i)
                        dy += event.getHistoricalY(i)
                    }
                }
                dx += event.x
                dy += event.y
                if (dx != 0f || dy != 0f) {
                    KvmService.sendUdp(peer, "MOVE:$dx:$dy")
                }
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                KvmService.sendUdp(peer, "BTN:${MotionEvent.ACTION_DOWN}:${event.buttonState}")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                KvmService.sendUdp(peer, "BTN:${MotionEvent.ACTION_UP}:0")
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                KvmService.sendUdp(peer, "SCR:$v:$h")
            }
        }
    }

    fun releaseAndClose() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { captureView.releasePointerCapture() } catch (_: Exception) {}
            }
            KvmState.captureActivity = null
            KvmState.activePeer = null
            KvmState.activeEdge = null
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        KvmState.captureActivity = null
        super.onDestroy()
    }
}
