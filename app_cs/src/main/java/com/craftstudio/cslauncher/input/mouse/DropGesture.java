package com.craftstudio.cslauncher.input.mouse;


import android.os.Handler;
import android.view.KeyEvent;

import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import com.craftstudio.cslauncher.CallbackBridge;

public class DropGesture implements Runnable{
    private final Handler mHandler;
    private boolean mActive;

    public DropGesture(Handler mHandler) {
        this.mHandler = mHandler;
    }

    public void submit() {
        if(!mActive) {
            mActive = true;
            mHandler.postDelayed(this, LauncherPreferences.PREF_LONGPRESS_TRIGGER);
        }
    }

    public void cancel() {
        mActive = false;
        mHandler.removeCallbacks(this);
    }

    @Override
    public void run() {
        if(!mActive) return;
        CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_Q);
        mHandler.postDelayed(this, 250);
    }
}
