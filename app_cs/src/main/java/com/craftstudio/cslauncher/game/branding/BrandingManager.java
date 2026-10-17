package com.craftstudio.cslauncher.game.branding;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.JVersionList;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Applies a profile's {@link BrandingMode} to its game directory:
 * generates/reuses the CS Dynamic Branding pack, enables or disables it in {@code options.txt}
 * and keeps the per-profile restore record so "Vanilla" really puts things back.
 * <p>
 * Everything here is blocking file work: call from a worker thread (the launch pipeline already
 * runs off the UI thread; the profile editor uses an executor).
 * <p>
 * What the launcher can and cannot do without a client mod is documented in
 * docs/DYNAMIC_BRANDING.md; in short: title logo, edition line, panorama, buttons, menu
 * backgrounds, splashes and some loading texts are replaced by the pack; the very first
 * Mojang Studios logo screen can only be switched to its dark variant via an option.
 */
public final class BrandingManager {
    private static final String TAG = "CSBranding";
    private static final String ASSET_DIR = "cs_branding";

    private BrandingManager() {}

    /** Outcome of one apply pass; never throws so launching can continue either way. */
    public static final class Outcome {
        public final BrandingMode mode;
        /** True when the requested mode is now in effect (vanilla restored or custom active). */
        public final boolean applied;
        /** Null on success; the reason the custom look is not active otherwise. */
        @Nullable public final BrandingException.Reason reason;
        @Nullable public final String detail;
        public final boolean packReused;
        @Nullable public final String packFormat;
        @Nullable public final String gameVersion;

        Outcome(BrandingMode mode, boolean applied, @Nullable BrandingException.Reason reason, @Nullable String detail,
                boolean packReused, @Nullable String packFormat, @Nullable String gameVersion) {
            this.mode = mode;
            this.applied = applied;
            this.reason = reason;
            this.detail = detail;
            this.packReused = packReused;
            this.packFormat = packFormat;
            this.gameVersion = gameVersion;
        }

        /** True when the user should see a message (every non-applied custom outcome). */
        public boolean needsAttention() {
            return !applied;
        }

        /** Human readable, actionable message for toasts / the editor row. */
        @NonNull
        public String message(@NonNull Context context) {
            String version = gameVersion == null ? "" : gameVersion;
            if (applied) {
                if (mode == BrandingMode.VANILLA) return context.getString(R.string.branding_status_vanilla);
                return context.getString(R.string.branding_status_custom_active, version);
            }
            if (reason == null) return context.getString(R.string.branding_error_generic);
            switch (reason) {
                case UNSUPPORTED_VERSION: return context.getString(R.string.branding_error_unsupported, version);
                case JAR_MISSING: return context.getString(R.string.branding_error_jar_missing);
                case INVALID_METADATA: return context.getString(R.string.branding_error_metadata, version);
                case ASSET_MISSING: return context.getString(R.string.branding_error_assets);
                case WRITE_FAILED: return context.getString(R.string.branding_error_write);
                case VERIFY_FAILED: return context.getString(R.string.branding_error_verify);
                default: return context.getString(R.string.branding_error_generic);
            }
        }
    }

    public static BrandingMode modeOf(@Nullable Instance instance) {
        return instance == null ? BrandingMode.VANILLA : BrandingMode.fromKey(instance.appearance);
    }

    /**
     * Applies the profile's mode. Safe to call on every launch: a matching pack is reused
     * (marker check only), options are rewritten only when a line actually changes.
     *
     * @param resolvedVersionId the concrete version id that will be launched (never "latest_release")
     */
    @WorkerThread
    @NonNull
    public static Outcome apply(@NonNull Context context, @NonNull Instance instance, @Nullable String resolvedVersionId) {
        BrandingMode mode = modeOf(instance);
        File gameDir;
        try {
            gameDir = instance.getGameDirectory();
        } catch (Exception e) {
            Log.w(TAG, "Cannot resolve game directory", e);
            return new Outcome(mode, false, BrandingException.Reason.WRITE_FAILED, "game dir unresolved", false, null, null);
        }
        if (mode == BrandingMode.VANILLA) {
            return restoreVanilla(instance, gameDir, resolvedVersionId);
        }
        return activateCustom(context, instance, gameDir, resolvedVersionId);
    }

    @WorkerThread
    private static Outcome restoreVanilla(Instance instance, File gameDir, @Nullable String versionId) {
        try {
            boolean changed = deactivateInOptions(instance, gameDir);
            if (instance.appearanceRestore != null) {
                instance.appearanceRestore = null;
                instance.write();
            }
            Log.i(TAG, "Vanilla experience for '" + instance.name + "'" + (changed ? " (options restored)" : " (nothing to change)"));
            return new Outcome(BrandingMode.VANILLA, true, null, null, false, null, versionId);
        } catch (IOException e) {
            Log.w(TAG, "Could not restore vanilla options for '" + instance.name + "'", e);
            return new Outcome(BrandingMode.VANILLA, false, BrandingException.Reason.WRITE_FAILED, e.toString(), false, null, versionId);
        }
    }

    @WorkerThread
    private static Outcome activateCustom(Context context, Instance instance, File gameDir, @Nullable String versionId) {
        String displayVersion = versionId;
        try {
            if (versionId == null || versionId.isEmpty()
                    || Instance.VERSION_LATEST_RELEASE.equals(versionId) || Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)) {
                throw new BrandingException(BrandingException.Reason.JAR_MISSING, "version not resolved yet: " + versionId);
            }
            File jar = locateClientJar(versionId);
            if (jar == null) throw new BrandingException(BrandingException.Reason.JAR_MISSING, "no client jar for " + versionId);

            ClientJarFacts facts = ClientJarFacts.read(jar, BrandingPackGenerator.candidateEntries());
            displayVersion = facts.versionId;
            if (!facts.isSupported()) {
                // Never leave a pack built for another version enabled on an unsupported one
                deactivateInOptions(instance, gameDir);
                throw new BrandingException(BrandingException.Reason.UNSUPPORTED_VERSION,
                        facts.versionId + " has resource format " + facts.packFormatLabel());
            }

            BrandingPackGenerator generator = new BrandingPackGenerator(new ApkAssets(context.getAssets()));
            BrandingPackGenerator.Result result = generator.generate(jar, facts, new File(gameDir, "resourcepacks"));

            boolean optionsChanged = activateInOptions(instance, gameDir);

            // Verify end state: pack folder valid + entry enabled + dark loading screen set
            GameOptionsFile check = new GameOptionsFile(new File(gameDir, "options.txt"));
            if (!check.getList(BrandingPack.OPT_RESOURCE_PACKS).contains(BrandingPack.PACK_ENTRY)
                    || BrandingPackGenerator.reusableFiles(result.packDir, result.stamp) == null) {
                throw new BrandingException(BrandingException.Reason.VERIFY_FAILED, "post-activation check failed");
            }
            Log.i(TAG, "Custom experience for '" + instance.name + "': " + facts.versionId
                    + " pack_format=" + facts.packFormatLabel()
                    + (result.reused ? " (pack reused)" : " (pack generated: " + result.written.size() + " files, "
                    + result.skipped.size() + " skipped, " + result.langOverrides + " lang keys)")
                    + (optionsChanged ? ", options updated" : ", options already set")
                    + " -> " + result.packDir);
            return new Outcome(BrandingMode.CUSTOM, true, null, null, result.reused, facts.packFormatLabel(), facts.versionId);
        } catch (BrandingException e) {
            Log.w(TAG, "Custom experience not applied for '" + instance.name + "': " + e.reason + " - " + e.getMessage());
            return new Outcome(BrandingMode.CUSTOM, false, e.reason, e.getMessage(), false, null, displayVersion);
        } catch (IOException e) {
            Log.w(TAG, "Custom experience not applied for '" + instance.name + "'", e);
            return new Outcome(BrandingMode.CUSTOM, false, BrandingException.Reason.WRITE_FAILED, e.toString(), false, null, displayVersion);
        } catch (RuntimeException e) {
            // Defensive: a bug here must never block the game from launching
            Log.e(TAG, "Unexpected failure while applying the custom experience", e);
            return new Outcome(BrandingMode.CUSTOM, false, BrandingException.Reason.WRITE_FAILED, e.toString(), false, null, displayVersion);
        }
    }

    /**
     * Adds the pack to the END of {@code resourcePacks} (highest priority), removes it from the
     * incompatible list and turns on the dark loading screen; remembers the previous value of
     * that option once so Vanilla can restore it.
     * @return true when options.txt was rewritten
     */
    private static boolean activateInOptions(Instance instance, File gameDir) throws IOException {
        GameOptionsFile options = new GameOptionsFile(new File(gameDir, "options.txt"));
        boolean dirty = false;

        List<String> packs = options.getList(BrandingPack.OPT_RESOURCE_PACKS);
        if (packs.isEmpty() && !options.has(BrandingPack.OPT_RESOURCE_PACKS)) packs.add("vanilla");
        int pos = packs.indexOf(BrandingPack.PACK_ENTRY);
        if (pos != packs.size() - 1) {
            while (packs.remove(BrandingPack.PACK_ENTRY)) { /* drop every stale occurrence */ }
            packs.add(BrandingPack.PACK_ENTRY);
            options.setList(BrandingPack.OPT_RESOURCE_PACKS, packs);
            dirty = true;
        }

        if (options.has(BrandingPack.OPT_INCOMPATIBLE_PACKS)) {
            List<String> incompatible = options.getList(BrandingPack.OPT_INCOMPATIBLE_PACKS);
            if (incompatible.remove(BrandingPack.PACK_ENTRY)) {
                options.setList(BrandingPack.OPT_INCOMPATIBLE_PACKS, incompatible);
                dirty = true;
            }
        }

        String dark = options.getRaw(BrandingPack.OPT_DARK_LOADING);
        if (!"true".equals(dark)) {
            if (instance.appearanceRestore == null) {
                JsonObject record = new JsonObject();
                record.addProperty("darkPresent", dark != null);
                if (dark != null) record.addProperty("dark", dark);
                instance.appearanceRestore = record.toString();
                instance.write();
            }
            options.setRaw(BrandingPack.OPT_DARK_LOADING, "true");
            dirty = true;
        } else if (instance.appearanceRestore == null) {
            // User already had the dark screen on: remember that so Vanilla keeps it
            JsonObject record = new JsonObject();
            record.addProperty("darkPresent", true);
            record.addProperty("dark", "true");
            instance.appearanceRestore = record.toString();
            instance.write();
        }

        if (dirty) options.save();
        return dirty;
    }

    /**
     * Removes the pack from both pack lists and restores the dark-loading option from the
     * profile's restore record. Other packs, their order and every other line are untouched.
     * @return true when options.txt was rewritten
     */
    private static boolean deactivateInOptions(Instance instance, File gameDir) throws IOException {
        File file = new File(gameDir, "options.txt");
        if (!file.isFile()) return false;
        GameOptionsFile options = new GameOptionsFile(file);
        boolean dirty = false;

        if (options.has(BrandingPack.OPT_RESOURCE_PACKS)) {
            List<String> packs = options.getList(BrandingPack.OPT_RESOURCE_PACKS);
            boolean removed = false;
            while (packs.remove(BrandingPack.PACK_ENTRY)) removed = true;
            if (removed) {
                options.setList(BrandingPack.OPT_RESOURCE_PACKS, packs);
                dirty = true;
            }
        }
        if (options.has(BrandingPack.OPT_INCOMPATIBLE_PACKS)) {
            List<String> incompatible = options.getList(BrandingPack.OPT_INCOMPATIBLE_PACKS);
            if (incompatible.remove(BrandingPack.PACK_ENTRY)) {
                options.setList(BrandingPack.OPT_INCOMPATIBLE_PACKS, incompatible);
                dirty = true;
            }
        }
        if (instance.appearanceRestore != null) {
            boolean present = false;
            String previous = null;
            try {
                JsonObject record = JsonParser.parseString(instance.appearanceRestore).getAsJsonObject();
                present = record.has("darkPresent") && record.get("darkPresent").getAsBoolean();
                if (record.has("dark")) previous = record.get("dark").getAsString();
            } catch (Exception e) {
                Log.w(TAG, "Corrupted restore record for '" + instance.name + "', falling back to the game default");
            }
            String current = options.getRaw(BrandingPack.OPT_DARK_LOADING);
            if (present && previous != null) {
                if (!previous.equals(current)) { options.setRaw(BrandingPack.OPT_DARK_LOADING, previous); dirty = true; }
            } else if (current != null) {
                options.remove(BrandingPack.OPT_DARK_LOADING);
                dirty = true;
            }
        }
        if (dirty) options.save();
        return dirty;
    }

    /**
     * The vanilla client jar for a version id. Loader profiles (fabric-loader-…-1.21.1) inherit
     * from the vanilla version, whose jar carries the assets.
     * @return null when the jar is not downloaded yet
     */
    @Nullable
    public static File locateClientJar(@NonNull String versionId) {
        String base = versionId;
        try {
            JVersionList.Version info = Tools.getVersionInfo(versionId, true);
            if (info != null && info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) base = info.inheritsFrom;
        } catch (Exception e) {
            // No version json yet: fall through and probe the jar by id
        }
        File jar = new File(Tools.DIR_HOME_VERSION, base + "/" + base + ".jar");
        if (jar.isFile() && jar.length() > 0) return jar;
        if (!base.equals(versionId)) {
            File own = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".jar");
            if (own.isFile() && own.length() > 0) return own;
        }
        return null;
    }

    /**
     * Last-moment guard, called right before the JVM is started: if the profile is on the custom
     * experience and the pack folder is valid but some later options write dropped our entry
     * (anything that saves the cached options after {@link #apply}), put it back. Cheap: one
     * small file read; a write only happens when something is actually missing.
     *
     * @return a one-line description of the final options state for the launcher log
     */
    @WorkerThread
    @NonNull
    public static String reassertBeforeExec(@NonNull Instance instance) {
        try {
            File gameDir = instance.getGameDirectory();
            File optionsFile = new File(gameDir, "options.txt");
            if (modeOf(instance) != BrandingMode.CUSTOM) {
                return "vanilla mode, resourcePacks=" + rawPacks(optionsFile);
            }
            File packDir = new File(new File(gameDir, "resourcepacks"), BrandingPack.DIR_NAME);
            if (!BrandingPackGenerator.isOwned(packDir) || !new File(packDir, "pack.mcmeta").isFile()) {
                return "custom mode but no valid pack at " + packDir + " (not re-asserted)";
            }
            boolean rewritten = activateInOptions(instance, gameDir);
            return (rewritten ? "re-asserted pack entry, " : "pack entry present, ") + "resourcePacks=" + rawPacks(optionsFile);
        } catch (Exception e) {
            Log.w(TAG, "reassert failed", e);
            return "reassert failed: " + e;
        }
    }

    private static String rawPacks(File optionsFile) {
        try {
            String raw = new GameOptionsFile(optionsFile).getRaw(BrandingPack.OPT_RESOURCE_PACKS);
            return raw == null ? "<absent>" : raw;
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    /** Quick, cheap state for the editor row: is the pack currently enabled in options.txt? */
    @WorkerThread
    public static boolean isPackEnabled(@NonNull Instance instance) {
        try {
            File file = new File(instance.getGameDirectory(), "options.txt");
            if (!file.isFile()) return false;
            return new GameOptionsFile(file).getList(BrandingPack.OPT_RESOURCE_PACKS).contains(BrandingPack.PACK_ENTRY);
        } catch (Exception e) {
            return false;
        }
    }

    /** Asset source backed by the APK's {@code assets/cs_branding} folder. */
    private static final class ApkAssets implements BrandingAssetSource {
        private final AssetManager mAssets;

        ApkAssets(AssetManager assets) {
            mAssets = assets;
        }

        @Override
        public InputStream open(String name) throws IOException {
            return mAssets.open(ASSET_DIR + "/" + name);
        }
    }
}
