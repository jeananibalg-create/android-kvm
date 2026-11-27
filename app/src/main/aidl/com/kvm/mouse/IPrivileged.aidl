package com.kvm.mouse;

interface IPrivileged {
    int startRemote(String host, int port);
    oneway void stopRemote();
    oneway void wakeScreen();
    oneway void sleepScreen();

    boolean mouseCreate();
    oneway void mouseReport(int buttons, int dx, int dy, int wheel, int pan);
    oneway void mousePlace(int slamX, int slamY, int moveX, int moveY);
    void mouseDestroy();

    void destroy();
}
