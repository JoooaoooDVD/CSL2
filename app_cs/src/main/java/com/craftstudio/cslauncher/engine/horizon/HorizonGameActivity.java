package com.craftstudio.cslauncher.engine.horizon;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CallbackBridge;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.game.GameActivity;
import com.craftstudio.cslauncher.game.HorizonInputView;
import com.craftstudio.cslauncher.game.InGUIEventProcessor;
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
    public static final String EXTRA_ACCOUNT_NAME = "cs_account_name";
    public static final String EXTRA_ACCOUNT_MICROSOFT = "cs_account_microsoft";
    public static final String EXTRA_ACCOUNT_KEY = "cs_account_key";
    private static final int REQUEST_IMPORT_SKIN = 0x5C1;
    private static final int REQUEST_IMPORT_CAPE = 0x5C2;

    /**
     * The manifest keeps the plain fullscreen framework theme a NativeActivity needs; everything the CS
     * control system inflates (Material switches in the button editor, dialogs, toasts) needs the
     * launcher's Material theme, which an inflated view only gets from its context.
     */
    private ContextThemeWrapper mUi;

    @Nullable private HorizonSkinDialog mSkinDialog;

    /**
     * CS Friends notices from the engine (invites, chat, requests) as system notifications, so a
     * player who switched apps sees them; tapping one brings the game back (the in-game popup has
     * the Join button). The game screen itself shows the same notice, nothing else lives here.
     */
    private static final String FRIENDS_CHANNEL = "cs_friends";
    private final android.os.Handler mNoticeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int mNoticeId = 7100;
    private boolean mNoticeChannelReady;
    private final Runnable mNoticePoll = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < 8; i++) {
                String notice;
                try { notice = HorizonNative.nativeHostNotice(); } catch (Throwable t) { notice = null; }
                if (notice == null || notice.isEmpty()) break;
                showFriendsNotification(notice);
            }
            mNoticeHandler.postDelayed(this, 2500);
        }
    };

    private void showFriendsNotification(@NonNull String notice) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            int nl = notice.indexOf('\n');
            String title = nl < 0 ? getString(R.string.horizon_friends_notice_title) : notice.substring(0, nl);
            String text = nl < 0 ? notice : notice.substring(nl + 1);
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!mNoticeChannelReady && android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(FRIENDS_CHANNEL,
                        getString(R.string.horizon_friends_channel), android.app.NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(c);
                mNoticeChannelReady = true;
            }
            Intent open = new Intent(this, HorizonGameActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, mNoticeId, open, flags);
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, FRIENDS_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi);
            nm.notify(mNoticeId++, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "friends notification failed", t);
        }
    }
    public static final String EXTRA_CONTROLS = "cs_controls";
    /** Written by the engine when it ends; CS reads it from the launcher process. */
    public static final String EXIT_FILE = "cs-exit.json";
    public static final String ERROR_FILE = "android-error.txt";

    private ControlLayout mControlLayout;
    private HorizonInputView mInputView;
    private View mMenuButton;
    private View mEditorExit;
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
        // CS Engine title screen: the skin panel's Customize button opens the Skin & Cape dialog
        mBackend.setSkinRequestListener(() -> {
            if (!isFinishing() && mSkinDialog == null) openSkinDialog();
        });

        // NativeActivity hands its window surface to the engine (takeSurface): the activity's own view
        // hierarchy is then never hardware-drawn, which is why controls added with addContentView were
        // invisible / only partially visible. The CS overlay therefore lives in its OWN translucent window
        // (application sub-window above the engine surface) with its own surface and input channel.
        mUi = new ContextThemeWrapper(this, R.style.AppTheme);
        OverlayRoot root = new OverlayRoot(mUi);
        View overlay = LayoutInflater.from(mUi).inflate(R.layout.activity_horizon_game, root, false);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlayRoot = root;
        mControlLayout = overlay.findViewById(R.id.main_control_layout);
        mInputView = overlay.findViewById(R.id.main_game_render_view);
        mMenuButton = overlay.findViewById(R.id.drawer_button);
        mEditorExit = overlay.findViewById(R.id.horizon_editor_exit);
        mEditorExit.setOnClickListener(v -> exitControlEditor());
        TouchCharInput charInput = overlay.findViewById(R.id.mainTouchCharInput);
        GameActivity.touchCharInput = charInput;
        mInputView.cursorView().setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        // The Mouse control button (SPECIALBTN_VIRTUALMOUSE) toggles the cursor GameActivity registered;
        // this activity runs in its own process, so it has to register its own cursor view or the
        // button does nothing.
        GameActivity.registerCursorView(mInputView.cursorView());
        // PojavLauncher behaviour: the Mouse button shows/hides the cursor in menus only; in menus
        // the finger moves it like a touchpad and a tap clicks; without it, taps land where the finger is.
        if (LauncherPreferences.PREF_VIRTUAL_MOUSE_START) mInputView.cursorView().setVisibility(View.VISIBLE);
        mControlLayout.setModifiable(false);
        mControlLayout.setMenuListener(this);
        mMenuButton.setOnClickListener(v -> onClickedMenu());
        Platform.resetCursorPosition();
        applyImmersive(getWindow().getDecorView());
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7101); } catch (Throwable ignored) {}
        }
        mNoticeHandler.removeCallbacks(mNoticePoll);
        mNoticeHandler.postDelayed(mNoticePoll, 4000);
    }

    private OverlayRoot mOverlayRoot;
    private boolean mOverlayAttached;

    /** Menu → Mouse & touch settings: applied live, saved in the launcher preferences. */
    private void openMouseSettings() {
        android.content.SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        android.widget.LinearLayout box = new android.widget.LinearLayout(mUi);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        android.widget.TextView speedLabel = new android.widget.TextView(mUi);
        android.widget.SeekBar speed = new android.widget.SeekBar(mUi);
        speed.setMax(290);
        speed.setProgress(prefs.getInt("mousespeed", 100) - 10);
        speedLabel.setText(getString(R.string.horizon_mouse_speed, prefs.getInt("mousespeed", 100)));
        speed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                int v = p + 10;
                speedLabel.setText(getString(R.string.horizon_mouse_speed, v));
                prefs.edit().putInt("mousespeed", v).apply();
                LauncherPreferences.PREF_MOUSESPEED = v / 100f;
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        android.widget.TextView sizeLabel = new android.widget.TextView(mUi);
        android.widget.SeekBar size = new android.widget.SeekBar(mUi);
        size.setMax(275);
        size.setProgress(prefs.getInt("mousescale", 100) - 25);
        sizeLabel.setText(getString(R.string.horizon_mouse_size, prefs.getInt("mousescale", 100)));
        size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                int v = p + 25;
                sizeLabel.setText(getString(R.string.horizon_mouse_size, v));
                prefs.edit().putInt("mousescale", v).apply();
                LauncherPreferences.PREF_MOUSESCALE = v / 100f;
                mInputView.cursorView().setCursorScale(v / 100f);
                mInputView.cursorView().invalidate();
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        box.addView(speedLabel); box.addView(speed);
        box.addView(sizeLabel); box.addView(size);
        android.widget.TextView hint = new android.widget.TextView(mUi);
        hint.setText(R.string.horizon_mouse_hint);
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);
        android.widget.ScrollView scroll = new android.widget.ScrollView(mUi);
        scroll.addView(box);
        new AlertDialog.Builder(mUi)
                .setTitle(R.string.horizon_menu_mouse_settings)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Report problem: one text file with the engine's own log (<home>/performance/diagnostic.log,
     * written by the engine, secrets redacted by it), the previous session's log tail, the exit
     * report, the input diagnostics and device info — shared over the system share sheet.
     */
    private void shareEngineReport() {
        writeInputDiagnostics("report");
        File home = HorizonEngine.home();
        StringBuilder sb = new StringBuilder();
        sb.append("CS Engine problem report\n");
        sb.append("time: ").append(new java.util.Date()).append('\n');
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" · Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        try { sb.append("engine: ").append(HorizonNative.load() ? HorizonNative.nativeVersion() : "not loaded").append('\n'); } catch (Throwable t) { sb.append("engine: ?\n"); }
        sb.append("profile: ").append(getIntent().getStringExtra(EXTRA_PROFILE)).append('\n');
        sb.append("cursor_shown: ").append(mInputView != null && mInputView.cursorView().getVisibility() == View.VISIBLE)
                .append(" grabbed: ").append(mBackend != null && mBackend.grabbed()).append('\n');
        appendFile(sb, new File(home, "cs-input-diagnostics.json"), 16 * 1024);
        appendFile(sb, new File(home, EXIT_FILE), 16 * 1024);
        appendFile(sb, new File(home, "android-error.txt"), 32 * 1024);
        appendFile(sb, new File(home, "performance/current-session.json"), 16 * 1024);
        appendFile(sb, new File(home, "performance/diagnostic.log"), 400 * 1024);
        appendFile(sb, new File(home, "performance/previous-diagnostic.log"), 120 * 1024);
        try {
            File dir = new File(getCacheDir(), "reports");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("reports dir");
            File out = new File(dir, "cs-engine-report.txt");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".updates", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.horizon_report_subject));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.horizon_menu_report)));
        } catch (Throwable t) {
            Toast.makeText(mUi, getString(R.string.skin_editor_error, t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static void appendFile(StringBuilder sb, File file, int tailBytes) {
        sb.append("\n===== ").append(file.getName()).append(" =====\n");
        if (!file.isFile()) {
            sb.append("(missing)\n");
            return;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - tailBytes);
            if (start > 0) sb.append("(last ").append(tailBytes / 1024).append(" KB of ").append(len / 1024).append(" KB)\n");
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            sb.append(new String(buf, "UTF-8")).append('\n');
        } catch (IOException e) {
            sb.append("(unreadable: ").append(e.getMessage()).append(")\n");
        }
    }

    private void openSkinDialog() {
        Intent intent = getIntent();
        String profile = intent.getStringExtra(EXTRA_PROFILE);
        if (profile == null) profile = "default";
        mSkinDialog = new HorizonSkinDialog(mUi, profile,
                intent.getStringExtra(EXTRA_ACCOUNT_NAME),
                intent.getBooleanExtra(EXTRA_ACCOUNT_MICROSOFT, false),
                intent.getStringExtra(EXTRA_ACCOUNT_KEY),
                cape -> {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/png");
                    try {
                        startActivityForResult(Intent.createChooser(pick, null), cape ? REQUEST_IMPORT_CAPE : REQUEST_IMPORT_SKIN);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(mUi, R.string.horizon_skin_no_picker, Toast.LENGTH_LONG).show();
                    }
                });
        mSkinDialog.setOnDismissListener(d -> mSkinDialog = null);
        mSkinDialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_SKIN && requestCode != REQUEST_IMPORT_CAPE) return;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null && mSkinDialog != null) mSkinDialog.onPicked(uri, requestCode == REQUEST_IMPORT_CAPE);
    }

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
            o.put("cursor_shown", mInputView != null && mInputView.cursorView().getVisibility() == View.VISIBLE);
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
                if (event.getAction() == KeyEvent.ACTION_DOWN) exitControlEditor();
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
                getString(mInputView != null && mInputView.cursorView().getVisibility() == View.VISIBLE
                        ? R.string.horizon_menu_mouse_off : R.string.horizon_menu_mouse_on),
                getString(R.string.horizon_menu_mouse_settings),
                getString(R.string.horizon_menu_toggle_controls),
                getString(R.string.horizon_menu_edit_controls),
                getString(R.string.horizon_menu_hud_editor),
                getString(R.string.horizon_menu_account_skin),
                getString(R.string.horizon_menu_report),
                getString(R.string.horizon_menu_exit)
        };
        new AlertDialog.Builder(mUi)
                .setTitle(R.string.horizon_menu_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: GameActivity.switchKeyboardState(false); break;
                        case 1: GameActivity.toggleMouse(mUi); break;
                        case 2: openMouseSettings(); break;
                        case 3: mControlLayout.toggleControlVisible(); break;
                        case 4: startControlEditor(); break;
                        case 5: hostCommand("cs:hud_edit"); break;
                        case 6: openSkinDialog(); break;
                        case 7: shareEngineReport(); break;
                        case 8:
                            // The engine saves the world (or leaves the server) and returns here.
                            hostCommand("cs:exit");
                            break;
                    }
                })
                .show();
    }

    /** A CS command token to the engine (game/cs_hud_edit.rs `cs_host_token`): a press and a release. */
    private static void hostCommand(String token) {
        HorizonNative.nativeHostKey(token, true);
        HorizonNative.nativeHostKey(token, false);
    }

    /** CS control editor over the engine: the game pauses behind a dimmed screen (`cs:blur`). */
    private void startControlEditor() {
        if (mControlLayout == null) return;
        HorizonNative.nativeHostKey("cs:blur", true);
        mControlLayout.setModifiable(true);
        if (mEditorExit != null) mEditorExit.setVisibility(View.VISIBLE);
    }

    private void exitControlEditor() {
        if (mControlLayout == null || !mControlLayout.getModifiable()) return;
        mControlLayout.askToExit(() -> {
            mControlLayout.setModifiable(false);
            loadControls();
            if (mEditorExit != null) mEditorExit.setVisibility(View.GONE);
            HorizonNative.nativeHostKey("cs:blur", false);
        });
    }

    @Override
    protected void onDestroy() {
        mNoticeHandler.removeCallbacks(mNoticePoll);
        writeInputDiagnostics("destroy");
        if (mSkinDialog != null) mSkinDialog.dismiss();
        detachOverlay();
        super.onDestroy();
        // The engine calls exit(0) itself after saving; make sure no cached process lingers if it did not.
        File exit = new File(HorizonEngine.home(), EXIT_FILE);
        Log.i(TAG, "Engine activity destroyed, exit report " + (exit.isFile() ? "present" : "missing"));
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
