package dev.as9pa.rawpoll;

import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * MouseHelper that takes its deltas from the raw input thread instead of LWJGL.
 *
 * While the cursor is grabbed and the window is active, legacy mouse messages are switched off
 * (RIDEV_NOLEGACY) so LWJGL's message pump is not flooded at high polling rates. Buttons and wheel
 * then come from raw input and are injected into LWJGL's WindowsMouse on the client thread.
 */
public class RawPollHelper extends MouseHelper {

    // Diagnostic counters. Only touched on the client thread.
    static long calls;
    static long absSum;
    static long maxAbsDX;
    static long starvation;
    static long injected;
    private static boolean lastNonZero;
    private static long lastEvents;

    // NOLEGACY state. Only touched on the client thread.
    private static boolean noLegacyEnabled;
    private static boolean grabbed;
    private static boolean requestedOff;
    private static final boolean[] injectedDown = new boolean[5];
    private static long lastMillis;

    // Reflection into LWJGL 2.9.4: Display.display_impl (WindowsDisplay) -> mouse (WindowsMouse).
    private static Object windowsDisplay;
    private static Field mouseField;
    private static Method handleMouseButton;
    private static Method handleMouseScrolled;
    // WindowsMouse.poll only recenters after WM_MOUSEMOVE, which NOLEGACY suppresses, so we recenter ourselves.
    private static Method centerCursor;
    private static Method getHwnd;

    /**
     * Resolves the LWJGL internals used for injection. Returns false (and leaves NOLEGACY mode off)
     * if anything is missing.
     */
    static boolean initNoLegacy() {
        try {
            Field implField = Display.class.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.WindowsDisplay");
            if (!displayClass.isInstance(impl)) {
                throw new IllegalStateException("display_impl is " + (impl == null ? "null" : impl.getClass().getName()));
            }
            Field mf = displayClass.getDeclaredField("mouse");
            mf.setAccessible(true);
            Class<?> mouseClass = Class.forName("org.lwjgl.opengl.WindowsMouse");
            Method button = mouseClass.getDeclaredMethod("handleMouseButton", byte.class, byte.class, long.class);
            button.setAccessible(true);
            Method scrolled = mouseClass.getDeclaredMethod("handleMouseScrolled", int.class, long.class);
            scrolled.setAccessible(true);
            Method center = displayClass.getDeclaredMethod("centerCursor", long.class);
            center.setAccessible(true);
            Method hwnd = displayClass.getDeclaredMethod("getHwnd");
            hwnd.setAccessible(true);
            centerCursor = center;
            getHwnd = hwnd;
            windowsDisplay = impl;
            mouseField = mf;
            handleMouseButton = button;
            handleMouseScrolled = scrolled;
            noLegacyEnabled = true;
            return true;
        } catch (Throwable t) {
            RawPoll.LOGGER.warn("Could not access LWJGL mouse internals; legacy mouse messages stay on", t);
            noLegacyEnabled = false;
            return false;
        }
    }

    @Override
    public void grabMouseCursor() {
        super.grabMouseCursor();
        RawInputThread.reset();
        grabbed = true;
        updateLegacyMode();
    }

    @Override
    public void ungrabMouseCursor() {
        grabbed = false;
        updateLegacyMode();
        super.ungrabMouseCursor();
    }

    /**
     * Called once per frame at RenderTickEvent START. Injects queued raw buttons and wheel into LWJGL;
     * the next Display.update picks them up.
     */
    static void onFrameStart() {
        if (!noLegacyEnabled) {
            return;
        }
        updateLegacyMode();
        drain();
        if (requestedOff && RawInputThread.isLegacyOff()) {
            // Keep the hidden cursor pinned to the window centre so it cannot reach the taskbar or other monitors.
            try {
                centerCursor.invoke(null, getHwnd.invoke(windowsDisplay));
            } catch (Throwable t) {
                RawPoll.LOGGER.warn("centerCursor failed; turning legacy mouse messages back on", t);
                noLegacyEnabled = false;
                RawInputThread.requestLegacyOff(false);
                requestedOff = false;
            }
        }
        if (!requestedOff && !RawInputThread.isLegacyOff()) {
            // Legacy messages are back; release anything we pressed so no button sticks.
            for (int b = 0; b < injectedDown.length; b++) {
                if (injectedDown[b]) {
                    inject(b, 0, 0, lastMillis);
                }
            }
        }
    }

    /** Legacy off only while grabbed and the window is active (covers alt-tab). */
    private static void updateLegacyMode() {
        if (!noLegacyEnabled) {
            return;
        }
        boolean wantOff = grabbed && Display.isActive();
        if (wantOff != requestedOff && RawInputThread.requestLegacyOff(wantOff)) {
            requestedOff = wantOff;
        }
    }

    private static void drain() {
        RawInputThread.ButtonEvent e;
        while ((e = RawInputThread.BUTTON_EVENTS.poll()) != null) {
            inject(e.button, e.state, e.wheel, e.millis);
        }
    }

    private static void inject(int button, int state, int wheel, long millis) {
        try {
            Object mouse = mouseField.get(windowsDisplay);
            if (mouse == null) {
                return;
            }
            if (button < 0) {
                // WindowsDisplay passes the signed WM_MOUSEWHEEL delta unscaled; usButtonData is the same value.
                handleMouseScrolled.invoke(mouse, wheel, millis);
            } else {
                handleMouseButton.invoke(mouse, (byte) button, (byte) state, millis);
                injectedDown[button] = state != 0;
            }
            lastMillis = millis;
            injected++;
        } catch (Throwable t) {
            RawPoll.LOGGER.warn("Mouse event injection failed; switching legacy mouse messages back on", t);
            noLegacyEnabled = false;
            RawInputThread.requestLegacyOff(false);
            requestedOff = false;
            RawInputThread.BUTTON_EVENTS.clear();
        }
    }

    @Override
    public void mouseXYChange() {
        calls++;
        // Drain LWJGL's accumulated deltas so its state stays clean; the values are discarded.
        Mouse.getDX();
        Mouse.getDY();

        if (!Display.isActive()) {
            RawInputThread.reset();
            this.deltaX = 0;
            this.deltaY = 0;
            lastNonZero = false;
            return;
        }

        // Raw Y is down-positive; Minecraft deltaY is up-positive.
        this.deltaX = (int) clamp(RawInputThread.takeDX());
        this.deltaY = (int) -clamp(RawInputThread.takeDY());

        long events = RawInputThread.events();
        boolean nonZero = this.deltaX != 0 || this.deltaY != 0;
        if (!nonZero && lastNonZero && events != lastEvents) {
            starvation++;
        }
        lastNonZero = nonZero;
        lastEvents = events;
        long ax = Math.abs((long) this.deltaX);
        absSum += ax + Math.abs((long) this.deltaY);
        if (ax > maxAbsDX) {
            maxAbsDX = ax;
        }
    }

    private static long clamp(long v) {
        // Keep the value negatable and within int range.
        return Math.max(-Integer.MAX_VALUE, Math.min(Integer.MAX_VALUE, v));
    }
}
