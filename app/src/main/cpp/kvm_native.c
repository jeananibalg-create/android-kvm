#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

JNIEXPORT jint JNICALL
Java_com_kvm_mouse_NativeEvdev_openDevice(JNIEnv *env, jobject thiz, jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, 0);
    int fd = open(cpath, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_kvm_mouse_NativeEvdev_grabDevice(JNIEnv *env, jobject thiz, jint fd, jboolean grab) {
    if (fd < 0) return -1;
    unsigned long req = EVIOCGRAB;
    int arg = grab ? 1 : 0;
    return ioctl(fd, req, arg);
}

JNIEXPORT jint JNICALL
Java_com_kvm_mouse_NativeEvdev_readEvent(JNIEnv *env, jobject thiz, jint fd, jlongArray outArr) {
    if (fd < 0) return -1;
    struct input_event ev;
    ssize_t n = read(fd, &ev, sizeof(struct input_event));
    if (n != sizeof(struct input_event)) {
        return -1;
    }
    jlong buf[3];
    buf[0] = (jlong)ev.type;
    buf[1] = (jlong)ev.code;
    buf[2] = (jlong)ev.value;
    (*env)->SetLongArrayRegion(env, outArr, 0, 3, buf);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_kvm_mouse_NativeEvdev_closeDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) {
        unsigned long req = EVIOCGRAB;
        ioctl(fd, req, 0);
        close(fd);
    }
}
