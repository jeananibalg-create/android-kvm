package com.kvm.mouse;

// Serviço privilegiado executado no processo do Shizuku (uid shell).
interface IPrivileged {
    // ---- Mestre ----
    // Prende o mouse físico no kernel (EVIOCGRAB) e envia pacotes UDP de 8 bytes direto ao escravo.
    // Retorno: 2 = captura exclusiva ativa, 1 = repasse sem captura exclusiva, 0 = falha.
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
