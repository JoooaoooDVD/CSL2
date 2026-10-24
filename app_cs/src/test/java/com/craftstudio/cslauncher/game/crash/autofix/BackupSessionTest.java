package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class BackupSessionTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void fullRoundTrip() throws Exception {
        File profile = tmp.newFolder("profile");
        File backups = new File(profile, ".autofix/backups");
        File mod = new File(profile, "mods/sodium.jar");
        File config = new File(profile, "config/broken.json");
        File options = new File(profile, "options.txt");
        write(mod, "jar"); write(config, "{bad"); write(options, "resourcePacks:[\"file/x.zip\"]\n");

        BackupSession s = BackupSession.create(backups, "key", "SIG", "MOD_CONFLICT");
        s.rename(mod, new File(mod.getParentFile(), "sodium.jar.disabled"));
        s.moveToBackup(config);
        s.snapshot(options);
        BackupSession.writeAtomic(options, "resourcePacks:[]\n");
        File downloaded = new File(profile, "mods/fabric-api.jar");
        write(downloaded, "dl");
        s.created(downloaded);
        s.recordValue("instance", "ramAllocation", "1024");
        s.markCompleted();

        assertFalse(mod.exists());
        assertTrue(new File(profile, "mods/sodium.jar.disabled").isFile());
        assertFalse(config.exists());
        assertEquals("resourcePacks:[]\n", read(options));
        assertTrue(new File(s.dir(), "manifest.json").isFile());
        assertEquals(5, s.entries().size());

        // reopen from disk like the UI "undo" does
        BackupSession reopened = BackupSession.open(s.dir());
        assertNotNull(reopened);
        final List<String> restored = new ArrayList<>();
        List<String> problems = reopened.rollback((scope, key, old) -> restored.add(scope + ":" + key + "=" + old));
        assertTrue(problems.toString(), problems.isEmpty());

        assertTrue(mod.isFile());
        assertFalse(new File(profile, "mods/sodium.jar.disabled").exists());
        assertEquals("{bad", read(config));
        assertEquals("resourcePacks:[\"file/x.zip\"]\n", read(options));
        assertFalse(downloaded.exists());
        assertEquals("[instance:ramAllocation=1024]", restored.toString());
        assertTrue(BackupSession.open(s.dir()).manifest().rolledBack);
    }

    @Test public void rollbackReportsMissingBackupCopyInsteadOfThrowing() throws Exception {
        File profile = tmp.newFolder("p2");
        File cfg = new File(profile, "config/a.json");
        write(cfg, "x");
        BackupSession s = BackupSession.create(new File(profile, "bk"), "k", "S", "C");
        s.moveToBackup(cfg);
        for (BackupSession.Entry e : s.entries()) new File(e.backup).delete();
        List<String> problems = s.rollback(null);
        assertEquals(1, problems.size());
        assertFalse(s.manifest().rolledBack);
    }

    @Test public void renameRefusesToOverwrite() throws Exception {
        File profile = tmp.newFolder("p3");
        File a = new File(profile, "mods/a.jar"), b = new File(profile, "mods/a.jar.disabled");
        write(a, "1"); write(b, "2");
        BackupSession s = BackupSession.create(new File(profile, "bk"), "k", "S", "C");
        try {
            s.rename(a, b);
            throw new AssertionError("expected failure");
        } catch (java.io.IOException expected) {
            assertEquals("1", read(a));
            assertEquals("2", read(b));
            assertTrue(s.isEmpty());
        }
    }
}
