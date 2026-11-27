package com.kvm.mouse;

interface IMouseEventCallback {
    void onMouseDelta(float dx, float dy);
    void onMouseButton(int action, int button);
    void onMouseScroll(float vScroll);
}
