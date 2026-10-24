package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RepairHistoryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void budgetAndPersistence() throws Exception {
        File f = new File(tmp.getRoot(), "history.json");
        RepairHistory h = new RepairHistory(f);
        List<RepairStep> plan = Collections.singletonList(new RepairStep(RepairStep.Kind.DISABLE_MOD, "sodium"));
        assertTrue(h.canAttempt("p", "SIG"));
        h.record("p", "SIG", "MOD_CONFLICT", plan, null, RepairHistory.OUTCOME_APPLIED, "ok", "123");
        assertTrue(h.canAttempt("p", "SIG"));
        h.record("p", "SIG", "MOD_CONFLICT", plan, null, RepairHistory.OUTCOME_FAILED, "nope", null);
        assertFalse(h.canAttempt("p", "SIG"));
        assertTrue(h.canAttempt("other", "SIG"));
        assertTrue(h.canAttempt("p", "OTHER"));

        RepairHistory reloaded = new RepairHistory(f);
        assertEquals(2, reloaded.all().size());
        assertFalse(reloaded.canAttempt("p", "SIG"));
    }

    @Test public void noChangeDoesNotCostBudget() {
        RepairHistory h = new RepairHistory(new File(tmp.getRoot(), "h2.json"));
        List<RepairStep> plan = Collections.singletonList(new RepairStep(RepairStep.Kind.VERIFY_LIBRARIES));
        h.record("p", "S", "CORRUPTED_LIBRARY", plan, null, RepairHistory.OUTCOME_NO_CHANGE, "fine", null);
        h.record("p", "S", "CORRUPTED_LIBRARY", plan, null, RepairHistory.OUTCOME_NO_CHANGE, "fine", null);
        assertTrue(h.canAttempt("p", "S"));
    }

    @Test public void failedStepsAreNeverRepeated() {
        RepairHistory h = new RepairHistory(new File(tmp.getRoot(), "h3.json"));
        RepairStep a = new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, "fabric-api");
        RepairStep b = new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, "sodium");
        h.record("p", "S", "MISSING_DEPENDENCY", Arrays.asList(a, b), Collections.singletonList(b.key()), RepairHistory.OUTCOME_FAILED, "x", null);
        List<RepairStep> filtered = h.filterPlan("p", Arrays.asList(a, b));
        assertEquals(Collections.singletonList(a), filtered);
        assertEquals(Arrays.asList(a, b), h.filterPlan("otherProfile", Arrays.asList(a, b)));
    }

    @Test public void relapseMarksAppliedAttemptAndBlocksItsSteps() {
        RepairHistory h = new RepairHistory(new File(tmp.getRoot(), "h4.json"));
        RepairStep a = new RepairStep(RepairStep.Kind.ADJUST_MEMORY, "", "heap");
        h.record("p", "OUT_OF_MEMORY|heap", "OUT_OF_MEMORY", Collections.singletonList(a), null, RepairHistory.OUTCOME_APPLIED, "ok", "b1");
        assertNotNull(h.lastRevertible("p"));
        assertTrue(h.markRelapse("p", "OUT_OF_MEMORY|heap"));
        assertFalse(h.markRelapse("p", "OUT_OF_MEMORY|heap"));
        assertEquals(RepairHistory.OUTCOME_RELAPSED, h.all().get(0).outcome);
        assertTrue(h.filterPlan("p", Collections.singletonList(a)).isEmpty());
        // relapsed attempts can still be undone
        assertNotNull(h.lastRevertible("p"));
        h.setOutcome(h.all().get(0), RepairHistory.OUTCOME_ROLLED_BACK, "undo");
        assertNull(h.lastRevertible("p"));
    }

    @Test public void corruptFileStartsEmpty() throws Exception {
        File f = new File(tmp.getRoot(), "bad.json");
        java.nio.file.Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        assertTrue(new RepairHistory(f).all().isEmpty());
    }
}
