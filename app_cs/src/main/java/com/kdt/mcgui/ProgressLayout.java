package com.kdt.mcgui;


import com.craftstudio.cslauncher.utils.AnimationManager;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.res.ResourcesCompat;

import com.craftstudio.cslauncher.R;

import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.progresskeeper.ProgressListener;
import com.craftstudio.cslauncher.progresskeeper.TaskCountListener;

import java.util.ArrayList;


/** Class staring at specific values and automatically show something if the progress is present
 * Since progress is posted in a specific way, The packing/unpacking is handheld by the class
 *
 * This class relies on ExtraCore for its behavior.
 */
public class ProgressLayout extends ConstraintLayout implements View.OnClickListener, TaskCountListener{
    public static final String UNPACK_RUNTIME = "unpack_runtime";
    public static final String DOWNLOAD_GAME = "download_minecraft";
    public static final String DOWNLOAD_VERSION_LIST = "download_verlist";
    public static final String AUTHENTICATE = "authenticate";
    public static final String INSTALL_MODPACK = "install_modpack";
    public static final String EXTRACT_COMPONENTS = "extract_components";
    public static final String EXTRACT_SINGLE_FILES = "extract_single_files";
    public static final String INSTANCE_INSTALL = "instance_install";
    public static final String DATA_MIGRATION = "data_migration";

    public ProgressLayout(@NonNull Context context) {
        super(context);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private final ArrayList<LayoutProgressListener> mMap = new ArrayList<>();
    private LinearLayout mLinearLayout;
    private TextView mTaskNumberDisplayer;
    private TextView mPercentDisplayer;
    private View mStrip;
    private ImageView mFlipArrow;
    private View mDot;
    private ObjectAnimator mPulse;



    public void observe(String progressKey){
        mMap.add(new LayoutProgressListener(progressKey));
    }

    public void cleanUpObservers() {
        for(LayoutProgressListener progressListener : mMap) {
            ProgressKeeper.removeListener(progressListener.progressKey, progressListener);
        }
    }

    public boolean hasProcesses(){
        return ProgressKeeper.getTaskCount() > 0;
    }


    private void init(){
        inflate(getContext(), R.layout.view_progress, this);
        mLinearLayout = findViewById(R.id.progress_linear_layout);
        mTaskNumberDisplayer = findViewById(R.id.progress_textview);
        mPercentDisplayer = findViewById(R.id.progress_percent);
        mStrip = findViewById(R.id.progress_strip);
        mStrip.setPivotX(0f);
        mFlipArrow = findViewById(R.id.progress_flip_arrow);
        mDot = findViewById(R.id.progress_dot);
        setBackground(ResourcesCompat.getDrawable(getResources(), R.drawable.bg_dl_dock, getContext().getTheme()));
        setOnClickListener(this);
        // Keep the console summary fresh even while it is collapsed
        ProgressKeeper.addGlobalListener(mGlobalListener);
    }

    private void startPulse() {
        if (mDot == null || !AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT)) return;
        if (mPulse == null) {
            mPulse = ObjectAnimator.ofFloat(mDot, "alpha", 1f, 0.3f);
            mPulse.setDuration(900L);
            mPulse.setRepeatCount(ValueAnimator.INFINITE);
            mPulse.setRepeatMode(ValueAnimator.REVERSE);
            mPulse.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        if (!mPulse.isRunning()) mPulse.start();
    }

    private void stopPulse() {
        if (mPulse != null) mPulse.cancel();
        if (mDot != null) mDot.setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        ProgressKeeper.removeGlobalListener(mGlobalListener);
        super.onDetachedFromWindow();
    }


    /** Update the progress bar content */
    public static void setProgress(String progressKey, int progress){
        ProgressKeeper.submitProgress(progressKey, progress, -1, (Object)null);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, @StringRes int resource, Object... message){
        ProgressKeeper.submitProgress(progressKey, progress, resource, message);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, String message){
        setProgress(progressKey,progress, -1, message);
    }

    /** Update the text and progress content */
    public static void clearProgress(String progressKey){
        setProgress(progressKey, -1, -1);
    }

    @Override
    public void onClick(View v) {
        mLinearLayout.setVisibility(mLinearLayout.getVisibility() == GONE ? VISIBLE : GONE);
        mFlipArrow.setRotation(mLinearLayout.getVisibility() == GONE? 0 : 180);
    }

    /** Keeps the header summary ("3 active") and the big percent + strip in sync. */
    private final ProgressKeeper.GlobalProgressListener mGlobalListener = (taskCount, percent) -> post(() -> {
        if (taskCount == 0) return;
        mTaskNumberDisplayer.setText(
                getContext().getString(R.string.progress_console_subtitle_unknown, taskCount));
        if (percent >= 0) {
            mPercentDisplayer.setText(percent + "%");
            mStrip.setScaleX(Math.max(0f, Math.min(1f, percent / 100f)));
        } else {
            mPercentDisplayer.setText("–");
            mStrip.setScaleX(0f);
        }
    });

    /** When true (set by the home screen, which shows progress on the PLAY button instead),
     *  the floating panel stays hidden while still keeping its task-count registration. */
    private boolean mHiddenByHost = false;

    public void setHiddenByHost(boolean hidden) {
        post(() -> {
            mHiddenByHost = hidden;
            applyVisibility(ProgressKeeper.getTaskCount());
        });
    }

    private void applyVisibility(int tc) {
        boolean motion = AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT);
        if (mHiddenByHost || tc <= 0) {
            stopPulse();
            if (getVisibility() == GONE) return;
            if (!motion || !isAttachedToWindow()) {
                setVisibility(GONE);
                return;
            }
            animate().alpha(0f).translationY(-16f).setDuration(220L).withEndAction(() -> {
                setVisibility(GONE);
                setAlpha(1f);
                setTranslationY(0f);
            }).start();
            return;
        }
        mTaskNumberDisplayer.setText(
                getContext().getString(R.string.progress_console_subtitle_unknown, tc));
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            if (motion && isAttachedToWindow()) {
                setAlpha(0f);
                setTranslationY(-16f);
                animate().alpha(1f).translationY(0f).setDuration(300L).start();
            }
        }
        startPulse();
    }

    @Override
    public boolean onUpdateTaskCount(int tc) {
        post(()->applyVisibility(tc));
        return false;
    }

    class LayoutProgressListener implements ProgressListener {
        final String progressKey;
        final TaskProgressCard card;
        final LinearLayout.LayoutParams params;
        boolean isAttached = false;
        public LayoutProgressListener(String progressKey) {
            this.progressKey = progressKey;
            card = new TaskProgressCard(getContext());
            card.setTitle(describeTask(getContext(), progressKey));
            card.setIcon(iconForTask(progressKey));
            int margin = getResources().getDimensionPixelOffset(R.dimen._6sdp);
            params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.bottomMargin = margin;
            ProgressKeeper.addListener(progressKey, this);
        }
        @Override
        public void onProgressStarted() {
            post(()-> {
                Log.i("ProgressLayout", "onProgressStarted");
                if(!isAttached) mLinearLayout.addView(card, params);
                isAttached = true;
                update(-1, -1, new Object[0]);
            });
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            post(()-> update(progress, resid, va));
        }

        private void update(int progress, int resid, Object... va) {
            if (progress >= 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    card.setProgress(progress, false);
                } else {
                    card.setProgress(progress, true);
                }
            } else {
                card.setProgress(-1, true);
            }
            String detail = null;
            if(resid != -1) detail = getContext().getString(resid, va);
            else if(va.length > 0 && va[0] != null) detail = va[0].toString();
            card.setDetail(detail == null ? getContext().getString(R.string.progress_task_unknown) : detail);
        }

        @Override
        public void onProgressEnded() {
            post(()-> {
                mLinearLayout.removeView(card);
                isAttached = false;
            });
        }
    }

    /** Glyph for a progress key, shown on the card tile. */
    private static int iconForTask(@NonNull String key) {
        switch (key) {
            case DOWNLOAD_GAME: return R.drawable.ic_px_file_dl;
            case DOWNLOAD_VERSION_LIST: return R.drawable.ic_px_hash;
            case UNPACK_RUNTIME: return R.drawable.ic_px_runtime_mgr;
            case AUTHENTICATE: return R.drawable.ic_px_account_edit;
            case INSTALL_MODPACK: return R.drawable.ic_px_data_import;
            case EXTRACT_COMPONENTS: return R.drawable.ic_px_sliders;
            case EXTRACT_SINGLE_FILES: return R.drawable.ic_px_file;
            case INSTANCE_INSTALL: return R.drawable.ic_px_folder;
            case DATA_MIGRATION: return R.drawable.ic_px_migrate;
            default: return R.drawable.ic_px_file_dl;
        }
    }

    /** Human label for a progress key, used as the card title. */
    private static String describeTask(Context context, @NonNull String key) {        switch (key) {
            case DOWNLOAD_GAME: return "Game files";
            case DOWNLOAD_VERSION_LIST: return "Version list";
            case UNPACK_RUNTIME: return "Java runtime";
            case AUTHENTICATE: return "Signing in";
            case INSTALL_MODPACK: return "Modpack";
            case EXTRACT_COMPONENTS: return "Components";
            case EXTRACT_SINGLE_FILES: return "Assets";
            case INSTANCE_INSTALL: return "Instance";
            case DATA_MIGRATION: return "Data migration";
            default: return key;
        }
    }
}
