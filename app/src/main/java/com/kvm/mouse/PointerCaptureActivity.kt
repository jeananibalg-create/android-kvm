package com.kvm.mouse

import android.app.Activity
import android.os.Bundle

// Mantida apenas por compatibilidade de manifest; na v6.0 o EVIOCGRAB nativo C substitui a janela transparente sem travar a tela
class PointerCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
