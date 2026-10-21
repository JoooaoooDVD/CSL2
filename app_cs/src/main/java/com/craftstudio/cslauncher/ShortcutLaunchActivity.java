package com.craftstudio.cslauncher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.tasks.AsyncAssetManager;
import com.craftstudio.cslauncher.tasks.AsyncVersionList;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonDownloader;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonExtras;
import com.craftstudio.cslauncher.utils.ProfileShortcuts;
import com.craftstudio.cslauncher.utils.lifecycle.ContextAwareDoneListener;
import com.craftstudio.cslauncher.utils.lifecycle.ContextExecutor;
import com.craftstudio.cslauncher.utils.storage.StorageRoot;

/**
 * Target of a profile's home-screen shortcut. Instead of opening the launcher UI it does exactly
 * what PLAY does for that profile and nothing else: selects it, checks the version list, runs the
 * game downloader (verifying / fetching files) and hands over to {@link com.craftstudio.cslauncher.game.GameActivity}
 * through the same {@link ContextAwareDoneListener} the launcher uses.
 * <p>
 * Anything that needs the full launcher (first-run policy / storage setup, storage permission,
 * no account, a modpack still installing, the profile gone) falls back to the normal entry point,
 * handing the profile over so the launcher still starts it where it can.
 */
public class ShortcutLaunchActivity extends BaseActivity implements ProgressKeeper.GlobalProgressListener {
    private static final String TAG = "ShortcutLaunch";
    private static final long VERSION_LIST_TIMEOUT_MS = 8000L;

    private TextView mStatus;
    private ProgressBar mProgress;
    private View mRing;
    @Nullable private ValueAnimator mPulse;
    private boolean mLaunched;
    private boolean mLeaving;
    private String mKey;
    private Instance mInstance;
    private final Runnable mListTimeout = this::launch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mKey = ProfileShortcuts.profileFrom(getIntent());
        if (mKey == null) {
            openLauncher(null);
            return;
        }
        // Same gates as the normal entry point (TestStorageActivity): it handles all of them
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && !TestStorageActivity.isStorageAllowed(this)) {
            openLauncher(mKey);
            return;
        }
        if (!Tools.checkStorageRoot(this)) {
            openLauncher(mKey);
            return;
        }
        LauncherPreferences.loadPreferences(this);
        AsyncAssetManager.unpackComponents(this);
        AsyncAssetManager.unpackSingleFiles(this);
        if (!com.craftstudio.cslauncher.about.WelcomePolicy.isAccepted()
                || StorageRoot.isSetupPending(this) || !StorageRoot.isSetupDone(this)) {
            openLauncher(mKey);
            return;
        }

        mInstance = Instances.loadInstance(mKey);
        if (mInstance == null) {
            Toast.makeText(this, R.string.shortcut_profile_missing, Toast.LENGTH_LONG).show();
            ProfileShortcuts.disable(this, mKey);
            openLauncher(null);
            return;
        }
        Instances.setSelectedInstance(mInstance);

        setContentView(R.layout.activity_shortcut_launch);
        bindUi();

        if (mInstance.installer != null) {
            // A modpack is still installing: the launcher owns that flow
            openLauncher(mKey);
            return;
        }
        if (!Tools.isValidString(mInstance.versionId)) {
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            openLauncher(null);
            return;
        }
        if (Accounts.getCurrent() == null) {
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            openLauncher(null);
            return;
        }
        if (ProgressKeeper.hasOngoingTasks()) {
            // Something is already downloading in this process: let the launcher show it
            openLauncher(mKey);
            return;
        }

        ProgressKeeper.addGlobalListener(this);
        // The version list: usually cached, otherwise a quick fetch; never wait forever
        if (ExtraCore.getValue(ExtraConstants.RELEASE_TABLE) != null) {
            launch();
        } else {
            setStatus(getString(R.string.psl_version_list));
            new AsyncVersionList().getVersionList(versions -> {
                if (versions != null) ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
                runOnUiThread(this::launch);
            });
            mStatus.postDelayed(mListTimeout, VERSION_LIST_TIMEOUT_MS);
        }
    }

    private void bindUi() {
        ImageView icon = findViewById(R.id.sl_icon);
        TextView name = findViewById(R.id.sl_name);
        TextView version = findViewById(R.id.sl_version);
        mStatus = findViewById(R.id.sl_status);
        mProgress = findViewById(R.id.sl_progress);
        mRing = findViewById(R.id.sl_ring);
        try {
            icon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), mInstance));
        } catch (Throwable t) {
            icon.setImageResource(R.drawable.ic_pe_shortcut);
        }
        String display = mInstance.name == null || mInstance.name.trim().isEmpty() ? mKey : mInstance.name.trim();
        name.setText(display);
        version.setText(mInstance.versionId == null ? "" : mInstance.versionId);
        version.setVisibility(mInstance.versionId == null ? View.GONE : View.VISIBLE);
        setStatus(getString(R.string.psl_preparing));

        View card = findViewById(R.id.sl_card);
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        mPulse = ObjectAnimator.ofPropertyValuesHolder(mRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0.15f));
        mPulse.setDuration(1300);
        mPulse.setRepeatCount(ValueAnimator.INFINITE);
        mPulse.setRepeatMode(ValueAnimator.RESTART);
        mPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        mPulse.start();

        View open = findViewById(R.id.sl_open_launcher);
        open.setOnClickListener(v -> {
            ProgressKeeper.cancelAll();
            openLauncher(null);
        });
    }

    /** Runs the exact PLAY pipeline for the selected profile */
    private void launch() {
        if (mLaunched || mLeaving || isFinishing()) return;
        mLaunched = true;
        mStatus.removeCallbacks(mListTimeout);
        try {
            String normalized = MoJsonExtras.normalizeVersionId(mInstance.versionId);
            JVersionList.Version mcVersion = MoJsonExtras.getListedVersion(normalized);
            setStatus(getString(R.string.psl_checking_files));
            ProgressKeeper.markLaunching();
            new MoJsonDownloader().start(getAssets(), mcVersion, normalized, new ContextAwareDoneListener(this, normalized));
        } catch (Throwable t) {
            Log.w(TAG, "launch failed, falling back to the launcher", t);
            openLauncher(mKey);
        }
    }

    private void setStatus(CharSequence text) {
        if (mStatus != null) mStatus.setText(text);
    }

    @Override
    public void onGlobalProgress(int taskCount, int aggregatePercent) {
        runOnUiThread(() -> {
            if (mProgress == null || mLeaving) return;
            if (taskCount > 0 && aggregatePercent >= 0) {
                mProgress.setIndeterminate(false);
                mProgress.setProgress(aggregatePercent);
                setStatus(ProgressKeeper.isRealDownload()
                        ? getString(R.string.psl_downloading_fmt, aggregatePercent)
                        : getString(R.string.psl_checking_fmt, aggregatePercent));
            } else if (taskCount > 0) {
                mProgress.setIndeterminate(true);
            } else if (mLaunched) {
                mProgress.setIndeterminate(true);
                setStatus(getString(R.string.psl_starting));
            }
        });
    }

    /** Normal entry point, optionally still carrying the profile so the launcher starts it */
    private void openLauncher(@Nullable String profileKey) {
        if (mLeaving) return;
        mLeaving = true;
        Intent intent = new Intent(this, TestStorageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (profileKey != null) {
            intent.setAction(ProfileShortcuts.ACTION_LAUNCH_PROFILE);
            intent.putExtra(ProfileShortcuts.EXTRA_PROFILE, profileKey);
        } else {
            intent.setAction(Intent.ACTION_MAIN);
        }
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onBackPressed() {
        // Leaving while files are being checked: stop the work and go to the launcher
        ProgressKeeper.cancelAll();
        openLauncher(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextExecutor.setActivity(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ContextExecutor.clearActivity();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProgressKeeper.removeGlobalListener(this);
        if (mPulse != null) mPulse.cancel();
        if (mStatus != null) mStatus.removeCallbacks(mListTimeout);
    }
}
