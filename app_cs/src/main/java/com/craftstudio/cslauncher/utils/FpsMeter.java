package com.craftstudio.cslauncher.utils;

import android.os.SystemClock;

import com.craftstudio.cslauncher.game.platform.Platform;
import com.craftstudio.cslauncher.game.platform.backend.GLFWBackend;
import com.craftstudio.cslauncher.game.platform.backend.SDLBackend;
import com.craftstudio.glfw.GLFW;
import com.craftstudio.sdl.SDLActivity;

/**
 * Measures the real presentation rate of the game.
 * <p>
 * Both windowing backends (GLFW and SDL) bump an atomic counter every time a frame goes
 * through eglSwapBuffers. Sampling that counter over wall-clock time gives the exact
 * number of frames the player actually saw, independent of what the game thinks it renders.
 */
public final class FpsMeter {

    /** Returned by {@link #readFrames()} when no backend can supply a frame count yet. */
    public static final long UNAVAILABLE = -1L;

    private long mLastFrames = UNAVAILABLE;
    private long mLastTimeNs;
    private float mSmoothed = -1f;
    private int mLastInstant = -1;
    private int mMin = Integer.MAX_VALUE;
    private int mMax = 0;

    /** Raw presented frame count from the active backend, or {@link #UNAVAILABLE}. */
    public static long readFrames() {
        try {
            if (Platform.PLATFORM instanceof SDLBackend) {
                return SDLActivity.nativeGetFrameCount();
            }
            if (Platform.PLATFORM instanceof GLFWBackend) {
                return GLFW.nativeGetFrameCount();
            }
            // No windowing backend yet (e.g. the controls editor): nothing to measure
            return UNAVAILABLE;
        } catch (Throwable t) {
            return UNAVAILABLE;
        }
    }

    /**
     * Take a sample. Call this periodically (every 250-1000ms is ideal).
     *
     * @return the frames per second measured since the previous call, or -1 if unknown yet
     */
    public int sample() {
        long frames = readFrames();
        long now = SystemClock.elapsedRealtimeNanos();
        if (frames == UNAVAILABLE) {
            mLastFrames = UNAVAILABLE;
            mLastInstant = -1;
            return -1;
        }
        if (mLastFrames == UNAVAILABLE || frames < mLastFrames) {
            // First sample or counter reset (game restarted)
            mLastFrames = frames;
            mLastTimeNs = now;
            mLastInstant = -1;
            return -1;
        }
        long deltaFrames = frames - mLastFrames;
        long deltaNs = now - mLastTimeNs;
        mLastFrames = frames;
        mLastTimeNs = now;
        if (deltaNs <= 0) return mLastInstant;

        float instant = deltaFrames * 1_000_000_000f / deltaNs;
        // Light smoothing so the number doesn't flicker, but still reacts within ~2 samples
        mSmoothed = mSmoothed < 0 ? instant : mSmoothed * 0.35f + instant * 0.65f;
        mLastInstant = Math.round(mSmoothed);
        if (deltaFrames > 0) {
            mMin = Math.min(mMin, mLastInstant);
            mMax = Math.max(mMax, mLastInstant);
        }
        return mLastInstant;
    }

    /** Last computed value, -1 if nothing was measured yet. */
    public int getLast() {
        return mLastInstant;
    }

    /** Lowest value seen since {@link #resetStats()}; -1 if none. */
    public int getMin() {
        return mMin == Integer.MAX_VALUE ? -1 : mMin;
    }

    /** Highest value seen since {@link #resetStats()}; -1 if none. */
    public int getMax() {
        return mMax == 0 && mMin == Integer.MAX_VALUE ? -1 : mMax;
    }

    public void resetStats() {
        mMin = Integer.MAX_VALUE;
        mMax = 0;
    }

    /** Whether a frame source is currently available at all */
    public static boolean isAvailable() {
        return readFrames() != UNAVAILABLE;
    }
}
