package dev.as9pa.rawpoll;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Diagnostic frame timing and mouse helper guard. Logs one line per second while the mouse is grabbed.
 * Runs on the client thread only.
 */
public class RawPollDiag {

    private static final long SECOND_NS = 1000000000L;
    private static final int MAX_FRAMES = 16384;

    // Frame times of the current second, kept so spikes can be judged against that second's mean.
    private final long[] frames = new long[MAX_FRAMES];
    private int frameCount;
    private long frameSum;
    private long maxFrame;
    private long maxRender;
    private long maxOutside;

    private long lastStart;
    private long lastEnd;
    private long windowStart;
    private long windowEvents;
    private long windowCalls;
    private long windowAbsSum;
    private long windowInjected;

    private boolean warned;
    private long reinstalls;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            onStart(now);
        } else if (lastStart != 0) {
            long render = now - lastStart;
            if (render > maxRender) {
                maxRender = render;
            }
            lastEnd = now;
        }
    }

    private void onStart(long now) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.mouseHelper instanceof RawPollHelper)) {
            if (!warned) {
                warned = true;
                RawPoll.LOGGER.warn("mouseHelper was replaced by {}; re-installing RawPollHelper",
                        mc.mouseHelper == null ? "null" : mc.mouseHelper.getClass().getName());
            }
            mc.mouseHelper = new RawPollHelper();
            reinstalls++;
        }
        RawPollHelper.onFrameStart();

        if (lastStart != 0) {
            long frame = now - lastStart;
            if (frameCount < MAX_FRAMES) {
                frames[frameCount] = frame;
            }
            frameCount++;
            frameSum += frame;
            if (frame > maxFrame) {
                maxFrame = frame;
            }
        }
        if (lastEnd != 0) {
            long outside = now - lastEnd;
            if (outside > maxOutside) {
                maxOutside = outside;
            }
        }
        lastStart = now;

        if (windowStart == 0) {
            resetWindow(now);
            return;
        }
        long elapsed = now - windowStart;
        if (elapsed < SECOND_NS) {
            return;
        }

        if (RawPoll.debugLog && mc.inGameHasFocus && frameCount > 0) {
            double secs = elapsed / 1e9;
            double mean = (double) frameSum / frameCount;
            int stored = Math.min(frameCount, MAX_FRAMES);
            int spikes = 0;
            for (int i = 0; i < stored; i++) {
                if (frames[i] > 2 * mean) {
                    spikes++;
                }
            }
            RawPoll.LOGGER.info(String.format(
                    "[rawpoll diag] fps=%.0f raw/s=%.0f calls/s=%.0f absDelta/s=%.0f meanFrame=%.2fms maxFrame=%.2fms"
                            + " spikes=%d maxRender=%.2fms maxOutside=%.2fms maxDX=%d starvation=%d reinstalls=%d"
                            + " legacyOff=%b injected=%.0f",
                    frameCount / secs,
                    (RawInputThread.events() - windowEvents) / secs,
                    (RawPollHelper.calls - windowCalls) / secs,
                    (RawPollHelper.absSum - windowAbsSum) / secs,
                    mean / 1e6, maxFrame / 1e6, spikes, maxRender / 1e6, maxOutside / 1e6,
                    RawPollHelper.maxAbsDX, RawPollHelper.starvation, reinstalls,
                    RawInputThread.isLegacyOff(), (RawPollHelper.injected - windowInjected) / secs));
        }
        resetWindow(now);
    }

    private void resetWindow(long now) {
        windowStart = now;
        windowEvents = RawInputThread.events();
        windowCalls = RawPollHelper.calls;
        windowAbsSum = RawPollHelper.absSum;
        windowInjected = RawPollHelper.injected;
        RawPollHelper.maxAbsDX = 0;
        frameCount = 0;
        frameSum = 0;
        maxFrame = 0;
        maxRender = 0;
        maxOutside = 0;
    }
}
