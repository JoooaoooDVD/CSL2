package com.craftstudio.cslauncher.tutorial;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A tutorial module: metadata for the Tutorial Center plus its ordered steps */
public final class TutorialDefinition {

    public enum Category { BASICS, PLAY, CONTENT, SETTINGS, TOOLS }

    @NonNull public final String id;
    @StringRes public final int title;
    @StringRes public final int summary;
    @StringRes public final int description;
    @DrawableRes public final int icon;
    @NonNull public final Category category;
    public final boolean beginner;
    public final boolean recommended;
    /** Shown in the Tutorial Center only when it returns true (feature exists in this build/config) */
    @Nullable public final TutorialStep.Condition availability;
    /** Tutorial ids that should be completed first (informational: shown as a hint, never enforced) */
    @NonNull public final List<String> prerequisites;
    @NonNull private final List<TutorialStep> mSteps;

    private TutorialDefinition(Builder b) {
        id = b.id;
        title = b.title;
        summary = b.summary;
        description = b.description;
        icon = b.icon;
        category = b.category;
        beginner = b.beginner;
        recommended = b.recommended;
        availability = b.availability;
        prerequisites = Collections.unmodifiableList(new ArrayList<>(b.prerequisites));
        mSteps = Collections.unmodifiableList(new ArrayList<>(b.steps));
    }

    @NonNull
    public List<TutorialStep> steps() {
        return mSteps;
    }

    public boolean isAvailable(@NonNull Context context) {
        if (availability == null) return true;
        try {
            return availability.isMet(context);
        } catch (Throwable t) {
            return false;
        }
    }

    public static final class Builder {
        private final String id;
        private int title, summary, description, icon;
        private Category category = Category.BASICS;
        private boolean beginner, recommended;
        private TutorialStep.Condition availability;
        private final List<String> prerequisites = new ArrayList<>();
        private final List<TutorialStep> steps = new ArrayList<>();

        public Builder(@NonNull String id, @StringRes int title, @StringRes int summary, @StringRes int description) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.description = description;
        }

        public Builder icon(@DrawableRes int icon) { this.icon = icon; return this; }
        public Builder category(@NonNull Category category) { this.category = category; return this; }
        public Builder beginner() { this.beginner = true; return this; }
        public Builder recommended() { this.recommended = true; return this; }
        public Builder availableWhen(@Nullable TutorialStep.Condition c) { this.availability = c; return this; }
        public Builder after(@NonNull String tutorialId) { this.prerequisites.add(tutorialId); return this; }
        public Builder step(@NonNull TutorialStep step) { this.steps.add(step); return this; }
        public Builder step(@NonNull TutorialStep.Builder step) { this.steps.add(step.build()); return this; }

        public TutorialDefinition build() {
            return new TutorialDefinition(this);
        }
    }
}
