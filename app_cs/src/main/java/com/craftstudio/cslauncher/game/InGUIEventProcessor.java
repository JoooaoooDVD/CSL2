package com.craftstudio.cslauncher.game;

import android.view.MotionEvent;
import android.view.View;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.input.mouse.LeftClickGesture;
import com.craftstudio.cslauncher.input.mouse.PointerTracker;
import com.craftstudio.cslauncher.input.mouse.Scroller;
import com.craftstudio.cslauncher.input.mouse.TapDetector;
import com.craftstudio.cslauncher.input.mouse.VirtualTouchpad;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import com.craftstudio.cslauncher.CallbackBridge;

public class InGUIEventProcessor extends TouchEventProcessor {
    public static final float FINGER_SCROLL_THRESHOLD = Tools.dpToPx(6);
    public static final float FINGER_STILL_THRESHOLD = Tools.dpToPx(5);

    private final PointerTracker mTracker = new PointerTracker();
    private final TapDetector mSingleTapDetector;
    private View mTouchpad;
    private boolean mIsMouseDown = false;
    private float mStartX, mStartY;
    private final Scroller mScroller = new Scroller(FINGER_SCROLL_THRESHOLD);

    public InGUIEventProcessor(GameView hostView) {
        super(hostView);
        mSingleTapDetector = new TapDetector(1, TapDetector.DETECTION_METHOD_BOTH);
    }

    /**
     * Virtual-mouse (touchpad) mode: the finger never positions the cursor. Relative movement only,
     * tap = left click at the cursor, two-finger tap = right click, two moving fingers = scroll.
     */
    private final VirtualTouchpad mVirtualTouchpad = new VirtualTouchpad(new VirtualTouchpad.Sink() {
        @Override
        public void move(float dx, float dy) {
            applyMoveVector(dx, dy);
        }

        @Override
        public void click(int button) {
            CallbackBridge.performClick(button == VirtualTouchpad.BUTTON_RIGHT
                    ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY);
        }

        @Override
        public void scroll(float dx, float dy) {
            mScroller.performScroll(dx, dy);
        }

        @Override
        public void press(int button, boolean down) {
            CallbackBridge.sendMouseButton(button == VirtualTouchpad.BUTTON_RIGHT
                    ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY, down);
        }
    }, FINGER_STILL_THRESHOLD, TAP_MAX_MS);

    /** Re-reads the touchpad options (in-game Mouse settings). */
    public void refreshTouchpadOptions() {
        mVirtualTouchpad.tapToClick = LauncherPreferences.PREF_VMOUSE_TAP_CLICK;
        mVirtualTouchpad.twoFingerRightClick = LauncherPreferences.PREF_VMOUSE_TWO_FINGER_RIGHT;
        mVirtualTouchpad.doubleTapDrag = LauncherPreferences.PREF_VMOUSE_DOUBLE_TAP_DRAG;
    }
    private static final long TAP_MAX_MS = 300;

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        if (touchpadDisplayed() || mVirtualTouchpad.isActive()) {
            if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) refreshTouchpadOptions();
            processTouchpadEvent(motionEvent);
            return true;
        }
        boolean singleTap = mSingleTapDetector.onTouchEvent(motionEvent);

        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracker.startTracking(motionEvent);
                sendTouchCoordinates(motionEvent.getX(), motionEvent.getY());

                // disabled gestures means no scrolling possible, send gesture early
                if (LauncherPreferences.PREF_DISABLE_GESTURES) enableMouse();
                else setGestureStart(motionEvent);
                break;

            case MotionEvent.ACTION_MOVE:
                int pointerCount = motionEvent.getPointerCount();
                int pointerIndex = mTracker.trackEvent(motionEvent);
                if(pointerCount == 1 || LauncherPreferences.PREF_DISABLE_GESTURES) {
                    float mainPointerX = motionEvent.getX(pointerIndex);
                    float mainPointerY = motionEvent.getY(pointerIndex);
                    sendTouchCoordinates(mainPointerX, mainPointerY);

                    if(!mIsMouseDown) {
                        if(!hasGestureStarted()) setGestureStart(motionEvent);
                        if(!LeftClickGesture.isFingerStill(mStartX, mStartY, mainPointerX, mainPointerY, FINGER_STILL_THRESHOLD))
                            enableMouse();
                    }
                } else mScroller.performScroll(mTracker.getMotionVector());
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mScroller.resetScrollOvershoot();
                mTracker.cancelTracking();

                // Handle single tap on gestures
                if(!LauncherPreferences.PREF_DISABLE_GESTURES && !mIsMouseDown && singleTap) {
                    CallbackBridge.performClick(MotionEvent.BUTTON_PRIMARY);
                }

                if(mIsMouseDown) disableMouse();
                resetGesture();
        }


        return true;
    }

    private void processTouchpadEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mVirtualTouchpad.down(e.getEventTime(), e.getX(0), e.getY(0));
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP: {
                // Pointer 0 after this event: on a pointer-up of index 0 the next pointer takes over.
                int count = e.getPointerCount();
                int index = 0;
                if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                    count--;
                    if (e.getActionIndex() == 0 && e.getPointerCount() > 1) index = 1;
                }
                mVirtualTouchpad.pointerCount(count, e.getX(index), e.getY(index));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                mVirtualTouchpad.move(e.getX(0), e.getY(0), e.getPointerCount());
                break;
            case MotionEvent.ACTION_UP:
                mScroller.resetScrollOvershoot();
                mVirtualTouchpad.up(e.getEventTime());
                break;
            case MotionEvent.ACTION_CANCEL:
                mScroller.resetScrollOvershoot();
                mVirtualTouchpad.cancel();
                break;
        }
    }

    private boolean touchpadDisplayed() {
        return mTouchpad != null && mTouchpad.getVisibility() == View.VISIBLE;
    }

    public void setAbstractTouchpad(View touchpad) {
        mTouchpad = touchpad;
    }

    private void enableMouse() {
        CallbackBridge.sendMouseButton(MotionEvent.BUTTON_PRIMARY, true);
        mIsMouseDown = true;
    }

    private void disableMouse() {
        CallbackBridge.sendMouseButton(MotionEvent.BUTTON_PRIMARY, false);
        mIsMouseDown = false;
    }

    private void setGestureStart(MotionEvent event) {
        mStartX = event.getX();
        mStartY = event.getY();
    }

    private void resetGesture() {
        mStartX = mStartY = -1;
    }

    private boolean hasGestureStarted() {
        return mStartX != -1 || mStartY != -1;
    }

    @Override
    public void cancelPendingActions() {
        mScroller.resetScrollOvershoot();
        mVirtualTouchpad.cancel();
        if(mIsMouseDown) disableMouse();
    }
}
