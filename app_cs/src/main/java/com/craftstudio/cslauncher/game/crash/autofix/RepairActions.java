package com.craftstudio.cslauncher.game.crash.autofix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Architecture;
import com.craftstudio.cslauncher.JVersionList;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.game.renderer.def.Renderers;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.InstalledContentIndex;
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonDownloader;
import com.craftstudio.cslauncher.utils.DownloadUtils;
import com.craftstudio.cslauncher.utils.HashUtils;
import com.craftstudio.cslauncher.value.ClientInfo;
import com.craftstudio.cslauncher.value.DependentLibrary;
import com.craftstudio.cslauncher.value.MoJsonRule;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Concrete {@link RepairAction}s, one per {@link RepairStep.Kind}, plus the factory the engine
 * uses. All file writes stay inside the profile (mods/, config/, options.txt, shader configs), the
 * launcher's libraries/versions folders, or the profile's own metadata; everything else is a
 * settings change recorded as a value entry in the backup.
 */
public final class RepairActions {
    public static final String SCOPE_INSTANCE = "instance";
    public static final String SCOPE_PREFS = "prefs";
    public static final String KEY_RAM = "ramAllocation";
    public static final String KEY_RENDERER = "renderer";
    public static final String KEY_JVM_ARGS = "jvmArgs";
    public static final String KEY_RUNTIME = "selectedRuntime";
    public static final String KEY_PREF_JAVA_ARGS = "javaArgs";

    private RepairActions() {}

    @NonNull
    public static RepairAction create(@NonNull RepairStep step, @NonNull CrashContext ctx) {
        switch (step.kind) {
            case DISABLE_MOD: return new DisableMod(step, ctx);
            case ENABLE_MOD: return new EnableMod(step, ctx);
            case INSTALL_DEPENDENCY: return new InstallDependency(step, ctx);
            case VERIFY_LIBRARIES: return new VerifyLibraries(step, ctx);
            case PRUNE_JVM_ARGS: return new PruneJvmArgs(step, ctx);
            case ADJUST_MEMORY: return new AdjustMemory(step, ctx);
            case FALLBACK_RENDERER: return new FallbackRenderer(step, ctx);
            case SELECT_RUNTIME: return new SelectRuntime(step, ctx);
            case DISABLE_SHADERS: return new DisableShaders(step, ctx);
            case DISABLE_RESOURCE_PACK: return new DisableResourcePack(step, ctx);
            case RESET_CONFIG: return new ResetConfig(step, ctx);
            case CHECK_NETWORK: return new CheckNetwork(step, ctx);
            default: throw new IllegalArgumentException("Unknown step kind " + step.kind);
        }
    }

    /** Restores setting values recorded by the actions below (used by rollback). */
    public static void restoreValue(@NonNull CrashContext ctx, String scope, String key, String old) throws IOException {
        if (SCOPE_PREFS.equals(scope)) {
            if (KEY_PREF_JAVA_ARGS.equals(key)) {
                LauncherPreferences.DEFAULT_PREF.edit().putString(KEY_PREF_JAVA_ARGS, old == null ? "" : old).commit();
                LauncherPreferences.PREF_CUSTOM_JAVA_ARGS = old == null ? "" : old;
                return;
            }
            throw new IOException("Unknown preference " + key);
        }
        if (SCOPE_INSTANCE.equals(scope)) {
            Instance inst = Instances.loadInstance(ctx.instanceKey);
            if (inst == null) throw new IOException("Profile not found");
            switch (key) {
                case KEY_RAM: inst.ramAllocation = old == null || old.isEmpty() ? 0 : Integer.parseInt(old); break;
                case KEY_RENDERER: inst.renderer = old; break;
                case KEY_JVM_ARGS: inst.jvmArgs = old; break;
                case KEY_RUNTIME: inst.selectedRuntime = old; break;
                default: throw new IOException("Unknown profile field " + key);
            }
            inst.write();
            return;
        }
        throw new IOException("Unknown scope " + scope);
    }

    // ---- shared base --------------------------------------------------------------------------

    private abstract static class Base implements RepairAction {
        final RepairStep mStep;
        final CrashContext mCtx;

        Base(RepairStep step, CrashContext ctx) { mStep = step; mCtx = ctx; }

        @NonNull @Override public RepairStep step() { return mStep; }

        Instance freshInstance() throws IOException {
            Instance inst = Instances.loadInstance(mCtx.instanceKey);
            if (inst == null) throw new IOException("Profile not found");
            return inst;
        }

        static String readText(File f) throws IOException {
            StringBuilder sb = new StringBuilder();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        }

        static List<String> splitLines(String text) {
            List<String> out = new ArrayList<>();
            if (text.isEmpty()) return out;
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (i == parts.length - 1 && p.isEmpty()) break; // trailing newline
                if (p.endsWith("\r")) p = p.substring(0, p.length() - 1);
                out.add(p);
            }
            return out;
        }

        static String joinLines(List<String> lines, boolean crlf) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append(crlf ? "\r\n" : "\n");
            return sb.toString();
        }
    }

    // ---- DISABLE_MOD ----------------------------------------------------------------------------

    static final class DisableMod extends Base {
        private File mJar;
        DisableMod(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            return (mJar == null ? mStep.target : mJar.getName()) + " \u2192 .disabled";
        }

        @NonNull @Override public Readiness prepare() {
            if (mCtx.modsDir == null) return Readiness.blocked("Profile has no mods folder");
            File jar;
            if ("jar".equals(mStep.detail) || mStep.target.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jar = mCtx.findModFile(mStep.target);
                if (jar == null) {
                    File disabled = mCtx.findModFile(mStep.target + ".disabled");
                    if (disabled != null) return Readiness.nothingToDo(mStep.target + " is already disabled");
                    return Readiness.blocked("Jar " + mStep.target + " is not in this profile's mods folder");
                }
            } else {
                jar = mCtx.findModJar(mStep.target, false);
                if (jar == null) {
                    if (mCtx.findModJar(mStep.target, true) != null) return Readiness.nothingToDo("Mod " + mStep.target + " is already disabled");
                    return Readiness.blocked("No installed mod declares the id '" + mStep.target + "'");
                }
            }
            if (!PathGuard.isInside(mCtx.modsDir, jar)) return Readiness.blocked("Refusing to touch a file outside mods/");
            mJar = jar;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            backup.rename(mJar, new File(mJar.getParentFile(), mJar.getName() + ".disabled"));
        }

        @Override public boolean verify() {
            return mJar != null && !mJar.exists() && new File(mJar.getParentFile(), mJar.getName() + ".disabled").isFile();
        }
    }

    // ---- ENABLE_MOD -----------------------------------------------------------------------------

    static final class EnableMod extends Base {
        private File mDisabled;
        EnableMod(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            return (mDisabled == null ? mStep.target : mDisabled.getName()) + " \u2192 enabled";
        }

        @NonNull @Override public Readiness prepare() {
            if (mCtx.modsDir == null) return Readiness.blocked("Profile has no mods folder");
            if (mCtx.findModJar(mStep.target, false) != null) return Readiness.nothingToDo(mStep.target + " is already enabled");
            File f = mCtx.findModJar(mStep.target, true);
            if (f == null || !CrashContext.isDisabled(f)) return Readiness.blocked("No disabled jar provides '" + mStep.target + "'");
            if (!PathGuard.isInside(mCtx.modsDir, f)) return Readiness.blocked("Refusing to touch a file outside mods/");
            mDisabled = f;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            String n = mDisabled.getName();
            backup.rename(mDisabled, new File(mDisabled.getParentFile(), n.substring(0, n.length() - ".disabled".length())));
        }

        @Override public boolean verify() {
            if (mDisabled == null) return false;
            String n = mDisabled.getName();
            return new File(mDisabled.getParentFile(), n.substring(0, n.length() - ".disabled".length())).isFile();
        }
    }

    // ---- INSTALL_DEPENDENCY ---------------------------------------------------------------------

    static final class InstallDependency extends Base {
        private static final int DOWNLOAD_ATTEMPTS = 2;
        private File mDisabledJar;                 // re-enable path
        private ModrinthDependencyResolver.Candidate mCandidate; // download path
        private File mTarget;

        InstallDependency(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            if (mDisabledJar != null) return mDisabledJar.getName() + " \u2192 enabled";
            if (mCandidate != null) return mCandidate.fileName + " (Modrinth, " + mCandidate.versionNumber + ")";
            return mStep.target;
        }

        @NonNull @Override public Readiness prepare() throws Exception {
            if (mCtx.modsDir == null) return Readiness.blocked("Profile has no mods folder");
            if (mCtx.findModJar(mStep.target, false) != null)
                return Readiness.nothingToDo("'" + mStep.target + "' is already installed");
            File disabled = mCtx.findModJar(mStep.target, true);
            if (disabled != null && CrashContext.isDisabled(disabled)) {
                mDisabledJar = disabled;
                return Readiness.ready();
            }
            if (mCtx.loader.isEmpty() || "optifine".equals(mCtx.loader))
                return Readiness.blocked("This profile has no mod loader, so dependencies cannot be installed");
            if (mCtx.mcVersion.isEmpty())
                return Readiness.blocked("Could not determine the profile's Minecraft version");
            ModrinthDependencyResolver.Candidate c = ModrinthDependencyResolver.resolve(mStep.target, mCtx.loader, mCtx.mcVersion, mStep.detail);
            if (c == null) return Readiness.blocked("No Modrinth release of '" + mStep.target + "' for " + mCtx.loader + " " + mCtx.mcVersion
                    + (mStep.detail.isEmpty() ? "" : " (needs " + mStep.detail + ")"));
            File target = new File(mCtx.modsDir, c.fileName);
            if (!PathGuard.isInside(mCtx.modsDir, target)) return Readiness.blocked("Refusing to write outside mods/");
            if (target.exists()) return Readiness.blocked(c.fileName + " already exists in mods/ but does not declare '" + mStep.target + "'");
            mCandidate = c;
            mTarget = target;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            if (mDisabledJar != null) {
                String n = mDisabledJar.getName();
                File to = new File(mDisabledJar.getParentFile(), n.substring(0, n.length() - ".disabled".length()));
                backup.rename(mDisabledJar, to);
                mTarget = to;
                return;
            }
            if (!mCtx.modsDir.isDirectory() && !mCtx.modsDir.mkdirs()) throw new IOException("Cannot create mods folder");
            File tmp = new File(mCtx.modsDir, "." + mCandidate.fileName + ".autofix-part");
            IOException last = null;
            for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    if (tmp.exists() && !tmp.delete()) throw new IOException("Cannot clear temp file");
                    DownloadUtils.downloadFile(mCandidate.url, tmp);
                    if (!HashUtils.compareSHA1(tmp, mCandidate.sha1)) throw new IOException("Checksum mismatch for " + mCandidate.fileName);
                    last = null;
                    break;
                } catch (IOException e) {
                    last = e;
                    if (attempt < DOWNLOAD_ATTEMPTS) { try { Thread.sleep(1200L * attempt); } catch (InterruptedException ignored) {} }
                }
            }
            if (last != null) { tmp.delete(); throw last; }
            // The jar must really be the dependency the loader asked for.
            String declared = InstalledContentIndex.readModId(tmp);
            if (!DependencyIdMatcher.matches(declared, mStep.target, mCandidate.projectSlug)) {
                tmp.delete();
                throw new IOException("Downloaded jar declares '" + declared + "', not '" + mStep.target + "' - not installed");
            }
            if (mTarget.exists()) { tmp.delete(); throw new IOException(mTarget.getName() + " appeared meanwhile"); }
            if (!tmp.renameTo(mTarget)) { tmp.delete(); throw new IOException("Cannot move " + mCandidate.fileName + " into mods/"); }
            backup.created(mTarget);
        }

        @Override public boolean verify() throws Exception {
            if (mTarget == null || !mTarget.isFile()) return false;
            if (mCandidate != null && !HashUtils.compareSHA1(mTarget, mCandidate.sha1)) return false;
            String declared = InstalledContentIndex.readModId(mTarget);
            return DependencyIdMatcher.matches(declared, mStep.target, mCandidate == null ? "" : mCandidate.projectSlug);
        }
    }

    // ---- VERIFY_LIBRARIES -----------------------------------------------------------------------

    static final class VerifyLibraries extends Base {
        private final List<File> mCorrupt = new ArrayList<>();
        private int mChecked;

        VerifyLibraries(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            if (mCorrupt.isEmpty()) return "verify " + mChecked + " game files";
            StringBuilder sb = new StringBuilder("re-download ");
            for (int i = 0; i < mCorrupt.size() && i < 3; i++) { if (i > 0) sb.append(", "); sb.append(mCorrupt.get(i).getName()); }
            if (mCorrupt.size() > 3) sb.append(" +").append(mCorrupt.size() - 3);
            return sb.toString();
        }

        @NonNull @Override public Readiness prepare() {
            String versionId = mCtx.versionId;
            if (versionId.isEmpty() || Instance.VERSION_LATEST_RELEASE.equals(versionId) || Instance.VERSION_LATEST_SNAPSHOT.equals(versionId))
                return Readiness.blocked("Profile version is not fixed, cannot map libraries");
            JVersionList.Version info;
            try {
                info = Tools.getVersionInfo(versionId);
            } catch (Throwable t) {
                info = null;
            }
            if (info == null) return Readiness.blocked("Version metadata for " + versionId + " is missing; launching again will re-download it");
            File libRoot = new File(Tools.DIR_HOME_LIBRARY);
            File verRoot = new File(Tools.DIR_HOME_VERSION);
            if (info.libraries != null) {
                for (DependentLibrary lib : info.libraries) {
                    if (lib == null || lib.name == null) continue;
                    if (lib.rules != null && !"allow".equals(MoJsonRule.ruleSetCheck(lib.rules))) continue;
                    String path = null, sha1 = null;
                    if (lib.downloads != null && lib.downloads.artifact != null) {
                        path = lib.downloads.artifact.path;
                        sha1 = lib.downloads.artifact.sha1;
                    }
                    if (path == null) {
                        try { path = lib.name.toPath(); } catch (Throwable t) { continue; }
                    }
                    File f = new File(libRoot, path);
                    if (!PathGuard.isInside(libRoot, f) || !f.isFile()) continue;
                    mChecked++;
                    if (!healthy(f, sha1)) mCorrupt.add(f);
                }
            }
            // client jar(s): the profile's own copy and the vanilla one it inherits from
            ClientInfo client = info.downloads == null ? null : info.downloads.get("client");
            String clientSha = client == null ? null : client.sha1;
            List<String> ids = new ArrayList<>();
            ids.add(versionId);
            if (info.inheritsFrom != null && !ids.contains(info.inheritsFrom)) ids.add(info.inheritsFrom);
            for (String id : ids) {
                File jar = MoJsonDownloader.createGameJarPath(id);
                if (!PathGuard.isInside(verRoot, jar) || !jar.isFile()) continue;
                mChecked++;
                if (!healthy(jar, clientSha)) mCorrupt.add(jar);
            }
            // The library the JVM named explicitly, if it exists and is ours
            if (!mStep.target.isEmpty()) {
                File named = new File(mStep.target);
                if (named.isFile() && (PathGuard.isInside(libRoot, named) || PathGuard.isInside(verRoot, named))) {
                    boolean present = false;
                    for (File c : mCorrupt) if (c.getAbsolutePath().equals(named.getAbsolutePath())) present = true;
                    if (!present) mCorrupt.add(named);
                }
            }
            if (mCorrupt.isEmpty()) return Readiness.nothingToDo("All " + mChecked + " game files passed verification");
            return Readiness.ready();
        }

        /** sha1 match when known, otherwise the zip central directory must open. */
        private static boolean healthy(File f, @Nullable String sha1) {
            try {
                if (sha1 != null && !sha1.isEmpty()) return HashUtils.compareSHA1(f, sha1);
                try (ZipFile z = new ZipFile(f)) { return z.size() >= 0; }
            } catch (Throwable t) {
                return false;
            }
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            for (File f : mCorrupt) backup.moveToBackup(f);
        }

        @Override public boolean verify() {
            for (File f : mCorrupt) if (f.exists()) return false;
            return !mCorrupt.isEmpty();
        }
    }

    // ---- PRUNE_JVM_ARGS -------------------------------------------------------------------------

    static final class PruneJvmArgs extends Base {
        private boolean mInstance, mPrefs;
        private JvmArgsSanitizer.Result mInstanceResult, mPrefsResult;

        PruneJvmArgs(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            List<String> removed = new ArrayList<>();
            if (mInstanceResult != null) removed.addAll(mInstanceResult.removed);
            if (mPrefsResult != null) removed.addAll(mPrefsResult.removed);
            return "remove " + (removed.isEmpty() ? mStep.target : removed.toString());
        }

        @NonNull @Override public Readiness prepare() throws Exception {
            Instance inst = freshInstance();
            String instArgs = inst.jvmArgs == null ? "" : inst.jvmArgs;
            String prefArgs = LauncherPreferences.PREF_CUSTOM_JAVA_ARGS == null ? "" : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS;
            JvmArgsSanitizer.Result ri = JvmArgsSanitizer.prune(instArgs, mStep.target, mStep.detail);
            JvmArgsSanitizer.Result rp = JvmArgsSanitizer.prune(prefArgs, mStep.target, mStep.detail);
            mInstance = ri.changed();
            // global args only count when the profile actually uses them
            boolean usesGlobal = !Tools.isValidString(inst.jvmArgs) || inst.argsMode != Instance.ARGS_MODE_REPLACE;
            mPrefs = rp.changed() && usesGlobal;
            if (!mInstance && !mPrefs) return Readiness.blocked("'" + mStep.target + "' is not in the profile's custom JVM arguments");
            mInstanceResult = mInstance ? ri : null;
            mPrefsResult = mPrefs ? rp : null;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            if (mInstance) {
                Instance inst = freshInstance();
                backup.recordValue(SCOPE_INSTANCE, KEY_JVM_ARGS, inst.jvmArgs == null ? "" : inst.jvmArgs);
                inst.jvmArgs = mInstanceResult.args;
                inst.write();
            }
            if (mPrefs) {
                backup.recordValue(SCOPE_PREFS, KEY_PREF_JAVA_ARGS, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS == null ? "" : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
                LauncherPreferences.DEFAULT_PREF.edit().putString(KEY_PREF_JAVA_ARGS, mPrefsResult.args).commit();
                LauncherPreferences.PREF_CUSTOM_JAVA_ARGS = mPrefsResult.args;
            }
        }

        @Override public boolean verify() throws Exception {
            Instance inst = freshInstance();
            if (mInstance && JvmArgsSanitizer.contains(inst.jvmArgs, mStep.target, mStep.detail)) return false;
            if (mPrefs && JvmArgsSanitizer.contains(LauncherPreferences.DEFAULT_PREF.getString(KEY_PREF_JAVA_ARGS, ""), mStep.target, mStep.detail)) return false;
            return true;
        }
    }

    // ---- ADJUST_MEMORY --------------------------------------------------------------------------

    static final class AdjustMemory extends Base {
        private int mOld, mNew = -1;
        AdjustMemory(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() { return mOld + " MB \u2192 " + mNew + " MB (profile only)"; }

        @NonNull @Override public Readiness prepare() throws Exception {
            Instance inst = freshInstance();
            int current = inst.ramAllocation > 0 ? inst.ramAllocation : LauncherPreferences.PREF_RAM_ALLOCATION;
            int total = Tools.getTotalDeviceMemory(mCtx.appContext);
            boolean heap = "heap".equals(mStep.detail) || "metaspace".equals(mStep.detail);
            int rec = SafeMemory.recommend(current, total, Architecture.is32BitsDevice(), heap);
            if (rec <= 0) return Readiness.blocked(heap
                    ? "The allocation (" + current + " MB) is already at the device's safe maximum; lower render distance / remove heavy mods instead"
                    : "The allocation (" + current + " MB) is already at the safe minimum for this device");
            mOld = current;
            mNew = rec;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            Instance inst = freshInstance();
            backup.recordValue(SCOPE_INSTANCE, KEY_RAM, String.valueOf(inst.ramAllocation));
            inst.ramAllocation = mNew;
            inst.write();
        }

        @Override public boolean verify() throws Exception {
            return freshInstance().ramAllocation == mNew;
        }
    }

    // ---- FALLBACK_RENDERER ----------------------------------------------------------------------

    static final class FallbackRenderer extends Base {
        private String mOld;
        FallbackRenderer(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() { return (mOld == null ? "?" : mOld) + " \u2192 " + Renderers.GL4ES_RENDERER + " (profile only)"; }

        @NonNull @Override public Readiness prepare() throws Exception {
            Instance inst = freshInstance();
            String current = inst.getLaunchRenderer();
            if (Renderers.GL4ES_RENDERER.equals(current))
                return Readiness.blocked("The profile already uses the most compatible renderer (gl4es)");
            mOld = current;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            Instance inst = freshInstance();
            backup.recordValue(SCOPE_INSTANCE, KEY_RENDERER, inst.renderer == null ? "" : inst.renderer);
            inst.renderer = Renderers.GL4ES_RENDERER;
            inst.write();
        }

        @Override public boolean verify() throws Exception {
            return Renderers.GL4ES_RENDERER.equals(freshInstance().getLaunchRenderer());
        }
    }

    // ---- SELECT_RUNTIME -------------------------------------------------------------------------

    static final class SelectRuntime extends Base {
        private String mPick;
        SelectRuntime(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() { return "runtime \u2192 " + mPick; }

        @NonNull @Override public Readiness prepare() throws Exception {
            int major;
            try { major = Integer.parseInt(mStep.target); } catch (NumberFormatException e) { return Readiness.blocked("Bad Java version"); }
            Runtime best = null;
            try {
                for (Runtime r : MultiRTUtils.getRuntimes()) {
                    if (r.javaVersion >= major && (best == null || r.javaVersion < best.javaVersion)) best = r;
                }
            } catch (Throwable t) {
                return Readiness.blocked("Cannot list installed runtimes");
            }
            if (best == null) return Readiness.blocked("No installed Java " + major + "+ runtime; install one in Settings \u203a Java");
            Instance inst = freshInstance();
            if (best.name.equals(inst.selectedRuntime)) return Readiness.nothingToDo("Profile already uses " + best.name);
            mPick = best.name;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            Instance inst = freshInstance();
            backup.recordValue(SCOPE_INSTANCE, KEY_RUNTIME, inst.selectedRuntime == null ? "" : inst.selectedRuntime);
            inst.selectedRuntime = mPick;
            inst.write();
        }

        @Override public boolean verify() throws Exception {
            return mPick != null && mPick.equals(freshInstance().selectedRuntime);
        }
    }

    // ---- DISABLE_SHADERS ------------------------------------------------------------------------

    static final class DisableShaders extends Base {
        private File mIris, mOptifine;
        DisableShaders(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            List<String> n = new ArrayList<>();
            if (mIris != null) n.add("config/iris.properties: enableShaders=false");
            if (mOptifine != null) n.add("optionsshaders.txt: shaderPack=OFF");
            return n.isEmpty() ? "shaders off" : n.toString();
        }

        @NonNull @Override public Readiness prepare() throws Exception {
            if (mCtx.gameDir == null) return Readiness.blocked("No game folder");
            File iris = new File(mCtx.gameDir, "config/iris.properties");
            File of = new File(mCtx.gameDir, "optionsshaders.txt");
            if (iris.isFile() && irisEnabled(readText(iris))) mIris = iris;
            if (of.isFile() && optifineEnabled(readText(of))) mOptifine = of;
            if (mIris == null && mOptifine == null) {
                if (!iris.isFile() && !of.isFile()) return Readiness.blocked("No shader configuration (Iris / OptiFine) found in this profile");
                return Readiness.nothingToDo("Shaders are already turned off");
            }
            return Readiness.ready();
        }

        static boolean irisEnabled(String text) {
            boolean enabled = true; // Iris defaults to enabled when the key is absent but a pack is set
            String pack = null;
            for (String l : splitLines(text)) {
                String t = l.trim();
                if (t.startsWith("enableShaders=")) enabled = Boolean.parseBoolean(t.substring("enableShaders=".length()).trim());
                else if (t.startsWith("shaderPack=")) pack = t.substring("shaderPack=".length()).trim();
            }
            return enabled && pack != null && !pack.isEmpty();
        }

        static boolean optifineEnabled(String text) {
            for (String l : splitLines(text)) {
                String t = l.trim();
                if (t.startsWith("shaderPack=")) {
                    String v = t.substring("shaderPack=".length()).trim();
                    return !(v.isEmpty() || v.equalsIgnoreCase("OFF") || v.equals("(internal)"));
                }
            }
            return false;
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            if (mIris != null) {
                backup.snapshot(mIris);
                String text = readText(mIris);
                boolean crlf = text.contains("\r\n");
                List<String> lines = splitLines(text);
                boolean found = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().startsWith("enableShaders=")) { lines.set(i, "enableShaders=false"); found = true; }
                }
                if (!found) lines.add("enableShaders=false");
                BackupSession.writeAtomic(mIris, joinLines(lines, crlf));
            }
            if (mOptifine != null) {
                backup.snapshot(mOptifine);
                String text = readText(mOptifine);
                boolean crlf = text.contains("\r\n");
                List<String> lines = splitLines(text);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().startsWith("shaderPack=")) lines.set(i, "shaderPack=OFF");
                }
                BackupSession.writeAtomic(mOptifine, joinLines(lines, crlf));
            }
        }

        @Override public boolean verify() throws Exception {
            if (mIris != null && irisEnabled(readText(mIris))) return false;
            if (mOptifine != null && optifineEnabled(readText(mOptifine))) return false;
            return true;
        }
    }

    // ---- DISABLE_RESOURCE_PACK ------------------------------------------------------------------

    static final class DisableResourcePack extends Base {
        private boolean mCrlf;
        private List<String> mLines;
        DisableResourcePack(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() {
            return "options.txt: remove " + (mStep.target.isEmpty() ? "all file packs" : mStep.target);
        }

        @NonNull @Override public Readiness prepare() throws Exception {
            File f = mCtx.optionsFile;
            if (f == null || !f.isFile()) return Readiness.blocked("options.txt not found in this profile");
            String text = readText(f);
            mCrlf = text.contains("\r\n");
            mLines = splitLines(text);
            if (!OptionsFileEditor.isEnabled(mLines, mStep.target))
                return Readiness.nothingToDo(mStep.target.isEmpty() ? "No external resource pack is enabled" : "'" + mStep.target + "' is not enabled");
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            backup.snapshot(mCtx.optionsFile);
            OptionsFileEditor.Result r = OptionsFileEditor.disableResourcePack(mLines, mStep.target);
            BackupSession.writeAtomic(mCtx.optionsFile, joinLines(r.lines, mCrlf));
        }

        @Override public boolean verify() throws Exception {
            return !OptionsFileEditor.isEnabled(splitLines(readText(mCtx.optionsFile)), mStep.target);
        }
    }

    // ---- RESET_CONFIG ---------------------------------------------------------------------------

    static final class ResetConfig extends Base {
        private File mFile;
        ResetConfig(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() { return "config/" + mStep.target + " \u2192 backup (mod regenerates it)"; }

        @NonNull @Override public Readiness prepare() {
            if (mCtx.configDir == null) return Readiness.blocked("No config folder");
            File f = PathGuard.resolveRelative(mCtx.configDir, mStep.target);
            if (f == null) return Readiness.blocked("Config path '" + mStep.target + "' is not inside config/");
            if (!f.isFile()) return Readiness.nothingToDo("config/" + mStep.target + " does not exist (already reset)");
            mFile = f;
            return Readiness.ready();
        }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            backup.moveToBackup(mFile);
        }

        @Override public boolean verify() { return mFile != null && !mFile.exists(); }
    }

    // ---- CHECK_NETWORK --------------------------------------------------------------------------

    static final class CheckNetwork extends Base {
        private static final String[] PROBES = {
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "https://resources.download.minecraft.net/",
                "https://api.modrinth.com/v2/tag/loader"
        };
        private boolean mOk;
        CheckNetwork(RepairStep s, CrashContext c) { super(s, c); }

        @NonNull @Override public String describe() { return "check connection to Mojang servers (3 attempts)"; }

        @NonNull @Override public Readiness prepare() { return Readiness.ready(); }

        @Override public void apply(@NonNull BackupSession backup) throws Exception {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                for (String url : PROBES) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setRequestMethod("GET");
                        c.setRequestProperty("User-Agent", DownloadUtils.USER_AGENT);
                        int code = c.getResponseCode();
                        c.disconnect();
                        if (code > 0 && code < 500) { mOk = true; return; }
                        last = new IOException(url + " answered " + code);
                    } catch (IOException e) {
                        last = e;
                    }
                }
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
            throw last == null ? new IOException("No connection") : last;
        }

        @Override public boolean verify() { return mOk; }
    }

}
