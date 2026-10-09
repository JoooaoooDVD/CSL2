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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
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
import androidx.transition.Fade;
import androidx.transition.Slide;
import androidx.transition.Transition;
import androidx.transition.TransitionSet;

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

    /** Hard cap for staggered enter animations, keeps frame times stable on weak GPUs */
    private static final int MAX_STAGGERED_CHILDREN = 14;

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
        activity.getWindow().setWindowAnimations(isEnabled(activity, TYPE_SCREENS)
                ? R.style.Animation_CS_Activity
                : R.style.Animation_CS_ActivityNone);
    }

    /**
     * Lightweight horizontal slide + fade fragment transitions, tuned for the
     * two-pane landscape launcher: screens travel along the X axis like pages.
     */
    public static void installFragmentTransitions(@NonNull FragmentActivity activity) {
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentPreCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                     @Nullable android.os.Bundle savedInstanceState) {
                        if (f instanceof DialogFragment) return;
                        if (!isEnabled(activity, TYPE_SCREENS)) return;
                        f.setEnterTransition(makeForwardEnter());
                        f.setExitTransition(makeForwardExit());
                        f.setReenterTransition(makeBackEnter());
                        f.setReturnTransition(makeBackExit());
                    }
                }, true);
    }

    /** Forward: the new screen slides in from the right edge with a fade */
    private static Transition makeForwardEnter() {
        TransitionSet set = new TransitionSet();
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        Slide slide = new Slide(Gravity.END);
        slide.setDuration(190);
        Fade fade = new Fade(Fade.IN);
        fade.setDuration(150);
        set.addTransition(slide);
        set.addTransition(fade);
        return set;
    }

    /** Forward: the old screen just fades out fast - no lag, no wasted frames */
    private static Transition makeForwardExit() {
        Fade fade = new Fade(Fade.OUT);
        fade.setDuration(130);
        return fade;
    }

    /** Back: the screen underneath reappears with a quick fade */
    private static Transition makeBackEnter() {
        Fade fade = new Fade(Fade.IN);
        fade.setDuration(160);
        return fade;
    }

    /** Back: the top screen slides back out to the right edge */
    private static Transition makeBackExit() {
        TransitionSet set = new TransitionSet();
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        Slide slide = new Slide(Gravity.END);
        slide.setDuration(170);
        Fade fade = new Fade(Fade.OUT);
        fade.setDuration(140);
        set.addTransition(slide);
        set.addTransition(fade);
        return set;
    }

    /**
     * Makes the items of a list enter one after the other, but only ONCE: the layout
     * animation clears itself when it finishes so scrolling a recycled list never
     * re-triggers staggered item animations (that re-trigger was a scroll-lag source).
     */
    public static void applyListAnimation(@Nullable ViewGroup list) {
        if (list == null) return;
        Context context = list.getContext();
        if (!isEnabled(context, TYPE_CONTENT)) {
            list.setLayoutAnimation(null);
            return;
        }
        android.view.animation.LayoutAnimationController controller =
                AnimationUtils.loadLayoutAnimation(context, R.anim.cs_layout_list);
        if (controller == null) return;
        final ViewGroup target = list;
        controller.getAnimation().setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            @Override public void onAnimationStart(android.view.animation.Animation animation) {}
            @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
            @Override public void onAnimationEnd(android.view.animation.Animation animation) {
                target.post(() -> target.setLayoutAnimation(null));
            }
        });
        list.setLayoutAnimation(controller);
    }

    /**
     * Makes the direct children of a layout slide in from the start (left) one after another.
     * Horizontal motion matches the landscape sidebar; the stagger is capped so a full
     * screen of buttons still animates smoothly on low-end devices.
     */
    public static void staggerIn(@Nullable ViewGroup parent) {
        if (parent == null || !isEnabled(parent.getContext(), TYPE_CONTENT)) return;
        float offset = 12 * parent.getResources().getDisplayMetrics().density;
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
                    .setStartDelay(Math.min(shown, MAX_STAGGERED_CHILDREN) * 22L)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
            shown++;
        }
    }

    /**
     * Plays the CS Launcher Plus intro once: the brand fades and springs in,
     * holds for a moment, then dissolves into the launcher. Pure alpha/scale on a
     * tiny overlay - it never blocks touches and never slows down the UI.
     */
    public static void playBrandIntro(@NonNull ViewGroup root) {
        Context context = root.getContext();
        if (!isEnabled(context, TYPE_SCREENS) && !isEnabled(context, TYPE_CONTENT)) return;

        final FrameLayout overlay = new FrameLayout(context);
        overlay.setClickable(false);
        overlay.setFocusable(false);

        View scrim = new View(context);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout group = buildBrandGroup(context);
        group.setAlpha(0f);
        group.setScaleX(0.88f);
        group.setScaleY(0.88f);
        FrameLayout.LayoutParams groupParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupParams.gravity = Gravity.CENTER;
        overlay.addView(group, groupParams);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int enterMs = 340, holdMs = 520, exitMs = 300;
        group.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(enterMs)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
        scrim.animate()
                .alpha(0.45f)
                .setDuration(enterMs)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> group.postDelayed(() -> {
                    group.animate()
                            .alpha(0f).scaleX(1.06f).scaleY(1.06f)
                            .setDuration(exitMs)
                            .setInterpolator(new AccelerateInterpolator(1.2f))
                            .start();
                    scrim.animate()
                            .alpha(0f)
                            .setDuration(exitMs)
                            .setInterpolator(new AccelerateInterpolator())
                            .withEndAction(() -> root.removeView(overlay))
                            .start();
                }, holdMs))
                .start();
    }

    /** CS Launcher Plus 1.4 / CRAFT STUDIO brand block used by the intro animation */
    private static LinearLayout buildBrandGroup(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = (int) (24 * density);
        int padV = (int) (18 * density);
        group.setPadding(padH, padV, padH, padV);

        int accent = themeColor(context, R.attr.colorPrimary, 0xFFB87333);

        TextView title = new TextView(context);
        title.setText(R.string.brand_short_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(0.14f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        group.addView(title);

        TextView version = new TextView(context);
        version.setText(R.string.brand_version);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        version.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        version.setLetterSpacing(0.30f);
        version.setTextColor(accent);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = (int) (4 * density);
        group.addView(version, versionParams);

        TextView team = new TextView(context);
        team.setText(R.string.brand_team_name);
        team.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        team.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        team.setLetterSpacing(0.34f);
        team.setTextColor(0xB3FFFFFF);
        team.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams teamParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        teamParams.topMargin = (int) (10 * density);
        group.addView(team, teamParams);

        return group;
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
