package com.craftstudio.cslauncher.engine.horizon;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;

import java.io.File;

/**
 * Hosts the Horizon engine window. The Rust side ({@code android_main} with the {@code cs-host}
 * feature) reads the {@code cs_launch} intent extra, runs the game and ends the process when the
 * player leaves. Lives in its own process ({@code :horizon}) so that neither the launcher nor the
 * Java game process is affected by the engine's lifecycle.
 *
 * Phase 2: engine host only. CS overlay (controls, menu, keyboard) is added in Phase 4.
 */
public class HorizonGameActivity extends NativeActivity {
    private static final String TAG = "HorizonGameActivity";
    public static final String EXTRA_PROFILE = "cs_profile";
    /** Written by the engine when it ends; CS reads it from the launcher process. */
    public static final String EXIT_FILE = "cs-exit.json";
    public static final String ERROR_FILE = "android-error.txt";

    static {
        // NativeActivity loads android.app.lib_name itself; loading here surfaces a missing library early.
        HorizonNative.load();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Tools.DIR_GAME_HOME == null && Tools.checkStorageRoot(this)) Tools.initStorageConstants(this);
        Intent intent = getIntent();
        if (intent == null || intent.getStringExtra(HorizonLaunch.EXTRA) == null) {
            Log.e(TAG, "Started without a cs_launch request");
            finish();
            return;
        }
        if (!HorizonNative.load()) {
            Log.e(TAG, "libhorizon_android.so is not available in this build");
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The engine calls exit(0) itself after saving; make sure no cached process lingers if it did not.
        File exit = new File(HorizonEngine.home(), EXIT_FILE);
        Log.i(TAG, "Engine activity destroyed, exit report " + (exit.isFile() ? "present" : "missing"));
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
