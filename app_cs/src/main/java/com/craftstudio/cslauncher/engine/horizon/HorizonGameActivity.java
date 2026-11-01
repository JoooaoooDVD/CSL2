package com.craftstudio.cslauncher.engine.horizon;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CallbackBridge;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.game.GameActivity;
import com.craftstudio.cslauncher.game.HorizonInputView;
import com.craftstudio.cslauncher.game.platform.Platform;
import com.craftstudio.cslauncher.input.ControlButtonMenuListener;
import com.craftstudio.cslauncher.input.ControlLayout;
import com.craftstudio.cslauncher.input.keyboard.TouchCharInput;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;

/**
 * Hosts the Horizon engine window and the CS control system on top of it.
 *
 * The Rust side ({@code android_main} with the {@code cs-host} feature) reads the {@code cs_launch}
 * intent extra, runs the game and ends the process when the player leaves. Lives in its own process
 * ({@code :horizon}) so that neither the launcher nor the Java game process is affected.
 *
 * Input: NativeActivity normally takes the window's input queue away from the view hierarchy; this
 * activity gives it back, so touches reach the CS {@link ControlLayout} (same layouts, buttons,
 * joystick, opacity, profiles as the Java game) and are forwarded to the engine through
 * {@link HorizonBackend}. The engine draws none of its own touch controls in host mode.
 */
public class HorizonGameActivity extends NativeActivity implements ControlButtonMenuListener {
    private static final String TAG = "HorizonGameActivity";
    public static final String EXTRA_PROFILE = "cs_profile";
    public static final String EXTRA_CONTROLS = "cs_controls";
    /** Written by the engine when it ends; CS reads it from the launcher process. */
    public static final String EXIT_FILE = "cs-exit.json";
    public static final String ERROR_FILE = "android-error.txt";

    private ControlLayout mControlLayout;
    private HorizonInputView mInputView;
    private View mMenuButton;
    private HorizonBackend mBackend;
    private String mControlsPath;

    static {
        // NativeActivity loads android.app.lib_name itself; loading here surfaces a missing library early.
        HorizonNative.load();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Tools.DIR_GAME_HOME == null && Tools.checkStorageRoot(this)) {
            Tools.initStorageConstants(this);
            LauncherPreferences.loadPreferences(this);
        }
        Intent intent = getIntent();
        if (intent == null || intent.getStringExtra(HorizonLaunch.EXTRA) == null) {
            Log.e(TAG, "Started without a cs_launch request");
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        if (!HorizonNative.load()) {
            Log.e(TAG, "libhorizon_android.so is not available in this build");
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        mControlsPath = intent.getStringExtra(EXTRA_CONTROLS);
        super.onCreate(savedInstanceState);
        // Input goes to our views, not to the native glue (see class comment).
        getWindow().takeInputQueue(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Platform.initializeMinimal(getApplicationContext());
        mBackend = new HorizonBackend();
        Platform.setPlatformLibrary(mBackend);

        View overlay = LayoutInflater.from(this).inflate(R.layout.activity_horizon_game, null);
        addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mControlLayout = overlay.findViewById(R.id.main_control_layout);
        mInputView = overlay.findViewById(R.id.main_game_render_view);
        mMenuButton = overlay.findViewById(R.id.drawer_button);
        TouchCharInput charInput = overlay.findViewById(R.id.mainTouchCharInput);
        GameActivity.touchCharInput = charInput;
        mInputView.cursorView().setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        mControlLayout.setModifiable(false);
        mControlLayout.setMenuListener(this);
        mMenuButton.setOnClickListener(v -> onClickedMenu());
        Platform.resetCursorPosition();
        mControlLayout.post(() -> {
            Tools.getDisplayMetrics(this);
            mInputView.refreshSize(true);
            loadControls();
        });
    }

    private void loadControls() {
        if (mControlLayout == null) return;
        try {
            String path = Tools.isValidString(mControlsPath) ? mControlsPath : LauncherPreferences.PREF_DEFAULTCTRL_PATH;
            mControlLayout.loadLayout(path);
        } catch (IOException e) {
            Log.w(TAG, "Unable to load the control file, loading the default now", e);
            try {
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mMenuButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        mControlLayout.toggleControlVisible();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBackend != null) mBackend.startPolling();
    }

    @Override
    protected void onPause() {
        if (mBackend != null) {
            mBackend.stopPolling();
            mBackend.releaseAll();
        }
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mInputView == null) return super.dispatchKeyEvent(event);
        if (mControlLayout.getModifiable()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(() -> {
                    mControlLayout.setModifiable(false);
                    loadControls();
                });
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handled = mInputView.processKeyEvent(event);
        if (!handled && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && (GameActivity.touchCharInput == null || !GameActivity.touchCharInput.isEnabled())) {
            if (event.getAction() == KeyEvent.ACTION_UP) CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
            return true;
        }
        return handled || super.dispatchKeyEvent(event);
    }

    /** In-game menu (Phase 2/4 minimum; the full CS drawer menu comes with the Horizon UI phase). */
    @Override
    public void onClickedMenu() {
        String[] items = {
                getString(R.string.horizon_menu_keyboard),
                getString(R.string.horizon_menu_toggle_controls),
                getString(R.string.horizon_menu_edit_controls),
                getString(R.string.horizon_menu_exit)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.horizon_menu_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: GameActivity.switchKeyboardState(false); break;
                        case 1: mControlLayout.toggleControlVisible(); break;
                        case 2: mControlLayout.setModifiable(true); break;
                        case 3:
                            // Escape twice reaches the pause menu; the engine saves and exits on "Save and Quit".
                            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
                            break;
                    }
                })
                .show();
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
