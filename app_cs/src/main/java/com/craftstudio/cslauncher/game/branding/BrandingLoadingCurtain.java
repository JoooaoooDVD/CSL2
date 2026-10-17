package com.craftstudio.cslauncher.game.branding;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Logger;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.multiplayer.LogTap;

import java.io.InputStream;

/**
 * Launcher-side branded loading screen for the CS Custom Experience.
 * <p>
 * Minecraft's very first loading overlay (the Mojang Studios logo) is drawn from the built-in
 * vanilla pack before any resource pack is active, so a pack cannot replace it. Instead the
 * launcher draws this curtain over the game surface from the moment the activity is created
 * until the game reports (via its own log) that the title screen is about to appear. The game
 * keeps rendering underneath; nothing about the game process changes.
 * <p>
 * Milestones come from log lines every 1.21+ client prints (vanilla, Fabric, Forge/NeoForge):
 * "Setting user", "Backend library: LWJGL", "Reloading ResourceManager", atlas "Created:",
 * "Sound engine started". The sound engine starts at the end of the first resource reload, right
 * before the title screen fades in, so the curtain fades out {@value #TITLE_GRACE_MS} ms later.
 * Safety nets: no further log activity for {@value #IDLE_FALLBACK_MS} ms after textures were
 * created, a hard cap of {@value #HARD_CAP_MS} ms, a tap anywhere, or the activity going away.
 */
public final class BrandingLoadingCurtain {
    private static final long TITLE_GRACE_MS = 1600L;
    private static final long IDLE_FALLBACK_MS = 12_000L;
    private static final long HARD_CAP_MS = 180_000L;
    private static final long FADE_MS = 420L;

    private final Activity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final View mRoot;
    private final TextView mStatus;
    private final View mBar;
    private final View mTrack;
    private final View mGlow;
    private final View mHint;
    @Nullable private ValueAnimator mBarAnimator;
    @Nullable private ValueAnimator mGlowAnimator;
    private boolean mDismissed;
    private boolean mSawTextures;
    private boolean mSawSound;

    private final Runnable mDismissRunnable = () -> dismiss("milestone");
    private final Runnable mIdleRunnable = () -> dismiss("idle fallback");
    private final Runnable mHardCapRunnable = () -> dismiss("hard cap");

    private final Logger.eventLogListener mLogSink = this::onLogLine;

    private BrandingLoadingCurtain(@NonNull Activity activity, @NonNull ViewGroup parent, int index,
                                   @Nullable String profileName, @Nullable String versionId) {
        mActivity = activity;
        mRoot = LayoutInflater.from(activity).inflate(R.layout.view_branding_curtain, parent, false);
        mStatus = mRoot.findViewById(R.id.bc_status);
        mBar = mRoot.findViewById(R.id.bc_bar);
        mTrack = mRoot.findViewById(R.id.bc_track);
        mGlow = mRoot.findViewById(R.id.bc_glow);
        mHint = mRoot.findViewById(R.id.bc_hint);
        ImageView logo = mRoot.findViewById(R.id.bc_logo);
        Bitmap bitmap = loadLogo(activity);
        if (bitmap != null) logo.setImageBitmap(bitmap);
        TextView footer = mRoot.findViewById(R.id.bc_footer);
        String name = profileName == null || profileName.trim().isEmpty() ? "Minecraft" : profileName.trim();
        footer.setText(activity.getString(R.string.bc_footer, name, versionId == null ? "" : versionId));
        mRoot.setOnClickListener(v -> dismiss("tap"));
        if (index < 0 || index > parent.getChildCount()) parent.addView(mRoot);
        else parent.addView(mRoot, index);
    }

    /**
     * Shows the curtain. Call from the UI thread once the game layout exists.
     *
     * @param parent the frame holding the game surface; the curtain is inserted at {@code index}
     *               so views after it (log view, drawer) stay on top
     */
    @MainThread
    @NonNull
    public static BrandingLoadingCurtain show(@NonNull Activity activity, @NonNull ViewGroup parent, int index,
                                              @Nullable String profileName, @Nullable String versionId) {
        BrandingLoadingCurtain curtain = new BrandingLoadingCurtain(activity, parent, index, profileName, versionId);
        curtain.start();
        return curtain;
    }

    private void start() {
        // Progress shimmer: a soft platinum bar sweeping the track
        mTrack.post(() -> {
            if (mDismissed) return;
            final float travel = mTrack.getWidth() + mBar.getWidth();
            mBar.setTranslationX(-mBar.getWidth());
            mBarAnimator = ValueAnimator.ofFloat(0f, 1f);
            mBarAnimator.setDuration(1400L);
            mBarAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mBarAnimator.setInterpolator(new LinearInterpolator());
            mBarAnimator.addUpdateListener(a -> mBar.setTranslationX(-mBar.getWidth() + travel * (Float) a.getAnimatedValue()));
            mBarAnimator.start();
        });
        // Gentle breathing glow behind the logo
        mGlowAnimator = ValueAnimator.ofFloat(0.55f, 1f);
        mGlowAnimator.setDuration(1800L);
        mGlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mGlowAnimator.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            mGlow.setAlpha(v);
            mGlow.setScaleX(0.9f + 0.1f * v);
            mGlow.setScaleY(0.9f + 0.1f * v);
        });
        mGlowAnimator.start();
        // The tap hint only appears after a while so a normal launch never shows it
        mHint.postDelayed(() -> { if (!mDismissed) mHint.animate().alpha(1f).setDuration(600L).start(); }, 20_000L);

        mHandler.postDelayed(mHardCapRunnable, HARD_CAP_MS);
        LogTap.addSink(mLogSink);
    }

    /** Called on the log thread for every line the game prints. */
    private void onLogLine(String line) {
        if (mDismissed || line == null) return;
        final int status;
        if (line.contains("Sound engine started")) {
            status = R.string.bc_status_almost;
            mSawSound = true;
        } else if (line.contains("Reloading ResourceManager")) {
            status = R.string.bc_status_resources;
        } else if (line.contains("Created: ") && line.contains("atlas")) {
            status = R.string.bc_status_textures;
            mSawTextures = true;
        } else if (line.contains("Backend library: LWJGL")) {
            status = R.string.bc_status_graphics;
        } else if (line.contains("Setting user:")) {
            status = R.string.bc_status_java;
        } else if (line.contains("Loading Minecraft") && line.contains("with Fabric Loader")
                || line.contains("ModLauncher running")) {
            status = R.string.bc_status_mods;
        } else {
            status = 0;
        }
        mHandler.post(() -> {
            if (mDismissed) return;
            if (status != 0) mStatus.setText(status);
            if (mSawSound) {
                mHandler.removeCallbacks(mIdleRunnable);
                mHandler.removeCallbacks(mDismissRunnable);
                mHandler.postDelayed(mDismissRunnable, TITLE_GRACE_MS);
            } else if (mSawTextures) {
                // Keep pushing the idle fallback while the reload is still chatty
                mHandler.removeCallbacks(mIdleRunnable);
                mHandler.postDelayed(mIdleRunnable, IDLE_FALLBACK_MS);
            }
        });
    }

    /** Fades the curtain out and removes it. Safe to call repeatedly / from any thread. */
    public void dismiss(@NonNull String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> dismiss(reason));
            return;
        }
        if (mDismissed) return;
        mDismissed = true;
        android.util.Log.i("CSBranding", "loading curtain dismissed (" + reason + ")");
        LogTap.removeSink(mLogSink);
        mHandler.removeCallbacksAndMessages(null);
        if (mBarAnimator != null) mBarAnimator.cancel();
        if (mGlowAnimator != null) mGlowAnimator.cancel();
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            detach();
            return;
        }
        mRoot.animate().alpha(0f).setDuration(FADE_MS).withEndAction(this::detach).start();
    }

    private void detach() {
        ViewGroup parent = (ViewGroup) mRoot.getParent();
        if (parent != null) parent.removeView(mRoot);
    }

    public boolean isShowing() {
        return !mDismissed;
    }

    @Nullable
    private static Bitmap loadLogo(@NonNull Activity activity) {
        try {
            InputStream in = activity.getAssets().open("cs_logo.png");
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4; // 1024 -> 256 px, plenty for 112dp
                return BitmapFactory.decodeStream(in, null, options);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
