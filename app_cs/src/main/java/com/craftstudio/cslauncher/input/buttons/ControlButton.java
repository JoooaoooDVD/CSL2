package com.craftstudio.cslauncher.input.buttons;

import static com.craftstudio.cslauncher.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.craftstudio.cslauncher.game.GameActivity;

import com.craftstudio.cslauncher.R;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.input.ControlData;
import com.craftstudio.cslauncher.input.ControlLayout;
import com.craftstudio.cslauncher.input.handleview.EditControlSideDialog;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.FpsMeter;

import com.craftstudio.cslauncher.CallbackBridge;

import static com.craftstudio.cslauncher.input.buttons.BackgroundTint.DEFAULT_TINT_LIST;
import static com.craftstudio.cslauncher.input.buttons.BackgroundTint.TOGGLE_TINT_LIST;
import static com.craftstudio.cslauncher.game.platform.Platform.PLATFORM;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;
    private boolean mHasBitmap;

    protected boolean mIsToggled = false;

    /* FPS counter mode: the button becomes a live read-out instead of a key */
    private boolean mIsFps;
    private FpsMeter mFpsMeter;
    private int mFps = -1;
    private final Paint mFpsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFpsBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF mFpsRect = new android.graphics.RectF();
    private final Runnable mFpsTick = new Runnable() {
        @Override
        public void run() {
            if (!mIsFps || !isAttachedToWindow()) return;
            if (mFpsMeter == null) mFpsMeter = new FpsMeter();
            int fps = mFpsMeter.sample();
            if (fps != mFps) {
                mFps = fps;
                invalidate();
            }
            postDelayed(this, 500);
        }
    };

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    private void setupBitmapTint() {
        // Image buttons draw their own pressed/toggled overlay (clipped to the corner radius),
        // exactly like colour buttons, so no tint list is needed any more.
        setBackgroundTintList(null);
        setupNormalTint();
    }

    private void setupNormalTint() {
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);
        setBackgroundTintList(null);
        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_TOGGLE_TINT_ALPHA);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_DEFAULT_TINT_ALPHA);
        }
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);

        mHasBitmap = Tools.isValidString(mProperties.bitmapTag);

        if(mHasBitmap) setupBitmapTint();
        else setupNormalTint();

        boolean wasFps = mIsFps;
        mIsFps = properties.isFpsCounter();
        setText(mIsFps ? "" : properties.name);
        if (mIsFps && !wasFps && isAttachedToWindow()) startFpsUpdates();
        if (!mIsFps && wasFps) stopFpsUpdates();
        if (mIsFps) invalidate();
    }

    /** Whether this control is a live FPS counter */
    public boolean isFpsCounter() {
        return mIsFps;
    }

    private void startFpsUpdates() {
        removeCallbacks(mFpsTick);
        if (mFpsMeter == null) mFpsMeter = new FpsMeter();
        post(mFpsTick);
    }

    private void stopFpsUpdates() {
        removeCallbacks(mFpsTick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mIsFps) startFpsUpdates();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFpsUpdates();
        super.onDetachedFromWindow();
    }

    private static int fpsTierColor(int fps) {
        if (fps < 0) return 0xFFB8C2D1;
        if (fps >= 50) return 0xFF4ADE80;
        if (fps >= 30) return 0xFFFACC15;
        return 0xFFF87171;
    }

    /** Draws the FPS read-out in the selected style, fitting the current view bounds */
    private void drawFps(Canvas canvas) {
        boolean editing = getControlLayoutParent() != null && getControlLayoutParent().getModifiable();
        int fps = mFps;
        if (editing && fps < 0) fps = 60; // Preview value in the editor
        String number = fps < 0 ? "--" : String.valueOf(fps);
        int tier = fpsTierColor(fps);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        int style = mProperties.fpsStyle;
        mFpsPaint.setTextAlign(Paint.Align.CENTER);

        switch (style) {
            default:
            case ControlData.FPS_STYLE_CLASSIC: {
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mFpsPaint.setColor(Color.WHITE);
                String text = number + " FPS";
                fitText(text, w * 0.85f, h * 0.5f);
                canvas.drawText(text, cx, cy - (mFpsPaint.ascent() + mFpsPaint.descent()) / 2f, mFpsPaint);
                break;
            }
            case ControlData.FPS_STYLE_MINIMAL: {
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mFpsPaint.setColor(tier);
                fitText(number, w * 0.8f, h * 0.7f);
                canvas.drawText(number, cx, cy - (mFpsPaint.ascent() + mFpsPaint.descent()) / 2f, mFpsPaint);
                break;
            }
            case ControlData.FPS_STYLE_STACKED: {
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mFpsPaint.setColor(tier);
                fitText(number, w * 0.8f, h * 0.55f);
                float numH = -mFpsPaint.ascent();
                mFpsPaint.setColor(tier);
                canvas.drawText(number, cx, h * 0.08f + numH, mFpsPaint);
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                mFpsPaint.setColor(0xCCFFFFFF);
                fitText("FPS", w * 0.6f, h * 0.22f);
                canvas.drawText("FPS", cx, h * 0.92f - mFpsPaint.descent(), mFpsPaint);
                break;
            }
            case ControlData.FPS_STYLE_DETAILED: {
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mFpsPaint.setColor(tier);
                String main = number + " FPS";
                fitText(main, w * 0.85f, h * 0.42f);
                canvas.drawText(main, cx, h * 0.1f - mFpsPaint.ascent(), mFpsPaint);
                int min = mFpsMeter == null ? -1 : mFpsMeter.getMin();
                int max = mFpsMeter == null ? -1 : mFpsMeter.getMax();
                if (editing && min < 0) { min = 55; max = 62; }
                String sub = (min < 0 ? "--" : String.valueOf(min)) + " min  ·  " + (max < 0 ? "--" : String.valueOf(max)) + " max";
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                mFpsPaint.setColor(0xB3FFFFFF);
                fitText(sub, w * 0.9f, h * 0.24f);
                canvas.drawText(sub, cx, h * 0.92f - mFpsPaint.descent(), mFpsPaint);
                break;
            }
            case ControlData.FPS_STYLE_METER: {
                mFpsPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mFpsPaint.setColor(Color.WHITE);
                String main = number + " FPS";
                fitText(main, w * 0.85f, h * 0.46f);
                canvas.drawText(main, cx, h * 0.1f - mFpsPaint.ascent(), mFpsPaint);
                float pad = w * 0.1f;
                float barH = Math.max(Tools.dpToPx(3), h * 0.1f);
                float top = h * 0.84f - barH;
                mFpsRect.set(pad, top, w - pad, top + barH);
                mFpsBarPaint.setColor(0x33FFFFFF);
                canvas.drawRoundRect(mFpsRect, barH, barH, mFpsBarPaint);
                float ratio = fps < 0 ? 0f : Math.min(1f, fps / 120f);
                if (ratio > 0f) {
                    mFpsRect.right = pad + (w - pad * 2) * Math.max(ratio, 0.04f);
                    mFpsBarPaint.setColor(tier);
                    canvas.drawRoundRect(mFpsRect, barH, barH, mFpsBarPaint);
                }
                break;
            }
        }
    }

    /** Sets the paint text size so that text fits both the width and height budgets */
    private void fitText(String text, float maxWidth, float maxHeight) {
        float size = Math.max(maxHeight, 4f);
        mFpsPaint.setTextSize(size);
        float measured = mFpsPaint.measureText(text);
        if (measured > maxWidth && measured > 0) {
            mFpsPaint.setTextSize(Math.max(4f, size * maxWidth / measured));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mIsFps) {
            drawFps(canvas);
            return;
        }
        if(!isActivated()) return;
        // A control that collapsed to a sliver (old resize bug) would render as a stray line
        if(getWidth() < Tools.dpToPx(24) || getHeight() < Tools.dpToPx(24)) return;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }

    @Override
    public boolean isActivated() {
        // Any possible side effects?
        return super.isActivated() || (mProperties.isToggle && mIsToggled);
    }

    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        ControlLayout parent = getControlLayoutParent();
        if(parent == null) return;
        parent.getLayout().mControlDataList.remove(getProperties());
        parent.removeView(this);
    }

    @Override
    public void handlePressed() {
        if(!getProperties().isToggle){
            sendKeyPresses(true);
        }
    }

    @Override
    public void handleReleased() {
        if(!triggerToggle()) {
            sendKeyPresses(false);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ControlData properties = getProperties();
        int action = event.getActionMasked();
        if (mIsFps) {
            // A read-out is not a key: hand the touch straight to the game
            ControlLayout layout = getControlLayoutParent();
            View gameSurface = layout == null ? null : layout.getGameSurface();
            if (gameSurface != null) gameSurface.dispatchTouchEvent(event);
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(properties.passThruEnabled){
                    //Send the event to be taken as a mouse action
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                break;
        }

        if(getProperties().isSwipeable) {
            getControlLayoutParent().onTouch(this, event);
            return true;
        }

        switch (action){
            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                handlePressed();
                break;
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                handleReleased();
                break;
            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= KeyEvent.KEYCODE_UNKNOWN){
                CallbackBridge.setModifiers(keycode, isDown);
                int modifiers = CallbackBridge.getCurrentMods();
                PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, modifiers);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) GameActivity.switchKeyboardState(false);
                break;

            case ControlData.SPECIALBTN_KEYBOARDPAN:
                if(isDown) GameActivity.switchKeyboardState(true);
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) GameActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(MotionEvent.BUTTON_PRIMARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(MotionEvent.BUTTON_TERTIARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;
            case ControlData.SPECIALBTN_FPS:
                // Display only
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
