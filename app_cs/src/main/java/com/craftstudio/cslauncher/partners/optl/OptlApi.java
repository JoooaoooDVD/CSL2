package com.craftstudio.cslauncher.partners.optl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.friends.FriendsApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OPTL (Official Pojav Tier List) integration: the public rankings feed from OPTL's website and
 * the "request a test" form, which is stored by Craft Studio and read by OPTL's website through
 * a keyed feed ({@code optl_requests_feed}).
 */
public final class OptlApi {
    public static final String RANKINGS_URL = "https://officialpojavtierlist.freesrv.com/api/rankings";
    public static final String WEBSITE_URL = "https://officialpojavtierlist.freesrv.com/";
    public static final String[] REGIONS = {"AS", "AS/AU", "EU", "NA", "SA", "AF", "AU", "ME"};
    public static final String[] GAMEMODES = {"Sword", "Axe", "Pot", "NethOP", "UHC", "SMP", "DIA SMP", "Vanilla", "Crystals", "Mace", "Spear-Mace", "CART"};

    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static final String PREFS = "cs_optl";
    private static final String K_LAST_ID = "last_request_id";
    private static final String K_LAST_USER = "last_request_user";

    public static final class Player {
        public String discordId = "", username = "", region = "", overallTier = "";
        public int points;
        public final Map<String, String> ranks = new LinkedHashMap<>();
    }

    public static final class Status {
        public long id; public String status = "pending", note = "", createdAt = "";
    }

    public interface RankingsCallback { void done(@Nullable List<Player> players, @Nullable String error); }
    public interface SubmitCallback { void done(long id, @Nullable String error); }
    public interface StatusCallback { void done(@Nullable List<Status> statuses, @Nullable String error); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    @Nullable private static List<Player> sRankings;
    private static long sRankingsAt;

    private OptlApi() {}

    // --- rankings --------------------------------------------------------------------------

    public static void rankings(boolean force, @NonNull RankingsCallback cb) {
        synchronized (LOCK) {
            if (!force && sRankings != null && SystemClock.elapsedRealtime() - sRankingsAt < CACHE_MS) {
                final List<Player> cached = sRankings;
                MAIN.post(() -> cb.done(cached, null));
                return;
            }
        }
        IO.execute(() -> {
            List<Player> list = null; String err = null;
            try {
                list = parseRankings(get(RANKINGS_URL));
                synchronized (LOCK) { sRankings = list; sRankingsAt = SystemClock.elapsedRealtime(); }
            } catch (Exception e) {
                err = e.getMessage() == null ? "Could not reach OPTL" : e.getMessage();
                synchronized (LOCK) { if (sRankings != null) { list = sRankings; err = null; } }
            }
            final List<Player> fl = list; final String fe = err;
            MAIN.post(() -> cb.done(fl, fe));
        });
    }

    @NonNull
    static List<Player> parseRankings(@NonNull String raw) throws JSONException {
        JSONArray arr = new JSONArray(raw.trim());
        List<Player> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Player p = new Player();
            p.discordId = o.optString("discordId", "");
            p.username = o.optString("minecraftUsername", "");
            p.region = o.optString("region", "");
            p.overallTier = o.optString("overallTier", "");
            p.points = o.optInt("overallPoints", 0);
            JSONObject ranks = o.optJSONObject("ranks");
            if (ranks != null) {
                Iterator<String> it = ranks.keys();
                while (it.hasNext()) { String k = it.next(); p.ranks.put(k, ranks.optString(k, "")); }
            }
            if (!p.username.isEmpty()) out.add(p);
        }
        // The feed is already ordered by points; keep it stable but make sure of it
        java.util.Collections.sort(out, (a, b) -> b.points - a.points);
        return out;
    }

    /** The player's entry in the cached/fetched list, matched by Minecraft username. */
    @Nullable
    public static Player find(@Nullable List<Player> list, @Nullable String username) {
        if (list == null || username == null) return null;
        for (Player p : list) if (p.username.equalsIgnoreCase(username.trim())) return p;
        return null;
    }

    public static int indexOf(@Nullable List<Player> list, @Nullable Player p) {
        return list == null || p == null ? -1 : list.indexOf(p);
    }

    // --- test requests ---------------------------------------------------------------------

    public static void submit(@NonNull Context context, @NonNull String mcUsername, @NonNull String discordUsername,
                              @Nullable String discordId, @NonNull String region, @NonNull List<String> gamemodes,
                              @Nullable String previousTier, @Nullable String experience, @Nullable String csUsername,
                              @Nullable String engine, @NonNull SubmitCallback cb) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            long id = -1; String err = null;
            try {
                JSONObject a = new JSONObject();
                a.put("p_mc_username", mcUsername.trim());
                a.put("p_discord_username", discordUsername.trim());
                a.put("p_discord_id", discordId == null ? "" : discordId.trim());
                a.put("p_region", region);
                a.put("p_gamemodes", new JSONArray(gamemodes));
                a.put("p_previous_tier", previousTier == null ? "" : previousTier.trim());
                a.put("p_experience", experience == null ? "" : experience.trim());
                a.put("p_device", (Build.MANUFACTURER + " " + Build.MODEL).trim());
                a.put("p_android_version", Build.VERSION.RELEASE);
                a.put("p_launcher_version", app.getString(com.craftstudio.cslauncher.R.string.brand_version));
                a.put("p_engine", engine == null ? "" : engine);
                a.put("p_cs_username", csUsername == null ? "" : csUsername);
                a.put("p_install_id", installId(app));
                JSONObject r = FriendsApi.call("optl_request_test", a);
                if (r.optBoolean("ok", false)) {
                    id = r.optLong("id", -1);
                    prefs(app).edit().putLong(K_LAST_ID, id).putString(K_LAST_USER, mcUsername.trim()).apply();
                } else {
                    err = describe(r.optString("error", "error"));
                    if ("already_pending".equals(r.optString("error"))) {
                        prefs(app).edit().putLong(K_LAST_ID, r.optLong("id", -1)).putString(K_LAST_USER, mcUsername.trim()).apply();
                    }
                }
            } catch (IOException | JSONException e) {
                err = e.getMessage() == null ? "Network error" : e.getMessage();
            }
            final long fid = id; final String fe = err;
            MAIN.post(() -> cb.done(fid, fe));
        });
    }

    public static void status(@NonNull String mcUsername, @NonNull StatusCallback cb) {
        IO.execute(() -> {
            List<Status> list = null; String err = null;
            try {
                JSONObject a = new JSONObject().put("p_mc_username", mcUsername.trim());
                JSONObject r = FriendsApi.call("optl_request_status", a);
                Object v = r.opt("value");
                JSONArray arr = v instanceof JSONArray ? (JSONArray) v : v instanceof String ? new JSONArray((String) v) : null;
                list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Status st = new Status();
                        st.id = o.optLong("id");
                        st.status = o.optString("status", "pending");
                        st.note = o.isNull("note") ? "" : o.optString("note", "");
                        st.createdAt = o.optString("created_at", "");
                        list.add(st);
                    }
                }
            } catch (IOException | JSONException e) {
                err = e.getMessage() == null ? "Network error" : e.getMessage();
            }
            final List<Status> fl = list; final String fe = err;
            MAIN.post(() -> cb.done(fl, fe));
        });
    }

    @NonNull
    public static String lastRequestUser(@NonNull Context context) { return prefs(context).getString(K_LAST_USER, ""); }
    public static long lastRequestId(@NonNull Context context) { return prefs(context).getLong(K_LAST_ID, -1); }

    private static String describe(String code) {
        switch (code) {
            case "invalid_username": return "Enter a valid Minecraft username (3–16 letters, digits or _).";
            case "invalid_discord": return "Enter your Discord username.";
            case "invalid_region": return "Pick your region.";
            case "no_gamemodes": return "Pick at least one gamemode.";
            case "rate_limited": return "Too many requests from this device today.";
            case "already_pending": return "A test request for this username is already pending.";
            default: return "The request was not accepted (" + code + ").";
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @SuppressWarnings("HardwareIds")
    private static String installId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CS-Launcher-Plus");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("OPTL replied " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
