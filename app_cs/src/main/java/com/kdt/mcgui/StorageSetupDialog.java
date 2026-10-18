package com.kdt.mcgui;

import android.content.Context;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.craftstudio.cslauncher.utils.storage.StorageRoot;

import java.io.File;

/**
 * First-launch storage setup. Two choices: the app folder (default) or a custom folder, which
 * walks the user through "All files access" and the system folder picker. Can't be dismissed
 * without choosing. The host activity performs the permission / picker launches and reports back
 * through {@link #onHostResumed()} and {@link #onFolderPicked(Uri)}.
 */
public class StorageSetupDialog extends CSPopupDialog {

    public interface Host {
        void requestAllFilesAccess();
        void pickFolder();
    }

    private final Host mHost;
    private boolean mCustom;
    @Nullable private File mPickedDir;
    private int mErrorColor;
    private boolean mFinishing;

    private View mOptDefault, mOptCustom, mSteps, mPermBtn, mPickBtn, mContinue;
    private TextView mPermDesc, mPickDesc, mCustomPath, mError, mContinueText, mPermNum, mPickNum;

    public StorageSetupDialog(@NonNull Context context, @NonNull Host host) {
        super(context);
        mHost = host;
        setCancelable(false);
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_storage_setup);
        View card = findViewById(R.id.ss_card);
        setupPopup(findViewById(R.id.ss_scrim), card, false);
        sizeCard(card);

        mOptDefault = findViewById(R.id.ss_opt_default);
        mOptCustom = findViewById(R.id.ss_opt_custom);
        mSteps = findViewById(R.id.ss_steps);
        mPermBtn = findViewById(R.id.ss_step_perm_btn);
        mPickBtn = findViewById(R.id.ss_step_pick_btn);
        mContinue = findViewById(R.id.ss_continue);
        mContinueText = findViewById(R.id.ss_continue_text);
        mPermDesc = findViewById(R.id.ss_step_perm_desc);
        mPickDesc = findViewById(R.id.ss_step_pick_desc);
        mCustomPath = findViewById(R.id.ss_custom_path);
        mError = findViewById(R.id.ss_error);
        mPermNum = findViewById(R.id.ss_step_perm_num);
        mPickNum = findViewById(R.id.ss_step_pick_num);
        mErrorColor = mError != null ? mError.getCurrentTextColor() : 0;

        ((TextView) findViewById(R.id.ss_default_path)).setText(StorageRoot.defaultRootLabel(getContext()));

        mOptDefault.setOnClickListener(v -> {
            Motion.bounce(findViewById(R.id.ss_default_tile));
            select(false);
        });
        mOptCustom.setOnClickListener(v -> {
            Motion.bounce(findViewById(R.id.ss_custom_tile));
            select(true);
        });
        mPermBtn.setOnClickListener(v -> {
            hideError();
            mHost.requestAllFilesAccess();
        });
        mPickBtn.setOnClickListener(v -> {
            hideError();
            mHost.pickFolder();
        });
        mContinue.setOnClickListener(v -> onContinue());
        for (View v : new View[]{mOptDefault, mOptCustom, mPermBtn, mPickBtn, mContinue}) {
            CSAnimationEngine.attachPressFeedback(v);
        }

        // A pick from before the activity got recreated (the system picker is portrait)
        String pending = StorageRoot.getPendingPick(getContext());
        int pendingError = StorageRoot.takePendingError(getContext());
        if (pending != null) {
            mPickedDir = new File(pending);
            select(true);
            if (findViewById(R.id.ss_step_perm).getVisibility() == View.VISIBLE) showError(R.string.ss_err_perm_needed);
        } else if (pendingError != 0) {
            select(true);
            showError(pendingError);
        } else {
            select(false);
        }
        CreatePopupMotion.chooserContent(findViewById(R.id.ss_header), new View[]{mOptDefault, mOptCustom}, findViewById(R.id.ss_subtitle));
    }

    /** Back / scrim: a choice is required, just nudge. */
    @Override
    protected void onDismissRequested() {
        if (mCard != null) CreatePopupMotion.nudge(mCard);
    }

    private void select(boolean custom) {
        if (mFinishing) return;
        mCustom = custom;
        hideError();
        mOptDefault.setActivated(!custom);
        mOptCustom.setActivated(custom);
        View defaultCheck = findViewById(R.id.ss_default_check);
        View customCheck = findViewById(R.id.ss_custom_check);
        defaultCheck.setVisibility(custom ? View.INVISIBLE : View.VISIBLE);
        customCheck.setVisibility(custom ? View.VISIBLE : View.INVISIBLE);
        Motion.popIn(custom ? customCheck : defaultCheck, 0L, 0.4f);

        if (custom) {
            if (mSteps.getVisibility() != View.VISIBLE) {
                mSteps.setVisibility(View.VISIBLE);
                Motion.riseIn(mSteps, 40L, 14f);
            }
            refreshSteps();
        } else {
            Motion.hide(mSteps, true);
        }
        Motion.swapText(mContinueText, getContext().getString(R.string.ss_continue));
    }

    /** Re-evaluates the picked folder; the permission row only shows when that folder needs it. */
    private void refreshSteps() {
        boolean needsPermission = mPickedDir != null && !StorageRoot.isWritableDirectory(mPickedDir)
                && !StorageRoot.hasAllFilesAccess(getContext());
        View permRow = findViewById(R.id.ss_step_perm);
        View divider = findViewById(R.id.ss_step_divider);
        permRow.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        divider.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        mPermDesc.setText(R.string.ss_step_perm_desc);
        mPermBtn.setVisibility(View.VISIBLE);
        mPermNum.setText("2");
        mPickNum.setText(mPickedDir != null ? "\u2713" : "1");
        if (mPickedDir != null) {
            mPickDesc.setText(mPickedDir.getAbsolutePath());
            mCustomPath.setText(mPickedDir.getAbsolutePath());
            mCustomPath.setVisibility(View.VISIBLE);
        } else {
            mPickDesc.setText(R.string.ss_step_pick_desc);
            mCustomPath.setVisibility(View.GONE);
        }
    }

    /** Host activity came back to the foreground (e.g. from the permission settings screen). */
    public void onHostResumed() {
        if (!isShowing() || !mCustom || mFinishing) return;
        boolean wasBlocked = findViewById(R.id.ss_step_perm).getVisibility() == View.VISIBLE;
        refreshSteps();
        if (wasBlocked && findViewById(R.id.ss_step_perm).getVisibility() != View.VISIBLE) {
            hideError();
            Motion.pulse(mContinue);
        }
    }

    /** Result of the system folder picker. */
    public void onFolderPicked(@Nullable Uri treeUri) {
        if (!isShowing() || mFinishing) return;
        if (treeUri == null) return; // cancelled
        File dir = StorageRoot.treeUriToFile(treeUri);
        if (dir == null) { showError(R.string.ss_err_unsupported); return; }
        if (!StorageRoot.isUsableRoot(dir)) { showError(R.string.ss_err_not_empty); return; }
        StorageRoot.persistTreeGrant(getContext(), treeUri);
        acceptPick(dir);
    }

    /** Folder resolved from a pick (possibly delivered while the dialog was being recreated). */
    public void acceptPick(@NonNull File dir) {
        hideError();
        mPickedDir = dir;
        StorageRoot.setPendingPick(getContext(), dir.getAbsolutePath());
        if (!mCustom) select(true); else refreshSteps();
        Motion.bounce(mCustomPath);
        if (findViewById(R.id.ss_step_perm).getVisibility() == View.VISIBLE) {
            // Selected through the picker but plain file access is blocked here (Android 11+ outside
            // the app's own folders): one more tap is needed
            showError(R.string.ss_err_perm_needed);
            Motion.shake(findViewById(R.id.ss_step_perm));
        } else {
            if (StorageRoot.isLauncherData(dir)) showInfo(R.string.ss_existing_data);
            Motion.pulse(mContinue);
        }
    }

    /** Positive note in the error slot (e.g. "existing data found"), without the shake. */
    private void showInfo(int res) {
        mError.setText(res);
        mError.setTextColor(getContext().getColor(R.color.hm_text_dim));
        if (mError.getVisibility() != View.VISIBLE) {
            mError.setVisibility(View.VISIBLE);
            Motion.riseIn(mError, 0L, 8f);
        }
    }

    private void onContinue() {
        if (mFinishing) return;
        if (!mCustom) {
            mFinishing = true;
            StorageRoot.useDefaultRoot(getContext());
            StorageRoot.setPendingPick(getContext(), null);
            CreatePopupMotion.optionChosen(mOptDefault, () -> close(null));
            return;
        }
        if (mPickedDir == null) {
            showError(R.string.ss_err_pick_first);
            Motion.shake(findViewById(R.id.ss_step_pick));
            return;
        }
        if (!StorageRoot.isWritableDirectory(mPickedDir)) {
            refreshSteps();
            if (findViewById(R.id.ss_step_perm).getVisibility() == View.VISIBLE) {
                showError(R.string.ss_err_perm_needed);
                Motion.shake(findViewById(R.id.ss_step_perm));
            } else {
                showError(R.string.ss_err_not_writable);
            }
            return;
        }
        mFinishing = true;
        StorageRoot.setCustomRoot(getContext(), mPickedDir);
        Motion.swapText(mContinueText, getContext().getString(R.string.ss_restarting));
        mContinue.setEnabled(false);
        CreatePopupMotion.optionChosen(mOptCustom, () -> close(() -> StorageRoot.restartLauncher(getContext())));
    }

    private void showError(int res) {
        mError.setText(res);
        if (mErrorColor != 0) mError.setTextColor(mErrorColor);
        if (mError.getVisibility() != View.VISIBLE) {
            mError.setVisibility(View.VISIBLE);
            Motion.riseIn(mError, 0L, 8f);
        }
        Motion.shake(mError);
    }

    private void hideError() {
        if (mError.getVisibility() == View.VISIBLE) Motion.hide(mError, true);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (480 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }
}
