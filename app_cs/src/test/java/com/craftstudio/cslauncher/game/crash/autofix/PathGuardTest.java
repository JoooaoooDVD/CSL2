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

public class PathGuardTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void insideAndOutside() throws Exception {
        File root = tmp.newFolder("config");
        File sibling = tmp.newFolder("configs");
        assertTrue(PathGuard.isInside(root, new File(root, "a/b.json")));
        assertTrue(PathGuard.isInside(root, root));
        assertFalse(PathGuard.isInside(root, new File(sibling, "x.json"))); // prefix trick
        assertFalse(PathGuard.isInside(root, new File(root, "../evil.json")));
    }

    @Test public void relativeResolutionRejectsTraversal() throws Exception {
        File root = tmp.newFolder("config");
        assertNotNull(PathGuard.resolveRelative(root, "sodium-extra.json"));
        assertNotNull(PathGuard.resolveRelative(root, "sub/dir/file.toml"));
        assertNull(PathGuard.resolveRelative(root, "../options.txt"));
        assertNull(PathGuard.resolveRelative(root, "/etc/passwd"));
        assertNull(PathGuard.resolveRelative(root, "a/../../b"));
        assertNull(PathGuard.resolveRelative(root, ""));
        assertEquals(new File(root, "x/y.json").getCanonicalPath(), PathGuard.resolveRelative(root, "x\\y.json").getCanonicalPath());
    }

    @Test public void plainFileNames() {
        assertTrue(PathGuard.isPlainFileName("sodium-0.5.jar"));
        assertFalse(PathGuard.isPlainFileName("../sodium.jar"));
        assertFalse(PathGuard.isPlainFileName("a/b.jar"));
        assertFalse(PathGuard.isPlainFileName(".."));
        assertFalse(PathGuard.isPlainFileName(""));
    }
}
