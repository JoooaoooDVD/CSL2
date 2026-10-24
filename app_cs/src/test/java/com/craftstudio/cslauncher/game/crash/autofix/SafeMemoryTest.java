package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafeMemoryTest {
    @Test public void defaultsMatchLauncherTable() {
        assertEquals(296, SafeMemory.defaultFor(900));
        assertEquals(936, SafeMemory.defaultFor(3000));
        assertEquals(2048, SafeMemory.defaultFor(8192));
    }

    @Test public void heapExhaustionRaisesWithinCeiling() {
        int rec = SafeMemory.recommend(1024, 6144, false, true);
        assertTrue(rec > 1024);
        assertTrue(rec <= 6144 * 0.55f);
        assertEquals(0, rec % 8);
    }

    @Test public void heapExhaustionAtCeilingReturnsNoChange() {
        int ceiling = (int) (6144 * 0.55f);
        assertEquals(-1, SafeMemory.recommend(ceiling + 100, 6144, false, true));
    }

    @Test public void nativePressureLowersTowardDefault() {
        int rec = SafeMemory.recommend(3000, 4096, false, false);
        assertTrue(rec < 3000);
        assertTrue(rec >= 512);
        assertEquals(-1, SafeMemory.recommend(400, 4096, false, false)); // already at floor
    }

    @Test public void thirtyTwoBitCeiling() {
        assertTrue(SafeMemory.recommend(1000, 8192, true, true) <= 1200);
    }
}
