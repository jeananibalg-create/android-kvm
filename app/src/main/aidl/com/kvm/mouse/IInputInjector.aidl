package com.kvm.mouse;

import com.kvm.mouse.IMouseEventCallback;

interface IInputInjector {
    void injectMove(float x, float y);
    void injectButton(int action, float x, float y, int buttonState);
    void injectScroll(float x, float y, float vScroll, float hScroll);
    void injectKey(int keyCode);
    void startHardwareMouseMonitor(IMouseEventCallback callback);
    void stopHardwareMouseMonitor();
    void destroy();
}
