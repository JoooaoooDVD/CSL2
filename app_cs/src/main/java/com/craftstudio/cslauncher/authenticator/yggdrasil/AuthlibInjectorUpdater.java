package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Keeps authlib-injector (used for launcher skins on local accounts and for Ely.by) up to date.
 * <p>
 * Every big Minecraft update can change Mojang's authlib, and an old authlib-injector then
 * silently stops working (example: 1.2.7 does nothing on Minecraft 26.3, 1.2.8 fixes it).
 * A launcher update ships the newest build; this also fetches newer official builds from
 * authlib-injector.yushi.moe (SHA-256 checked) at most twice a day.
 * <p>
 * Versions are stored as "b&lt;build number&gt;" in the component's version file.
 */
public final class AuthlibInjectorUpdater {
    private static final String TAG = "AuthlibInjectorUpdater";
    private static final String LATEST_URL = "https://authlib-injector.yushi.moe/artifact/latest.json";
    private static final String DOWNLOAD_PREFIX = "https://authlib-injector.yushi.moe/";
    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_JAR_BYTES = 8 * 1024 * 1024;

    private AuthlibInjectorUpdater() {}

    /** "b56" → 56, anything else → -1 */
    public static int buildOf(@Nullable String version) {
        if (version == null) return -1;
        String trimmed = version.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != 'b') return -1;
        try {
            return Integer.parseInt(trimmed.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    public static File componentDir() {
        return new File(Tools.DIR_DATA, "authlib-injector");
    }

    /** Human readable version of the installed authlib-injector ("1.2.8"), or null */
    @Nullable
    public static String installedVersionName() {
        File jar = new File(componentDir(), "authlib-injector.jar");
        if (!jar.isFile()) return null;
        try (JarFile file = new JarFile(jar)) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            return null;
        }
    }

    /** Blocking: call it from a background thread. Never throws. */
    public static void checkForUpdate() {
        try {
            File dir = componentDir();
            File jar = new File(dir, "authlib-injector.jar");
            File versionFile = new File(dir, "version");
            File stamp = new File(dir, "last_online_check");
            if (!jar.isFile()) return; // not unpacked yet: the built-in copy comes first
            if (stamp.isFile() && System.currentTimeMillis() - stamp.lastModified() < CHECK_INTERVAL_MS) return;

            JsonObject latest = JsonParser.parseString(httpGetString(LATEST_URL)).getAsJsonObject();
            touch(stamp);
            int remoteBuild = latest.get("build_number").getAsInt();
            int installedBuild = buildOf(readSmall(versionFile));
            if (remoteBuild <= installedBuild) return;

            String url = latest.get("download_url").getAsString();
            String sha256 = latest.getAsJsonObject("checksums").get("sha256").getAsString().toLowerCase(Locale.ROOT);
            if (!url.startsWith(DOWNLOAD_PREFIX)) {
                Log.w(TAG, "Unexpected download location " + url);
                return;
            }
            File temp = new File(dir, "authlib-injector.jar.download");
            download(url, temp);
            if (!sha256.equals(sha256Of(temp)) || !looksLikeAuthlibInjector(temp)) {
                Log.w(TAG, "Downloaded authlib-injector did not verify, keeping the current one");
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return;
            }
            if (!temp.renameTo(jar)) throw new IOException("Could not replace " + jar);
            Tools.write(versionFile, "b" + remoteBuild);
            Log.i(TAG, "authlib-injector updated to build " + remoteBuild);
        } catch (Throwable t) {
            Log.i(TAG, "authlib-injector update check skipped: " + t);
        }
    }

    private static boolean looksLikeAuthlibInjector(@NonNull File jar) {
        try (JarFile file = new JarFile(jar)) {
            Manifest manifest = file.getManifest();
            return manifest != null
                    && "moe.yushi.authlibinjector.Premain".equals(manifest.getMainAttributes().getValue("Premain-Class"));
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    private static String httpGetString(@NonNull String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static void download(@NonNull String url, @NonNull File target) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_JAR_BYTES) throw new IOException("authlib-injector download too large");
                out.write(buffer, 0, n);
            }
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static HttpURLConnection open(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "CSLauncherPlus");
        if (connection.getResponseCode() != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
        }
        return connection;
    }

    @NonNull
    private static String sha256Of(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    @Nullable
    private static String readSmall(@NonNull File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void touch(@NonNull File file) throws IOException {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
        } else {
            new FileOutputStream(file).close();
        }
    }
}
