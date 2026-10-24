package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogRedactorTest {
    @Test public void stripsLaunchArguments() {
        String s = LogRedactor.redact("args: --username Steve --uuid 069a79f444e94726a5befca90e38aaf5 --accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop --versionType release");
        assertFalse(s.contains("Steve"));
        assertFalse(s.contains("069a79f4"));
        assertFalse(s.contains("eyJhbGci"));
        assertTrue(s.contains("--versionType release"));
    }

    @Test public void stripsEmailIpAndStoragePath() {
        String s = LogRedactor.redact("user john.doe@example.com from 203.0.113.9:25565 at /storage/emulated/0/games/PojavLauncher/latestlog.txt");
        assertFalse(s.contains("john.doe"));
        assertFalse(s.contains("203.0.113.9"));
        assertTrue(s.contains("/storage/emulated/<user>/games/PojavLauncher/latestlog.txt"));
    }

    @Test public void keepsLoopbackAndVersions() {
        String s = LogRedactor.redact("Connecting to 127.0.0.1:25565, LWJGL 3.3.3, Fabric 0.16.5");
        assertEquals("Connecting to 127.0.0.1:25565, LWJGL 3.3.3, Fabric 0.16.5", s);
    }

    @Test public void settingUser() {
        assertEquals("Setting user: <redacted>", LogRedactor.redact("Setting user: Player123"));
    }
}
