package com.kvm.mouse;

// Serviço que roda no processo do Shizuku (uid shell).
interface IPrivileged {
    // ---- Mestre ----
    // Prende o mouse no kernel (EVIOCGRAB) e repassa os eventos por UDP direto ao escravo.
    // Retorna 2 = repassando com captura exclusiva, 1 = repassando SEM captura, 0 = falhou.
    int startRemote(String host, int port) = 1;
    oneway void stopRemote() = 2;
    oneway void wakeScreen() = 3;
    oneway void sleepScreen() = 4;

    // ---- Escravo (mouse Bluetooth virtual via /dev/uhid) ----
    boolean mouseCreate() = 5;
    oneway void mouseReport(int buttons, int dx, int dy, int wheel, int pan) = 6;
    oneway void mousePlace(int slamX, int slamY, int moveX, int moveY) = 7;
    void mouseDestroy() = 8;

    void destroy() = 16777114;
}
