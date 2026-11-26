package com.kvm.mouse;

interface IInputInjector {
    void injectMove(float x, float y);
    void injectButton(int action, float x, float y, int buttonState);
    void injectScroll(float x, float y, float vScroll, float hScroll);
    void destroy();
}
