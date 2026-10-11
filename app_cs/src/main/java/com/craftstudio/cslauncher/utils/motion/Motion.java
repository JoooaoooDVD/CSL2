package com.craftstudio.cslauncher.utils.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.animation.PathInterpolatorCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.AnimationManager;
import com.google.android.material.button.MaterialButton;

/**
 * Shared motion toolkit used by the per-screen motion engines (ProfileEditorMotion,
 * LibraryMotion, InstallMotion). Everything here is built for phones:
 * <ul>
 *   <li>only transform/alpha properties are animated (no layout passes per frame),
 *       complex views get a hardware layer for the duration of the animation;</li>
 *   <li>every call cancels the previous animation of the same view first and always ends
 *       in the final resting state, so cancelled or repeated calls never leave a view
 *       half transparent or offset;</li>
 *   <li>the user's animation preferences (AnimationManager) and the system animator
 *       duration scale are respected - when motion is off the end state is applied
 *       instantly.</li>
 * </ul>
 */
public final class Motion {

    /** Material 3 emphasized decelerate: fast start, long soft landing (entrances) */
    public static final Interpolator ENTER = PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f);
    /** Material 3 emphasized accelerate (exits) */
    public static final Interpolator EXIT = PathInterpolatorCompat.create(0.3f, 0f, 0.8f, 0.15f);
    /** Material 3 standard easing (state changes) */
    public static final Interpolator STANDARD = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f);
    /** Gentle spring-like overshoot for selections and pops */
    public static final Interpolator SPRING = new OvershootInterpolator(1.4f);
    /** Stronger overshoot for small badges and icons */
    public static final Interpolator SPRING_STRONG = new OvershootInterpolator(2.2f);

    public static final long SHORT = 160L;
    public static final long MEDIUM = 280L;
    public static final long LONG = 420L;

    private Motion() {}

    // ─── Gating ──────────────────────────────────────────────────────────────

    /** Whether content motion (entrances, list items, state changes) should play */
    public static boolean content(@Nullable View view) {
        return view != null && allowed(view.getContext(), AnimationManager.TYPE_CONTENT);
    }

    /** Whether button / selection feedback should play */
    public static boolean buttons(@Nullable View view) {
        return view != null && allowed(view.getContext(), AnimationManager.TYPE_BUTTONS);
    }

    public static boolean allowed(@Nullable Context context, @NonNull String type) {
        if (context == null || !systemAnimationsOn(context)) return false;
        try {
            return AnimationManager.isEnabled(context, type);
        } catch (Exception e) {
            return true;
        }
    }

    /** False when the user turned animations off in the developer / accessibility settings */
    public static boolean systemAnimationsOn(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled();
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (Exception e) {
            return true;
        }
    }

    public static float dp(@NonNull View view, float dp) {
        return dp * view.getResources().getDisplayMetrics().density;
    }

    // ─── Reset helpers ───────────────────────────────────────────────────────

    /** Cancels any running property animation and puts the view back to rest */
    public static void settle(@Nullable View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
    }

    private static void prepare(@NonNull View view) {
        view.animate().cancel();
        view.animate().setListener(null).setStartDelay(0);
    }

    /**
     * Entrances start from a clean transform: a cancelled earlier entrance on the same view
     * (e.g. a cascade followed by a pop) must not leave a translation / scale behind.
     */
    private static void prepareEntrance(@NonNull View view) {
        prepare(view);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    // ─── Entrances ───────────────────────────────────────────────────────────

    /** Fades the view in while it rises from {@code riseDp} below its place */
    public static void riseIn(@Nullable View view, long delay, float riseDp) {
        riseIn(view, delay, riseDp, MEDIUM + 60);
    }

    public static void riseIn(@Nullable View view, long delay, float riseDp, long duration) {
        if (view == null) return;
        if (!content(view)) {
            settle(view);
            return;
        }
        prepareEntrance(view);
        view.setAlpha(0f);
        view.setTranslationY(dp(view, riseDp));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(duration)
                .setInterpolator(ENTER)
                .withLayer()
                .start();
    }

    /** Fades the view in while it slides horizontally from {@code fromDp} */
    public static void slideInX(@Nullable View view, long delay, float fromDp) {
        if (view == null) return;
        if (!content(view)) {
            settle(view);
            return;
        }
        prepareEntrance(view);
        view.setAlpha(0f);
        view.setTranslationX(dp(view, fromDp));
        view.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(delay)
                .setDuration(MEDIUM + 80)
                .setInterpolator(ENTER)
                .withLayer()
                .start();
    }

    /** Scales the view up from {@code fromScale} with a soft overshoot */
    public static void popIn(@Nullable View view, long delay, float fromScale) {
        if (view == null) return;
        if (!content(view)) {
            settle(view);
            return;
        }
        prepareEntrance(view);
        view.setAlpha(0f);
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delay)
                .setDuration(MEDIUM + 40)
                .setInterpolator(SPRING)
                .start();
    }

    /** Plain fade in */
    public static void fadeIn(@Nullable View view, long delay, long duration) {
        if (view == null) return;
        view.setVisibility(View.VISIBLE);
        if (!content(view)) {
            view.animate().cancel();
            view.setAlpha(1f);
            return;
        }
        prepareEntrance(view);
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(delay).setDuration(duration).setInterpolator(STANDARD).start();
    }

    /** Shows a view that may currently be gone, with a short rise; no-op when already visible */
    public static void reveal(@Nullable View view) {
        if (view == null) return;
        if (view.getVisibility() == View.VISIBLE && view.getAlpha() == 1f) return;
        view.setVisibility(View.VISIBLE);
        riseIn(view, 0, 8, MEDIUM);
    }

    /** Fades a view out and then hides it (GONE or INVISIBLE) */
    public static void hide(@Nullable View view, boolean gone) {
        if (view == null) return;
        int target = gone ? View.GONE : View.INVISIBLE;
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(target);
            return;
        }
        if (!content(view)) {
            view.animate().cancel();
            view.setVisibility(target);
            view.setAlpha(1f);
            return;
        }
        prepare(view);
        view.animate().alpha(0f).setDuration(SHORT).setInterpolator(EXIT)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean mCancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.animate().setListener(null);
                        if (mCancelled) return;
                        view.setVisibility(target);
                        view.setAlpha(1f);
                    }
                }).start();
    }

    /** Staggers {@link #riseIn} over the direct children of a group */
    public static void cascadeChildren(@Nullable ViewGroup group, long startDelay, long step, float riseDp) {
        if (group == null) return;
        int visibleIndex = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            riseIn(child, startDelay + Math.min(visibleIndex, 10) * step, riseDp);
            visibleIndex++;
        }
    }

    /** Staggers {@link #popIn} over the direct children of a group */
    public static void popChildren(@Nullable ViewGroup group, long startDelay, long step, float fromScale) {
        if (group == null) return;
        int visibleIndex = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            popIn(child, startDelay + Math.min(visibleIndex, 12) * step, fromScale);
            visibleIndex++;
        }
    }

    // ─── Feedback ────────────────────────────────────────────────────────────

    /** Quick "squish and spring back" used when something gets selected */
    public static void bounce(@Nullable View view) {
        if (view == null || !buttons(view)) return;
        prepare(view);
        view.setScaleX(0.86f);
        view.setScaleY(0.86f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(MEDIUM + 60).setInterpolator(SPRING_STRONG).start();
    }

    /** Short attention pulse (grows a little and settles) */
    public static void pulse(@Nullable View view) {
        if (view == null || !buttons(view)) return;
        prepare(view);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).setInterpolator(STANDARD)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(SPRING).start())
                .start();
    }

    /** Horizontal "no" shake for invalid input */
    public static void shake(@Nullable View view) {
        if (view == null) return;
        if (!content(view)) return;
        view.animate().cancel();
        view.setTranslationX(0f);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        float amplitude = dp(view, 9);
        animator.setDuration(420);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            view.setTranslationX((float) (Math.sin(t * Math.PI * 6) * amplitude * (1f - t)));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setTranslationX(0f);
            }
        });
        animator.start();
    }

    /** Rotates a view (chevrons, refresh icons) to an absolute angle */
    public static void rotateTo(@Nullable View view, float degrees) {
        if (view == null) return;
        if (!buttons(view)) {
            view.setRotation(degrees);
            return;
        }
        view.animate().cancel();
        view.animate().rotation(degrees).setDuration(MEDIUM).setInterpolator(STANDARD).start();
    }

    /** Lifts a view with translationZ, used for focused fields */
    public static void lift(@Nullable View view, boolean lifted) {
        if (view == null) return;
        float target = lifted ? dp(view, 6) : 0f;
        if (!content(view)) {
            view.setTranslationZ(target);
            return;
        }
        view.animate().translationZ(target).setDuration(SHORT + 40).setInterpolator(STANDARD).start();
    }

    /** Swaps the text with a quick fade + slide, skipped when the text is unchanged */
    public static void swapText(@Nullable TextView view, @Nullable CharSequence text) {
        if (view == null) return;
        CharSequence current = view.getText();
        if (current != null && text != null && current.toString().contentEquals(text)) return;
        if (!content(view) || view.getVisibility() != View.VISIBLE || current == null || current.length() == 0) {
            view.setText(text);
            return;
        }
        prepare(view);
        float shift = dp(view, 6);
        view.animate().alpha(0f).translationY(-shift).setDuration(110).setInterpolator(EXIT)
                .withEndAction(() -> {
                    view.setText(text);
                    view.setTranslationY(shift);
                    view.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(ENTER).start();
                }).start();
    }

    /** Animates the background tint and text/icon color of a MaterialButton */
    public static void tintButton(@Nullable MaterialButton button, int backgroundColor, int contentColor) {
        if (button == null) return;
        ColorStateList currentBg = button.getBackgroundTintList();
        int fromBg = currentBg != null ? currentBg.getDefaultColor() : backgroundColor;
        int fromFg = button.getCurrentTextColor();
        Object running = button.getTag(R.id.motion_tint_animator);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        if (!content(button) || (fromBg == backgroundColor && fromFg == contentColor)) {
            applyButtonColors(button, backgroundColor, contentColor);
            return;
        }
        ArgbEvaluator evaluator = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MEDIUM);
        animator.setInterpolator(STANDARD);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            applyButtonColors(button, (int) evaluator.evaluate(t, fromBg, backgroundColor),
                    (int) evaluator.evaluate(t, fromFg, contentColor));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                button.setTag(R.id.motion_tint_animator, null);
            }
        });
        button.setTag(R.id.motion_tint_animator, animator);
        animator.start();
    }

    private static void applyButtonColors(MaterialButton button, int background, int content) {
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(content);
        button.setIconTint(ColorStateList.valueOf(content));
    }

    /** Counts a number up inside a TextView using a formatter, e.g. "12.4K downloads" */
    public interface NumberFormatter {
        CharSequence format(long value);
    }

    public static void countUp(@Nullable TextView view, long target, long delay, @NonNull NumberFormatter formatter) {
        if (view == null) return;
        if (!content(view) || target <= 0) {
            view.setText(formatter.format(target));
            return;
        }
        Object running = view.getTag(R.id.motion_count_animator);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setStartDelay(delay);
        animator.setDuration(900);
        animator.setInterpolator(ENTER);
        view.setText(formatter.format(0));
        animator.addUpdateListener(a -> view.setText(formatter.format(Math.round(target * (float) a.getAnimatedValue()))));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setText(formatter.format(target));
                view.setTag(R.id.motion_count_animator, null);
            }
        });
        view.setTag(R.id.motion_count_animator, animator);
        animator.start();
    }

    /** Subtle "Ken Burns" settle for big artwork: starts slightly zoomed and eases to rest */
    public static void settleZoom(@Nullable View view, long delay) {
        if (view == null) return;
        if (!content(view)) {
            settle(view);
            return;
        }
        prepareEntrance(view);
        view.setScaleX(1.08f);
        view.setScaleY(1.08f);
        view.setAlpha(0.2f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(900)
                .setInterpolator(ENTER).withLayer().start();
    }
}
