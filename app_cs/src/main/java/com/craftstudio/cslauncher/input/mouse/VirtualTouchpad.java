package com.craftstudio.cslauncher.input.mouse;

/**
 * Laptop-touchpad semantics for the virtual mouse, independent of Android classes so it can be unit
 * tested. The finger never positions the cursor: only finger <em>movement</em> moves it (relative
 * deltas), lifting the finger changes nothing, a quick still tap clicks at the cursor, a quick still
 * two-finger tap right-clicks, and two moving fingers scroll. Changing the number of fingers only
 * re-anchors the tracked position (no jump).
 *
 * The owner feeds raw pointer-0 coordinates of each gesture phase; speed scaling and the actual
 * mouse messages live in the {@link Sink}.
 */
public final class VirtualTouchpad {
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;

    public interface Sink {
        /** Relative cursor motion in raw pixels (apply the mouse-speed preference). */
        void move(float dx, float dy);
        /** A complete click (press + release) at the current cursor position. */
        void click(int button);
        /** Two-finger scroll motion in raw pixels. */
        void scroll(float dx, float dy);
        /** Button held / released (double-tap-and-hold drag). */
        void press(int button, boolean down);
    }

    /** Options, changeable at runtime (in-game Mouse settings). */
    public boolean tapToClick = true;
    public boolean twoFingerRightClick = true;
    public boolean doubleTapDrag = true;

    private final Sink mSink;
    private final float mTapSlop;
    private final long mTapMaxMs;

    private boolean mActive;
    private long mDownTime;
    private int mPointers, mMaxPointers;
    private float mAnchorX, mAnchorY;
    private float mTravel;
    private long mLastClickTime = Long.MIN_VALUE;
    private boolean mDragging;

    public VirtualTouchpad(Sink sink, float tapSlopPx, long tapMaxMs) {
        mSink = sink;
        mTapSlop = tapSlopPx;
        mTapMaxMs = tapMaxMs;
    }

    /** First finger down. */
    public void down(long time, float x, float y) {
        mActive = true;
        mDownTime = time;
        mPointers = mMaxPointers = 1;
        mAnchorX = x;
        mAnchorY = y;
        mTravel = 0;
        // Double-tap-and-hold: a second touch right after a left click holds the button (drag).
        if (doubleTapDrag && mLastClickTime != Long.MIN_VALUE && time - mLastClickTime <= mTapMaxMs) {
            mDragging = true;
            mLastClickTime = Long.MIN_VALUE;
            mSink.press(BUTTON_LEFT, true);
        }
    }

    /** The number of fingers changed (pointer down/up); x,y = pointer 0 now. No motion is emitted. */
    public void pointerCount(int count, float x, float y) {
        if (!mActive) return;
        mPointers = count;
        if (count > mMaxPointers) mMaxPointers = count;
        mAnchorX = x;
        mAnchorY = y;
    }

    /** Pointer 0 moved to x,y with {@code count} fingers on the screen. */
    public void move(float x, float y, int count) {
        if (!mActive) return;
        if (count != mPointers) {
            pointerCount(count, x, y);
            return;
        }
        float dx = x - mAnchorX, dy = y - mAnchorY;
        mAnchorX = x;
        mAnchorY = y;
        mTravel += Math.abs(dx) + Math.abs(dy);
        if (dx == 0 && dy == 0) return;
        if (count >= 2) mSink.scroll(dx, dy);
        else mSink.move(dx, dy);
    }

    /** Last finger up: a still, short gesture is a click; everything else just ends. */
    public void up(long time) {
        if (!mActive) return;
        mActive = false;
        if (mDragging) {
            mDragging = false;
            mSink.press(BUTTON_LEFT, false);
            return;
        }
        boolean still = mTravel <= mTapSlop;
        boolean quick = time - mDownTime <= mTapMaxMs;
        if (!still || !quick) return;
        if (mMaxPointers >= 2) {
            if (twoFingerRightClick) mSink.click(BUTTON_RIGHT);
        } else if (tapToClick) {
            mSink.click(BUTTON_LEFT);
            mLastClickTime = time;
        }
    }

    /** Gesture cancelled by the system: a held button is released, nothing else is emitted. */
    public void cancel() {
        mActive = false;
        if (mDragging) {
            mDragging = false;
            mSink.press(BUTTON_LEFT, false);
        }
    }

    public boolean isDragging() {
        return mDragging;
    }

    public boolean isActive() {
        return mActive;
    }
}
