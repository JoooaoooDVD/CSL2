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
 * &lt;app data&gt;/horizon/                     engine home (HOME of the Rust side), internal storage:
 *   engine-data.zip                        staged from assets/horizon/engine-data.zip
 *   .local/horizon-client/26.3/            prepared Minecraft resources (~500 MiB, ~15k small files;
 *                                          internal storage avoids the slow FUSE path of /sdcard)
 *   cs-ready.json                          written by the install service after the engine's ready() check
 *   sessions/                              transient account hand-off files (mode 0600 works here)
 * &lt;CS game home&gt;/instances/&lt;profile&gt;/     game home of a CS profile (saves, options.txt, packs, mods)
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
        // The library is loaded only in the :horizon process. The APK ships it for arm64-v8a only.
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
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

    /** Engine home: everything the Rust side owns. Internal app storage (see class comment). */
    @NonNull
    public static File home() {
        String data = Tools.DIR_DATA;
        if (data == null) throw new IllegalStateException("Tools.initEarlyConstants() not called");
        return new File(data, "horizon");
    }

    /** Written by {@link HorizonInstallService} once {@link HorizonNative#nativeReady} confirmed the install. */
    @NonNull
    public static File readyMarker() {
        return new File(home(), "cs-ready.json");
    }

    /**
     * Files the engine's {@code official_install::ready()} looks for. Checked from the launcher process
     * without loading the 60 MB engine library (which only the {@code :horizon} process loads).
     */
    public static boolean resourcesLookComplete() {
        File res = resourcesDir();
        return new File(res, "horizon-resources.json").isFile()
                && new File(res, "android-downloads-v2.json").isFile()
                && new File(res, "asset-index.json").isFile();
    }

    @NonNull
    public static File cacheDir(@NonNull Context context) {
        return new File(context.getCacheDir(), "horizon");
    }

    @NonNull
    public static File resourcesDir() {
        return new File(home(), ".local/horizon-client/" + MINECRAFT_VERSION);
    }

    /** Legacy engine home of the first test builds (on the shared storage); removed when found. */
    @NonNull
    public static File legacyHome() {
        return new File(Tools.DIR_GAME_HOME, "horizon");
    }

    @NonNull
    public static File sessionsDir() {
        return new File(home(), "sessions");
    }

    @NonNull
    public static File engineDataFile() {
        return new File(home(), "engine-data.zip");
    }

    /**
     * Prepared resources complete and verified: the engine can play. True only after the install service
     * saw {@code nativeReady() == true} and the resource markers are still in place (100% downloaded is
     * not installed).
     */
    public static boolean isInstalled(@NonNull Context context) {
        if (!isSupported(context)) return false;
        return readyMarker().isFile() && resourcesLookComplete();
    }

    /** Records a verified installation (called in the {@code :horizon} process after nativeReady). */
    static void writeReadyMarker(@NonNull Context context) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("minecraft", MINECRAFT_VERSION);
            json.put("engine", bundledLabel(context));
            json.put("time", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        Tools.write(readyMarker().getAbsolutePath(), json.toString());
    }

    /** Forgets a verified installation (resources changed / re-install requested). */
    public static void clearReadyMarker() {
        File marker = readyMarker();
        if (marker.exists() && !marker.delete()) marker.deleteOnExit();
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
