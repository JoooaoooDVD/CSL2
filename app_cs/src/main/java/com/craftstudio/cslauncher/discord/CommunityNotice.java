package com.craftstudio.cslauncher.discord;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * A short notice the Craft Studio team can push to every launcher from Discord
 * ({@code /launcher notice set …} in the community bot). Stored in the website CMS
 * ({@code site_settings.launcher_notice}), fetched quietly at most every few hours and shown once
 * per notice id in the launcher's popup style. Never blocks, never shows twice, errors are ignored.
 */
public final class CommunityNotice {
    private static final String TAG = "CommunityNotice";
    private static final String PREFS = "cs_community_notice";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SEEN_ID = "seen_id";
    private static final long CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000;
    private static final String ENDPOINT = DiscordLinkNotifier.SUPABASE_URL
            + "/rest/v1/site_settings?select=value&key=eq.launcher_notice";

    private CommunityNotice() {}

    /** Call from the home screen once things are quiet; shows the popup if a new notice is live */
    public static void checkAndShow(@NonNull Activity activity) {
        final Context app = activity.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

        final java.lang.ref.WeakReference<Activity> ref = new java.lang.ref.WeakReference<>(activity);
        Thread thread = new Thread(() -> {
            JSONObject notice = fetch();
            if (notice == null) return;
            if (!notice.optBoolean("enabled", false)) return;
            String id = notice.optString("id", "");
            String text = notice.optString("text", "").trim();
            if (id.isEmpty() || text.isEmpty()) return;
            if (id.equals(prefs.getString(KEY_SEEN_ID, ""))) return;
            String minVersion = notice.optString("min_app_version", "");
            String appVersion = appVersion(app);
            if (!minVersion.isEmpty() && compareVersions(appVersion, minVersion) < 0) return;
            String maxVersion = notice.optString("max_app_version", "");
            if (!maxVersion.isEmpty() && compareVersions(appVersion, maxVersion) > 0) return;
            long endsAt = notice.optLong("ends_at_ms", 0L);
            if (endsAt > 0 && System.currentTimeMillis() > endsAt) return;

            final String title = notice.optString("title", "").trim();
            final String link = notice.optString("link", "").trim();
            final String button = notice.optString("button", "").trim();
            new Handler(Looper.getMainLooper()).post(() -> {
                Activity host = ref.get();
                if (host == null || host.isFinishing() || host.isDestroyed()) return;
                prefs.edit().putString(KEY_SEEN_ID, id).apply();
                show(host, title, text, link, button);
            });
        }, "CSL-Notice");
        thread.setDaemon(true);
        thread.start();
    }

    private static void show(@NonNull Activity activity, @NonNull String title, @NonNull String text,
                             @NonNull String link, @NonNull String button) {
        try {
            SettingsPopup popup = new SettingsPopup(activity)
                    .title(title.isEmpty() ? activity.getString(R.string.cs_notice_default_title) : title)
                    .subtitle(activity.getString(R.string.cs_notice_subtitle))
                    .icon(ContextCompat.getDrawable(activity, R.drawable.ic_discord_mark))
                    .message(text);
            if (!link.isEmpty() && (link.startsWith("https://") || link.startsWith("http://"))) {
                popup.positive(button.isEmpty() ? activity.getString(R.string.cs_notice_open) : button,
                        () -> Tools.openURL(activity, link));
                popup.negative(R.string.cs_notice_dismiss, null);
            } else {
                popup.positive(android.R.string.ok, null);
            }
            popup.show();
        } catch (Throwable t) {
            Log.w(TAG, "Could not show notice", t);
        }
    }

    @Nullable
    private static JSONObject fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("apikey", DiscordLinkNotifier.ANON_KEY);
            connection.setRequestProperty("Authorization", "Bearer " + DiscordLinkNotifier.ANON_KEY);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0 && buffer.size() < 64 * 1024) buffer.write(chunk, 0, n);
            }
            JSONArray rows = new JSONArray(buffer.toString("UTF-8"));
            if (rows.length() == 0) return null;
            Object value = rows.getJSONObject(0).opt("value");
            return value instanceof JSONObject ? (JSONObject) value : null;
        } catch (Throwable t) {
            Log.d(TAG, "fetch failed: " + t);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private static String appVersion(@NonNull Context context) {
        try {
            String v = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** "1.10.2" vs "1.9": numeric, missing parts count as 0, non-numeric suffixes ignored */
    static int compareVersions(@NonNull String a, @NonNull String b) {
        String[] pa = a.split("[.\\-+ ]"), pb = b.split("[.\\-+ ]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? leadingInt(pa[i]) : 0;
            int y = i < pb.length ? leadingInt(pb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int leadingInt(@NonNull String s) {
        int v = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return v;
    }
}
