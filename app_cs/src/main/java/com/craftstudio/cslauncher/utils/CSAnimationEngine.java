package com.craftstudio.cslauncher.utils;

import android.animation.AnimatorInflater;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.transition.Fade;
import android.transition.Slide;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * CSAnimationEngine - the single, lightweight animation engine of CS Launcher Plus.
 *
 * <p>Every non-trivial motion entry point in the launcher routes through this class so
 * timings, interpolators, cancellation behaviour and cleanup live in exactly one place.
 * The engine only ever animates alpha / translation / scale (GPU-composited properties,
 * no layout passes), keeps durations short (90-260ms) and always respects the user's
 * animation-type preferences ({@link AnimationManager}) plus the system
 * "remove animations" accessibility setting.
 *
 * <p>Design rules baked into every method:
 * <ul>
 *   <li>the action itself is never delayed - animations are visual polish only;</li>
 *   <li>a new animation request on the same view always cancels the previous one first,
 *       so repeated calls replace each other predictably;</li>
 *   <li>no listeners or callbacks are retained past the animation, so destroyed views
 *       cannot be updated and nothing leaks;</li>
 *   <li>when animations are disabled, every method degrades to the instant state change
 *       (feedback is preserved, motion is not);</li>
 *   <li>layout animations are configured through {@link #applyListEntrance(ViewGroup)}
 *       only - see that method for the strict rules that keep ViewGroup.dispatchDraw()
 *       out of the framework's unguarded LayoutAnimationController.isDone() read.</li>
 * </ul>
 */
public final class CSAnimationEngine {

    /** Restore timing of the shared press animator (res/animator/cs_press_scale.xml) */
    public static final long DURATION_PRESS = 150L;
    /** Shortest transition: value refresh, quick fades */
    public static final long DURATION_FAST = 150L;
    /** Standard transition: list/screen entrances, dialog content */
    public static final long DURATION_NORMAL = 190L;
    /** Quick-settings value pulse - deliberately snappier than a transition */
    public static final long DURATION_VALUE_PULSE = 130L;

    private static final float SMALL_RISE_DP = 7f;

    private CSAnimationEngine() {}

    // ─── Gating (single source of truth: the user's animation preferences) ─────

    /** @return whether animations of this type should play right now */
    public static boolean enabled(@NonNull Context context, @NonNull String type) {
        return AnimationManager.isEnabled(context, type);
    }

    // ─── Button / card press feedback ──────────────────────────────────────────

    /**
     * Attaches the shared CS spring press animator to a view that does not have one yet.
     * Idempotent (a tag/state check keeps repeated binds from stacking animators) and
     * gated by the TYPE_BUTTONS preference. XML-declared views that already carry
     * {@code ?attr/csPressAnimator} are left untouched, so nested clickables never get
     * conflicting scale animations.
     */
    public static void attachPressFeedback(@Nullable View view) {
        if (view == null) return;
        if (view.getStateListAnimator() != null) return;
        Context context = view.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_BUTTONS)) return;
        try {
            StateListAnimator animator =
                    AnimatorInflater.loadStateListAnimator(context, R.animator.cs_press_scale);
            view.setStateListAnimator(animator);
        } catch (Exception ignored) {
            // A missing animator must never break binding a row - press ripple still works
        }
    }

    // ─── Popup / dropdown transitions ──────────────────────────────────────────

    /**
     * Installs the shared entrance/dismissal transition on a popup (profile dropdown,
     * other drop-downs). Returns the transition in use so callers that must temporarily
     * suppress it (instant dismiss) can restore the exact same instance, or null when
     * popup animations are disabled or unsupported.
     */
    @Nullable
    public static Transition applyPopupTransition(@NonNull Context context, @NonNull PopupWindow popup) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        if (!enabled(context, AnimationManager.TYPE_DIALOGS)) {
            popup.setEnterTransition(null);
            popup.setExitTransition(null);
            return null;
        }
        TransitionSet set = new TransitionSet();
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        Slide slide = new Slide(Gravity.BOTTOM);
        slide.setDuration(DURATION_NORMAL);
        Fade fade = new Fade(Fade.IN);
        fade.setDuration(DURATION_FAST);
        set.addTransition(slide);
        set.addTransition(fade);
        popup.setEnterTransition(set);
        popup.setExitTransition(set);
        return set;
    }

    // ─── Dialog entrance (content-level, runs with the themed window animation) ─

    /**
     * Plays the shared content entrance on a dialog's content view: a short fade with a
     * tiny rise. Runs together with the themed window scale/fade (Animation.CS.Dialog),
     * giving every dialog - confirmation, delete, version picker, theme color, quick
     * settings - one consistent entrance language. Safe to call repeatedly.
     */
    public static void animateDialogContentView(@Nullable View content) {
        if (content == null) return;
        Context context = content.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_DIALOGS)) return;
        content.animate().cancel();
        float rise = SMALL_RISE_DP * context.getResources().getDisplayMetrics().density;
        content.setAlpha(0.35f);
        content.setTranslationY(rise);
        content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(DURATION_NORMAL)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setListener(null)
                .start();
    }

    // ─── Navigation transitions (fragment screens) ─────────────────────────────

    /** Forward: the new screen slides in from the end edge with a fade */
    @NonNull
    public static androidx.transition.Transition makeFragmentForwardEnter() {
        androidx.transition.TransitionSet set = new androidx.transition.TransitionSet();
        set.setOrdering(androidx.transition.TransitionSet.ORDERING_TOGETHER);
        androidx.transition.Slide slide = new androidx.transition.Slide(Gravity.END);
        slide.setDuration(DURATION_NORMAL);
        androidx.transition.Fade fade = new androidx.transition.Fade(androidx.transition.Fade.IN);
        fade.setDuration(DURATION_FAST);
        set.addTransition(slide);
        set.addTransition(fade);
        return set;
    }

    /** Forward: the old screen fades out fast - no lag, no wasted frames */
    @NonNull
    public static androidx.transition.Transition makeFragmentForwardExit() {
        androidx.transition.Fade fade = new androidx.transition.Fade(androidx.transition.Fade.OUT);
        fade.setDuration(130L);
        return fade;
    }

    /** Back: the screen underneath reappears with a quick fade */
    @NonNull
    public static androidx.transition.Transition makeFragmentBackEnter() {
        androidx.transition.Fade fade = new androidx.transition.Fade(androidx.transition.Fade.IN);
        fade.setDuration(160L);
        return fade;
    }

    /** Back: the top screen slides back out to the end edge */
    @NonNull
    public static androidx.transition.Transition makeFragmentBackExit() {
        androidx.transition.TransitionSet set = new androidx.transition.TransitionSet();
        set.setOrdering(androidx.transition.TransitionSet.ORDERING_TOGETHER);
        androidx.transition.Slide slide = new androidx.transition.Slide(Gravity.END);
        slide.setDuration(170L);
        androidx.transition.Fade fade = new androidx.transition.Fade(androidx.transition.Fade.OUT);
        fade.setDuration(DURATION_FAST);
        set.addTransition(slide);
        set.addTransition(fade);
        return set;
    }

    // ─── Theme / window transitions ────────────────────────────────────────────

    /**
     * Applies the activity window transition for the current preference state.
     * Called for every activity before its content view exists, so screen changes
     * (including theme re-application on the next launch) share one visual language.
     */
    public static void applyWindowAnimations(@NonNull Activity activity) {
        activity.getWindow().setWindowAnimations(
                enabled(activity, AnimationManager.TYPE_SCREENS)
                        ? R.style.Animation_CS_Activity
                        : R.style.Animation_CS_ActivityNone);
    }

    // ─── Lightweight list-item appearance (CRASH-SAFE layout entrance) ─────────

    /**
     * Lets the items of a list enter one after the other, exactly once.
     *
     * <p><b>Why this method is the ONLY sanctioned way to touch a view's layout
     * animation.</b> ViewGroup.dispatchDraw() ends with an <i>unguarded</i>
     * {@code mLayoutAnimationController.isDone()} read whenever FLAG_ANIMATION_DONE and
     * FLAG_NOTIFY_ANIMATION_LISTENER are both clear, and the RUN_ANIMATION block reads
     * the controller unguarded as well. {@code setLayoutAnimation(null)} clears the
     * field but leaves those flags dirty, so an external null write - immediate,
     * deferred with post(), listener-driven or from another thread - lands the next
     * draw pass inside the framework and throws the reported
     * {@code NullPointerException: LayoutAnimationController.isDone()} (seen on both
     * ListView/AbsListView and RecyclerView draw paths).
     *
     * <p>Therefore:
     * <ul>
     *   <li>mutations are always serialized onto the UI thread (a draw can only happen
     *       there, so no write can land between the framework's field reads);</li>
     *   <li>the controller is <b>never</b> cleared - only a non-null controller is ever
     *       written. When content animations are off, nothing is applied: a stale
     *       controller from an earlier enabled state is inert because the framework
     *       only (re)starts a stagger from setLayoutAnimation(non-null);</li>
     *   <li>the framework itself completes the animation and settles its flags, so a
     *       single apply gives one self-cleaning enter pass - recycling, data changes
     *       and fragment navigation can never re-trigger it.</li>
     * </ul>
     */
    public static void applyListEntrance(@Nullable ViewGroup list) {
        if (list == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Cross-thread writes are the other way to race dispatchDraw: hop to UI
            list.post(() -> applyListEntrance(list));
            return;
        }
        Context context = list.getContext();
        if (context == null) return;
        if (!enabled(context, AnimationManager.TYPE_CONTENT)) {
            // Deliberately NOT setLayoutAnimation(null) - see the method doc
            return;
        }
        LayoutAnimationController controller =
                AnimationUtils.loadLayoutAnimation(context, R.anim.cs_layout_list);
        if (controller == null) return;
        list.setLayoutAnimation(controller);
    }

    // ─── Staggered container entrance (sidebar, card rows) ─────────────────────

    /**
     * Makes the direct children of a layout slide in from the start (left) one after
     * another. Horizontal motion matches the landscape sidebar; the stagger is capped
     * so a full screen of buttons still animates smoothly on low-end devices.
     * Cancels each child's previous animation first, so repeated calls replace cleanly.
     */
    public static void staggerChildren(@Nullable ViewGroup parent) {
        if (parent == null) return;
        Context context = parent.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_CONTENT)) return;
        float offset = 12f * parent.getResources().getDisplayMetrics().density;
        int shown = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationX(-offset);
            child.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setStartDelay(Math.min(shown, 14) * 22L)
                    .setDuration(DURATION_NORMAL)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
            shown++;
        }
    }

    // ─── Quick-settings value refresh ──────────────────────────────────────────

    /**
     * Updates a live value (RAM, Java runtime, renderer, control layout ...) and gives
     * it a brief fade pulse when - and only when - the text actually changed. The text
     * is always set first, so the information never depends on the animation.
     */
    public static void animateValueRefresh(@NonNull TextView target, @NonNull CharSequence value) {
        CharSequence current = target.getText();
        boolean changed = current == null || !value.toString().contentEquals(current);
        target.setText(value);
        if (!changed) return;
        Context context = target.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_CONTENT)) return;
        target.animate().cancel();
        target.setAlpha(0.35f);
        target.animate()
                .alpha(1f)
                .setDuration(DURATION_VALUE_PULSE)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setListener(null)
                .start();
    }

    // ─── Loading / progress state transitions ──────────────────────────────────

    /** Shows a loading view with a quick fade. Degrades to a plain show when disabled. */
    public static void showLoading(@Nullable View view) {
        if (view == null) return;
        view.animate().cancel();
        Context context = view.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_CONTENT)) {
            view.setAlpha(1f);
            view.setVisibility(View.VISIBLE);
            return;
        }
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .setDuration(DURATION_FAST)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .setListener(null)
                .start();
    }

    /**
     * Hides a loading view with a quick fade, then marks it GONE in the end action.
     * If the animation is cancelled (screen closing, replaced by {@link #showLoading}),
     * the next request on the same view starts from a known state - no stale callbacks
     * can touch a destroyed screen because no listener is retained.
     */
    public static void hideLoading(@Nullable View view) {
        if (view == null) return;
        if (view.getVisibility() == View.GONE) return;
        view.animate().cancel();
        Context context = view.getContext();
        if (context == null || !enabled(context, AnimationManager.TYPE_CONTENT)) {
            view.setAlpha(1f);
            view.setVisibility(View.GONE);
            return;
        }
        view.animate()
                .alpha(0f)
                .setDuration(DURATION_FAST)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                })
                .start();
    }
}
