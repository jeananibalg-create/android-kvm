package com.kvm.mouse

object NativeEvdev {
    init {
        System.loadLibrary("kvm_native")
    }

    external fun openDevice(path: String): Int
    external fun grabDevice(fd: Int, grab: Boolean): Int
    external fun readEvent(fd: Int, outArr: LongArray): Int
    external fun closeDevice(fd: Int)
}
