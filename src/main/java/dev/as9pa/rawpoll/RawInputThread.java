package dev.as9pa.rawpoll;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs on the daemon thread "rawpoll-input". Owns a message-only window registered for Windows Raw Input
 * mouse events and accumulates relative motion into atomic counters.
 *
 * Only JNA 3.4.0 core API is used (Minecraft 1.8.9 ships that version). All Win32
 * structs are laid out by hand in Memory blocks.
 */
public final class RawInputThread implements Runnable {

    /** Minimal user32 binding. BOOL returns are mapped to int to avoid any type mapper. */
    private interface User32 extends Library {
        Pointer CreateWindowExW(int dwExStyle, WString lpClassName, WString lpWindowName, int dwStyle,
                                int x, int y, int nWidth, int nHeight,
                                Pointer hWndParent, Pointer hMenu, Pointer hInstance, Pointer lpParam);

        int RegisterRawInputDevices(Pointer pRawInputDevices, int uiNumDevices, int cbSize);

        int GetMessageW(Pointer lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);

        int PostMessageW(Pointer hWnd, int msg, Pointer wParam, Pointer lParam);

        int GetRawInputData(Pointer hRawInput, int uiCommand, Pointer pData, Pointer pcbSize, int cbSizeHeader);

        int DestroyWindow(Pointer hWnd);
    }

    private static final Pointer HWND_MESSAGE = Pointer.createConstant(-3);
    private static final int WM_INPUT = 0x00FF;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    private static final int RIDEV_NOLEGACY = 0x00000030;
    /** Private message posted by the client thread: wParam 1 = legacy mouse messages off, 0 = on. */
    private static final int WM_APP_LEGACY = 0x8000;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIM_TYPEMOUSE = 0;
    private static final int MOUSE_MOVE_ABSOLUTE = 0x01;

    private static final int PTR = Pointer.SIZE;
    private static final boolean X64 = PTR == 8;

    /*
     * RAWINPUTDEVICE { USHORT usUsagePage; USHORT usUsage; DWORD dwFlags; HWND hwndTarget; }
     *   x64: 0, 2, 4, 8 -> 16 bytes. x86: 0, 2, 4, 8 -> 12 bytes.
     */
    private static final int RID_SIZE = 8 + PTR;

    /*
     * MSG { HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt; DWORD lPrivate; }
     *   x64: hwnd 0, message 8, (pad 12), wParam 16, lParam 24, time 32, pt 36, lPrivate 44 -> 48 bytes.
     *   x86: hwnd 0, message 4, wParam 8, lParam 12, time 16, pt 20, lPrivate 28 -> 32 bytes.
     */
    private static final int MSG_SIZE = X64 ? 48 : 32;
    private static final int MSG_MESSAGE = PTR;
    private static final int MSG_WPARAM = X64 ? 16 : 8;
    private static final int MSG_LPARAM = X64 ? 24 : 12;
    private static final int MSG_TIME = X64 ? 32 : 16;

    /*
     * RAWINPUTHEADER { DWORD dwType; DWORD dwSize; HANDLE hDevice; WPARAM wParam; }
     *   x64: 0, 4, 8, 16 -> 24 bytes. x86: 0, 4, 8, 12 -> 16 bytes.
     * RAWMOUSE follows the header:
     *   usFlags u16 +0, (pad), ulButtons +4 (usButtonFlags u16 +4, usButtonData s16 +6), ulRawButtons +8,
     *   lLastX i32 +12, lLastY i32 +16, ulExtraInformation +20 -> 24 bytes.
     */
    private static final int HEADER_SIZE = 8 + 2 * PTR;
    private static final int MOUSE_FLAGS = HEADER_SIZE;
    private static final int MOUSE_BUTTON_FLAGS = HEADER_SIZE + 4;
    private static final int MOUSE_BUTTON_DATA = HEADER_SIZE + 6;
    private static final int MOUSE_LAST_X = HEADER_SIZE + 12;
    private static final int MOUSE_LAST_Y = HEADER_SIZE + 16;
    private static final int RAWINPUT_BUFFER_SIZE = 64;

    /** RI_MOUSE_* down flags for LWJGL buttons 0..4 (left, right, middle, X1, X2); the up flag is down << 1. */
    private static final int[] BUTTON_DOWN_FLAGS = {0x0001, 0x0004, 0x0010, 0x0040, 0x0100};
    private static final int RI_MOUSE_WHEEL = 0x0400;

    /** A button or wheel event captured while legacy messages are off. button is -1 for wheel. */
    static final class ButtonEvent {
        final int button;
        final int state;
        final int wheel;
        final long millis;

        ButtonEvent(int button, int state, int wheel, long millis) {
            this.button = button;
            this.state = state;
            this.wheel = wheel;
            this.millis = millis;
        }
    }

    /** Filled by the raw input thread only while legacy messages are off; drained on the client thread. */
    static final ConcurrentLinkedQueue<ButtonEvent> BUTTON_EVENTS = new ConcurrentLinkedQueue<ButtonEvent>();

    private static final AtomicLong DX = new AtomicLong();
    private static final AtomicLong DY = new AtomicLong();
    /** Diagnostic: total non-zero relative mouse moves received. Never reset. */
    private static final AtomicLong EVENTS = new AtomicLong();

    private static RawInputThread instance;
    private static boolean started;
    private static volatile User32 user32Ref;
    private static volatile Pointer hwndRef;
    /** True while RIDEV_NOLEGACY is registered. Written only by the raw input thread. */
    private static volatile boolean legacyOff;

    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean ok;

    private RawInputThread() {
    }

    /**
     * Starts the thread (once) and waits up to 2 seconds for window creation and device
     * registration. Returns true only if raw input is registered and running.
     */
    public static synchronized boolean start() {
        if (instance != null) {
            return started;
        }
        instance = new RawInputThread();
        Thread thread = new Thread(instance, "rawpoll-input");
        thread.setDaemon(true);
        thread.start();
        try {
            started = instance.ready.await(2, TimeUnit.SECONDS) && instance.ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started = false;
        }
        if (!started) {
            RawPoll.LOGGER.warn("Raw input thread did not report success within 2 seconds");
        }
        return started;
    }

    public static long takeDX() {
        return DX.getAndSet(0);
    }

    public static long takeDY() {
        return DY.getAndSet(0);
    }

    public static long events() {
        return EVENTS.get();
    }

    public static void reset() {
        DX.set(0);
        DY.set(0);
    }

    public static boolean isLegacyOff() {
        return legacyOff;
    }

    /**
     * Asks the raw input thread to switch legacy mouse messages off (true) or back on (false).
     * Returns false if the request could not be posted.
     */
    public static boolean requestLegacyOff(boolean off) {
        User32 u = user32Ref;
        Pointer h = hwndRef;
        if (u == null || h == null) {
            return false;
        }
        return u.PostMessageW(h, WM_APP_LEGACY, off ? Pointer.createConstant(1) : null, null) != 0;
    }

    private static boolean register(User32 user32, Pointer hwnd, int flags) {
        Memory rid = new Memory(RID_SIZE);
        rid.clear();
        rid.setShort(0, (short) 0x01);   // usUsagePage: generic desktop
        rid.setShort(2, (short) 0x02);   // usUsage: mouse
        rid.setInt(4, flags);            // dwFlags
        rid.setPointer(8, hwnd);         // hwndTarget
        return user32.RegisterRawInputDevices(rid, 1, RID_SIZE) != 0;
    }

    @Override
    public void run() {
        User32 user32;
        Pointer hwnd;
        try {
            user32 = (User32) Native.loadLibrary("user32", User32.class);
            hwnd = user32.CreateWindowExW(0, new WString("STATIC"), new WString("rawpoll"), 0,
                    0, 0, 0, 0, HWND_MESSAGE, null, null, null);
            if (hwnd == null) {
                RawPoll.LOGGER.warn("CreateWindowExW failed, error {}", Native.getLastError());
                ready.countDown();
                return;
            }

            if (!register(user32, hwnd, RIDEV_INPUTSINK)) {
                RawPoll.LOGGER.warn("RegisterRawInputDevices failed, error {}", Native.getLastError());
                user32.DestroyWindow(hwnd);
                ready.countDown();
                return;
            }
        } catch (Throwable t) {
            RawPoll.LOGGER.warn("Raw input setup failed", t);
            ready.countDown();
            return;
        }

        user32Ref = user32;
        hwndRef = hwnd;
        ok = true;
        ready.countDown();

        Memory msg = new Memory(MSG_SIZE);
        Memory buffer = new Memory(RAWINPUT_BUFFER_SIZE);
        Memory size = new Memory(4);
        try {
            while (true) {
                // All messages for our window are read (WM_INPUT and WM_APP_LEGACY). None are dispatched,
                // so no WndProc is needed.
                int r = user32.GetMessageW(msg, hwnd, 0, 0);
                if (r == 0 || r == -1) {
                    break;
                }
                int message = msg.getInt(MSG_MESSAGE);
                if (message == WM_APP_LEGACY) {
                    // Low 32 bits of wParam are enough for 0/1 (little-endian).
                    boolean off = msg.getInt(MSG_WPARAM) != 0;
                    if (off != legacyOff) {
                        if (register(user32, hwnd, off ? RIDEV_INPUTSINK | RIDEV_NOLEGACY : RIDEV_INPUTSINK)) {
                            legacyOff = off;
                        } else {
                            RawPoll.LOGGER.warn("RegisterRawInputDevices (legacyOff={}) failed, error {}",
                                    off, Native.getLastError());
                        }
                    }
                    continue;
                }
                if (message != WM_INPUT) {
                    continue;
                }
                Pointer hRawInput = msg.getPointer(MSG_LPARAM);
                size.setInt(0, RAWINPUT_BUFFER_SIZE);
                int read = user32.GetRawInputData(hRawInput, RID_INPUT, buffer, size, HEADER_SIZE);
                if (read == -1 || read < MOUSE_LAST_Y + 4) {
                    continue;
                }
                if (buffer.getInt(0) != RIM_TYPEMOUSE) {
                    continue;
                }
                if (legacyOff) {
                    int buttonFlags = buffer.getShort(MOUSE_BUTTON_FLAGS) & 0xFFFF;
                    if (buttonFlags != 0) {
                        // Same clock LWJGL gets for legacy messages (GetMessageTime).
                        long millis = msg.getInt(MSG_TIME) & 0xFFFFFFFFL;
                        for (int b = 0; b < BUTTON_DOWN_FLAGS.length; b++) {
                            int down = BUTTON_DOWN_FLAGS[b];
                            if ((buttonFlags & down) != 0) {
                                BUTTON_EVENTS.add(new ButtonEvent(b, 1, 0, millis));
                            }
                            if ((buttonFlags & (down << 1)) != 0) {
                                BUTTON_EVENTS.add(new ButtonEvent(b, 0, 0, millis));
                            }
                        }
                        if ((buttonFlags & RI_MOUSE_WHEEL) != 0) {
                            int wheel = buffer.getShort(MOUSE_BUTTON_DATA);
                            if (wheel != 0) {
                                BUTTON_EVENTS.add(new ButtonEvent(-1, 0, wheel, millis));
                            }
                        }
                    }
                }
                int flags = buffer.getShort(MOUSE_FLAGS) & 0xFFFF;
                if ((flags & MOUSE_MOVE_ABSOLUTE) != 0) {
                    continue;
                }
                int x = buffer.getInt(MOUSE_LAST_X);
                int y = buffer.getInt(MOUSE_LAST_Y);
                if (x != 0) {
                    DX.addAndGet(x);
                }
                if (y != 0) {
                    DY.addAndGet(y);
                }
                if (x != 0 || y != 0) {
                    EVENTS.incrementAndGet();
                }
            }
        } catch (Throwable t) {
            RawPoll.LOGGER.warn("Raw input thread stopped unexpectedly", t);
        } finally {
            user32.DestroyWindow(hwnd);
        }
    }
}
