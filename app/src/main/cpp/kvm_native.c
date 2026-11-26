#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

JNIEXPORT jint JNICALL
Java_com_kvm_mouse_NativeEvdev_openDevice(JNIEnv *env, jobject thiz, jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, 0);
    int fd = open(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_kvm_mouse_NativeEvdev_grabDevice(JNIEnv *env, jobject thiz, jint fd, jboolean grab) {
    if (fd < 0) return -1;
    int arg = grab ? 1 : 0;
    return ioctl(fd, EVIOCGRAB, arg);
}

JNIEXPORT void JNICALL
Java_com_kvm_mouse_NativeEvdev_closeDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) {
        ioctl(fd, EVIOCGRAB, 0);
        close(fd);
    }
}
