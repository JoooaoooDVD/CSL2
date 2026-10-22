package com.craftstudio.cslauncher.game;

import com.craftstudio.cslauncher.game.resources.ResourceBrowserPopup;


import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import static com.craftstudio.cslauncher.Tools.dialogForceClose;
import static com.craftstudio.cslauncher.game.platform.Platform.PLATFORM;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_SUSTAINED_PERFORMANCE;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import com.craftstudio.cslauncher.BaseActivity;
import com.craftstudio.cslauncher.CallbackBridge;
import com.craftstudio.cslauncher.game.renderer.GameRenderer;
import com.craftstudio.cslauncher.utils.GpuUtils;
import com.craftstudio.cslauncher.utils.KeycodeUtils;
import com.craftstudio.cslauncher.Logger;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.input.ControlButtonMenuListener;
import com.craftstudio.cslauncher.input.ControlData;
import com.craftstudio.cslauncher.input.ControlDrawerData;
import com.craftstudio.cslauncher.input.ControlJoystickData;
import com.craftstudio.cslauncher.input.ControlLayout;
import com.craftstudio.cslauncher.input.CustomControls;
import com.craftstudio.cslauncher.input.EditorExitable;
import com.craftstudio.cslauncher.input.keyboard.TouchCharInput;
import com.craftstudio.cslauncher.input.mouse.GyroControl;
import com.craftstudio.cslauncher.input.mouse.HotbarView;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.utils.lifecycle.ContextExecutor;
import com.craftstudio.cslauncher.game.platform.Platform;
import com.craftstudio.cslauncher.game.platform.backend.DummyBackend;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.QuickSettingSideDialog;
import com.craftstudio.cslauncher.services.GameService;
import com.craftstudio.cslauncher.tasks.AsyncAssetManager;
import com.craftstudio.cslauncher.utils.JREUtils;
import com.craftstudio.cslauncher.utils.MCOptionUtils;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.game.renderer.impl.GLESRenderSpec;
import com.craftstudio.cslauncher.utils.jre.GameRunner;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

import com.craftstudio.cslauncher.R;

public class GameActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection {
    public static final String INTENT_LAUNCH_VERSION = "intent_version";
    public static final String INTENT_LAUNCH_CLASSPATH = "intent_classpath";
    public static final String INTENT_LAUNCH_ACCOUNT = "intent_account_file";

    public static TouchCharInput touchCharInput;
    private GameView launcherGLView;
    private static WeakReference<GameCursorView> weakCursor;
    private LoggerView loggerView;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPanel, mDrawerLogo, mDrawerTitles;
    private TextView mDrawerTitle, mDrawerSubtitle;
    private CharSequence mGameSubtitle = "";
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    private ControlLayout mControlLayout;
    private HotbarView mHotbarView;
    private View mLoadingScreen;
    @Nullable private android.animation.ValueAnimator mLaunchBarAnimator;
    private GameRenderer mGameRenderer;

    Instance instance;
    Account account;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;
    private GameService.LocalBinder mServiceBinder;

    private QuickSettingSideDialog mQuickSettingSideDialog;
    private ResourceBrowserPopup mResourceBrowser;
    private com.craftstudio.cslauncher.multiplayer.ui.VirtualLanPopup mVirtualLan;
    private String mLaunchVersionId;

    public static int mForcedPanningHeight = 0;
    public static int mImeHeight = 0;

    private static volatile boolean sRunning;

    /** Whether the game activity exists in this process (used by the Virtual LAN service) */
    public static boolean isRunning() { return sRunning; }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sRunning = true;
        // The game is starting: drop the "download finished, tap to start" fallback notification if it was posted
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(com.craftstudio.cslauncher.utils.NotificationUtils.NOTIFICATION_ID_GAME_START);
        } catch (Throwable ignored) {}
        // Route the native game log through the tap so the Virtual LAN host can see "Started serving on"
        com.craftstudio.cslauncher.multiplayer.LogTap.install();
        instance = Instances.loadSelectedInstance();
        // The launcher hands the account file over: this process may hold a stale preference
        // cache (separate process) and must never start the game with a null account.
        account = Accounts.resolveForLaunch(getIntent() != null ? getIntent().getStringExtra(INTENT_LAUNCH_ACCOUNT) : null);
        if(instance == null) {
            Toast.makeText(this, R.string.instance_dir_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if(account == null) {
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            Tools.restartLauncherActivity(this);
            finish();
            return;
        }
        mGameRenderer = new GameRenderer(instance.getLaunchRenderer());

        if(GpuUtils.getGlInfo().isAdreno() && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            mGameRenderer.overrideVulkanDriver();
        }

        AsyncAssetManager.extractDefaultSettings(this, instance.getGameDirectory());
        MCOptionUtils.load(instance.getGameDirectory().getAbsolutePath());

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);

        // Discord presence is owned by this process from now on (the launcher released it)
        com.craftstudio.cslauncher.discord.DiscordPresenceManager.getInstance(this)
                .onGameProcessStarted(this, instance, instance != null ? instance.versionId : "");

        Platform.initialize(this, launcherGLView);

        mGyroControl = new GyroControl(this);

        // Enabling this on TextureView results in a broken white result
        if(PREF_USE_ALTERNATE_SURFACE) getWindow().setBackgroundDrawable(null);
        else getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Set the sustained performance mode for available APIs
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            getWindow().setSustainedPerformanceMode(PREF_SUSTAINED_PERFORMANCE);

        // This is required on Android 10 for the insets listener
        // https://issuetracker.google.com/issues/266331465
        boolean androidCompat = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
        if(androidCompat)
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Make keyboard pan the activity so the user sees what they're typing
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, insets) -> {
            if(launcherGLView.mSurface == null)
                return insets;
            ViewPropertyAnimator animSurface = launcherGLView.mSurface.animate()
                    .setDuration(100);
            ViewPropertyAnimator animCursor = launcherGLView.mCursorView.animate()
                    .setDuration(100);
            if(!insets.isVisible(WindowInsetsCompat.Type.ime())){
                animSurface.translationY(0).start();
                animCursor.translationY(0).start();
                mImeHeight = 0;
                if(androidCompat) {
                    // AndroidX keeps SystemUI visible for some reason after IME session
                    view.postDelayed(() -> {
                        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
                    }, 150);
                }
                return insets;
            }
            if(mForcedPanningHeight == 0 && !LauncherPreferences.PREF_KEYBOARD_AUTOPANNING)
                return insets;
            mImeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int translationY;
            // Autopanning (if keyboardPan wasn't clicked)
            if(mForcedPanningHeight == 0) {
                translationY = Tools.getTranslationFromCursorY(
                        (int)(Platform.cursorY * launcherGLView.getCursorRatioY() + 100),
                        launcherGLView.getHeight(),
                        mImeHeight,
                        0
                );
            } else
                translationY = mForcedPanningHeight == -1 ? mImeHeight : Math.clamp(mImeHeight - mForcedPanningHeight, 0, mImeHeight);
            animSurface.translationY(-translationY).start();
            animCursor.translationY(-translationY).start();
            return insets;
        });

        ingameControlsEditorArrayAdapter = new GameMenuAdapter(this,
                getResources().getStringArray(R.array.menu_customcontrol), GameMenuAdapter.EDITOR_ICONS);
        ingameControlsEditorListener = (parent, view, position, id) -> {
            GameMenuMotion.tap(view);
            switch(position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.addControlButton(ControlData.createCommandButton()); break;
                case 4: mControlLayout.openLoadDialog(); break;
                case 5: mControlLayout.openSaveDialog(this); break;
                case 6: mControlLayout.openSetDefaultDialog(); break;
                case 7: mControlLayout.openExitDialog(this);
            }
        };

        // Recompute the gui scale when options are changed
        MCOptionUtils.MCOptionListener optionListener = MCOptionUtils::getMcScale;
        MCOptionUtils.addMCOptionListener(optionListener);
        mControlLayout.setModifiable(false);

        // Set the activity for the executor. Must do this here, or else Tools.showErrorRemote() may not
        // execute the correct method
        ContextExecutor.setActivity(this);
        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();
        mControlLayout.setMenuListener(this);

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        launcherGLView.mCursorView.setCursorScale(LauncherPreferences.PREF_MOUSESCALE);
        weakCursor = new WeakReference<>(launcherGLView.mCursorView);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());

            Intent activityIntent = getIntent();
            Bundle extras = Objects.requireNonNull(activityIntent.getExtras());
            String version = extras.getString(INTENT_LAUNCH_VERSION);
            File[] classpath = (File[]) extras.getSerializable(INTENT_LAUNCH_CLASSPATH);

            activityIntent.removeExtra(INTENT_LAUNCH_VERSION);
            activityIntent.removeExtra(INTENT_LAUNCH_CLASSPATH);

            setIntent(activityIntent);

            setTitle("CS Launcher Plus (" + version + ")");
            mLaunchVersionId = version;
            brandLaunchScreen(version);

            // Menu
            gameActionArrayAdapter = new GameMenuAdapter(this,
                    getResources().getStringArray(R.array.menu_ingame), GameMenuAdapter.INGAME_ICONS);
            mGameSubtitle = (instance != null && instance.name != null && !instance.name.trim().isEmpty()
                    ? instance.name.trim() + "  \u2022  " : "") + (version == null ? "" : version);
            setDrawerHeader(false);
            gameActionClickListener = (parent, view, position, id) -> {
                GameMenuMotion.tap(view);
                switch(position) {
                     case 0: dialogForceClose(GameActivity.this); break;
                     case 1: openLogOutput(); break;
                     case 2: dialogSendCustomKey(); break;
                     case 3: openQuickSettings(); break;
                     case 4: openCustomControls(); break;
                     case 5: openResourceBrowser(); break;
                     case 6: openVirtualLan(); break;
                }
                drawerLayout.closeDrawers();
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);
            drawerLayout.closeDrawers();

            launcherGLView.setSurfaceReadyListener(() -> {
                try {
                    Tools.runOnUiThread(() -> { if(PREF_VIRTUAL_MOUSE_START) launcherGLView.mCursorView.setVisibility(View.VISIBLE); });
                    if(version == null || classpath == null) {
                        Tools.runOnUiThread(()->{
                            Toast.makeText(this, R.string.main_please_restart, Toast.LENGTH_LONG).show();
                            finish();
                        });
                        return;
                    }
                    runCraft(version, classpath);
                }catch (Throwable e){
                    Tools.showErrorRemote(e);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void loadControls() {
        try {
            // Load keys
            mControlLayout.loadLayout(instance.getLaunchControls());
        } catch(IOException e) {
            try {
                Log.w("MainActivity", "Unable to load the control file, loading the default now", e);
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        mControlLayout.toggleControlVisible();
    }

    @Override
    public void onAttachedToWindow() {
        // Post to get the correct display dimensions after layout.
        mControlLayout.post(()->{
            Tools.getDisplayMetrics(this);
            loadControls();
        });
    }

    /** Boilerplate binding */
    private void bindValues(){
        mControlLayout = findViewById(R.id.main_control_layout);
        launcherGLView = findViewById(R.id.main_game_render_view);
        drawerLayout = findViewById(R.id.main_drawer_options);
        navDrawer = findViewById(R.id.main_navigation_view);
        mDrawerPanel = findViewById(R.id.main_drawer_panel);
        mDrawerLogo = findViewById(R.id.main_drawer_logo);
        mDrawerTitle = findViewById(R.id.main_drawer_title);
        mDrawerSubtitle = findViewById(R.id.main_drawer_subtitle);
        mDrawerTitles = (View) mDrawerTitle.getParent();
        loggerView = findViewById(R.id.mainLoggerView);
        touchCharInput = findViewById(R.id.mainTouchCharInput);
        mDrawerPullButton = findViewById(R.id.drawer_button);
        mHotbarView = findViewById(R.id.hotbar_view);
        mLoadingScreen = findViewById(R.id.main_loading_screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        if(PREF_ENABLE_GYRO) mGyroControl.enable();
        PLATFORM.setHovered(true);
    }

    @Override
    protected void onPause() {
        ContextExecutor.clearActivity(this);
        mGyroControl.disable();
        // Avoid going through the JNI each time.
        if (Platform.isGrabbing()){
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
        }
        if(mQuickSettingSideDialog != null) {
            mQuickSettingSideDialog.cancel();
        }
        PLATFORM.setHovered(false);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PLATFORM.setVisible(true);
    }

    @Override
    protected void onStop() {
        PLATFORM.setVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            com.craftstudio.cslauncher.discord.DiscordPresenceManager.getInstance(this).onGameExiting();
        }
        super.onDestroy();
        if (mLaunchBarAnimator != null) { mLaunchBarAnimator.cancel(); mLaunchBarAnimator = null; }
        sRunning = false;
        ContextExecutor.clearActivity(this);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if(mGyroControl != null) mGyroControl.updateOrientation();
        // Layout resize is practically guaranteed on a configuration change, and `onConfigurationChanged`
        // does not implicitly start a layout. So, request a layout and expect the screen dimensions to be valid after the]
        // post.
        if(mControlLayout == null) return;
        mControlLayout.requestLayout();
        mControlLayout.post(()->{
            // Child of mControlLayout, so refreshing size here is correct
            launcherGLView.refreshSize();
            mControlLayout.refreshControlButtonPositions();
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(mLoadingScreen != null && !(PLATFORM instanceof DummyBackend)) hideLoadingScreen();
        if(launcherGLView != null)  // Useful when backing out of the app
            Tools.MAIN_HANDLER.postDelayed(() -> launcherGLView.refreshSize(), 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            // Reload PREF_DEFAULTCTRL_PATH
            // If the storage root got unmounted/unreadable we won't be able to load the file anyway,
            // and MissingStorageActivity will be started.
            if(!Tools.checkStorageRoot(this)) return;
            LauncherPreferences.loadPreferences(getApplicationContext());
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void runCraft(String versionId, File[] classpath) throws Throwable {
        Logger.appendToLog("--------- Starting game with Launcher Debug!");
        Tools.printLauncherInfo(versionId, instance.getLaunchArgs(), mGameRenderer.getCurrentRenderer(), this);
        JREUtils.redirectAndPrintJRELog();

        // Show Angelica + SFPEW notice
        if (mGameRenderer.getCurrentRenderer() instanceof GLESRenderSpec.SFPEWRenderSpec
                && GameRunner.hasAngelica(instance.getGameDirectory())) {
            Tools.runOnUiThread(() -> Toast.makeText(this, R.string.angelica_found_sfpew_notice, Toast.LENGTH_LONG).show());
        }

        GameRunner.launchGame(this, account, instance, versionId, classpath, mGameRenderer);
        //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
        Tools.runOnUiThread(()-> mServiceBinder.isActive = false);
    }

    private void dialogSendCustomKey() {
        new SettingsPopup(this)
                .title(R.string.control_customkey)
                .subtitle(getString(R.string.gm_customkey_subtitle))
                .singleChoice(KeycodeUtils.generateKeyName(), -1, KeycodeUtils::execKeyIndex)
                .negative(android.R.string.cancel, null)
                .show();
    }

    /** Title + subtitle of the in-game menu card (game menu or control editor) */
    private void setDrawerHeader(boolean editor) {
        if (mDrawerTitle == null) return;
        mDrawerTitle.setText(editor ? R.string.gm_title_editor : R.string.gm_title_game);
        mDrawerSubtitle.setText(editor ? getText(R.string.gm_subtitle_editor) : mGameSubtitle);
        if (mDrawerLogo instanceof ViewGroup && ((ViewGroup) mDrawerLogo).getChildCount() > 0) {
            ((ImageView) ((ViewGroup) mDrawerLogo).getChildAt(0))
                    .setImageResource(editor ? R.drawable.ic_gm_sliders : R.drawable.ic_gm_gamepad);
        }
    }

    boolean isInEditor;
    private void openCustomControls() {
        if(ingameControlsEditorListener == null || ingameControlsEditorArrayAdapter == null) return;

        mControlLayout.setModifiable(true);
        navDrawer.setAdapter(ingameControlsEditorArrayAdapter);
        navDrawer.setOnItemClickListener(ingameControlsEditorListener);
        setDrawerHeader(true);
        mDrawerPullButton.setVisibility(View.VISIBLE);
        isInEditor = true;
    }

    private void openLogOutput() {
        loggerView.setVisibility(View.VISIBLE);
    }

    private void openQuickSettings() {
        if(mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = new QuickSettingSideDialog(this, mControlLayout) {
                @Override
                public void onResolutionChanged() {
                    launcherGLView.refreshSize();
                    mHotbarView.onResolutionChanged();
                }

                @Override
                public void onGyroStateChanged() {
                    mGyroControl.updateOrientation();
                    if (PREF_ENABLE_GYRO) {
                        mGyroControl.enable();
                    } else {
                        mGyroControl.disable();
                    }
                }

                @Override
                public void onButtonTransparencyChanged() {
                    mControlLayout.updateButtonOpacity();
                }
            };
        }
        mQuickSettingSideDialog.appear(true);
    }

    public static void toggleMouse(Context ctx) {
        // Avoid going through the JNI each time.
        if (Platform.isGrabbing()) return;
        GameCursorView cursorView = Tools.getWeakReference(weakCursor);
        if(cursorView == null) return;
        int toastString = 0;
        switch (cursorView.getVisibility()) {
            case View.GONE:
            case View.INVISIBLE:
                toastString = R.string.control_mouseon;
                cursorView.setVisibility(View.VISIBLE);
                break;
            case View.VISIBLE:
                toastString = R.string.control_mouseoff;
                cursorView.setVisibility(View.GONE);
                break;
        }

        if(toastString != 0) Toast.makeText(ctx, toastString, Toast.LENGTH_SHORT).show();
    }

    /** In-game "Browse Resources" popup (Modrinth resource packs / shaders for the running profile). */
    private void openResourceBrowser() {
        if (instance == null) {
            Toast.makeText(this, R.string.rb_state_profile_title, Toast.LENGTH_LONG).show();
            return;
        }
        if (mResourceBrowser != null && mResourceBrowser.isShowing()) return;
        mResourceBrowser = ResourceBrowserPopup.show(this, instance, mLaunchVersionId);
    }

    /** In-game "Virtual LAN Multiplayer" popup: create / join a cross-network LAN session. */
    private void openVirtualLan() {
        if (mVirtualLan != null && mVirtualLan.isShowing()) return;
        mVirtualLan = com.craftstudio.cslauncher.multiplayer.ui.VirtualLanPopup.show(this, () -> mVirtualLan = null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mVirtualLan != null && mVirtualLan.isShowing()) {
            // Keys go to the popup (code field), never to the game. Back is handled by the popup.
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) mVirtualLan.onBackPressed();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (mResourceBrowser != null && mResourceBrowser.isShowing()) {
            // Keys go to the popup (search field), never to the game. Back closes it.
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) mResourceBrowser.dismiss();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if(isInEditor) {
            if(event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if(event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent;
        if(!(handleEvent = launcherGLView.processKeyEvent(event))) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !touchCharInput.isEnabled()) {
                if(event.getAction() != KeyEvent.ACTION_UP) return true; // We eat it anyway
                CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ESCAPE);
                return true;
            }
        }
        return handleEvent;
    }

    public static void switchKeyboardState(boolean panning) {
        if(touchCharInput != null) {
            touchCharInput.switchKeyboardState();
            GameActivity.mForcedPanningHeight = panning ? -1 : 0;
        }
    }
    public static void toggleKeyboardState(boolean state, int panningHeight) {
        if(touchCharInput != null) {
            touchCharInput.setKeyboardState(state);
            GameActivity.mForcedPanningHeight = panningHeight;
        }
    }

    public void hideLoadingScreen(){
        if(mLoadingScreen == null) return;
        final View screen = mLoadingScreen;
        mLoadingScreen = null;
        ((TextView) screen.findViewById(R.id.main_loading_screen_text)).setText(getString(R.string.loading_screen_booted, PLATFORM.backendName()));
        if (mLaunchBarAnimator != null) {
            mLaunchBarAnimator.cancel();
            mLaunchBarAnimator = null;
        }
        final Runnable remove = () -> {
            ViewGroup parent = (ViewGroup) screen.getParent();
            if (parent != null) parent.removeView(screen);
        };
        final LaunchRocketView rocket = screen.findViewById(R.id.ls_rocket);
        final View pill = screen.findViewById(R.id.ls_pill);
        boolean motion = com.craftstudio.cslauncher.utils.AnimationManager.isEnabled(this,
                com.craftstudio.cslauncher.utils.AnimationManager.TYPE_CONTENT);
        if (rocket == null || !motion || isFinishing()) {
            if (rocket != null) rocket.finishNow();
            screen.animate().alpha(0f).setDuration(300).withEndAction(remove).start();
            return;
        }
        // Lift-off: the pill drops away while the rocket climbs and bursts, then the layer goes
        if (pill != null) {
            pill.animate().alpha(0f).translationY(14f * getResources().getDisplayMetrics().density)
                    .setStartDelay(220L).setDuration(320L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        }
        rocket.launch(() -> screen.animate().alpha(0f).setDuration(220).withEndAction(remove).start());
        // Safety net: never leave the layer around if the burst somehow stalls
        screen.postDelayed(() -> { if (screen.getParent() != null) { rocket.finishNow(); remove.run(); } }, 4500L);
    }

    /**
     * Fills the small launch status pill (version + profile, sweeping platinum line). It floats
     * at the bottom and never blocks touches, so the controls underneath stay visible and
     * editable while the game window starts. The crash hint only appears after 25 s.
     */
    private void brandLaunchScreen(@Nullable String version) {
        if (mLoadingScreen == null) return;
        TextView status = mLoadingScreen.findViewById(R.id.main_loading_screen_text);
        if (status != null && version != null && !version.isEmpty()) {
            String profile = instance != null && instance.name != null ? instance.name.trim() : "";
            status.setText(profile.isEmpty()
                    ? getString(R.string.loading_screen_launching, version)
                    : getString(R.string.loading_screen_launching_profile, version, profile));
        }
        final View track = mLoadingScreen.findViewById(R.id.ls_track);
        final View bar = mLoadingScreen.findViewById(R.id.ls_bar);
        if (track != null && bar != null) {
            final float density = getResources().getDisplayMetrics().density;
            final float barWidth = 56f * density;
            final android.animation.ValueAnimator sweep = android.animation.ValueAnimator.ofFloat(0f, 1f);
            sweep.setDuration(1300L);
            sweep.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            sweep.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            sweep.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                bar.setTranslationX(-barWidth + f * (track.getWidth() + barWidth));
            });
            track.setClipToOutline(true);
            track.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), density);
                }
            });
            sweep.start();
            mLaunchBarAnimator = sweep;
        }
        LaunchRocketView rocket = mLoadingScreen.findViewById(R.id.ls_rocket);
        if (rocket != null) {
            float density = getResources().getDisplayMetrics().density;
            // Rest just above the pill (pill ~46dp tall + 14dp margin + clearance)
            rocket.setRestOffsetFromBottom(104f * density);
        }
        View pill = mLoadingScreen.findViewById(R.id.ls_pill);
        if (pill != null) {
            pill.setAlpha(0f);
            pill.setTranslationY(18f * getResources().getDisplayMetrics().density);
            pill.animate().alpha(1f).translationY(0f).setDuration(360L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
        }
        final View warning = mLoadingScreen.findViewById(R.id.main_loading_screen_warning);
        if (warning != null) {
            warning.postDelayed(() -> {
                if (mLoadingScreen == null || warning.getVisibility() == View.VISIBLE) return;
                warning.setAlpha(0f);
                warning.setVisibility(View.VISIBLE);
                warning.animate().alpha(1f).setDuration(260L).start();
            }, 25000L);
        }
    }

    @Override
    public void onClickedMenu() {
        drawerLayout.openDrawer(mDrawerPanel);
        navDrawer.requestLayout();
        GameMenuMotion.enter(mDrawerLogo, mDrawerTitles, navDrawer);
    }

    @Override
    public void exitEditor() {
        try {
            mControlLayout.loadLayout((CustomControls)null);
            mControlLayout.setModifiable(false);
            System.gc();
            mControlLayout.loadLayout(instance.getLaunchControls());
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        } catch (Exception e) {
            Tools.showError(this,e);
        }

        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        setDrawerHeader(false);
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GameService.LocalBinder localBinder = (GameService.LocalBinder) service;
        mServiceBinder = localBinder;
        launcherGLView.start(localBinder.isActive);
        localBinder.isActive = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    /*
     * Android 14 (or some devices, at least) seems to dispatch the the captured mouse events as trackball events
     * due to a bug(?) somewhere(????)
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        // On my device, the mouse sends events as a relative mouse device.
        // Not comparing with == here because apparently `eventSource` is a mask that can
        // sometimes indicate multiple sources, like in the case of InputDevice.SOURCE_TOUCHPAD
        // (which is *also* an InputDevice.SOURCE_MOUSE when controlling a cursor)
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0 ||
                (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if(Tools.isAndroid8OrHigher() && checkCaptureDispatchConditions(ev))
            return launcherGLView.dispatchCapturedPointerEvent(ev);
        else return super.dispatchTrackballEvent(ev);
    }
}
