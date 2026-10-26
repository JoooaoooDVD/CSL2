package com.craftstudio.cslauncher.modding.modpacks;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi;
import com.craftstudio.cslauncher.modding.modpacks.api.ModrinthApi;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/**
 * Installs the Fabric API that matches a Minecraft version into a profile's {@code mods} folder.
 *
 * <p>Used by the Fabric installer when the "Fabric API" toggle is on. Modrinth is the primary
 * source (no key needed, exact loader + game-version filters), CurseForge is the fallback.
 * Duplicate protection: an existing Fabric API jar of the same version is kept as-is, an older
 * or newer one is replaced, so a profile never ends up with two copies.
 */
public final class FabricApiInstaller {
    private static final String TAG = "FabricApiInstaller";
    /** Modrinth project id of Fabric API */
    public static final String MODRINTH_ID = "P7dR8mSH";
    /** CurseForge project id of Fabric API */
    public static final String CURSEFORGE_ID = "306612";
    /** Preference: auto-install Fabric API when creating a Fabric profile (default on) */
    public static final String PREF_AUTO_INSTALL = "fabricApiAutoInstall";

    private FabricApiInstaller() {}

    public static boolean isAutoInstallEnabled() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_AUTO_INSTALL, true);
    }

    public static void setAutoInstallEnabled(boolean enabled) {
        LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_AUTO_INSTALL, enabled).apply();
    }

    /** Result of {@link #install}: what happened, for the UI message */
    public enum Result { INSTALLED, ALREADY_PRESENT, NOT_AVAILABLE }

    /**
     * Blocking: resolves the newest Fabric API release for {@code mcVersion} and places it in
     * the instance's mods folder. Reports on {@link ProgressLayout#INSTALL_MODPACK} and clears it.
     *
     * @throws IOException when the download or validation fails (the profile stays usable)
     */
    @NonNull
    public static Result install(@NonNull Context context, @NonNull Instance instance, @NonNull String mcVersion,
                                 @Nullable String curseforgeKey) throws IOException {
        File modsDir = new File(instance.getGameDirectory(), ContentType.MOD.folderName);
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.fabric_api_resolving, mcVersion);
        try {
            ModDetail detail = null;
            int index = -1;
            try {
                ModItem item = new ModItem(Constants.SOURCE_MODRINTH, false, MODRINTH_ID, "Fabric API", "", "");
                detail = new ModrinthApi().getModDetails(item, mcVersion, "fabric");
                index = CompatibleVersionPicker.pick(detail, mcVersion, "fabric", ContentType.MOD);
            } catch (Exception e) {
                Log.w(TAG, "Modrinth lookup failed: " + e);
            }
            if (index < 0 && curseforgeKey != null && !curseforgeKey.isEmpty()) {
                try {
                    ModItem item = new ModItem(Constants.SOURCE_CURSEFORGE, false, CURSEFORGE_ID, "Fabric API", "", "");
                    detail = new CurseforgeApi(curseforgeKey).getModDetails(item, mcVersion, "fabric");
                    index = CompatibleVersionPicker.pick(detail, mcVersion, "fabric", ContentType.MOD);
                } catch (Exception e) {
                    Log.w(TAG, "CurseForge lookup failed: " + e);
                }
            }
            if (detail == null || index < 0) return Result.NOT_AVAILABLE;

            // Duplicate guard: same file already there -> done; other Fabric API jars -> replaced
            String fileName = detail.versionFileNames != null && index < detail.versionFileNames.length
                    ? detail.versionFileNames[index] : null;
            File[] existing = modsDir.listFiles((dir, name) -> isFabricApiJar(name));
            if (existing != null) {
                for (File f : existing) {
                    if (fileName != null && f.getName().equals(fileName) && f.length() > 0) return Result.ALREADY_PRESENT;
                }
            }
            String oldPath = null;
            if (existing != null && existing.length > 0) {
                oldPath = existing[0].getAbsolutePath();
                for (int i = 1; i < existing.length; i++) {
                    if (!existing[i].delete()) Log.w(TAG, "Could not remove " + existing[i]);
                }
            }
            try {
                ContentInstaller.installBlocking(detail, index, ContentType.MOD, Collections.<ContentInstaller.Dependency>emptyList(),
                        oldPath, mcVersion, "fabric", curseforgeKey, modsDir);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
            return Result.INSTALLED;
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    /** fabric-api-0.100.0+1.21.jar and friends; never matches other "fabric-*" mods */
    static boolean isFabricApiJar(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".jar") && !n.endsWith(".jar.disabled")) return false;
        return n.startsWith("fabric-api-") || n.equals("fabric-api.jar");
    }
}
