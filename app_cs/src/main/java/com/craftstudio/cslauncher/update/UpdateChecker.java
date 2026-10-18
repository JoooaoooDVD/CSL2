package com.craftstudio.cslauncher.update;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the published builds from the GitHub Releases API (one request, no auth). Each release
 * published by the CI carries a machine-readable block in its body:
 * <pre>
 * cs-update: versionCode=130 versionName=1.4
 * cs-apk: file=app-debug-full.apk sha256=... size=...
 * </pre>
 * Releases without that block (older ones, drafts) are ignored, so nothing older than the
 * current build can ever be offered: comparison is on {@code versionCode}, which the CI
 * derives from the monotonically increasing run number.
 */
public final class UpdateChecker {
    public static final String REPO = "JoooaoooDVD/CSL2";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=12";
    private static final Pattern UPDATE_LINE = Pattern.compile("cs-update:\\s*versionCode=(\\d+)\\s+versionName=(\\S+)");
    private static final Pattern APK_LINE = Pattern.compile("cs-apk:\\s*file=(.+?)\\s+sha256=([0-9a-fA-F]{64})\\s+size=(\\d+)");
    private static final Pattern META_BLOCK = Pattern.compile("<!--\\s*cs-update-begin.*?cs-update-end\\s*-->", Pattern.DOTALL);

    private UpdateChecker() {}

    public static final class Result {
        /** The newest build that matches the channel, or null when there is none */
        @Nullable public final UpdateInfo newest;
        /** True when at least one release carried update metadata */
        public final boolean anyPublished;
        Result(@Nullable UpdateInfo newest, boolean anyPublished) { this.newest = newest; this.anyPublished = anyPublished; }
    }

    /** Blocking. {@code stableOnly} ignores pre-releases (the rolling "latest" build). */
    @NonNull
    public static Result fetch(boolean stableOnly) throws IOException {
        String body = get(API);
        try {
            JSONArray releases = new JSONArray(body);
            UpdateInfo best = null;
            boolean any = false;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft", false)) continue;
                UpdateInfo info = parse(r);
                if (info == null) continue;
                any = true;
                if (stableOnly && info.prerelease) continue;
                if (best == null || info.versionCode > best.versionCode
                        || (info.versionCode == best.versionCode && info.publishedAt > best.publishedAt)) best = info;
            }
            return new Result(best, any);
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage(), e);
        }
    }

    @Nullable
    private static UpdateInfo parse(JSONObject r) throws org.json.JSONException {
        String rawBody = r.optString("body", "");
        Matcher m = UPDATE_LINE.matcher(rawBody);
        if (!m.find()) return null;
        int code;
        try { code = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
        String name = m.group(2);
        String notes = META_BLOCK.matcher(rawBody).replaceAll("").trim();
        // Lines of the block outside the HTML comment (older format): strip them as well
        notes = notes.replaceAll("(?m)^cs-(update|apk):.*$", "").trim();
        UpdateInfo info = new UpdateInfo(
                r.optString("tag_name", ""), r.optString("name", ""), code, name,
                r.optBoolean("prerelease", false), parseTime(r.optString("published_at", "")),
                r.optString("html_url", "https://github.com/" + REPO + "/releases"), notes);
        JSONArray assets = r.optJSONArray("assets");
        Matcher a = APK_LINE.matcher(rawBody);
        while (a.find()) {
            String file = a.group(1).trim();
            String url = assetUrl(assets, file);
            if (url == null) continue; // listed but not uploaded: unusable
            long size;
            try { size = Long.parseLong(a.group(3)); } catch (NumberFormatException e) { size = -1; }
            info.apks.add(new UpdateInfo.Apk(file, a.group(2).toLowerCase(Locale.ROOT), size, url));
        }
        return info;
    }

    @Nullable
    private static String assetUrl(@Nullable JSONArray assets, String file) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            if (file.equalsIgnoreCase(asset.optString("name", ""))) {
                String url = asset.optString("browser_download_url", "");
                return TextUtils.isEmpty(url) ? null : url;
            }
        }
        return null;
    }

    private static long parseTime(String iso) {
        if (TextUtils.isEmpty(iso)) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    @NonNull
    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "CSLauncherPlus-Updater");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status = c.getResponseCode();
            if (status == 403 || status == 429) throw new IOException("GitHub rate limit reached, try again later");
            if (status < 200 || status >= 300) throw new IOException("GitHub answered HTTP " + status);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
