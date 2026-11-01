package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The Horizon engine as seen by CS Launcher Plus: availability, folders, bundled engine data.
 *
 * <pre>
 * &lt;CS game home&gt;/horizon/                 engine home (HOME of the Rust side)
 *   engine-data.zip                        staged from assets/horizon/engine-data.zip
 *   .local/horizon-client/26.3/            prepared Minecraft resources (installer output)
 *   instances/&lt;profile&gt;/                   game home of a CS profile (saves, options.txt, packs, mods)
 *   sessions/                              transient account hand-off files
 * &lt;cache&gt;/horizon/                         download + shader cache
 * </pre>
 */
public final class HorizonEngine {
    public static final String ENGINE_ID = "horizon";
    public static final String MINECRAFT_VERSION = "26.3";
    public static final String ASSET_DIR = "horizon";
    public static final String ASSET_ENGINE_DATA = ASSET_DIR + "/engine-data.zip";
    public static final String ASSET_BUILD = ASSET_DIR + "/BUILD.json";
    /** Horizon Android supports Android 9+ on 64-bit ABIs (the .hmod runtime needs 64-bit). */
    public static final int MIN_SDK = Build.VERSION_CODES.P;

    private HorizonEngine() {}

    /** The library is in this APK and the device can run it. */
    public static boolean isSupported(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false;
        if (!is64Bit()) return false;
        if (!hasAsset(context, ASSET_BUILD)) return false;
        return HorizonNative.load();
    }

    /** The APK was built with the engine (even if this device cannot run it). */
    public static boolean isBundled(@NonNull Context context) {
        return hasAsset(context, ASSET_BUILD);
    }

    public static boolean is64Bit() {
        return Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
    }

    private static boolean hasAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Engine home: everything the Rust side owns. */
    @NonNull
    public static File home() {
        return new File(Tools.DIR_GAME_HOME, "horizon");
    }

    @NonNull
    public static File cacheDir(@NonNull Context context) {
        return new File(context.getCacheDir(), "horizon");
    }

    @NonNull
    public static File resourcesDir() {
        return new File(home(), ".local/horizon-client/" + MINECRAFT_VERSION);
    }

    /** Game home of a CS profile (saves, options.txt, resourcepacks, shaderpacks, mods). */
    @NonNull
    public static File instanceDir(@NonNull String profileKey) {
        return new File(home(), "instances/" + profileKey.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    @NonNull
    public static File sessionsDir() {
        return new File(home(), "sessions");
    }

    @NonNull
    public static File engineDataFile() {
        return new File(home(), "engine-data.zip");
    }

    /** Prepared resources complete: the engine can play. */
    public static boolean isInstalled(@NonNull Context context) {
        return isSupported(context) && HorizonNative.nativeReady(home().getAbsolutePath());
    }

    /**
     * Copies {@code assets/horizon/engine-data.zip} next to the engine home so the Rust side can
     * open it as a file. Re-copied whenever the bundled build changes (BUILD.json commit).
     * @return the staged file
     */
    @NonNull
    public static File stageEngineData(@NonNull Context context) throws IOException {
        File home = home();
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("Cannot create " + home);
        File target = engineDataFile();
        File stamp = new File(home, "cs-engine-build.json");
        String build = readAsset(context, ASSET_BUILD);
        String current = stamp.isFile() ? Tools.read(stamp.getAbsolutePath()) : null;
        if (target.isFile() && build != null && build.equals(current)) return target;
        File partial = new File(home, "engine-data.partial");
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(ASSET_ENGINE_DATA); OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
        if (!partial.renameTo(target)) throw new IOException("Cannot move " + partial);
        if (build != null) Tools.write(stamp.getAbsolutePath(), build);
        return target;
    }

    /** BUILD.json of the bundled engine (version, commit) or null when not bundled. */
    @Nullable
    public static JSONObject bundledBuild(@NonNull Context context) {
        String text = readAsset(context, ASSET_BUILD);
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** "0.7.7 · build abc123" for the engine card. */
    @NonNull
    public static String bundledLabel(@NonNull Context context) {
        JSONObject build = bundledBuild(context);
        if (build == null) return "";
        String version = build.optString("version", "");
        String commit = build.optString("commit", "");
        if (commit.length() > 7) commit = commit.substring(0, 7);
        return commit.isEmpty() ? version : version + " · " + commit;
    }

    @Nullable
    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            byte[] bytes = new byte[in.available()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
