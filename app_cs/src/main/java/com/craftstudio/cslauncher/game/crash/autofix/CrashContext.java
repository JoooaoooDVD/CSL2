package com.craftstudio.cslauncher.game.crash.autofix;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.modding.modpacks.InstalledContentIndex;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything a repair needs to know about the crashed profile, resolved once on a worker thread.
 * Folder handles are the only places actions may write; {@link PathGuard} checks every target
 * against them.
 */
public final class CrashContext {
    public final Context appContext;
    @Nullable public final Instance instance;
    @NonNull public final String instanceKey;
    /** Concrete version id of the profile (may be "latest_release" when unresolved). */
    @NonNull public final String versionId;
    @NonNull public final String mcVersion;
    @NonNull public final String loader;
    public final int exitCode;
    public final boolean signal;
    @NonNull public final List<String> logLines;
    @Nullable public final String hsErrSummary;

    @Nullable public final File gameDir;
    @Nullable public final File modsDir;
    @Nullable public final File configDir;
    @Nullable public final File optionsFile;
    @NonNull public final File backupRoot;
    @NonNull public final File historyFile;

    private List<File> mModJars;

    public CrashContext(Context ctx, @Nullable Instance instance, int exitCode, boolean signal,
                        @NonNull List<String> logLines, @Nullable String hsErrSummary) {
        this.appContext = ctx.getApplicationContext();
        this.instance = instance;
        this.exitCode = exitCode;
        this.signal = signal;
        this.logLines = Collections.unmodifiableList(new ArrayList<>(logLines));
        this.hsErrSummary = hsErrSummary;
        String key = null;
        try { if (instance != null) key = instance.getKey(); } catch (Throwable ignored) {}
        this.instanceKey = key == null ? "" : key;
        this.versionId = instance != null && instance.versionId != null ? instance.versionId : "";
        InstanceVersionResolver.Info info = this.instanceKey.isEmpty() ? new InstanceVersionResolver.Info(null, "")
                : InstanceVersionResolver.resolve(this.instanceKey);
        String ldr = info.loader == null ? "" : info.loader;
        if (ldr.isEmpty() && this.versionId.toLowerCase(Locale.ROOT).contains("optifine")) ldr = "optifine";
        this.loader = ldr;
        this.mcVersion = info.mcVersion == null ? "" : info.mcVersion;
        File gd = null;
        try { if (instance != null) gd = instance.getGameDirectory(); } catch (Throwable ignored) {}
        this.gameDir = gd;
        this.modsDir = gd == null ? null : new File(gd, "mods");
        this.configDir = gd == null ? null : new File(gd, "config");
        this.optionsFile = gd == null ? null : new File(gd, "options.txt");
        File root = null;
        try { if (instance != null) root = instance.getInstanceRoot(); } catch (Throwable ignored) {}
        File autofixDir = new File(root != null ? root : new File(Tools.DIR_GAME_HOME), ".autofix");
        this.backupRoot = new File(autofixDir, "backups");
        this.historyFile = new File(Tools.DIR_GAME_HOME, "autofix/history.json");
    }

    /** Jars in mods/ (enabled and disabled), cached. */
    @NonNull
    public synchronized List<File> modJars() {
        if (mModJars != null) return mModJars;
        List<File> out = new ArrayList<>();
        if (modsDir != null) {
            File[] files = modsDir.listFiles();
            if (files != null) for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".jar.disabled"))) out.add(f);
            }
        }
        Collections.sort(out);
        mModJars = out;
        return out;
    }

    public static boolean isDisabled(File jar) {
        return jar.getName().toLowerCase(Locale.ROOT).endsWith(".disabled");
    }

    /** Finds the jar that declares {@code modId} (fabric.mod.json / quilt.mod.json / mods.toml). */
    @Nullable
    public File findModJar(String modId, boolean includeDisabled) {
        if (modId == null || modId.isEmpty()) return null;
        String want = modId.toLowerCase(Locale.ROOT);
        for (File f : modJars()) {
            if (!includeDisabled && isDisabled(f)) continue;
            String id = InstalledContentIndex.readModId(f);
            if (id != null && id.toLowerCase(Locale.ROOT).equals(want)) return f;
        }
        return null;
    }

    /** Resolves a plain jar file name inside mods/ (no paths accepted). */
    @Nullable
    public File findModFile(String fileName) {
        if (modsDir == null || !PathGuard.isPlainFileName(fileName)) return null;
        File f = new File(modsDir, fileName);
        return f.isFile() && PathGuard.isInside(modsDir, f) ? f : null;
    }

    /** Enabled mod ids, for the AI whitelist and the details pane. */
    @NonNull
    public List<String> installedModIds() {
        List<String> ids = new ArrayList<>();
        for (File f : modJars()) {
            if (isDisabled(f)) continue;
            String id = InstalledContentIndex.readModId(f);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    @NonNull
    public List<String> configFileNames() {
        List<String> out = new ArrayList<>();
        if (configDir == null) return out;
        collect(configDir, configDir, out, 0);
        return out;
    }

    private static void collect(File root, File dir, List<String> out, int depth) {
        if (depth > 3 || out.size() > 400) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(root, f, out, depth + 1);
            else {
                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace('\\', '/');
                out.add(rel);
            }
        }
    }

    public boolean hasProfile() {
        return instance != null && !instanceKey.isEmpty() && gameDir != null;
    }
}
