package com.craftstudio.cslauncher.game;

import android.content.Context;
import android.util.AttributeSet;

import com.craftstudio.cslauncher.CallbackBridge;
import com.craftstudio.cslauncher.Tools;

/**
 * {@link GameView} without a rendering surface: the Horizon engine draws into the NativeActivity's
 * own window underneath, this view only turns CS touch input (camera, virtual cursor, gestures)
 * into {@link com.craftstudio.cslauncher.game.platform.Platform} events. Window and view share
 * the same pixel space, so the cursor ratio is 1.
 */
public class HorizonInputView extends GameView {
    public HorizonInputView(Context context) {
        this(context, null);
    }

    public HorizonInputView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setBackgroundColor(0);
        setWillNotDraw(true);
    }

    public GameCursorView cursorView() {
        return mCursorView;
    }

    /**
     * Physical mouse (USB / Bluetooth). The Java game sets pointer capture up in {@link #start},
     * which this view never calls (the engine owns the surface): without it the system pointer stayed
     * visible over the world, stopped at the screen edges and never turned into look deltas. Capture is
     * requested here, when the view joins the control window (and again whenever that window gains focus, by
     * {@code AndroidPointerCapture}'s focus listener)
     * (same {@code AndroidPointerCapture} as the Java game, so relative mouse and touchpad behave the
     * same in both editions: captured while playing, the CS cursor in menus).
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Tools.isAndroid8OrHigher()) {
            setFocusable(true);
            setFocusableInTouchMode(true);
            setUpPointerCapture();
            requestFocus();
            post(() -> {
                if (hasWindowFocus()) {
                    try { requestPointerCapture(); } catch (Throwable ignored) {}
                }
            });
        }
    }

    /** A primary-pointer touch the hotbar took: its MOVE/UP are swallowed until it lifts. */
    private boolean mHotbarTouch;

    /**
     * Hotbar taps (CS Engine): with the pointer captured (in the world), a touch that begins on the
     * engine's hotbar selects that slot — the engine's own touch controls do the same — and does not
     * start a camera drag or a click gesture.
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        int action = e.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            mHotbarTouch = false;
            if (com.craftstudio.cslauncher.game.platform.Platform.isGrabbing()
                    && e.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) {
                try {
                    if (com.craftstudio.cslauncher.engine.horizon.HorizonNative.nativeHostHotbarTap(e.getX(), e.getY()) >= 0) {
                        mHotbarTouch = true;
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Older engine build without the binding: plain touch.
                }
            }
        } else if (mHotbarTouch) {
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) mHotbarTouch = false;
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void refreshSize(boolean immediate) {
        int width = getWidth(), height = getHeight();
        if (width < 1 || height < 1) return;
        CallbackBridge.windowWidth = width;
        CallbackBridge.windowHeight = height;
        cursorRatioX = 1;
        cursorRatioY = 1;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refreshSize(true);
    }
}
