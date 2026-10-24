package com.craftstudio.cslauncher.game.crash.autofix.ai;

import java.io.IOException;
import java.util.List;

/**
 * Optional second opinion for crashes the rule engine could not classify with confidence.
 *
 * <p>Contract: the provider receives <em>redacted</em> log lines and a list of candidate
 * components (mod ids / file names that actually exist in the profile) and must answer with the
 * strict JSON described in {@link AiResponseValidator}. The answer is advisory only: it is parsed,
 * validated, and turned into the same {@link com.craftstudio.cslauncher.game.crash.autofix.RepairStep}s
 * the rule engine produces, so it can never name a file outside the profile, never run code and
 * never bypass the backup / verification path. No provider ships enabled by default.</p>
 */
public interface AiDiagnosticProvider {
    /** Short human readable name shown in the details pane. */
    String name();

    /**
     * @param redactedLines log lines after {@link com.craftstudio.cslauncher.game.crash.autofix.LogRedactor}
     * @param request       structured context (loader, versions, candidates)
     * @return raw JSON text from the provider
     */
    String diagnose(List<String> redactedLines, AiRequest request) throws IOException;
}
