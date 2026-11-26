package com.kvm.mouse

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View

class PointerCaptureActivity : Activity() {
    private lateinit var captureView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KvmState.captureActivity = this

        captureView = object : View(this) {
            override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
                handleCapturedEvent(event)
                return true
            }
        }
        captureView.isFocusable = true
        captureView.isFocusableInTouchMode = true
        setContentView(captureView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureView.requestPointerCapture()
        }
    }

    private fun handleCapturedEvent(event: MotionEvent) {
        val peer = KvmState.activePeer ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = event.x
                val dy = event.y
                KvmService.sendUdp(peer, "MOVE:$dx:$dy")
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
                captureView.releasePointerCapture()
            }
            KvmState.captureActivity = null
            KvmState.activePeer = null
            KvmState.activeEdge = null
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        KvmState.captureActivity = null
        super.onDestroy()
    }
}
