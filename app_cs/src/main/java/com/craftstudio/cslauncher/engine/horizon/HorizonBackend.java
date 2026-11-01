package com.craftstudio.cslauncher.engine.horizon;

import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;

import com.craftstudio.cslauncher.LwjglGlfwKeycode;
import com.craftstudio.cslauncher.game.GameActivity;
import com.craftstudio.cslauncher.game.platform.Platform;
import com.craftstudio.cslauncher.game.platform.backend.PlatformBackend;

/**
 * {@link PlatformBackend} that forwards the CS control system (ControlLayout buttons, joystick,
 * touch camera, virtual cursor, keyboard) to the Horizon engine through
 * {@link HorizonNative#nativeHostKey} and friends. The engine polls the queue once per frame.
 *
 * Grab state (world view vs. menu cursor) and keyboard requests come back from the engine
 * through {@link HorizonNative#nativeHostPoll()}, polled on every display frame.
 */
public final class HorizonBackend implements PlatformBackend, Choreographer.FrameCallback {
    private static final int BUTTON_LEFT = 0, BUTTON_RIGHT = 1, BUTTON_MIDDLE = 2, BUTTON_BACK = 3, BUTTON_FORWARD = 4;

    private boolean mPolling;
    private boolean mLastNotifiedGrab;
    private boolean mGrabbed;
    private boolean mKeyboard;
    private double mLastX = Double.NaN, mLastY = Double.NaN;
    // Input diagnostics: what the CS control system handed to the engine (see HorizonGameActivity).
    private long mSentCursor, mSentLook, mSentButton, mSentKey, mSentScroll, mSentText;
    private double mLastCursorX = Double.NaN, mLastCursorY = Double.NaN;
    private int mGrabChanges;

    public boolean grabbed() { return mGrabbed; }
    /** The engine currently wants the soft keyboard (chat, sign, search...). */
    public boolean keyboard() { return mKeyboard; }
    /** Called on the UI thread when grab or keyboard state changes. */
    public void setStateListener(@androidx.annotation.Nullable Runnable listener) { mStateListener = listener; }
    @androidx.annotation.Nullable private Runnable mStateListener;

    /** One JSON object with the counters, for the input diagnostics file. */
    public org.json.JSONObject diagnostics() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("grabbed", mGrabbed);
            o.put("grab_changes", mGrabChanges);
            o.put("keyboard", mKeyboard);
            o.put("sent_cursor", mSentCursor);
            o.put("sent_look", mSentLook);
            o.put("sent_button", mSentButton);
            o.put("sent_key", mSentKey);
            o.put("sent_scroll", mSentScroll);
            o.put("sent_text", mSentText);
            o.put("last_cursor_x", Double.isNaN(mLastCursorX) ? null : mLastCursorX);
            o.put("last_cursor_y", Double.isNaN(mLastCursorY) ? null : mLastCursorY);
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    /** Start mirroring engine state (call on the UI thread when the activity is visible). */
    public void startPolling() {
        if (mPolling) return;
        mPolling = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stopPolling() {
        mPolling = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mPolling) return;
        int flags = HorizonNative.nativeHostPoll();
        boolean grabbed = (flags & 1) != 0;
        boolean keyboard = (flags & 2) != 0;
        if (grabbed != mGrabbed) {
            mGrabbed = grabbed;
            mGrabChanges++;
            mLastX = mLastY = Double.NaN;
            if (!grabbed) Platform.resetCursorPosition();
            Platform.grabStateChanged(grabbed);
        }
        boolean keyboardChanged = keyboard != mKeyboard;
        if (keyboardChanged) {
            mKeyboard = keyboard;
            if (keyboard) GameActivity.toggleKeyboardState(true, 0);
        }
        if ((grabbed != mLastNotifiedGrab || keyboardChanged) && mStateListener != null) {
            mLastNotifiedGrab = grabbed;
            mStateListener.run();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceCreated(Surface surface) {}

    @Override
    public void surfaceUpdated() {}

    @Override
    public void surfaceDestroyed() {}

    @Override
    public void sendMousePosition(double x, double y, boolean relative) {
        if (relative) {
            // CS accumulates the virtual pointer while grabbed; the engine wants deltas.
            if (!Double.isNaN(mLastX)) {
                double dx = x - mLastX, dy = y - mLastY;
                if (dx != 0 || dy != 0) {
                    mSentLook++;
                    HorizonNative.nativeHostLook(dx, dy);
                }
            }
            mLastX = x;
            mLastY = y;
        } else {
            mLastX = mLastY = Double.NaN;
            mSentCursor++;
            mLastCursorX = x;
            mLastCursorY = y;
            HorizonNative.nativeHostCursor(x, y);
        }
    }

    @Override
    public void sendMouseEvent(int button, int state, int mods, double x, double y, boolean relative) {
        int engineButton;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY: engineButton = BUTTON_LEFT; break;
            case MotionEvent.BUTTON_SECONDARY: engineButton = BUTTON_RIGHT; break;
            case MotionEvent.BUTTON_TERTIARY: engineButton = BUTTON_MIDDLE; break;
            case MotionEvent.BUTTON_BACK: engineButton = BUTTON_BACK; break;
            case MotionEvent.BUTTON_FORWARD: engineButton = BUTTON_FORWARD; break;
            default: return;
        }
        if (!relative) {
            mSentCursor++;
            mLastCursorX = x;
            mLastCursorY = y;
            HorizonNative.nativeHostCursor(x, y);
        }
        mSentButton++;
        HorizonNative.nativeHostButton(engineButton, state != 0);
    }

    @Override
    public boolean sendKeyEvent(int key, int state, int mods, char codepoint) {
        String token = HorizonKeyMap.token(key);
        if (token != null) {
            mSentKey++;
            HorizonNative.nativeHostKey(token, state != 0);
        }
        // Printable characters typed on a hardware keyboard go to the open text editor as text
        // (only while the engine asked for one, so the key that opens chat does not also type).
        if (mKeyboard && state != 0 && codepoint >= ' ' && codepoint != 127
                && (mods & LwjglGlfwKeycode.GLFW_MOD_CONTROL) == 0) {
            HorizonNative.nativeHostText(String.valueOf(codepoint));
        }
        return token != null || codepoint != 0;
    }

    @Override
    public boolean sendKeyEvent(int key, int state, int mods) {
        return sendKeyEvent(key, state, mods, (char) 0);
    }

    @Override
    public boolean sendKeyEvent(int key, boolean state, int mods) {
        return sendKeyEvent(key, state ? 1 : 0, mods, (char) 0);
    }

    @Override
    public void sendScrollEvent(double x, double y) {
        mSentScroll++;
        HorizonNative.nativeHostScroll((float) x, (float) y);
    }

    @Override
    public void sendBulkUnicodeEvent(String text, int mods) {
        if (text != null && !text.isEmpty()) {
            mSentText++;
            HorizonNative.nativeHostText(text);
        }
    }

    @Override
    public String backendName() {
        return "CS Engine";
    }

    @Override
    public void setHovered(boolean hovered) {}

    @Override
    public void setVisible(boolean visible) {}

    /** Drop every held key and button (activity paused, overlay hidden). */
    public void releaseAll() {
        mLastX = mLastY = Double.NaN;
        HorizonNative.nativeHostReleaseAll();
    }
}
