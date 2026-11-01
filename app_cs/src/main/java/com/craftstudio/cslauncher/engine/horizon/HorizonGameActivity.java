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
            Log.e(TAG, "libcs_engine.so is not available in this build");
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        mControlsPath = intent.getStringExtra(EXTRA_CONTROLS);
        super.onCreate(savedInstanceState);
        // Input goes to our views, not to the native glue (see class comment).
        getWindow().takeInputQueue(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The engine window and the CS control window must cover the same screen pixels: the control
        // window reports touches in its own coordinates and the engine maps them onto its render
        // target. Lay both out edge to edge, into the cutout, like the engine configures itself.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        Platform.initializeMinimal(getApplicationContext());
        mBackend = new HorizonBackend();
        Platform.setPlatformLibrary(mBackend);

        // NativeActivity hands its window surface to the engine (takeSurface): the activity's own view
        // hierarchy is then never hardware-drawn, which is why controls added with addContentView were
        // invisible / only partially visible. The CS overlay therefore lives in its OWN translucent window
        // (application sub-window above the engine surface) with its own surface and input channel.
        OverlayRoot root = new OverlayRoot(this);
        View overlay = LayoutInflater.from(this).inflate(R.layout.activity_horizon_game, root, false);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlayRoot = root;
        mControlLayout = overlay.findViewById(R.id.main_control_layout);
        mInputView = overlay.findViewById(R.id.main_game_render_view);
        mMenuButton = overlay.findViewById(R.id.drawer_button);
        TouchCharInput charInput = overlay.findViewById(R.id.mainTouchCharInput);
        GameActivity.touchCharInput = charInput;
        mInputView.cursorView().setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        // The Mouse control button (SPECIALBTN_VIRTUALMOUSE) toggles the cursor GameActivity registered;
        // this activity runs in its own process, so it has to register its own cursor view or the
        // button does nothing.
        GameActivity.registerCursorView(mInputView.cursorView());
        if (LauncherPreferences.PREF_VIRTUAL_MOUSE_START) mInputView.cursorView().setVisibility(View.VISIBLE);
        mControlLayout.setModifiable(false);
        mControlLayout.setMenuListener(this);
        mMenuButton.setOnClickListener(v -> onClickedMenu());
        Platform.resetCursorPosition();
        applyImmersive(getWindow().getDecorView());
    }

    private OverlayRoot mOverlayRoot;
    private boolean mOverlayAttached;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachOverlay();
    }

    /** Adds the CS control window once the activity window has a token (sub-windows need it). */
    private void attachOverlay() {
        if (mOverlayAttached || mOverlayRoot == null || isFinishing()) return;
        android.os.IBinder token = getWindow().getDecorView().getWindowToken();
        if (token == null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.token = token;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
        lp.setTitle("CS controls");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            getWindowManager().addView(mOverlayRoot, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot add the control window, falling back to the activity window", e);
            addContentView(mOverlayRoot, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mOverlayAttached = true;
        applyImmersive(mOverlayRoot);
        // Same sizing as the Java game: window metrics first, then the layout file (positions are
        // relative to CallbackBridge.windowWidth/Height, which refreshSize sets from the view size).
        mControlLayout.post(() -> {
            Tools.getDisplayMetrics(this);
            mInputView.refreshSize(true);
            loadControls();
        });
    }

    private void detachOverlay() {
        if (!mOverlayAttached || mOverlayRoot == null) return;
        mOverlayAttached = false;
        try {
            if (mOverlayRoot.getParent() != null && mOverlayRoot.getWindowToken() != null) {
                getWindowManager().removeViewImmediate(mOverlayRoot);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Fullscreen, no system bars, in both windows (the engine's and the control overlay's). */
    @SuppressWarnings("deprecation")
    private static void applyImmersive(View view) {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Root of the control window. Key events (back, hardware keyboard, gamepad) arrive here because the
     * focused window is this one, not the activity's; they are routed through the activity's dispatcher
     * so the CS key handling stays in one place.
     */
    private final class OverlayRoot extends android.widget.FrameLayout {
        OverlayRoot(android.content.Context context) {
            super(context);
            setBackgroundColor(0x00000000);
            setFitsSystemWindows(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (GameActivity.touchCharInput != null && GameActivity.touchCharInput.hasFocus()
                    && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event);
            }
            return HorizonGameActivity.this.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) applyImmersive(this);
        }
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
        writeInputDiagnostics("pause");
        super.onPause();
    }

    /**
     * Input diagnostics: device, display, both windows' sizes, the control system's state and the
     * counters of what was forwarded to the engine. One small JSON file in the engine home
     * ({@code cs-input-diagnostics.json}, next to {@code cs-exit.json}, which carries the engine's own
     * "received / processed / pointer mapping" counters). Written at pause and exit, not per event.
     */
    private void writeInputDiagnostics(String reason) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("reason", reason);
            o.put("time", System.currentTimeMillis());
            o.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            o.put("android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            o.put("display", dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi + "dpi");
            o.put("orientation", getResources().getConfiguration().orientation);
            View decor = getWindow().getDecorView();
            o.put("engine_window", decor.getWidth() + "x" + decor.getHeight());
            if (mOverlayRoot != null) o.put("control_window", mOverlayRoot.getWidth() + "x" + mOverlayRoot.getHeight());
            if (mInputView != null) o.put("input_view", mInputView.getWidth() + "x" + mInputView.getHeight());
            o.put("callback_window", CallbackBridge.windowWidth + "x" + CallbackBridge.windowHeight);
            o.put("overlay_attached", mOverlayAttached);
            o.put("overlay_focus", mOverlayRoot != null && mOverlayRoot.hasWindowFocus());
            o.put("engine_focus", decor.hasWindowFocus());
            o.put("grabbing", Platform.isGrabbing());
            o.put("cursor_x", Platform.cursorX);
            o.put("cursor_y", Platform.cursorY);
            if (mInputView != null) o.put("cursor_visible", mInputView.cursorView().getVisibility() == View.VISIBLE);
            o.put("controls_visible", mControlLayout != null && mControlLayout.areControlVisible());
            o.put("controls", mControlsPath);
            if (mBackend != null) o.put("backend", mBackend.diagnostics());
            File file = new File(HorizonEngine.home(), "cs-input-diagnostics.json");
            Tools.write(file.getAbsolutePath(), o.toString(2));
            Log.i(TAG, "Input diagnostics (" + reason + "): " + o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "Input diagnostics not written", t);
        }
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
        writeInputDiagnostics("destroy");
        detachOverlay();
        super.onDestroy();
        // The engine calls exit(0) itself after saving; make sure no cached process lingers if it did not.
        File exit = new File(HorizonEngine.home(), EXIT_FILE);
        Log.i(TAG, "Engine activity destroyed, exit report " + (exit.isFile() ? "present" : "missing"));
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
