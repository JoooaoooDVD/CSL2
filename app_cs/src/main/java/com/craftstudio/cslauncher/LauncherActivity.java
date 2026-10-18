package com.craftstudio.cslauncher;

import com.craftstudio.cslauncher.utils.AnimationManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.craftstudio.cslauncher.utils.storage.StorageRoot;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.extra.ExtraListener;
import com.craftstudio.cslauncher.fragments.GamepadMapperFragment;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.fragments.MicrosoftLoginFragment;
import com.craftstudio.cslauncher.fragments.SelectAuthFragment;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceInstaller;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.utils.lifecycle.ContextAwareDoneListener;
import com.craftstudio.cslauncher.utils.lifecycle.ContextExecutor;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.IconCacheJanitor;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceFragment;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.progresskeeper.TaskCountListener;
import com.craftstudio.cslauncher.services.ProgressServiceKeeper;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonExtras;
import com.craftstudio.cslauncher.tasks.AsyncVersionList;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonDownloader;
import com.craftstudio.cslauncher.utils.NotificationUtils;
import com.craftstudio.cslauncher.utils.TopNavDock;

public class LauncherActivity extends BaseActivity {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private NotificationManager mNotificationManager;
    private static ActivityResultLauncher<String> mRequestPermissionLauncher;

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            // Also called for the screens of the right pane of the main menu
            updateSettingsButton();
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        // The "false" value is used to stop auth method selection
        FragmentManager manager = getSupportFragmentManager();
        if(!value || manager.isStateSaved()) return false;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        return false;
    };

    /* Listener for the settings button: opens the settings, or closes them when they are open */
    private final View.OnClickListener mSettingButtonListener = v -> {
        FragmentManager manager = getSupportFragmentManager();
        if(manager.isStateSaved()) return;
        Fragment fragment = manager.findFragmentById(mFragmentView.getId());
        if(fragment != null && SETTING_FRAGMENT_TAG.equals(fragment.getTag())) {
            manager.popBackStack();
            return;
        }
        // The settings replace the whole screen, they are not opened in the right pane.
        // In landscape they have two panes of their own
        Class<? extends Fragment> settingsClass = getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE
                ? SettingsHostFragment.class : LauncherPreferenceFragment.class;
        Tools.swapFragmentFullScreen(this, settingsClass, SETTING_FRAGMENT_TAG, null);
    };

    /**
     * Top bar dock click: the destination always opens in the main menu, so any full screen
     * screen above it (settings, account...) is closed first.
     */
    private void onDockItemClicked(int id) {
        if(id == R.id.nav_controls) {
            // Touch controls editor: separate activity, whatever screen is open stays as it is
            startActivity(new Intent(this, CustomControlsActivity.class));
            return;
        }
        FragmentManager manager = getSupportFragmentManager();
        if(manager.isStateSaved()) return;
        Fragment current = manager.findFragmentById(R.id.container_fragment);
        if(!(current instanceof MainMenuFragment) || current.getView() == null) {
            manager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            current = manager.findFragmentById(R.id.container_fragment);
        }
        if(current instanceof MainMenuFragment) ((MainMenuFragment) current).navigate(id);
    }

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(mProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        Instance selectedInstance = Instances.loadSelectedInstance();

        if(selectedInstance == null) {
            Toast.makeText(this, R.string.no_instance, Toast.LENGTH_LONG).show();
            return false;
        }

        if(selectedInstance.installer != null) {
            selectedInstance.installer.start();
            return false;
        }

        if (!Tools.isValidString(selectedInstance.versionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if(Accounts.getCurrent() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }
        String normalizedVersionId = MoJsonExtras.normalizeVersionId(selectedInstance.versionId);
        JVersionList.Version mcVersion = MoJsonExtras.getListedVersion(normalizedVersionId);
        com.craftstudio.cslauncher.progresskeeper.ProgressKeeper.markLaunching();
        new MoJsonDownloader().start(
                this.getAssets(),
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
        return false;
    };
    /** Settings button = settings (selected while they are open); the dock follows the main menu */
    private void updateSettingsButton() {
        if(mSettingsButton == null) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container_fragment);
        boolean inSettings = current != null && SETTING_FRAGMENT_TAG.equals(current.getTag());
        mSettingsButton.setSelected(inSettings);
        mSettingsButton.setAlpha(1f);
        // Outside the main menu no dock destination is active
        if(!(current instanceof MainMenuFragment)) TopNavDock.setActive(this, View.NO_ID);
    }

    @Override
    protected boolean shouldIgnoreNotch() {
        return true;
    }

    @Override
    public boolean setFullscreen() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cs_ui);
        MoJsonDownloader.prepareSubstitutionMap(getAssets());

        try {
            Os.setenv("TMPDIR", Tools.DIR_CACHE.getAbsolutePath(), true);
         }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        IconCacheJanitor.runJanitor();

        getWindow().setBackgroundDrawable(null);
        bindViews();
        mRequestPermissionLauncher = this.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) Tools.runOnUiThread(() -> Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show());
                }
        );
        checkNotificationPermission();
        if(LauncherPreferences.PREF_MIGRATION_NOTICE)
            CSLauncherApplication.sExecutorService.submit(this::checkPreviousInstalls);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions));

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_GAME);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        mProgressLayout.observe(ProgressLayout.INSTANCE_INSTALL);
        mProgressLayout.observe(ProgressLayout.DATA_MIGRATION);

        // CS Launcher Plus brand intro - only on a fresh start, never on recreation
        if (savedInstanceState == null) {
            ViewGroup content = findViewById(android.R.id.content);
            if (content != null) content.post(() -> AnimationManager.playBrandIntro(content));
        }
        // First launch: privacy policy & terms, then the storage setup (where game files live)
        mFolderPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) return;
            if (mStorageSetupDialog != null && mStorageSetupDialog.isShowing()) {
                mStorageSetupDialog.onFolderPicked(uri);
                return;
            }
            // The picker is portrait, so this activity may have been recreated meanwhile and the
            // dialog isn't up yet: remember the pick, the re-shown dialog restores it
            java.io.File dir = StorageRoot.treeUriToFile(uri);
            if (dir == null) {
                StorageRoot.setPendingError(this, R.string.ss_err_unsupported);
            } else if (!StorageRoot.isUsableRoot(dir)) {
                StorageRoot.setPendingError(this, R.string.ss_err_not_empty);
            } else {
                StorageRoot.persistTreeGrant(this, uri);
                StorageRoot.setPendingPick(this, dir.getAbsolutePath());
            }
            // Make sure the setup comes back up right away with the result
            View root = findViewById(android.R.id.content);
            if (root != null) root.post(this::showStorageSetup);
        });
        mLegacyStorageLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (mStorageSetupDialog != null) mStorageSetupDialog.onHostResumed();
        });
        com.kdt.mcgui.DiscordInviteDialog.countLaunch(this);
        if (!com.craftstudio.cslauncher.about.WelcomePolicy.isAccepted()) {
            View root = findViewById(android.R.id.content);
            if (root != null) root.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                com.craftstudio.cslauncher.about.WelcomePolicy.showIfNeeded(this, () -> {
                    StorageRoot.markSetupPending(this);
                    showStorageSetup();
                });
            }, savedInstanceState == null ? 900L : 0L);
        } else if (StorageRoot.isSetupPending(this)) {
            // Policy accepted but the setup never finished (app closed in between): resume it
            View root = findViewById(android.R.id.content);
            if (root != null) root.postDelayed(this::showStorageSetup, savedInstanceState == null ? 900L : 0L);
        } else if (!StorageRoot.isSetupDone(this)) {
            // Existing install from before the setup existed: keep its current folder silently
            StorageRoot.markSetupDone(this);
        } else if (com.kdt.mcgui.DiscordInviteDialog.isDue(this)) {
            // Setup already done (e.g. we just restarted into the custom folder): scheduled community invite
            View root = findViewById(android.R.id.content);
            if (root != null) root.postDelayed(this::showDiscordInvite, savedInstanceState == null ? 1200L : 300L);
        } else if (savedInstanceState == null) {
            // Quiet update check after the intro (throttled inside, never blocks, errors ignored)
            View root = findViewById(android.R.id.content);
            if (root != null) root.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (mStorageSetupDialog != null && mStorageSetupDialog.isShowing()) return;
                com.craftstudio.cslauncher.update.UpdateManager.autoCheck(this);
            }, 3200L);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        if (com.craftstudio.cslauncher.update.UpdateManager.onActivityResult(this, requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void showDiscordInvite() {
        if (isFinishing() || isDestroyed()) return;
        if (!com.craftstudio.cslauncher.about.WelcomePolicy.isAccepted() || !StorageRoot.isSetupDone(this)) return;
        if (mStorageSetupDialog != null && mStorageSetupDialog.isShowing()) return;
        com.kdt.mcgui.DiscordInviteDialog.showIfDue(this);
    }

    private androidx.activity.result.ActivityResultLauncher<Uri> mFolderPickerLauncher;
    private androidx.activity.result.ActivityResultLauncher<String> mLegacyStorageLauncher;
    @Nullable private com.kdt.mcgui.StorageSetupDialog mStorageSetupDialog;

    private void showStorageSetup() {
        if (isFinishing() || isDestroyed() || StorageRoot.isSetupDone(this)) return;
        if (mStorageSetupDialog != null && mStorageSetupDialog.isShowing()) return;
        mStorageSetupDialog = new com.kdt.mcgui.StorageSetupDialog(this, new com.kdt.mcgui.StorageSetupDialog.Host() {
            @Override
            public void requestAllFilesAccess() {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent intent = StorageRoot.allFilesAccessIntent(LauncherActivity.this);
                    if (intent != null) {
                        try { startActivity(intent); } catch (Exception e) { Log.w("StorageSetup", "No settings screen", e); }
                    }
                } else if (Build.VERSION.SDK_INT >= 23) {
                    mLegacyStorageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                } else if (mStorageSetupDialog != null) {
                    mStorageSetupDialog.onHostResumed();
                }
            }

            @Override
            public void pickFolder() {
                try {
                    mFolderPickerLauncher.launch(null);
                } catch (Exception e) {
                    Toast.makeText(LauncherActivity.this, R.string.ss_err_unsupported, Toast.LENGTH_LONG).show();
                }
            }
        });
        mStorageSetupDialog.setOnDismissListener(d -> {
            mStorageSetupDialog = null;
            if (StorageRoot.isSetupDone(this)) {
                View root = findViewById(android.R.id.content);
                if (root != null) root.postDelayed(this::showDiscordInvite, 450L);
            }
        });
        mStorageSetupDialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        InstanceInstaller.postInstallCheck(this);
        if (mStorageSetupDialog != null) mStorageSetupDialog.onHostResumed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContextExecutor.clearActivity();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
        AnimationManager.installFragmentTransitions(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        super.onBackPressed();
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = findFragmentByTag(getSupportFragmentManager(), tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    /** Looks in the fragments nested in others too, like the ones in the right pane of the main menu */
    private static Fragment findFragmentByTag(FragmentManager manager, String tag) {
        Fragment fragment = manager.findFragmentByTag(tag);
        if(fragment != null) return fragment;
        for(Fragment child : manager.getFragments()) {
            fragment = findFragmentByTag(child.getChildFragmentManager(), tag);
            if(fragment != null) return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    public void askForPermission(int minApi, final String permission) {
        if(Build.VERSION.SDK_INT < minApi) return;
        mRequestPermissionLauncher.launch(permission);
    }
    public boolean checkForPermission(int minApi, final String permission) {
        return Build.VERSION.SDK_INT < minApi ||
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_DENIED;
    }
    public boolean checkForPermissionRationale(int minApi, final String permission) {
        return checkForPermission(minApi, permission) || ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
            this.checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS)) {
            return;
        }
        showNotificationPermissionReasoning();
    }

    // Call async
    private void checkPreviousInstalls(){
        // Legacy-install detection disabled during the CS Launcher Plus rebrand.
        // Data import is still available from Miscellaneous settings.
        final String[] packages = {};
        for(String s : packages){
            Intent i = getPackageManager().getLaunchIntentForPackage(s);
            if(i == null) continue;
            Tools.runOnUiThread(() ->
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.migration_progress_warning_title)
                        .setMessage(R.string.migration_notice)
                        .setPositiveButton(android.R.string.ok, (d, button) -> LauncherPreferences.DEFAULT_PREF.edit().putBoolean("migrationNotice", false).apply())
                        .setOnDismissListener(d -> LauncherPreferences.PREF_MIGRATION_NOTICE = false)
                        .show());
            break;
        }
    }

    private void showNotificationPermissionReasoning() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        askForPermission(33, Manifest.permission.POST_NOTIFICATIONS))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
    }

    /** Stuff all the view boilerplate here */
    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mProgressLayout = findViewById(R.id.progress_layout);
        TopNavDock.bind(this, this::onDockItemClicked);
    }

    /**
     * The three-dots menu of the header: shortcuts to the flows that lost their dedicated buttons.
     * The header button itself has been removed, but other surfaces (and future docks) may still
     * route here, so the entry point stays available.
     */
    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.home_overflow);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.overflow_wiki) {
                Tools.openURL(this, Tools.URL_HOME);
            } else if (id == R.id.overflow_community) {
                Tools.openURL(this, getString(R.string.social_media_invite));
            } else if (id == R.id.overflow_install_jar) {
                MainMenuFragment menu0 = peekMainMenu();
                if (menu0 != null) menu0.openJarInstaller();
            } else if (id == R.id.overflow_directory) {
                MainMenuFragment menu0 = peekMainMenu();
                if (menu0 != null) menu0.openGameDirectory();
            } else if (id == R.id.overflow_share_logs) {
                Tools.shareLog(this);
            } else if (id == R.id.overflow_gamepad) {
                Tools.swapFragment(this, GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            } else if (id == R.id.overflow_about) {
                Tools.swapFragment(this, com.craftstudio.cslauncher.fragments.AboutFragment.class,
                        com.craftstudio.cslauncher.fragments.AboutFragment.TAG, null);
            }
            return true;
        });
        menu.show();
    }

    @Nullable
    private MainMenuFragment peekMainMenu() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container_fragment);
        return fragment instanceof MainMenuFragment ? (MainMenuFragment) fragment : null;
    }
}
