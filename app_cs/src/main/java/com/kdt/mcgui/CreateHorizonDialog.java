package com.kdt.mcgui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.engine.horizon.HorizonEngine;
import com.craftstudio.cslauncher.engine.horizon.HorizonInstallService;
import com.craftstudio.cslauncher.engine.horizon.HorizonInstallState;
import com.craftstudio.cslauncher.engine.horizon.HorizonProfile;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * "Create New Instance → Horizon Engine": profile form, then the shared engine setup with progress
 * inside the same popup. Also reopened by PLAY for a Horizon profile whose engine is not ready yet
 * ({@link #resume}), so an interrupted setup is picked up where it stopped — verified downloads are
 * cached by the engine, nothing is downloaded twice and no second profile is created.
 */
public class CreateHorizonDialog extends CSPopupDialog {
    private static final long POLL_MS = 1000;

    @Nullable private final Runnable mOnBack;
    /** Existing profile when reopened from PLAY; null while the form is shown. */
    @Nullable private Instance mInstance;

    private EditText mName;
    private TextView mRendererValue, mControlsValue, mScaleValue, mEngineStatus, mEngineBuild;
    private View mEngineDot;
    private SeekBar mScaleSlider;
    private View mForm, mProgress, mCreate, mBack;
    private TextView mCancel;
    private TextView mCreateText, mProgressProfile, mProgressStage, mProgressPercent, mProgressDetail,
            mProgressError, mProgressHint, mSummary;
    private View mCreateIcon;
    private ProgressBar mProgressBar;

    private final List<String> mRendererIds = HorizonProfile.rendererIds();
    private int mRendererIndex = -1; // -1 = engine default
    private final List<String> mControls = new ArrayList<>();
    private int mControlsIndex = -1; // -1 = launcher default
    private int mScaleIndex = HorizonProfile.SCALE_STEPS.length - 1;

    private boolean mCreating;
    private boolean mSetupMode;
    private boolean mReceiverRegistered;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mSetupMode || !isShowing()) return;
            refreshSetup();
            mHandler.postDelayed(this, POLL_MS);
        }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mSetupMode) refreshSetup();
        }
    };

    public CreateHorizonDialog(@NonNull Context context, @Nullable Runnable onBack) {
        super(context);
        mOnBack = onBack;
    }

    /** PLAY on a Horizon profile that is not ready: show/continue the setup for that profile. */
    public static CreateHorizonDialog resume(@NonNull Context context, @NonNull Instance instance) {
        CreateHorizonDialog dialog = new CreateHorizonDialog(context, null);
        dialog.mInstance = instance;
        return dialog;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_create_horizon);
        View card = findViewById(R.id.hz_card);
        setupPopup(findViewById(R.id.hz_scrim), card, true);
        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        sizeCard(card);
        bindViews();
        if (mInstance != null) {
            enterSetupMode(mInstance);
        } else {
            CreatePopupMotion.formContent(findViewById(R.id.hz_header), findViewById(R.id.hz_left),
                    findViewById(R.id.hz_right), findViewById(R.id.hz_footer));
        }
        CreatePopupMotion.enter(mScrim, mCard);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (780 * d), metrics.widthPixels - (int) (40 * d));
        params.height = metrics.heightPixels > 560 * d ? (int) (470 * d) : ViewGroup.LayoutParams.MATCH_PARENT;
        card.setLayoutParams(params);
    }

    private void bindViews() {
        final Context context = getContext();
        mName = findViewById(R.id.hz_name);
        mRendererValue = findViewById(R.id.hz_renderer_value);
        mControlsValue = findViewById(R.id.hz_controls_value);
        mScaleValue = findViewById(R.id.hz_scale_value);
        mScaleSlider = findViewById(R.id.hz_scale_slider);
        mEngineStatus = findViewById(R.id.hz_engine_status);
        mEngineBuild = findViewById(R.id.hz_engine_build);
        mEngineDot = findViewById(R.id.hz_engine_dot);
        mForm = findViewById(R.id.hz_form);
        mProgress = findViewById(R.id.hz_progress);
        mCreate = findViewById(R.id.hz_create);
        mCreateText = findViewById(R.id.hz_create_text);
        mCreateIcon = findViewById(R.id.hz_create_icon);
        mCancel = findViewById(R.id.hz_cancel);
        mBack = findViewById(R.id.hz_back);
        mSummary = findViewById(R.id.hz_summary);
        mProgressProfile = findViewById(R.id.hz_progress_profile);
        mProgressStage = findViewById(R.id.hz_progress_stage);
        mProgressPercent = findViewById(R.id.hz_progress_percent);
        mProgressDetail = findViewById(R.id.hz_progress_detail);
        mProgressError = findViewById(R.id.hz_progress_error);
        mProgressHint = findViewById(R.id.hz_progress_hint);
        mProgressBar = findViewById(R.id.hz_progress_bar);
        mCreate.setClipToOutline(true);
        findViewById(R.id.hz_header_tile).setClipToOutline(true);

        String build = HorizonEngine.bundledLabel(context);
        mEngineBuild.setText(build.isEmpty() ? "" : "v" + build);

        mBack.setOnClickListener(v -> {
            if (mCreating) return;
            hideKeyboard();
            close(mOnBack);
        });
        if (mOnBack == null) mBack.setVisibility(View.GONE);
        findViewById(R.id.hz_close).setOnClickListener(v -> onDismissRequested());
        mCancel.setOnClickListener(v -> onCancelClicked());
        mCreate.setOnClickListener(v -> onPrimaryClicked());

        // Renderer
        mRendererValue.setText(HorizonProfile.rendererLabel(context, null));
        findViewById(R.id.hz_renderer_field).setOnClickListener(v -> {
            if (mCreating) return;
            hideKeyboard();
            List<String> names = HorizonProfile.rendererNames(context);
            new RendererPickerDialog(context, mRendererIds, names, mRendererIndex < 0 ? mRendererIds.size() : mRendererIndex,
                    context.getString(R.string.hz_renderer_default), index -> {
                        mRendererIndex = index >= mRendererIds.size() ? -1 : index;
                        Motion.swapText(mRendererValue, HorizonProfile.rendererLabel(context,
                                mRendererIndex < 0 ? null : mRendererIds.get(mRendererIndex)));
                    }).show();
        });

        // CS controls layout
        mControls.clear();
        mControls.addAll(HorizonProfile.controlLayouts());
        mControlsValue.setText(R.string.hz_controls_default);
        findViewById(R.id.hz_controls_field).setOnClickListener(v -> {
            if (mCreating) return;
            hideKeyboard();
            List<String> items = new ArrayList<>();
            items.add(context.getString(R.string.hz_controls_default));
            for (String name : mControls) items.add(name.replace(".json", ""));
            new ListPickerDialog(context, context.getText(R.string.hz_title), context.getString(R.string.hz_controls),
                    items, null, mControlsIndex + 1, index -> {
                        mControlsIndex = index - 1;
                        Motion.swapText(mControlsValue, items.get(index));
                    }).show();
        });

        // Resolution scale
        mScaleSlider.setMax(HorizonProfile.SCALE_STEPS.length - 1);
        mScaleSlider.setProgress(mScaleIndex);
        mScaleValue.setText(HorizonProfile.SCALE_STEPS[mScaleIndex] + "%");
        mScaleSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mScaleIndex = progress;
                mScaleValue.setText(HorizonProfile.SCALE_STEPS[progress] + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        refreshEngineStatus();
    }

    private void refreshEngineStatus() {
        Context context = getContext();
        int text;
        int color;
        if (HorizonEngine.isInstalled(context)) {
            text = R.string.hz_engine_installed;
            color = 0xFF5BD37A;
        } else if (HorizonInstallState.get(context).isRunning()) {
            text = R.string.hz_engine_installing;
            color = 0xFFE7B75F;
        } else {
            text = R.string.hz_engine_missing;
            color = ContextCompat.getColor(context, R.color.hm_text_dim);
        }
        mEngineStatus.setText(text);
        mEngineDot.getBackground().mutate().setTint(color);
    }

    private String defaultName() {
        return getContext().getString(R.string.hz_name_hint);
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    private void onPrimaryClicked() {
        if (mSetupMode) {
            onSetupPrimary();
            return;
        }
        if (mCreating) return;
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(getContext(), R.string.hz_tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        hideKeyboard();
        String typed = mName.getText() != null ? mName.getText().toString().trim() : "";
        final String name = typed.isEmpty() ? defaultName() : typed;
        final String renderer = mRendererIndex < 0 ? null : mRendererIds.get(mRendererIndex);
        final int scale = HorizonProfile.SCALE_STEPS[mScaleIndex];
        final String controls = mControlsIndex < 0 ? null : mControls.get(mControlsIndex);
        final Context context = getContext();
        mCreating = true;
        mCreate.setAlpha(0.6f);
        CSLauncherApplication.sExecutorService.execute(() -> {
            try {
                final Instance created = HorizonProfile.create(context, name, renderer, scale, controls);
                Tools.runOnUiThread(() -> onCreated(created));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    mCreating = false;
                    mCreate.setAlpha(1f);
                    if (isShowing()) Tools.showError(context, e);
                    else Tools.showErrorRemote(e);
                });
            }
        });
    }

    private void onCreated(@NonNull Instance instance) {
        mCreating = false;
        mCreate.setAlpha(1f);
        mInstance = instance;
        // Appears in the profile selector right away, and becomes the selected profile
        Instances.setSelectedInstance(instance);
        Context context = getContext();
        if (HorizonEngine.isInstalled(context)) {
            Toast.makeText(context, context.getString(R.string.hz_ready_toast, instance.name), Toast.LENGTH_LONG).show();
            if (isShowing()) close(null);
            return;
        }
        Toast.makeText(context, context.getString(R.string.hz_created_toast, instance.name), Toast.LENGTH_SHORT).show();
        enterSetupMode(instance);
    }

    // ─── Setup (download / verify / prepare) ────────────────────────────────

    private void enterSetupMode(@NonNull Instance instance) {
        mInstance = instance;
        mSetupMode = true;
        mForm.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mBack.setVisibility(View.GONE);
        mSummary.setText("");
        mProgressProfile.setText(getContext().getString(R.string.hz_progress_profile, instance.name));
        HorizonProfile.ensureInstalling(getContext());
        registerReceiver();
        refreshSetup();
        mHandler.removeCallbacks(mPoll);
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void refreshSetup() {
        if (mInstance == null) return;
        Context context = getContext();
        String state = HorizonProfile.effectiveState(context, mInstance);
        HorizonInstallState install = HorizonInstallState.get(context);
        mProgressError.setVisibility(View.GONE);
        switch (state) {
            case HorizonProfile.STATE_READY: {
                mProgressStage.setText(R.string.horizon_install_done);
                mProgressPercent.setText("100%");
                mProgressBar.setIndeterminate(false);
                mProgressBar.setProgress(100);
                mProgressDetail.setText(R.string.hz_state_ready);
                mProgressHint.setVisibility(View.INVISIBLE);
                setPrimary(R.string.hz_progress_play, true);
                mCancel.setVisibility(View.GONE);
                break;
            }
            case HorizonProfile.STATE_FAILED: {
                mProgressStage.setText(R.string.horizon_install_failed);
                mProgressPercent.setText("");
                mProgressBar.setIndeterminate(false);
                mProgressBar.setProgress(0);
                mProgressDetail.setText(R.string.hz_state_failed);
                String error = install.isInterrupted() ? context.getString(R.string.horizon_install_interrupted)
                        : install.error == null ? context.getString(R.string.horizon_install_incomplete) : install.error;
                mProgressError.setText(error);
                mProgressError.setVisibility(View.VISIBLE);
                mProgressHint.setVisibility(View.VISIBLE);
                setPrimary(R.string.hz_progress_retry, true);
                mCancel.setText(R.string.hz_progress_hide);
                mCancel.setVisibility(View.VISIBLE);
                break;
            }
            case HorizonProfile.STATE_NOT_INSTALLED: {
                // Cancelled, or the service has not reported yet: offer to start again.
                boolean cancelled = install.status == HorizonInstallState.STATUS_CANCELLED;
                mProgressStage.setText(cancelled ? R.string.horizon_install_cancelled : R.string.horizon_install_preparing);
                mProgressPercent.setText(cancelled ? "" : "0%");
                mProgressBar.setIndeterminate(!cancelled);
                mProgressBar.setProgress(0);
                mProgressDetail.setText(HorizonProfile.stateLabel(context, state));
                mProgressHint.setVisibility(View.VISIBLE);
                setPrimary(cancelled ? R.string.hz_progress_retry : R.string.hz_progress_hide, cancelled);
                mCancel.setText(cancelled ? R.string.hz_progress_hide : R.string.hz_progress_cancel);
                mCancel.setVisibility(View.VISIBLE);
                break;
            }
            default: {
                int percent = install.overallPercent();
                mProgressStage.setText(install.stageLabel(context));
                mProgressPercent.setText(percent + "%");
                boolean indeterminate = install.total <= 0 && install.stageIndex() != HorizonInstallState.STAGE_DOWNLOAD;
                mProgressBar.setIndeterminate(indeterminate && percent == 0);
                if (!mProgressBar.isIndeterminate()) mProgressBar.setProgress(percent);
                String detail = install.detailLabel(context);
                String stateText = HorizonProfile.stateLabel(context, state);
                mProgressDetail.setText(detail.isEmpty() ? stateText : stateText + " · " + detail);
                mProgressHint.setVisibility(View.VISIBLE);
                setPrimary(R.string.hz_progress_hide, false);
                mCancel.setText(R.string.hz_progress_cancel);
                mCancel.setVisibility(View.VISIBLE);
                break;
            }
        }
    }

    private void setPrimary(int text, boolean accent) {
        mCreateText.setText(text);
        mCreateIcon.setVisibility(accent ? View.VISIBLE : View.GONE);
        mCreate.setAlpha(1f);
    }

    private void onSetupPrimary() {
        if (mInstance == null) return;
        Context context = getContext();
        String state = HorizonProfile.effectiveState(context, mInstance);
        switch (state) {
            case HorizonProfile.STATE_READY:
                // PLAY through the normal launch path (account checks, launching animation)
                Instances.setSelectedInstance(mInstance);
                close(() -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
                break;
            case HorizonProfile.STATE_FAILED:
                HorizonInstallService.start(context);
                refreshSetup();
                break;
            case HorizonProfile.STATE_NOT_INSTALLED:
                if (HorizonInstallState.get(context).status == HorizonInstallState.STATUS_CANCELLED) {
                    HorizonInstallService.start(context);
                    refreshSetup();
                } else {
                    close(null);
                }
                break;
            default:
                close(null); // hide: the service keeps going
                break;
        }
    }

    private void onCancelClicked() {
        if (!mSetupMode) {
            if (mCreating) return;
            hideKeyboard();
            close(null);
            return;
        }
        Context context = getContext();
        HorizonInstallState install = HorizonInstallState.get(context);
        if (install.isRunning()) {
            HorizonInstallService.cancel(context);
            mCancel.setText(R.string.hz_progress_hide);
            Toast.makeText(context, R.string.horizon_install_cancelled, Toast.LENGTH_SHORT).show();
            mHandler.postDelayed(this::refreshSetup, 400);
        } else {
            close(null);
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    private void registerReceiver() {
        if (mReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(HorizonInstallService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(mReceiver, filter);
        }
        mReceiverRegistered = true;
    }

    @Override
    protected void onDismissRequested() {
        if (mCreating) {
            CreatePopupMotion.nudge(mCard);
            return;
        }
        hideKeyboard();
        close(null);
    }

    @Override
    public void dismiss() {
        mHandler.removeCallbacks(mPoll);
        if (mReceiverRegistered) {
            try {
                getContext().unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mReceiverRegistered = false;
        }
        // Profile list / home pill pick up the final state
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
        super.dismiss();
    }

    private void hideKeyboard() {
        if (mName == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mName.getWindowToken(), 0);
    }
}
