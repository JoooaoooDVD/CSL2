package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Result of {@link CrashClassifier#analyze}. Immutable, Android free.
 */
public final class CrashAnalysis {
    public final CrashCategory category;
    /** 0..1 */
    public final float confidence;
    /** Short technical reason in English, for the log / details pane (UI shows a localised explanation per category). */
    public final String reason;
    /** Lines from the log that triggered the classification (already trimmed, max a few). */
    public final List<String> evidence;
    /** Mod id, library path, option token, pack name... Empty when unknown. */
    public final String suspectComponent;
    /** Ordered repair plan. Empty means "nothing we can safely change". */
    public final List<RepairStep> plan;
    public final boolean safeToAutoFix;
    public final boolean requiresConfirmation;
    /** True when simply launching again is a reasonable thing to try after the fix. */
    public final boolean canRetryLaunch;
    /** Stable fingerprint of this crash kind (category + component) used for retry bookkeeping. */
    public final String signature;

    public CrashAnalysis(CrashCategory category, float confidence, String reason, List<String> evidence,
                         String suspectComponent, List<RepairStep> plan, boolean safeToAutoFix,
                         boolean requiresConfirmation, boolean canRetryLaunch) {
        this.category = category == null ? CrashCategory.UNKNOWN_ERROR : category;
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.reason = reason == null ? "" : reason;
        this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence == null ? Collections.<String>emptyList() : evidence));
        this.suspectComponent = suspectComponent == null ? "" : suspectComponent.trim();
        this.plan = Collections.unmodifiableList(new ArrayList<>(plan == null ? Collections.<RepairStep>emptyList() : plan));
        // A plan with no steps is never auto-fixable, whatever the rule said.
        this.safeToAutoFix = safeToAutoFix && !this.plan.isEmpty();
        this.requiresConfirmation = requiresConfirmation;
        this.canRetryLaunch = canRetryLaunch;
        this.signature = this.category.name() + "|" + this.suspectComponent.toLowerCase(Locale.ROOT);
    }

    public static CrashAnalysis unknown(List<String> evidence) {
        return new CrashAnalysis(CrashCategory.UNKNOWN_ERROR, 0.2f, "No known pattern matched",
                evidence, "", Collections.<RepairStep>emptyList(), false, false, true);
    }

    /** Copy with a different plan (used after the history filter removes already-failed steps). */
    public CrashAnalysis withPlan(List<RepairStep> newPlan) {
        return new CrashAnalysis(category, confidence, reason, evidence, suspectComponent, newPlan,
                safeToAutoFix, requiresConfirmation, canRetryLaunch);
    }

    public String evidenceText() {
        StringBuilder sb = new StringBuilder();
        for (String e : evidence) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return category + " conf=" + confidence + " component='" + suspectComponent + "' plan=" + plan;
    }
}
