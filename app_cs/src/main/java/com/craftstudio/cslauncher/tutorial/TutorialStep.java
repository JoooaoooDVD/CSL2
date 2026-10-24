package com.craftstudio.cslauncher.tutorial;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * One step of a tutorial: what to show, where, which real control to spotlight and what has to
 * happen before the tutorial moves on. Built with the fluent helpers from {@link TutorialRegistry}.
 */
public final class TutorialStep {

    /** How the step is completed */
    public enum Completion {
        /** The user reads and presses Next */
        NEXT,
        /** The user taps the spotlighted control (the control works normally) */
        TAP_TARGET,
        /** A {@link TutorialEvents} event with {@link #event} is emitted */
        EVENT,
        /** A fragment of class {@link #event} (simple name) is resumed */
        SCREEN,
        /** The launcher preference {@link #event} changed */
        PREF_CHANGED
    }

    /** Optional runtime condition: a step is silently dropped when it returns false */
    public interface Condition {
        boolean isMet(@NonNull Context context);
    }

    @NonNull public final String id;
    @StringRes public final int title;
    @StringRes public final int body;
    /** 0 = no instruction line */
    @StringRes public final int instruction;
    @NonNull public final TutorialScreen screen;
    /** View id of the control to spotlight, 0 = none (card centred) */
    @IdRes public final int targetViewId;
    /** Preference key to spotlight (inside the visible preference screen), null = none */
    @Nullable public final String targetPrefKey;
    @NonNull public final Completion completion;
    /** Event name / screen simple class name / preference key, depending on {@link #completion} */
    @Nullable public final String event;
    /** Hide the overlay after the target was tapped until {@link #resumeEvent} arrives (dialogs) */
    @Nullable public final String resumeEvent;
    /** In guided mode an interactive step degrades to NEXT; essential steps stay interactive */
    public final boolean essential;
    /** Step is dropped when the target is missing instead of showing the fallback */
    public final boolean optional;
    @Nullable public final Condition condition;

    private TutorialStep(Builder b) {
        this.id = b.id;
        this.title = b.title;
        this.body = b.body;
        this.instruction = b.instruction;
        this.screen = b.screen;
        this.targetViewId = b.targetViewId;
        this.targetPrefKey = b.targetPrefKey;
        this.completion = b.completion;
        this.event = b.event;
        this.resumeEvent = b.resumeEvent;
        this.essential = b.essential;
        this.optional = b.optional;
        this.condition = b.condition;
    }

    public boolean hasTarget() {
        return targetViewId != 0 || targetPrefKey != null;
    }

    /** Whether the step waits for a real interaction (as opposed to the Next button) */
    public boolean isInteractive(boolean interactiveMode) {
        if (completion == Completion.NEXT) return false;
        return interactiveMode || essential;
    }

    public static final class Builder {
        private final String id;
        private int title, body, instruction;
        private TutorialScreen screen = TutorialScreen.ANY;
        private int targetViewId;
        private String targetPrefKey;
        private Completion completion = Completion.NEXT;
        private String event;
        private String resumeEvent;
        private boolean essential;
        private boolean optional;
        private Condition condition;

        public Builder(@NonNull String id, @StringRes int title, @StringRes int body) {
            this.id = id;
            this.title = title;
            this.body = body;
        }

        public Builder instruction(@StringRes int instruction) { this.instruction = instruction; return this; }
        public Builder on(@NonNull TutorialScreen screen) { this.screen = screen; return this; }
        public Builder target(@IdRes int viewId) { this.targetViewId = viewId; return this; }
        public Builder targetPref(@NonNull String key) { this.targetPrefKey = key; return this; }
        public Builder tap() { this.completion = Completion.TAP_TARGET; return this; }
        public Builder waitEvent(@NonNull String event) { this.completion = Completion.EVENT; this.event = event; return this; }
        public Builder waitScreen(@NonNull Class<?> fragment) { this.completion = Completion.SCREEN; this.event = fragment.getSimpleName(); return this; }
        public Builder waitPref(@NonNull String key) { this.completion = Completion.PREF_CHANGED; this.event = key; return this; }
        public Builder resumeOn(@NonNull String event) { this.resumeEvent = event; return this; }
        public Builder essential() { this.essential = true; return this; }
        public Builder optional() { this.optional = true; return this; }
        public Builder when(@Nullable Condition condition) { this.condition = condition; return this; }

        public TutorialStep build() {
            return new TutorialStep(this);
        }
    }
}
