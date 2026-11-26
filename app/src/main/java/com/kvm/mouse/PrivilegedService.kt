package com.kvm.mouse

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.File
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

// ---- constantes do kernel (linux/input-event-codes.h e linux/uhid.h) ----
private const val EV_SYN = 0
private const val EV_KEY = 1
private const val EV_REL = 2
private const val SYN_REPORT = 0
private const val REL_X = 0
private const val REL_Y = 1
private const val REL_HWHEEL = 6
private const val REL_WHEEL = 8
private const val BTN_LEFT = 0x110
private const val BTN_RIGHT = 0x111
private const val BTN_MIDDLE = 0x112
private const val BTN_SIDE = 0x113
private const val BTN_EXTRA = 0x114

private const val UHID_DESTROY = 1
private const val UHID_OPEN = 4
private const val UHID_CLOSE = 5
private const val UHID_CREATE2 = 11
private const val UHID_INPUT2 = 12
private const val UHID_CREATE_SIZE = 4376 // 4 (tipo) + 4372 (struct uhid_create2_req)
private const val UHID_NAME = "Samsung Bluetooth Mouse KVM"

// Mouse HID: 5 botões, X/Y 16 bits relativos, roda vertical e horizontal (AC Pan). Relatório de 7 bytes.
private val HID_DESC: ByteArray = intArrayOf(
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x03, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
    0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0xC0, 0xC0
).map { it.toByte() }.toByteArray()

private val NAME_RE = Regex("N: Name=\"(.*)\"")
private val HANDLERS_RE = Regex("H: Handlers=(.*)")
private val EVENT_RE = Regex("event(\\d+)")
private val REL_RE = Regex("B: REL=([0-9a-fA-F ]+)")

private fun ByteBuffer.putFixed(s: String, len: Int) {
    val start = position()
    val bytes = s.toByteArray(Charsets.UTF_8)
    put(bytes, 0, minOf(bytes.size, len - 1))
    position(start + len)
}

/** EVIOCGRAB: entrega o dispositivo só a quem o abriu; o Android (InputReader) deixa de receber eventos dele. */
private object Evdev {
    private const val EVIOCGRAB = 0x40044590
    private val os: Any? = runCatching {
        Class.forName("libcore.io.Libcore").getField("os").get(null)
    }.getOrNull()

    fun grab(fd: FileDescriptor): Boolean {
        val o = os ?: return false
        return try {
            o.javaClass.getMethod("ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType)
                .invoke(o, fd, EVIOCGRAB)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

private fun resolveInjector(): (InputEvent) -> Unit {
    var last: Throwable? = null
    for (name in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
        try {
            val cls = Class.forName(name)
            val instance = cls.getMethod("getInstance").invoke(null)
            val method = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            return { event -> method.invoke(instance, event, 0) }
        } catch (t: Throwable) {
            last = t
        }
    }
    throw IllegalStateException("InputManager indisponível", last)
}

/** Roda no processo do Shizuku (uid shell). Não tem acesso a Context. */
class PrivilegedService : IPrivileged.Stub() {

    private val inject: (InputEvent) -> Unit by lazy { resolveInjector() }

    private fun key(code: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                inject(KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
            }
        } catch (_: Throwable) {
        }
    }

    override fun wakeScreen() {
        key(KeyEvent.KEYCODE_WAKEUP)
    }

    override fun sleepScreen() {
        key(KeyEvent.KEYCODE_SLEEP)
    }

    // =====================================================================
    // MESTRE: lê o mouse físico direto do kernel, prende (EVIOCGRAB) e envia por UDP
    // =====================================================================

    private class Session {
        @Volatile var alive = true
    }

    private val remoteLock = Any()
    private var session: Session? = null
    private var sock: DatagramSocket? = null
    private val readers = mutableListOf<Thread>()
    @Volatile private var buttons = 0

    override fun startRemote(host: String, port: Int): Int = synchronized(remoteLock) { startLocked(host, port) }

    override fun stopRemote() {
        synchronized(remoteLock) { stopLocked() }
    }

    private fun startLocked(host: String, port: Int): Int {
        stopLocked()
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Throwable) {
            return 0
        }
        val s = try {
            DatagramSocket()
        } catch (_: Throwable) {
            return 0
        }
        runCatching { s.trafficClass = 0x10 }
        val sess = Session()
        var opened = 0
        var grabbed = 0
        buttons = 0
        for (path in findMouseNodes()) {
            val fd = try {
                Os.open(path, OsConstants.O_RDONLY, 0)
            } catch (_: Throwable) {
                continue
            }
            if (Evdev.grab(fd)) grabbed++
            opened++
            readers += thread(name = "kvm-evdev", isDaemon = true) { readLoop(fd, sess, s, addr, port) }
        }
        if (opened == 0) {
            runCatching { s.close() }
            return 0
        }
        session = sess
        sock = s
        return if (grabbed > 0) 2 else 1
    }

    private fun stopLocked() {
        session?.alive = false
        readers.forEach { runCatching { it.join(500) } }
        readers.clear()
        session = null
        runCatching { sock?.close() }
        sock = null
        buttons = 0
    }

    /** Nós /dev/input/eventN de dispositivos com REL_X e REL_Y (mouses). */
    private fun findMouseNodes(): List<String> {
        val out = mutableListOf<String>()
        val text = try {
            File("/proc/bus/input/devices").readText()
        } catch (_: Throwable) {
            return out
        }
        for (block in text.split("\n\n")) {
            val name = NAME_RE.find(block)?.groupValues?.get(1) ?: ""
            if (name == UHID_NAME) continue
            val handlers = HANDLERS_RE.find(block)?.groupValues?.get(1) ?: continue
            if (!handlers.contains("mouse")) continue
            val event = EVENT_RE.find(handlers)?.groupValues?.get(1) ?: continue
            val rel = REL_RE.find(block)?.groupValues?.get(1)?.trim()
                ?.split(Regex("\\s+"))?.last()?.toLongOrNull(16) ?: continue
            if ((rel and 3L) != 3L) continue
            out += "/dev/input/event$event"
        }
        return out
    }

    private fun readLoop(fd: FileDescriptor, sess: Session, s: DatagramSocket, addr: InetAddress, port: Int) {
        val evSize = if (Process.is64Bit()) 24 else 16 // struct input_event
        val buf = ByteArray(evSize * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
        val pfd = arrayOf(StructPollfd().also {
            it.fd = fd
            it.events = OsConstants.POLLIN.toShort()
        })
        var dx = 0
        var dy = 0
        var wheel = 0
        var pan = 0
        var changed = false
        try {
            while (sess.alive) {
                if (Os.poll(pfd, 100) <= 0) continue
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) break
                var off = 0
                while (off + evSize <= n) {
                    val t = off + evSize - 8
                    val type = bb.getShort(t).toInt() and 0xFFFF
                    val code = bb.getShort(t + 2).toInt() and 0xFFFF
                    val value = bb.getInt(t + 4)
                    off += evSize
                    when (type) {
                        EV_REL -> when (code) {
                            REL_X -> dx += value
                            REL_Y -> dy += value
                            REL_WHEEL -> wheel += value
                            REL_HWHEEL -> pan += value
                        }
                        EV_KEY -> {
                            val bit = when (code) {
                                BTN_LEFT -> 1
                                BTN_RIGHT -> 2
                                BTN_MIDDLE -> 4
                                BTN_SIDE -> 8
                                BTN_EXTRA -> 16
                                else -> 0
                            }
                            if (bit != 0) {
                                buttons = if (value != 0) buttons or bit else buttons and bit.inv()
                                changed = true
                            }
                        }
                        EV_SYN -> if (code == SYN_REPORT && (changed || dx != 0 || dy != 0 || wheel != 0 || pan != 0)) {
                            sendReport(s, addr, port, dx, dy, wheel, pan)
                            // mudança de botão: repete só o estado (protege o "soltar" contra perda de pacote)
                            if (changed) sendReport(s, addr, port, 0, 0, 0, 0)
                            dx = 0
                            dy = 0
                            wheel = 0
                            pan = 0
                            changed = false
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { Os.close(fd) } // fechar libera a captura exclusiva
        }
    }

    private fun sendReport(s: DatagramSocket, addr: InetAddress, port: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        val b = ByteBuffer.allocate(8)
        b.put(Proto.REPORT)
        b.put(buttons.toByte())
        b.putShort(dx.coerceIn(-32767, 32767).toShort())
        b.putShort(dy.coerceIn(-32767, 32767).toShort())
        b.put(wheel.coerceIn(-127, 127).toByte())
        b.put(pan.coerceIn(-127, 127).toByte())
        try {
            s.send(DatagramPacket(b.array(), 8, addr, port))
        } catch (_: Throwable) {
        }
    }

    // =====================================================================
    // ESCRAVO: mouse Bluetooth HID virtual no kernel (/dev/uhid)
    // =====================================================================

    private class UhidDev(val fd: FileDescriptor) {
        @Volatile var alive = true
        @Volatile var ready = false
    }

    private val uhidLock = Any()
    @Volatile private var uhid: UhidDev? = null

    override fun mouseCreate(): Boolean = synchronized(uhidLock) {
        if (uhid != null) return@synchronized true
        var fd: FileDescriptor? = null
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
            Os.write(fd, createEvent(), 0, UHID_CREATE_SIZE)
            val dev = UhidDev(fd)
            uhid = dev
            thread(name = "kvm-uhid", isDaemon = true) { drain(dev) }
            true
        } catch (_: Throwable) {
            fd?.let { runCatching { Os.close(it) } }
            uhid = null
            false
        }
    }

    private fun createEvent(): ByteArray {
        val b = ByteBuffer.allocate(UHID_CREATE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_CREATE2)
        b.putFixed(UHID_NAME, 128)
        b.putFixed("kvm/uhid", 64)
        b.putFixed("kvm-uhid-0", 64)
        b.putShort(HID_DESC.size.toShort())
        b.putShort(0x05) // BUS_BLUETOOTH
        b.putInt(0x04E8) // Samsung
        b.putInt(0x7021)
        b.putInt(0x0100)
        b.putInt(0)
        b.put(HID_DESC)
        return b.array()
    }

    /** Lê os eventos do kernel. UHID_OPEN = o Android abriu o dispositivo (pronto para receber). */
    private fun drain(dev: UhidDev) {
        val buf = ByteArray(4400)
        val pfd = arrayOf(StructPollfd().also {
            it.fd = dev.fd
            it.events = OsConstants.POLLIN.toShort()
        })
        try {
            while (dev.alive) {
                if (Os.poll(pfd, 100) <= 0) continue
                val n = Os.read(dev.fd, buf, 0, buf.size)
                if (n < 4) break
                when ((buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)) {
                    UHID_OPEN -> dev.ready = true
                    UHID_CLOSE -> dev.ready = false
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { Os.close(dev.fd) }
        }
    }

    private fun waitReady(dev: UhidDev) {
        var waited = 0
        while (!dev.ready && dev.alive && waited < 1500) {
            Thread.sleep(5)
            waited += 5
        }
        dev.ready = true // não espera de novo, mesmo se o aviso não chegar
    }

    private fun writeReport(dev: UhidDev, buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        val b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_INPUT2)
        b.putShort(7)
        b.put(buttons.toByte())
        b.putShort(dx.toShort())
        b.putShort(dy.toShort())
        b.put(wheel.toByte())
        b.put(pan.toByte())
        try {
            Os.write(dev.fd, b.array(), 0, 13)
        } catch (_: Throwable) {
        }
    }

    override fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        val dev = uhid ?: return
        waitReady(dev)
        var rx = dx
        var ry = dy
        var w = wheel
        var p = pan
        do {
            val sx = rx.coerceIn(-32767, 32767)
            val sy = ry.coerceIn(-32767, 32767)
            writeReport(dev, buttons, sx, sy, w, p)
            rx -= sx
            ry -= sy
            w = 0
            p = 0
        } while (rx != 0 || ry != 0)
    }

    /** Encosta o cursor na borda de entrada (slam) e depois desloca pela outra borda em passos curtos. */
    override fun mousePlace(slamX: Int, slamY: Int, moveX: Int, moveY: Int) {
        val dev = uhid ?: return
        waitReady(dev)
        repeat(4) {
            writeReport(dev, 0, slamX * 30000, slamY * 30000, 0, 0)
            Thread.sleep(3)
        }
        var rx = moveX
        var ry = moveY
        while ((rx != 0 || ry != 0) && dev.alive) {
            val sx = rx.coerceIn(-48, 48)
            val sy = ry.coerceIn(-48, 48)
            writeReport(dev, 0, sx, sy, 0, 0)
            rx -= sx
            ry -= sy
            Thread.sleep(2)
        }
    }

    override fun mouseDestroy() {
        val dev = synchronized(uhidLock) {
            val d = uhid
            uhid = null
            d
        } ?: return
        try {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(UHID_DESTROY)
            Os.write(dev.fd, b.array(), 0, 4)
        } catch (_: Throwable) {
        }
        dev.alive = false // a thread de leitura fecha o fd
    }

    override fun destroy() {
        exitProcess(0)
    }
}
