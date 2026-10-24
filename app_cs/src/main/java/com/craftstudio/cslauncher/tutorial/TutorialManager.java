package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * The tutorial engine. One instance per process; it binds to the visible {@link LauncherActivity}
 * ({@link #attach}/{@link #detach} from onStart/onStop) and survives recreation: the current
 * module/step live here and in {@link TutorialState}, the overlay is rebuilt on the new activity.
 * <p>
 * Running a step = make sure the required screen is open (real navigation, polled until the
 * fragment is resumed) → find the real target view (bounded retries) → show the spotlight →
 * wait for the completion (Next, a tap on the control, a launcher event, a screen or a
 * preference change) → persist progress → next step. Every failure path ends in a card with
 * Retry / Skip / Exit, never in a stuck overlay: the launcher stays usable at all times.
 */
public final class TutorialManager implements TutorialEvents.Listener, TutorialOverlay.Callbacks {
    private static final long TICK_MS = 150L;
    private static final int NAVIGATION_TICKS = 30;   // 4.5 s
    private static final int TARGET_TICKS = 16;       // 2.4 s
    private static final int NAVIGATE_EVERY = 5;      // re-issue the navigation every 750 ms

    public interface SequenceListener {
        /** The whole sequence finished (every module completed or skipped) */
        void onSequenceFinished(boolean onboarding);
        /** The user exited the sequence (resumable) */
        void onSequencePaused(boolean onboarding);
    }

    private enum Phase { IDLE, NAVIGATING, LOCATING, SHOWING, WAITING_RESUME, FALLBACK }

    private static TutorialManager sInstance;

    @NonNull
    public static synchronized TutorialManager getInstance() {
        if (sInstance == null) sInstance = new TutorialManager();
        return sInstance;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Nullable private LauncherActivity mActivity;
    @Nullable private TutorialOverlay mOverlay;
    @Nullable private TutorialWindowCallback mWindowCallback;
    @Nullable private SequenceListener mSequenceListener;

    // run state
    @Nullable private TutorialDefinition mDefinition;
    private int mStepIndex;
    private List<String> mSequence = new ArrayList<>();
    private int mSequenceIndex;
    private boolean mOnboarding;
    private boolean mInteractive;
    private Phase mPhase = Phase.IDLE;
    private int mTicks;
    private int mDirection = 1;
    @Nullable private View mTargetView;
    private boolean mStepCompleted;
    private boolean mFallbackUnavailable;
    private long mShownAt;
    @Nullable private SettingsPopup mExitPopup;

    private final Runnable mTick = this::tick;
    private boolean mDialogSeen;
    /** Armed when a tap hid the overlay for a dialog: if the dialog never showed up, come back */
    private final Runnable mResumeSafety = () -> {
        if (mPhase == Phase.WAITING_RESUME && !mDialogSeen) {
            mPhase = Phase.SHOWING;
            if (mOverlay != null) mOverlay.setSuspended(false);
        }
    };

    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            TutorialEvents.screen(f.getClass());
            final LauncherActivity activity = mActivity;
            if (activity == null || isRunning()) return;
            final String name = f.getClass().getSimpleName();
            mHandler.postDelayed(() -> {
                if (mActivity == activity && !isRunning() && f.isResumed()) ContextualHelp.onScreenShown(activity, name);
            }, 900L);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = (prefs, key) -> {
        if (key == null) return;
        TutorialStep step = currentStep();
        if (step != null && step.completion == TutorialStep.Completion.PREF_CHANGED && key.equals(step.event)
                && (mPhase == Phase.SHOWING || mPhase == Phase.WAITING_RESUME)) {
            completeStep(400L);
        }
    };

    private TutorialManager() {}

    // ───────────────────────────── binding ─────────────────────────────

    @MainThread
    public void attach(@NonNull LauncherActivity activity) {
        if (mActivity == activity) return;
        if (mActivity != null) detach(mActivity);
        mActivity = activity;
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbacks, true);
        TutorialEvents.addListener(this);
        try {
            LauncherPreferences.DEFAULT_PREF.registerOnSharedPreferenceChangeListener(mPrefListener);
        } catch (Throwable ignored) {
        }
        FirstLaunchSetup.onActivityAttached(activity);
        if (mDefinition != null && mPhase != Phase.IDLE) {
            // Recreated (theme change, configuration): rebuild the current step on the new activity
            mHandler.removeCallbacks(mTick);
            mHandler.postDelayed(() -> { if (mActivity == activity) beginStep(); }, 650L);
        }
    }

    @MainThread
    public void detach(@NonNull LauncherActivity activity) {
        if (mActivity != activity) return;
        mHandler.removeCallbacks(mTick);
        removeOverlay();
        uninstallWindowCallback();
        try {
            activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbacks);
        } catch (Throwable ignored) {
        }
        TutorialEvents.removeListener(this);
        try {
            LauncherPreferences.DEFAULT_PREF.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        } catch (Throwable ignored) {
        }
        if (mExitPopup != null) {
            try { mExitPopup.dismiss(); } catch (Throwable ignored) {}
            mExitPopup = null;
        }
        mTargetView = null;
        mActivity = null;
    }

    /** The bound activity, if any */
    @Nullable
    public LauncherActivity activity() {
        return mActivity;
    }

    public boolean isRunning() {
        return mDefinition != null && mPhase != Phase.IDLE;
    }

    @Nullable
    public String runningTutorialId() {
        return isRunning() && mDefinition != null ? mDefinition.id : null;
    }

    public void setSequenceListener(@Nullable SequenceListener listener) {
        mSequenceListener = listener;
    }

    // ───────────────────────────── starting ─────────────────────────────

    /** Starts (or restarts from the first step) a single tutorial */
    @MainThread
    public boolean start(@NonNull String tutorialId) {
        List<String> seq = new ArrayList<>();
        seq.add(tutorialId);
        return startSequence(seq, false);
    }

    /** Runs the modules one after the other; unavailable modules are skipped */
    @MainThread
    public boolean startSequence(@NonNull List<String> tutorialIds, boolean onboarding) {
        LauncherActivity activity = mActivity;
        if (activity == null) return false;
        mSequence = new ArrayList<>(tutorialIds);
        mSequenceIndex = -1;
        mOnboarding = onboarding;
        mInteractive = TutorialState.isInteractive(activity);
        return nextInSequence(0);
    }

    /** Resumes the interrupted run saved in {@link TutorialState}, if any */
    @MainThread
    public boolean resumeSaved() {
        LauncherActivity activity = mActivity;
        if (activity == null) return false;
        TutorialState.ActiveRun run = TutorialState.getActiveRun(activity);
        if (run == null) return false;
        TutorialDefinition def = TutorialRegistry.get(run.tutorialId);
        if (def == null) {
            TutorialState.clearActiveRun(activity);
            return false;
        }
        mSequence = run.sequence.isEmpty() ? new ArrayList<>() : new ArrayList<>(run.sequence);
        if (mSequence.isEmpty()) mSequence.add(def.id);
        mSequenceIndex = Math.min(run.sequenceIndex, mSequence.size() - 1);
        mOnboarding = run.onboarding;
        mInteractive = TutorialState.isInteractive(activity);
        mDefinition = def;
        mStepIndex = Math.min(run.step, Math.max(0, def.steps().size() - 1));
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
        clearRun();
        if (activity != null) TutorialState.clearActiveRun(activity);
        if (mSequenceListener != null) mSequenceListener.onSequenceFinished(onboarding);
    }

    private void clearRun() {
        mHandler.removeCallbacks(mTick);
        mHandler.removeCallbacks(mResumeSafety);
        removeOverlay();
        uninstallWindowCallback();
        mDefinition = null;
        mStepIndex = 0;
        mPhase = Phase.IDLE;
        mTargetView = null;
        mStepCompleted = false;
    }

    // ───────────────────────────── pausing / exiting ─────────────────────────────

    /** Exit for now, keeping the position (Tutorial Center → Resume) */
    @MainThread
    public void pause() {
        LauncherActivity activity = mActivity;
        if (mDefinition == null) return;
        if (activity != null) {
            TutorialState.setActiveRun(activity, mDefinition.id, mStepIndex, mSequence, mSequenceIndex, mOnboarding);
            TutorialState.setActivePaused(activity, true);
        }
        boolean onboarding = mOnboarding;
        clearRun();
        if (mSequenceListener != null) mSequenceListener.onSequencePaused(onboarding);
    }

    /** Drops the run completely (nothing to resume) */
    @MainThread
    public void cancel() {
        LauncherActivity activity = mActivity;
        clearRun();
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
        mHandler.removeCallbacks(mResumeSafety);
        mStepCompleted = false;
        mTargetView = null;
        mFallbackUnavailable = false;

        // Skip steps whose runtime condition is not met (in the direction we are moving)
        List<TutorialStep> steps = def.steps();
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
        TutorialState.setActiveRun(activity, def.id, mStepIndex, mSequence, mSequenceIndex, mOnboarding);
        TutorialState.setActivePaused(activity, false);
        TutorialState.setProgress(activity, def.id, mStepIndex);
        installWindowCallback();

        TutorialStep step = steps.get(mStepIndex);
        if (step.screen != TutorialScreen.ANY && !step.screen.isAvailable(activity)) {
            showFallback(true);
            return;
        }
        mTicks = 0;
        if (step.screen == TutorialScreen.ANY || step.screen.isOn(activity)) {
            mPhase = Phase.LOCATING;
            // A hidden overlay while the screen settles avoids a flash of the previous card
            if (mOverlay != null) mOverlay.setSuspended(true);
            tick();
        } else {
            mPhase = Phase.NAVIGATING;
            if (mOverlay != null) mOverlay.setSuspended(true);
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
        if (activity == null || step == null || activity.isFinishing()) return;
        mTicks++;
        if (mPhase == Phase.NAVIGATING) {
            if (step.screen.isOn(activity)) {
                mPhase = Phase.LOCATING;
                mTicks = 0;
                mHandler.postDelayed(mTick, 250L); // let the entrance transition settle
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
                if (TutorialTargets.visibleFraction(target) < 0.85f && mTicks < TARGET_TICKS - 2) {
                    TutorialTargets.scrollIntoView(target);
                    mHandler.postDelayed(mTick, 200L);
                    return;
                }
                present(target);
                return;
            }
            if (mTicks > TARGET_TICKS) {
                if (step.optional) {
                    advance();
                } else {
                    showFallback(false);
                }
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
        mTargetView = target;
        mShownAt = SystemClock.elapsedRealtime();
        boolean interactive = step.isInteractive(mInteractive) && target != null;
        int total = def.steps().size();
        String eyebrow = activity.getString(R.string.tut_step_counter, mStepIndex + 1, total, activity.getString(def.title));
        CharSequence instruction = step.instruction != 0 ? activity.getText(step.instruction) : null;
        if (!interactive && step.completion != TutorialStep.Completion.NEXT) {
            // Guided mode: the real interaction is optional, Next moves on
            instruction = instruction == null ? null : activity.getString(R.string.tut_guided_hint, instruction);
        }
        boolean last = mStepIndex >= total - 1 && mSequenceIndex >= mSequence.size() - 1;
        CharSequence next = interactive ? null : activity.getText(last ? R.string.tut_done : R.string.tut_next);
        CharSequence skip = interactive ? activity.getText(R.string.tut_skip_step) : null;
        overlay.setSuspended(false);
        overlay.setContent(eyebrow, activity.getText(step.title), activity.getText(step.body), instruction,
                mStepIndex > 0, next, skip, total <= 1 ? -1f : (mStepIndex + 1) / (float) total);
        // The spotlighted control always stays usable, also in guided mode
        overlay.setTarget(target, target != null);
    }

    /** Something is missing: offer Retry / Skip / Exit instead of hanging */
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
        mTargetView = null;
        int bodyRes = mFallbackUnavailable ? R.string.tut_fallback_unavailable
                : screenProblem ? R.string.tut_fallback_navigation : R.string.tut_fallback_target;
        String eyebrow = activity.getString(R.string.tut_step_counter, mStepIndex + 1, def.steps().size(), activity.getString(def.title));
        overlay.setSuspended(false);
        overlay.setContent(eyebrow, activity.getText(step.title), activity.getString(bodyRes, activity.getString(step.title)),
                null, mStepIndex > 0, activity.getText(mFallbackUnavailable ? R.string.tut_skip_step : R.string.tut_retry),
                mFallbackUnavailable ? null : activity.getText(R.string.tut_skip_step), -1f);
        overlay.setTarget(null, false);
    }

    /** The step's requirement was met: move on (after a short delay so the UI reaction is visible) */
    private void completeStep(long delay) {
        if (mStepCompleted) return;
        mStepCompleted = true;
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(this::advance, delay);
    }

    private void advance() {
        if (mDefinition == null) return;
        mDirection = 1;
        mStepIndex++;
        if (mStepIndex >= mDefinition.steps().size()) {
            completeModule();
        } else {
            beginStep();
        }
    }

    private void completeModule() {
        LauncherActivity activity = mActivity;
        TutorialDefinition def = mDefinition;
        if (def == null) return;
        if (activity != null) TutorialState.setCompleted(activity, def.id);
        if (mSequenceIndex + 1 < mSequence.size()) {
            nextInSequence(mSequenceIndex + 1);
        } else {
            finishSequence();
            if (activity != null && !mOnboarding) {
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
            return;
        }
        if (mPhase == Phase.SHOWING) {
            TutorialStep step = currentStep();
            if (step != null && step.isInteractive(mInteractive) && mTargetView != null) return; // Next hidden anyway
            completeStep(0L);
        }
    }

    @Override
    public void onBack() {
        if (mDefinition == null) return;
        mHandler.removeCallbacks(mTick);
        mDirection = -1;
        if (mStepIndex > 0) {
            mStepIndex--;
            beginStep();
        } else if (mSequenceIndex > 0) {
            // Previous module, its last step
            for (int i = mSequenceIndex - 1; i >= 0; i--) {
                TutorialDefinition def = TutorialRegistry.get(mSequence.get(i));
                if (def != null && !def.steps().isEmpty() && mActivity != null && def.isAvailable(mActivity)) {
                    mSequenceIndex = i;
                    mDefinition = def;
                    mStepIndex = def.steps().size() - 1;
                    beginStep();
                    return;
                }
            }
            mDirection = 1;
            beginStep();
        } else {
            mDirection = 1;
            beginStep();
        }
    }

    @Override
    public void onSkipStep() {
        if (mDefinition == null) return;
        mHandler.removeCallbacks(mTick);
        advance();
    }

    @Override
    public void onClose() {
        confirmExit();
    }

    @Override
    public void onTargetLost() {
        // The control disappeared (screen changed under us): locate it again, with the usual bounds
        if (mPhase != Phase.SHOWING || mStepCompleted) return;
        beginStep();
    }

    // ───────────────────────────── launcher events ─────────────────────────────

    @Override
    public void onTutorialEvent(@NonNull String event) {
        TutorialStep step = currentStep();
        if (step == null || mDefinition == null) return;
        if (mPhase == Phase.WAITING_RESUME) {
            // Waiting for the dialog opened from the target to close
            if (event.equals(step.event)) mDialogSeen = true;
            if (event.equals(step.resumeEvent) || event.startsWith(TutorialEvents.SCREEN_PREFIX)) {
                mHandler.removeCallbacks(mResumeSafety);
                if (mOverlay != null) mOverlay.setSuspended(false);
                completeStep(350L);
            }
            return;
        }
        if (mPhase != Phase.SHOWING || mStepCompleted) return;
        boolean matches = false;
        switch (step.completion) {
            case EVENT:
                matches = event.equals(step.event);
                break;
            case SCREEN:
                matches = event.equals(TutorialEvents.SCREEN_PREFIX + step.event);
                break;
            case TAP_TARGET:
                // A tap that opened a dialog instrumented with events also counts
                matches = step.resumeEvent == null && event.equals(step.event);
                break;
            default:
                break;
        }
        if (!matches) return;
        if (step.resumeEvent != null) {
            mPhase = Phase.WAITING_RESUME;
            mDialogSeen = true;
            mHandler.removeCallbacks(mResumeSafety);
            if (mOverlay != null) mOverlay.setSuspended(true);
        } else {
            completeStep(step.completion == TutorialStep.Completion.SCREEN ? 450L : 300L);
        }
    }

    /** Called by the window callback when the user tapped inside the cutout */
    private void onTargetTapped() {
        TutorialStep step = currentStep();
        if (step == null || mPhase != Phase.SHOWING || mStepCompleted) return;
        if (SystemClock.elapsedRealtime() - mShownAt < 250L) return; // ignore the tap that opened the step
        switch (step.completion) {
            case TAP_TARGET:
                if (step.resumeEvent != null) {
                    // A dialog is about to open: hide, come back when it closes
                    mPhase = Phase.WAITING_RESUME;
                    mDialogSeen = true;
                    if (mOverlay != null) mOverlay.setSuspended(true);
                } else {
                    completeStep(350L);
                }
                break;
            case EVENT:
            case SCREEN:
            case PREF_CHANGED:
                // The tap happened, the real state change (or a dialog) follows; hide so the control's
                // own UI (spinner popups, pickers) is not covered. Events bring us back.
                if (step.resumeEvent != null && mOverlay != null) {
                    mPhase = Phase.WAITING_RESUME;
                    mDialogSeen = false;
                    mOverlay.setSuspended(true);
                    mHandler.removeCallbacks(mResumeSafety);
                    mHandler.postDelayed(mResumeSafety, 1500L);
                }
                break;
            default:
                break;
        }
    }

    /** Back while the overlay is visible pauses the tutorial instead of popping screens under it */
    private boolean onBackKey() {
        if (mOverlay == null || mOverlay.isSuspended() || mPhase == Phase.IDLE) return false;
        if (mPhase == Phase.WAITING_RESUME) return false;
        confirmExit();
        return true;
    }

    // ───────────────────────────── overlay / window plumbing ─────────────────────────────

    @Nullable
    private TutorialOverlay ensureOverlay(@NonNull LauncherActivity activity) {
        if (mOverlay != null && mOverlay.isAttachedToWindow()) {
            // Always keep it on top of whatever the launcher added to the content view meanwhile
            ViewGroup parent = (ViewGroup) mOverlay.getParent();
            if (parent != null && parent.getChildAt(parent.getChildCount() - 1) != mOverlay) {
                mOverlay.bringToFront();
            }
            return mOverlay;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        TutorialOverlay overlay = new TutorialOverlay(activity);
        overlay.setCallbacks(this);
        content.addView(overlay, TutorialOverlay.fullSize());
        mOverlay = overlay;
        return overlay;
    }

    private void removeOverlay() {
        TutorialOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay == null) return;
        overlay.setCallbacks(null);
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent == null) return;
        if (Motion.content(overlay)) {
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
        if (original == null || original instanceof TutorialWindowCallback) return;
        mWindowCallback = new TutorialWindowCallback(original);
        window.setCallback(mWindowCallback);
    }

    private void uninstallWindowCallback() {
        TutorialWindowCallback callback = mWindowCallback;
        mWindowCallback = null;
        if (callback == null) return;
        callback.active = false;
        LauncherActivity activity = mActivity;
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window.getCallback() == callback) window.setCallback(callback.wrapped);
    }

    /**
     * Sees every touch before the decor view does: detects taps inside the cutout (the overlay lets
     * them through, so it cannot observe them itself) and turns Back into "pause tutorial".
     */
    private final class TutorialWindowCallback implements Window.Callback {
        final Window.Callback wrapped;
        boolean active = true;
        private final Rect mCutout = new Rect();
        private boolean mDownInside;
        private float mDownX, mDownY;

        TutorialWindowCallback(Window.Callback wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (active && mOverlay != null && !mOverlay.isSuspended()) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    mOverlay.getCutoutInWindow(mCutout);
                    mDownInside = !mCutout.isEmpty() && mCutout.contains((int) event.getX(), (int) event.getY());
                    mDownX = event.getX();
                    mDownY = event.getY();
                } else if (action == MotionEvent.ACTION_UP && mDownInside) {
                    mDownInside = false;
                    float slop = 24f * mActivity.getResources().getDisplayMetrics().density;
                    if (Math.abs(event.getX() - mDownX) < slop && Math.abs(event.getY() - mDownY) < slop
                            && mCutout.contains((int) event.getX(), (int) event.getY())) {
                        mHandler.post(TutorialManager.this::onTargetTapped);
                    }
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    mDownInside = false;
                }
            }
            return wrapped.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (active && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                    && !event.isCanceled() && onBackKey()) {
                return true;
            }
            if (active && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                    && mOverlay != null && !mOverlay.isSuspended() && mPhase != Phase.IDLE && mPhase != Phase.WAITING_RESUME) {
                return true; // swallow the DOWN too, so the launcher does not pop a screen on UP
            }
            return wrapped.dispatchKeyEvent(event);
        }

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

    // ───────────────────────────── helpers for other components ─────────────────────────────

    /** Tutorial that explains the screen currently visible, for contextual help */
    @Nullable
    public String tutorialForCurrentScreen() {
        LauncherActivity activity = mActivity;
        if (activity == null) return null;
        return TutorialRegistry.tutorialForScreen(activity);
    }
}
