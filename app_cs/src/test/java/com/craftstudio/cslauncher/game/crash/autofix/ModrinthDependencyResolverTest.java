package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ModrinthDependencyResolverTest {
    @Test public void lowerBounds() {
        assertEquals("0.5.8", ModrinthDependencyResolver.lowerBound("0.5.8"));
        assertEquals("4.4", ModrinthDependencyResolver.lowerBound("[4.4,)"));
        assertEquals("1.2", ModrinthDependencyResolver.lowerBound(">=1.2"));
        assertNull(ModrinthDependencyResolver.lowerBound(""));
        assertNull(ModrinthDependencyResolver.lowerBound("*"));
        assertNull(ModrinthDependencyResolver.lowerBound("<2.0"));
    }

    @Test public void versionCompare() {
        assertTrue(ModrinthDependencyResolver.compareVersions("0.100.1+1.21", "0.99") > 0);
        assertTrue(ModrinthDependencyResolver.compareVersions("4.4", "4.4.0") == 0);
        assertTrue(ModrinthDependencyResolver.compareVersions("4.3.9", "4.4") < 0);
    }

    @Test public void slugCandidates() {
        List<String> c = ModrinthDependencyResolver.slugCandidates("fabric");
        assertEquals("fabric-api", c.get(0));
        assertEquals(Arrays.asList("cloth-config", "cloth_config"), ModrinthDependencyResolver.slugCandidates("cloth_config"));
        assertEquals(Arrays.asList("some_mod", "some-mod"), ModrinthDependencyResolver.slugCandidates("Some_Mod"));
    }

    @Test public void idMatching() {
        assertTrue(DependencyIdMatcher.matches("fabric-api", "fabric", "fabric-api"));
        assertTrue(DependencyIdMatcher.matches("cloth-config", "cloth-config2", "cloth-config"));
        assertTrue(DependencyIdMatcher.matches("geckolib", "geckolib", "geckolib"));
        assertTrue(!DependencyIdMatcher.matches("sodium", "lithium", "lithium"));
        assertTrue(!DependencyIdMatcher.matches(null, "x", "x"));
    }
}
