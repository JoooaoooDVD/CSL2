package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * The guide engine. One instance per process, bound to the visible {@link LauncherActivity}
 * (onStart/onStop). A guide is a list of steps; running a step means: make sure its screen is
 * open (launcher navigation, polled until the fragment is resumed), find the target view
 * (bounded retries, scrolled into view), then spotlight it with the explanation card.
 * <p>
 * Guided only: the overlay swallows every touch outside its card, so the launcher cannot change
 * under the guide; Next / Back / Close are the only controls. Every failure ends in a Retry /
 * Skip card, never in a stuck overlay. Back pauses the guide (resumable from the Tutorials screen).
 */
public final class TutorialManager implements TutorialOverlay.Callbacks {
    private static final long TICK_MS = 150L;
    private static final int NAVIGATION_TICKS = 34;   // ~5 s
    private static final int TARGET_TICKS = 20;       // ~3 s
    private static final int NAVIGATE_EVERY = 6;      // re-issue the navigation every ~900 ms

    public interface SequenceListener {
        void onSequenceFinished(boolean onboarding);
        void onSequencePaused(boolean onboarding);
    }

    private enum Phase { IDLE, NAVIGATING, LOCATING, SHOWING, FALLBACK }

    private static TutorialManager sInstance;

    @NonNull
    public static synchronized TutorialManager getInstance() {
        if (sInstance == null) sInstance = new TutorialManager();
        return sInstance;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable private LauncherActivity mActivity;
    @Nullable private TutorialOverlay mOverlay;
    @Nullable private BackCallback mWindowCallback;
    @Nullable private SequenceListener mSequenceListener;

    @Nullable private TutorialDefinition mDefinition;
    private int mStepIndex;
    private List<String> mSequence = new ArrayList<>();
    private int mSequenceIndex;
    private boolean mOnboarding;
    private Phase mPhase = Phase.IDLE;
    private int mTicks;
    private int mDirection = 1;
    private int mRelocates;
    private boolean mFallbackUnavailable;
    @Nullable private SettingsPopup mExitPopup;
    private final Runnable mTick = this::tick;

    private TutorialManager() {}

    // ───────────────────────────── binding ─────────────────────────────

    @MainThread
    public void attach(@NonNull LauncherActivity activity) {
        if (mActivity == activity) return;
        if (mActivity != null) detach(mActivity);
        mActivity = activity;
        if (mDefinition != null && mPhase != Phase.IDLE) {
            // Recreated activity (theme / configuration): rebuild the current step on it
            mHandler.removeCallbacks(mTick);
            mHandler.postDelayed(() -> { if (mActivity == activity) beginStep(); }, 700L);
        }
    }

    @MainThread
    public void detach(@NonNull LauncherActivity activity) {
        if (mActivity != activity) return;
        mHandler.removeCallbacks(mTick);
        removeOverlay(false);
        uninstallWindowCallback();
        if (mExitPopup != null) {
            try { mExitPopup.dismiss(); } catch (Throwable ignored) {}
            mExitPopup = null;
        }
        mActivity = null;
    }

    @Nullable
    public LauncherActivity activity() {
        return mActivity;
    }

    public boolean isRunning() {
        return mDefinition != null && mPhase != Phase.IDLE;
    }

    public void setSequenceListener(@Nullable SequenceListener listener) {
        mSequenceListener = listener;
    }

    // ───────────────────────────── starting ─────────────────────────────

    @MainThread
    public boolean start(@NonNull String tutorialId) {
        List<String> seq = new ArrayList<>();
        seq.add(tutorialId);
        return startSequence(seq, false);
    }

    @MainThread
    public boolean startSequence(@NonNull List<String> tutorialIds, boolean onboarding) {
        if (mActivity == null) return false;
        if (isRunning()) cancel();
        mSequence = new ArrayList<>(tutorialIds);
        mSequenceIndex = -1;
        mOnboarding = onboarding;
        return nextInSequence(0);
    }

    /** Resumes the paused run saved in {@link TutorialState}, if any */
    @MainThread
    public boolean resumeSaved() {
        LauncherActivity activity = mActivity;
        if (activity == null) return false;
        TutorialState.ActiveRun run = TutorialState.getActiveRun(activity);
        if (run == null) return false;
        TutorialDefinition def = TutorialRegistry.get(run.tutorialId);
        if (def == null || def.steps().isEmpty()) {
            TutorialState.clearActiveRun(activity);
            return false;
        }
        mSequence = new ArrayList<>();
        mSequence.add(def.id);
        mSequenceIndex = 0;
        mOnboarding = false;
        mDefinition = def;
        mStepIndex = Math.max(0, Math.min(run.step, def.steps().size() - 1));
        mDirection = 1;
        TutorialState.setActivePaused(activity, false);
        beginStep();
        return true;
    }

    public boolean hasResumableRun(@NonNull Context context) {
        TutorialState.ActiveRun run = TutorialState.getActiveRun(context);
        return run != null && TutorialRegistry.get(run.tutorialId) != null;
    }

    private boolean nextInSequence(int from) {
        LauncherActivity activity = mActivity;
        if (activity == null) return false;
        for (int i = from; i < mSequence.size(); i++) {
            TutorialDefinition def = TutorialRegistry.get(mSequence.get(i));
            if (def == null || !def.isAvailable(activity) || def.steps().isEmpty()) continue;
            mSequenceIndex = i;
            mDefinition = def;
            mStepIndex = 0;
            mDirection = 1;
            beginStep();
            return true;
        }
        finishSequence();
        return false;
    }

    private void finishSequence() {
        LauncherActivity activity = mActivity;
        boolean onboarding = mOnboarding;
        clearRun(true);
        if (activity != null) TutorialState.clearActiveRun(activity);
        if (mSequenceListener != null) mSequenceListener.onSequenceFinished(onboarding);
    }

    private void clearRun(boolean animateOut) {
        mHandler.removeCallbacks(mTick);
        removeOverlay(animateOut);
        uninstallWindowCallback();
        mDefinition = null;
        mStepIndex = 0;
        mPhase = Phase.IDLE;
    }

    // ───────────────────────────── pausing / exiting ─────────────────────────────

    /** Stop for now; the position is kept so the Tutorials screen can offer Resume */
    @MainThread
    public void pause() {
        LauncherActivity activity = mActivity;
        if (mDefinition == null) return;
        if (activity != null) {
            TutorialState.setActiveRun(activity, mDefinition.id, mStepIndex, mSequence, mSequenceIndex, false);
            TutorialState.setActivePaused(activity, true);
        }
        boolean onboarding = mOnboarding;
        clearRun(true);
        if (mSequenceListener != null) mSequenceListener.onSequencePaused(onboarding);
    }

    /** Drops the run completely */
    @MainThread
    public void cancel() {
        LauncherActivity activity = mActivity;
        clearRun(true);
        if (activity != null) TutorialState.clearActiveRun(activity);
    }

    private void confirmExit() {
        LauncherActivity activity = mActivity;
        if (activity == null || activity.isFinishing()) {
            pause();
            return;
        }
        if (mExitPopup != null && mExitPopup.isShowing()) return;
        mExitPopup = new SettingsPopup(activity)
                .title(R.string.tut_exit_title)
                .message(R.string.tut_exit_message)
                .positive(R.string.tut_exit_confirm, this::pause)
                .negative(R.string.tut_exit_keep, null)
                .onClosed(() -> mExitPopup = null);
        mExitPopup.show();
    }

    // ───────────────────────────── step machine ─────────────────────────────

    @Nullable
    private TutorialStep currentStep() {
        if (mDefinition == null) return null;
        List<TutorialStep> steps = mDefinition.steps();
        if (mStepIndex < 0 || mStepIndex >= steps.size()) return null;
        return steps.get(mStepIndex);
    }

    private void beginStep() {
        LauncherActivity activity = mActivity;
        TutorialDefinition def = mDefinition;
        if (activity == null || def == null) return;
        mHandler.removeCallbacks(mTick);
        mRelocates = 0;
        mFallbackUnavailable = false;
        List<TutorialStep> steps = def.steps();
        // Skip steps whose condition is not met, in the direction we are moving
        while (mStepIndex >= 0 && mStepIndex < steps.size()) {
            TutorialStep s = steps.get(mStepIndex);
            boolean ok;
            try {
                ok = s.condition == null || s.condition.isMet(activity);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) break;
            mStepIndex += mDirection;
        }
        if (mStepIndex < 0) {
            mStepIndex = 0;
            mDirection = 1;
            beginStep();
            return;
        }
        if (mStepIndex >= steps.size()) {
            completeModule();
            return;
        }
        TutorialState.setActiveRun(activity, def.id, mStepIndex, mSequence, mSequenceIndex, false);
        TutorialState.setActivePaused(activity, false);
        TutorialState.setProgress(activity, def.id, mStepIndex);
        installWindowCallback();
        TutorialOverlay overlay = ensureOverlay(activity);
        if (overlay == null) return;
        TutorialStep step = steps.get(mStepIndex);
        if (step.screen != TutorialScreen.ANY && !step.screen.isAvailable(activity)) {
            showFallback(true);
            return;
        }
        mTicks = 0;
        if (step.screen == TutorialScreen.ANY || step.screen.isOn(activity)) {
            mPhase = Phase.LOCATING;
            tick();
        } else {
            // Screen change ahead: keep the dim, hide the card, navigate with the launcher's own calls
            mPhase = Phase.NAVIGATING;
            overlay.showTransition();
            if (!step.screen.navigate(activity)) {
                showFallback(true);
                return;
            }
            mHandler.postDelayed(mTick, TICK_MS);
        }
    }

    private void tick() {
        LauncherActivity activity = mActivity;
        TutorialStep step = currentStep();
        if (activity == null || step == null || activity.isFinishing() || activity.isDestroyed()) return;
        mTicks++;
        if (mPhase == Phase.NAVIGATING) {
            if (step.screen.isOn(activity)) {
                mPhase = Phase.LOCATING;
                mTicks = 0;
                mHandler.postDelayed(mTick, 320L); // let the entrance transition settle
                return;
            }
            if (mTicks > NAVIGATION_TICKS) {
                showFallback(true);
                return;
            }
            if (mTicks % NAVIGATE_EVERY == 0) step.screen.navigate(activity);
            mHandler.postDelayed(mTick, TICK_MS);
            return;
        }
        if (mPhase == Phase.LOCATING) {
            if (!step.hasTarget()) {
                present(null);
                return;
            }
            View target = TutorialTargets.resolve(activity, step);
            if (target != null) {
                if (TutorialTargets.visibleFraction(target) < 0.9f && mTicks < TARGET_TICKS - 3) {
                    TutorialTargets.scrollIntoView(target);
                    mHandler.postDelayed(mTick, 220L);
                    return;
                }
                present(target);
                return;
            }
            if (mTicks > TARGET_TICKS) {
                if (step.optional) advance();
                else showFallback(false);
                return;
            }
            mHandler.postDelayed(mTick, TICK_MS);
        }
    }

    private void present(@Nullable View target) {
        LauncherActivity activity = mActivity;
        TutorialDefinition def = mDefinition;
        TutorialStep step = currentStep();
        if (activity == null || def == null || step == null) return;
        TutorialOverlay overlay = ensureOverlay(activity);
        if (overlay == null) return;
        mPhase = Phase.SHOWING;
        int total = def.steps().size();
        String eyebrow = activity.getString(R.string.tut_step_counter, mStepIndex + 1, total, activity.getString(def.title));
        boolean last = mStepIndex >= total - 1 && mSequenceIndex >= mSequence.size() - 1;
        overlay.setContent(eyebrow, activity.getText(step.title), activity.getText(step.body), null,
                mStepIndex > 0, activity.getText(last ? R.string.tut_done : R.string.tut_next), null,
                total <= 1 ? -1f : (mStepIndex + 1) / (float) total);
        overlay.setTarget(target, false);
    }

    /** Something is missing: Retry / Skip instead of hanging */
    private void showFallback(boolean screenProblem) {
        LauncherActivity activity = mActivity;
        TutorialDefinition def = mDefinition;
        TutorialStep step = currentStep();
        if (activity == null || def == null || step == null) return;
        if (step.optional) {
            advance();
            return;
        }
        TutorialOverlay overlay = ensureOverlay(activity);
        if (overlay == null) return;
        mPhase = Phase.FALLBACK;
        mFallbackUnavailable = screenProblem && !step.screen.isAvailable(activity);
        int bodyRes = mFallbackUnavailable ? R.string.tut_fallback_unavailable
                : screenProblem ? R.string.tut_fallback_navigation : R.string.tut_fallback_target;
        String eyebrow = activity.getString(R.string.tut_step_counter, mStepIndex + 1, def.steps().size(), activity.getString(def.title));
        overlay.setContent(eyebrow, activity.getText(step.title), activity.getText(bodyRes), null, mStepIndex > 0,
                activity.getText(mFallbackUnavailable ? R.string.tut_skip_step : R.string.tut_retry),
                mFallbackUnavailable ? null : activity.getText(R.string.tut_skip_step), -1f);
        overlay.setTarget(null, false);
    }

    private void advance() {
        if (mDefinition == null) return;
        mHandler.removeCallbacks(mTick);
        mDirection = 1;
        mStepIndex++;
        if (mStepIndex >= mDefinition.steps().size()) completeModule();
        else beginStep();
    }

    private void completeModule() {
        LauncherActivity activity = mActivity;
        TutorialDefinition def = mDefinition;
        if (def == null) return;
        if (activity != null) TutorialState.setCompleted(activity, def.id);
        if (mSequenceIndex + 1 < mSequence.size()) {
            nextInSequence(mSequenceIndex + 1);
        } else {
            boolean onboarding = mOnboarding;
            finishSequence();
            if (activity != null && !onboarding) {
                Toast.makeText(activity, activity.getString(R.string.tut_module_done_toast, activity.getString(def.title)), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ───────────────────────────── overlay callbacks ─────────────────────────────

    @Override
    public void onNext() {
        if (mPhase == Phase.FALLBACK) {
            if (mFallbackUnavailable) advance();
            else beginStep(); // retry
        } else if (mPhase == Phase.SHOWING) {
            advance();
        }
    }

    @Override
    public void onBack() {
        if (mDefinition == null || mPhase == Phase.NAVIGATING) return;
        mHandler.removeCallbacks(mTick);
        mDirection = -1;
        if (mStepIndex > 0) {
            mStepIndex--;
        } else {
            mDirection = 1;
        }
        beginStep();
    }

    @Override
    public void onSkipStep() {
        if (mDefinition == null || mPhase == Phase.NAVIGATING) return;
        advance();
    }

    @Override
    public void onClose() {
        if (mPhase == Phase.IDLE) return;
        confirmExit();
    }

    @Override
    public void onTargetLost() {
        // The view went away (layout change): look it up again, once; then the Retry card
        if (mPhase != Phase.SHOWING) return;
        if (++mRelocates > 2) {
            showFallback(false);
            return;
        }
        mPhase = Phase.LOCATING;
        mTicks = 0;
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, 250L);
    }

    private boolean onBackKey() {
        if (mOverlay == null || mPhase == Phase.IDLE) return false;
        confirmExit();
        return true;
    }

    // ───────────────────────────── overlay / window plumbing ─────────────────────────────

    @Nullable
    private TutorialOverlay ensureOverlay(@NonNull LauncherActivity activity) {
        TutorialOverlay overlay = mOverlay;
        if (overlay != null && overlay.isAttachedToWindow() && overlay.getContext() == activity) {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null && parent.getChildAt(parent.getChildCount() - 1) != overlay) overlay.bringToFront();
            return overlay;
        }
        if (overlay != null) removeOverlay(false);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        overlay = new TutorialOverlay(activity);
        overlay.setCallbacks(this);
        content.addView(overlay, TutorialOverlay.fullSize());
        overlay.fadeIn();
        mOverlay = overlay;
        return overlay;
    }

    private void removeOverlay(boolean animate) {
        final TutorialOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay == null) return;
        overlay.setCallbacks(null);
        final ViewGroup parent = overlay.getParent() instanceof ViewGroup ? (ViewGroup) overlay.getParent() : null;
        if (parent == null) return;
        if (animate && Motion.content(overlay)) {
            overlay.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction(() -> parent.removeView(overlay)).start();
        } else {
            parent.removeView(overlay);
        }
    }

    private void installWindowCallback() {
        LauncherActivity activity = mActivity;
        if (activity == null || mWindowCallback != null) return;
        Window window = activity.getWindow();
        Window.Callback original = window.getCallback();
        if (original == null || original instanceof BackCallback) return;
        mWindowCallback = new BackCallback(original);
        window.setCallback(mWindowCallback);
    }

    private void uninstallWindowCallback() {
        BackCallback callback = mWindowCallback;
        mWindowCallback = null;
        if (callback == null) return;
        callback.active = false;
        LauncherActivity activity = mActivity;
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window.getCallback() == callback) window.setCallback(callback.wrapped);
    }

    /** Turns the Back key into "stop the guide?" while the overlay is up; everything else passes through */
    private final class BackCallback implements Window.Callback {
        final Window.Callback wrapped;
        boolean active = true;

        BackCallback(Window.Callback wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (active && event.getKeyCode() == KeyEvent.KEYCODE_BACK && mOverlay != null && mPhase != Phase.IDLE
                    && (mExitPopup == null || !mExitPopup.isShowing())) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackKey();
                return true;
            }
            return wrapped.dispatchKeyEvent(event);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) { return wrapped.dispatchTouchEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return wrapped.dispatchKeyShortcutEvent(event); }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) { return wrapped.dispatchTrackballEvent(event); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return wrapped.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { return wrapped.dispatchPopulateAccessibilityEvent(event); }
        @Nullable @Override public View onCreatePanelView(int featureId) { return wrapped.onCreatePanelView(featureId); }
        @Override public boolean onCreatePanelMenu(int featureId, @NonNull android.view.Menu menu) { return wrapped.onCreatePanelMenu(featureId, menu); }
        @Override public boolean onPreparePanel(int featureId, @Nullable View view, @NonNull android.view.Menu menu) { return wrapped.onPreparePanel(featureId, view, menu); }
        @Override public boolean onMenuOpened(int featureId, @NonNull android.view.Menu menu) { return wrapped.onMenuOpened(featureId, menu); }
        @Override public boolean onMenuItemSelected(int featureId, @NonNull android.view.MenuItem item) { return wrapped.onMenuItemSelected(featureId, item); }
        @Override public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) { wrapped.onWindowAttributesChanged(attrs); }
        @Override public void onContentChanged() { wrapped.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean hasFocus) { wrapped.onWindowFocusChanged(hasFocus); }
        @Override public void onAttachedToWindow() { wrapped.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { wrapped.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int featureId, @NonNull android.view.Menu menu) { wrapped.onPanelClosed(featureId, menu); }
        @Override public boolean onSearchRequested() { return wrapped.onSearchRequested(); }
        @Override public boolean onSearchRequested(android.view.SearchEvent searchEvent) { return wrapped.onSearchRequested(searchEvent); }
        @Nullable @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) { return wrapped.onWindowStartingActionMode(callback); }
        @Nullable @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) { return wrapped.onWindowStartingActionMode(callback, type); }
        @Override public void onActionModeStarted(android.view.ActionMode mode) { wrapped.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(android.view.ActionMode mode) { wrapped.onActionModeFinished(mode); }
        @Override public void onProvideKeyboardShortcuts(List<android.view.KeyboardShortcutGroup> data, @Nullable android.view.Menu menu, int deviceId) { wrapped.onProvideKeyboardShortcuts(data, menu, deviceId); }
        @Override public void onPointerCaptureChanged(boolean hasCapture) { wrapped.onPointerCaptureChanged(hasCapture); }
    }

    /** Guide that explains the screen currently visible (the "?" button) */
    @Nullable
    public String tutorialForCurrentScreen() {
        LauncherActivity activity = mActivity;
        if (activity == null) return null;
        return TutorialRegistry.tutorialForScreen(activity);
    }
}
