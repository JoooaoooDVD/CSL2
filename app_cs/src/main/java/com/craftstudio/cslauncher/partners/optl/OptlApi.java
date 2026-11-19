package com.craftstudio.cslauncher.partners.optl;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


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
 * OPTL (Official Pojav Tier List) integration: the public rankings feed from OPTL's website.
 * Tests themselves are requested on OPTL's Discord (the launcher explains how); nothing is
 * stored by Craft Studio.
 */
public final class OptlApi {
    public static final String RANKINGS_URL = "https://officialpojavtierlist.freesrv.com/api/rankings";
    public static final String WEBSITE_URL = "https://officialpojavtierlist.freesrv.com/";
    public static final String DISCORD_URL = "https://discord.gg/1-21-official-pojav-tierlist-1501790037316993144";

    private static final long CACHE_MS = 5 * 60 * 1000L;

    public static final class Player {
        public String discordId = "", username = "", region = "", overallTier = "";
        public int points;
        public final Map<String, String> ranks = new LinkedHashMap<>();
    }

    public interface RankingsCallback { void done(@Nullable List<Player> players, @Nullable String error); }

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


    @SuppressWarnings("HardwareIds")
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
