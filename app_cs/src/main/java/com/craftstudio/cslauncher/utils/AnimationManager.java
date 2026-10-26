package com.craftstudio.cslauncher.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.craftstudio.cslauncher.R;

/**
 * CS animation system - designed by CRAFT STUDIO for CS Launcher Plus.
 *
 * Central place that decides which kinds of animations the launcher plays.
 * The user picks the kinds (or none) in the launcher appearance settings.
 * <ul>
 *     <li>{@link #TYPE_SCREENS}: activity and fragment transitions</li>
 *     <li>{@link #TYPE_DIALOGS}: dialogs and popups</li>
 *     <li>{@link #TYPE_BUTTONS}: button press effects</li>
 *     <li>{@link #TYPE_CONTENT}: lists and screen content entering</li>
 * </ul>
 *
 * Performance rules that every animation in this system follows, so the launcher
 * never feels laggy on mobile:
 * <ul>
 *     <li>only alpha / translation / scale are animated - GPU-composited properties,
 *         never anything that triggers a layout pass or re-draw of heavy views;</li>
 *     <li>short durations (130-280ms) with tuned interpolators - the UI always reacts
 *         instantly, animation is polish and never a wait;</li>
 *     <li>horizontal (X-axis) motion that matches the landscape launcher;</li>
 *     <li>a hard cap on staggered children so a screen full of buttons still runs
 *         smoothly on low-end devices;</li>
 *     <li>the system "remove animations" accessibility setting is always respected.</li>
 * </ul>
 */
public final class AnimationManager {
    public static final String PREF_TYPES = "animation_types";

    public static final String TYPE_NONE = "none";
    public static final String TYPE_SCREENS = "screens";
    public static final String TYPE_DIALOGS = "dialogs";
    public static final String TYPE_BUTTONS = "buttons";
    public static final String TYPE_CONTENT = "content";

    private static final Set<String> DEFAULT_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(TYPE_SCREENS, TYPE_DIALOGS, TYPE_BUTTONS, TYPE_CONTENT)));

    private AnimationManager() {}

    /** @return whether animations of this type should be played */
    public static boolean isEnabled(@NonNull Context context, @NonNull String type) {
        if (systemAnimationsDisabled(context)) return false;
        Set<String> types = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(PREF_TYPES, DEFAULT_TYPES);
        return types != null && !types.contains(TYPE_NONE) && types.contains(type);
    }

    /** Respect the "remove animations" accessibility setting */
    private static boolean systemAnimationsDisabled(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Applies the animation related theme overlays and window animations to a not yet created activity.
     * Must be called before the content view is set.
     */
    public static void applyToActivity(@NonNull Activity activity) {
        Resources.Theme theme = activity.getTheme();
        if (isEnabled(activity, TYPE_DIALOGS)) {
            theme.applyStyle(R.style.ThemeOverlay_CS_Animations_Dialogs, true);
        }
        if (isEnabled(activity, TYPE_BUTTONS)) {
            theme.applyStyle(R.style.ThemeOverlay_CS_Animations_Buttons, true);
        }
        // Window transitions live in the shared engine so every screen uses one spec
        CSAnimationEngine.applyWindowAnimations(activity);
    }

    /**
     * Lightweight horizontal slide + fade fragment transitions, tuned for the
     * two-pane landscape launcher: screens travel along the X axis like pages.
     * The transition specs are owned by {@link CSAnimationEngine}.
     */
    public static void installFragmentTransitions(@NonNull FragmentActivity activity) {
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentPreCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                     @Nullable android.os.Bundle savedInstanceState) {
                        if (f instanceof DialogFragment) return;
                        if (!isEnabled(activity, TYPE_SCREENS)) return;
                        f.setEnterTransition(CSAnimationEngine.makeFragmentForwardEnter());
                        f.setExitTransition(CSAnimationEngine.makeFragmentForwardExit());
                        f.setReenterTransition(CSAnimationEngine.makeFragmentBackEnter());
                        f.setReturnTransition(CSAnimationEngine.makeFragmentBackExit());
                    }
                }, true);
    }

    /**
     * Makes the items of a list enter one after the other, exactly once.
     *
     * Delegates to {@link CSAnimationEngine#applyListEntrance(ViewGroup)} - the single
     * sanctioned writer of layout animations in the launcher. That method documents the
     * crash this replaced: ViewGroup.dispatchDraw() reads mLayoutAnimationController
     * unguarded when its animation flags are dirty, so any external
     * setLayoutAnimation(null) - deferred, listener-driven or cross-thread - could land
     * between the framework's reads and NPE inside the draw pass
     * ("LayoutAnimationController.isDone() on a null object reference", seen on both
     * ListView and RecyclerView draw paths). The engine only ever writes a non-null
     * controller, always on the UI thread, and never clears one - the framework settles
     * the flags itself, so no stale or invalid animation state can survive navigation,
     * recycling or data changes.
     */
    public static void applyListAnimation(@Nullable ViewGroup list) {
        CSAnimationEngine.applyListEntrance(list);
    }

    /**
     * Makes the direct children of a layout slide in from the start (left) one after another.
     * Horizontal motion matches the landscape sidebar; the stagger is capped so a full
     * screen of buttons still animates smoothly on low-end devices.
     */
    public static void staggerIn(@Nullable ViewGroup parent) {
        CSAnimationEngine.staggerChildren(parent);
    }

    /**
     * Plays the CS Launcher Plus intro once: the brand fades and springs in,
     * holds for a moment, then dissolves into the launcher. Pure alpha/scale on a
     * tiny overlay - it never blocks touches and never slows down the UI.
     */
    public static void playBrandIntro(@NonNull ViewGroup root) {
        Context context = root.getContext();
        if (!isEnabled(context, TYPE_SCREENS) && !isEnabled(context, TYPE_CONTENT)) return;

        final View overlay = android.view.LayoutInflater.from(context).inflate(R.layout.view_brand_intro, root, false);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final float d = context.getResources().getDisplayMetrics().density;
        // Startup chime: swell with the logo rise, bells land with the title (0.76 s / 0.98 s)
        StartupSound.playOnce(context);

        final View group = overlay.findViewById(R.id.bi_group);
        final View glow = overlay.findViewById(R.id.bi_glow);
        final View logo = overlay.findViewById(R.id.bi_logo);
        final View line = overlay.findViewById(R.id.bi_line);
        final TextView team = overlay.findViewById(R.id.bi_team);
        final TextView title = overlay.findViewById(R.id.bi_title);
        final View version = overlay.findViewById(R.id.bi_version);

        // Initial pose
        glow.setAlpha(0f); glow.setScaleX(0.6f); glow.setScaleY(0.6f);
        logo.setAlpha(0f); logo.setScaleX(0.72f); logo.setScaleY(0.72f); logo.setTranslationY(22f * d);
        line.setScaleX(0f); line.setAlpha(0f);
        team.setAlpha(0f); team.setTranslationY(10f * d); team.setLetterSpacing(0.9f);
        title.setAlpha(0f); title.setTranslationY(14f * d);
        version.setAlpha(0f);

        final android.view.animation.Interpolator out = com.craftstudio.cslauncher.utils.motion.Motion.ENTER;
        final android.view.animation.Interpolator soft = new android.view.animation.OvershootInterpolator(1.1f);

        // 1. logo rises, halo blooms
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(60L).setDuration(560L).setInterpolator(soft).start();
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60L).setDuration(760L).setInterpolator(out).start();
        // 2. platinum rule draws from the centre
        line.animate().alpha(1f).scaleX(1f).setStartDelay(380L).setDuration(460L).setInterpolator(out).start();
        // 3. CRAFT STUDIO tracks in (letter spacing tightens while it fades up)
        android.animation.ValueAnimator tracking = android.animation.ValueAnimator.ofFloat(0.9f, 0.42f);
        tracking.setStartDelay(520L);
        tracking.setDuration(620L);
        tracking.setInterpolator(out);
        tracking.addUpdateListener(a -> team.setLetterSpacing((Float) a.getAnimatedValue()));
        tracking.start();
        team.animate().alpha(1f).translationY(0f).setStartDelay(520L).setDuration(520L).setInterpolator(out).start();
        // 4. title lands, then a highlight sweeps across it once
        title.animate().alpha(1f).translationY(0f).setStartDelay(700L).setDuration(520L).setInterpolator(out)
                .withEndAction(() -> shimmerOnce(title)).start();
        version.animate().alpha(1f).setStartDelay(980L).setDuration(400L).start();
        // 5. idle breathing of the halo while we hold
        android.animation.ObjectAnimator breathe = android.animation.ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.55f, 1f);
        breathe.setStartDelay(900L);
        breathe.setDuration(1500L);
        breathe.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breathe.start();

        // 6. reveal the launcher: backdrop fades, brand lifts and softens
        final long holdUntil = 2050L;
        overlay.postDelayed(() -> {
            breathe.cancel();
            group.animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).translationY(-12f * d)
                    .setDuration(380L).setInterpolator(com.craftstudio.cslauncher.utils.motion.Motion.EXIT).start();
            overlay.animate().alpha(0f).setStartDelay(120L).setDuration(420L)
                    .setInterpolator(com.craftstudio.cslauncher.utils.motion.Motion.EXIT)
                    .withEndAction(() -> root.removeView(overlay)).start();
        }, holdUntil);
    }

    /** One pass of a light band across the glyphs (shader on the text paint, restored afterwards). */
    private static void shimmerOnce(@NonNull final TextView text) {
        final int base = text.getCurrentTextColor();
        final float width = Math.max(1f, text.getWidth());
        android.animation.ValueAnimator sweep = android.animation.ValueAnimator.ofFloat(-0.6f, 1.6f);
        sweep.setDuration(900L);
        sweep.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sweep.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            float x0 = (p - 0.35f) * width, x1 = (p + 0.35f) * width;
            text.getPaint().setShader(new android.graphics.LinearGradient(x0, 0f, x1, 0f,
                    new int[]{base, 0xFFFFFFFF, base}, new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            text.invalidate();
        });
        sweep.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                text.getPaint().setShader(null);
                text.invalidate();
            }
        });
        sweep.start();
    }

    /** Resolves a theme color attribute, with a fallback for exotic themes */
    private static int themeColor(@NonNull Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) return context.getColor(value.resourceId);
            return value.data;
        }
        return fallback;
    }
}
