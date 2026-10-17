package com.craftstudio.cslauncher.game.resources;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;

import java.io.File;
import java.util.Locale;

/**
 * Everything the in-game resource browser needs to know about the profile that is currently
 * running: display name, Minecraft version, loader, game directory and which shader loaders
 * are present. Resolved once on a worker thread ({@link #resolve}) because reading the version
 * JSON and scanning the mods folder touch disk.
 */
public final class ResourceProfileContext {
    private static final String TAG = "ResourceProfile";

    @NonNull public final String profileName;
    /** Plain Minecraft version, e.g. "1.20.1"; null when it could not be determined. */
    @Nullable public final String mcVersion;
    /** "", "fabric", "forge", "quilt", "neoforge". */
    @NonNull public final String loader;
    /** The raw version id the game was launched with (for display fallbacks). */
    @Nullable public final String versionId;
    /** Resolved game directory; null when the profile could not be resolved at all. */
    @Nullable public final File gameDirectory;
    public final boolean hasIris;      // Iris / Oculus jar found in mods
    public final boolean hasOptiFine;  // OptiFine jar in mods, or an OptiFine version id

    private ResourceProfileContext(@NonNull String profileName, @Nullable String mcVersion, @NonNull String loader,
                                   @Nullable String versionId, @Nullable File gameDirectory,
                                   boolean hasIris, boolean hasOptiFine) {
        this.profileName = profileName;
        this.mcVersion = mcVersion;
        this.loader = loader;
        this.versionId = versionId;
        this.gameDirectory = gameDirectory;
        this.hasIris = hasIris;
        this.hasOptiFine = hasOptiFine;
    }

    /** True when installs are possible: a usable game directory is known. */
    public boolean isUsable() {
        return gameDirectory != null;
    }

    /** Target folder for a content type (resourcepacks / shaderpacks) under the game directory. */
    @Nullable
    public File folderFor(@NonNull ContentType type) {
        if (gameDirectory == null) return null;
        return new File(gameDirectory, type.folderName);
    }

    /** Blocking. Call from a worker thread. Never throws. */
    @NonNull
    public static ResourceProfileContext resolve(@Nullable Instance instance, @Nullable String launchVersionId) {
        String name = instance != null && instance.name != null && !instance.name.trim().isEmpty()
                ? instance.name.trim() : "Minecraft";
        String versionId = launchVersionId;
        if ((versionId == null || versionId.isEmpty()) && instance != null) versionId = instance.versionId;

        File gameDir = null;
        try {
            if (instance != null) {
                gameDir = instance.getGameDirectory();
                if (gameDir != null && !gameDir.isDirectory() && !gameDir.mkdirs()) {
                    Log.w(TAG, "Game directory missing and could not be created: " + gameDir);
                    if (!gameDir.isDirectory()) gameDir = null;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve the game directory", e);
            gameDir = null;
        }

        String loader = "";
        String mcVersion = null;
        if (versionId != null && !versionId.isEmpty()
                && !Instance.VERSION_LATEST_RELEASE.equals(versionId)
                && !Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)) {
            try {
                loader = InstanceVersionResolver.loaderFromVersionId(versionId);
                mcVersion = InstanceVersionResolver.cleanMcVersion(versionId, loader);
            } catch (Exception e) {
                Log.w(TAG, "Could not resolve the Minecraft version from " + versionId, e);
            }
        }
        if (mcVersion != null && !looksLikeVersion(mcVersion)) mcVersion = null;

        boolean iris = false, optifine = false;
        if (versionId != null && versionId.toLowerCase(Locale.ROOT).contains("optifine")) optifine = true;
        if (gameDir != null) {
            File[] mods = new File(gameDir, "mods").listFiles();
            if (mods != null) {
                for (File mod : mods) {
                    String n = mod.getName().toLowerCase(Locale.ROOT);
                    if (!n.endsWith(".jar")) continue;
                    if (n.contains("iris") || n.contains("oculus")) iris = true;
                    if (n.contains("optifine")) optifine = true;
                }
            }
        }
        return new ResourceProfileContext(name, mcVersion, loader == null ? "" : loader, versionId, gameDir, iris, optifine);
    }

    /** Accepts "1.20.1", "1.21", "24w14a", "1.20.1-pre1" style ids, rejects things like loader ids. */
    static boolean looksLikeVersion(@NonNull String v) {
        return v.matches("\\d+\\.\\d+(\\.\\d+)?([-_ ].*)?") || v.matches("\\d{2}w\\d{2}[a-z]");
    }

    /** "1.20" for "1.20.1" — used to decide "possibly compatible". */
    @Nullable
    public static String majorMinor(@Nullable String v) {
        if (v == null) return null;
        String[] parts = v.split("[-_ ]")[0].split("\\.");
        if (parts.length < 2) return null;
        return parts[0] + "." + parts[1];
    }
}
