package com.craftstudio.cslauncher.game.crash.autofix.ai;

import com.craftstudio.cslauncher.game.crash.autofix.CrashCategory;
import com.craftstudio.cslauncher.game.crash.autofix.RepairStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A validated provider answer. Only produced by {@link AiResponseValidator}. */
public final class AiDiagnosis {
    public final CrashCategory category;
    public final float confidence;
    public final String explanation;
    public final String component;
    public final List<RepairStep> actions;

    AiDiagnosis(CrashCategory category, float confidence, String explanation, String component, List<RepairStep> actions) {
        this.category = category;
        this.confidence = confidence;
        this.explanation = explanation;
        this.component = component;
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    }
}
