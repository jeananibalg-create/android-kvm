package com.kvm.mouse;

import com.kvm.mouse.IMouseEventCallback;

interface IInputInjector {
    void createVirtualMouse();
    void destroyVirtualMouse();
    boolean isVirtualMouseActive();
    void sendVirtualMouseDelta(int dx, int dy);
    void sendVirtualMouseButton(int buttonMask);
    void sendVirtualMouseScroll(int wheel);
    void injectKey(int keyCode);
    void applyMultiControlSystemPolicy(boolean enableLockscreenMode);
    void startHardwareMouseMonitor(IMouseEventCallback callback);
    void stopHardwareMouseMonitor();
    void turnOffScreen();
    void wakeUpScreen();
    void destroy();
}
