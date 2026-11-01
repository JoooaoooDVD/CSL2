package com.craftstudio.cslauncher.game;

import android.content.Context;
import android.util.AttributeSet;

import com.craftstudio.cslauncher.CallbackBridge;

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
