package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JvmArgsSanitizerTest {
    @Test public void removesOnlyTheOffendingXxOption() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-XX:+UseG1GC -XX:+UseZGCFoo -Dfoo=bar -Xss1m", "-XX:UseZGCFoo", "");
        assertEquals("-XX:+UseG1GC -Dfoo=bar -Xss1m", r.args);
        assertEquals(1, r.removed.size());
        assertFalse(JvmArgsSanitizer.contains(r.args, "-XX:UseZGCFoo", ""));
    }

    @Test public void matchesValueOptionsByName() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-XX:MaxFoo=12 -XX:+UseG1GC", "-XX:MaxFoo=99", "");
        assertEquals("-XX:+UseG1GC", r.args);
    }

    @Test public void removesExactXOption() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-Xfoo -Xmx2G", "-Xfoo", "");
        assertEquals("-Xmx2G", r.args);
    }

    @Test public void heapModeRemovesByPrefix() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-Xmx99999G -Dkeep=1", "-Xmx", "heap");
        assertEquals("-Dkeep=1", r.args);
    }

    @Test public void gcConflictModeRemovesCollectorsOnly() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-XX:+UseG1GC -XX:+UseZGC -XX:+UseStringDeduplication", "-XX:+Use", "gc-conflict");
        assertEquals("-XX:+UseStringDeduplication", r.args);
    }

    @Test public void keepsQuotedGroupsIntact() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-Dpath=\"/a b/c\" -Xfoo", "-Xfoo", "");
        assertEquals("-Dpath=\"/a b/c\"", r.args);
    }

    @Test public void unchangedWhenAbsent() {
        JvmArgsSanitizer.Result r = JvmArgsSanitizer.prune("-Xmx2G", "-Xfoo", "");
        assertFalse(r.changed());
        assertTrue(JvmArgsSanitizer.prune(null, "-Xfoo", "").args.isEmpty());
    }
}
