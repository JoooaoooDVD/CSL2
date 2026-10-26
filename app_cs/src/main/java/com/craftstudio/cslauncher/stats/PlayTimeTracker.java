package com.craftstudio.cslauncher.stats;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of how long each profile has been played.
 * <p>
 * The game runs in its own process ({@code :game}), so the data lives in a JSON file in the
 * launcher data directory that both processes can see. The game process is the only writer:
 * it opens a session right before the JVM starts, refreshes a heartbeat every
 * {@link #HEARTBEAT_MS} and closes the session when the game exits. If the process is killed
 * (OOM, force stop, crash) the session stays open in the file; readers treat the last heartbeat
 * as the end, so at most {@link #HEARTBEAT_MS} of play is lost.
 * <p>
 * Totals per profile are kept separately from the session list so the list can be trimmed
 * without losing the lifetime numbers.
 */
public final class PlayTimeTracker {
    private static final String TAG = "PlayTimeTracker";
    private static final String FILE_NAME = "cs_playtime.json";
    private static final long HEARTBEAT_MS = 20_000L;
    private static final int MAX_SESSIONS = 400;
    /** A session shorter than this is still counted, but not reported as "longest" etc. */
    private static final long MIN_SESSION_MS = 1_000L;

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    @Nullable private static Thread sHeartbeat;
    @Nullable private static String sOpenSessionId;

    private PlayTimeTracker() {}

    // ─── Data model ─────────────────────────────────────────────────────────

    public static final class Session {
        @SerializedName("id") public String id;
        @SerializedName("key") public String profileKey;
        @SerializedName("name") public String profileName;
        @SerializedName("version") public String versionId;
        @SerializedName("start") public long start;
        @SerializedName("end") public long end;
        @SerializedName("beat") public long heartbeat;
        @SerializedName("closed") public boolean closed;

        /** Effective end: the real one, or the last heartbeat if the game died */
        public long effectiveEnd() {
            if (closed && end > 0) return end;
            return Math.max(start, heartbeat);
        }

        public long duration() {
            return Math.max(0L, effectiveEnd() - start);
        }

        /** Still being written by a live game process */
        public boolean isLive(long now) {
            return !closed && now - heartbeat < HEARTBEAT_MS * 3;
        }
    }

    public static final class ProfileStats {
        @SerializedName("key") public String profileKey;
        @SerializedName("name") public String profileName;
        @SerializedName("version") public String versionId;
        @SerializedName("total") public long totalMs;
        @SerializedName("sessions") public int sessions;
        @SerializedName("first") public long firstPlayed;
        @SerializedName("last") public long lastPlayed;
        @SerializedName("longest") public long longestMs;
    }

    static final class Store {
        @SerializedName("profiles") Map<String, ProfileStats> profiles = new HashMap<>();
        @SerializedName("sessions") List<Session> sessions = new ArrayList<>();
    }

    /** Snapshot for the UI: everything resolved (open sessions folded in) */
    public static final class Summary {
        public long totalMs;
        public int sessionCount;
        public long todayMs;
        public long weekMs;
        public long longestMs;
        public long averageMs;
        public long firstPlayed;
        public long lastPlayed;
        @Nullable public String lastProfileKey;
        @Nullable public String lastProfileName;
        @Nullable public Session liveSession;
        /** Sorted by total time, descending */
        public final List<ProfileStats> profiles = new ArrayList<>();
        /** Newest first */
        public final List<Session> recent = new ArrayList<>();
    }

    // ─── Writing (game process) ──────────────────────────────────────────────

    /** Call right before the JVM starts; idempotent while a session is open */
    public static void beginSession(@Nullable Instance instance, @Nullable String versionId) {
        if (instance == null) return;
        synchronized (LOCK) {
            if (sOpenSessionId != null) return;
            Store store = read();
            long now = System.currentTimeMillis();
            Session session = new Session();
            session.id = now + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
            session.profileKey = instance.getKey();
            session.profileName = instance.name;
            session.versionId = versionId != null ? versionId : instance.versionId;
            session.start = now;
            session.heartbeat = now;
            session.closed = false;
            store.sessions.add(session);
            trim(store);
            write(store);
            sOpenSessionId = session.id;
            startHeartbeat();
            Log.i(TAG, "Session started for " + session.profileName);
        }
    }

    /** Call when the game exits; safe to call more than once */
    public static void endSession() {
        synchronized (LOCK) {
            String id = sOpenSessionId;
            if (id == null) return;
            sOpenSessionId = null;
            stopHeartbeat();
            Store store = read();
            Session session = find(store, id);
            if (session == null) return;
            long now = System.currentTimeMillis();
            session.end = now;
            session.heartbeat = now;
            session.closed = true;
            fold(store, session);
            write(store);
            Log.i(TAG, "Session ended: " + formatDuration(session.duration()));
        }
    }

    private static void startHeartbeat() {
        stopHeartbeat();
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (LOCK) {
                    if (sOpenSessionId == null) return;
                    Store store = read();
                    Session session = find(store, sOpenSessionId);
                    if (session == null) return;
                    session.heartbeat = System.currentTimeMillis();
                    write(store);
                }
            }
        }, "PlayTimeHeartbeat");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        sHeartbeat = thread;
        thread.start();
    }

    private static void stopHeartbeat() {
        if (sHeartbeat != null) {
            sHeartbeat.interrupt();
            sHeartbeat = null;
        }
    }

    @Nullable
    private static Session find(Store store, String id) {
        for (Session s : store.sessions) if (id.equals(s.id)) return s;
        return null;
    }

    /** Adds a finished session to the lifetime totals of its profile */
    private static void fold(Store store, Session session) {
        if (session.profileKey == null) return;
        ProfileStats stats = store.profiles.get(session.profileKey);
        if (stats == null) {
            stats = new ProfileStats();
            stats.profileKey = session.profileKey;
            stats.firstPlayed = session.start;
            store.profiles.put(session.profileKey, stats);
        }
        long duration = session.duration();
        stats.profileName = session.profileName;
        stats.versionId = session.versionId;
        stats.totalMs += duration;
        stats.sessions++;
        stats.lastPlayed = Math.max(stats.lastPlayed, session.effectiveEnd());
        if (stats.firstPlayed == 0 || session.start < stats.firstPlayed) stats.firstPlayed = session.start;
        stats.longestMs = Math.max(stats.longestMs, duration);
    }

    /** Folds sessions that were never closed (process killed) into the totals, then drops old ones */
    private static void trim(Store store) {
        long now = System.currentTimeMillis();
        for (Session s : store.sessions) {
            if (!s.closed && !s.isLive(now)) {
                s.closed = true;
                s.end = s.effectiveEnd();
                fold(store, s);
            }
        }
        if (store.sessions.size() > MAX_SESSIONS) {
            store.sessions = new ArrayList<>(store.sessions.subList(store.sessions.size() - MAX_SESSIONS, store.sessions.size()));
        }
    }

    // ─── Reading (launcher process) ──────────────────────────────────────────

    /** Lifetime stats of one profile, with a live / orphaned session folded in; null if never played */
    @Nullable
    public static ProfileStats statsFor(@Nullable String profileKey) {
        if (profileKey == null) return null;
        Summary summary = summary();
        for (ProfileStats p : summary.profiles) if (profileKey.equals(p.profileKey)) return p;
        return null;
    }

    /** Last time a profile was played (end of the last session), 0 if never */
    public static long lastPlayed(@Nullable String profileKey) {
        ProfileStats stats = statsFor(profileKey);
        return stats == null ? 0L : stats.lastPlayed;
    }

    @NonNull
    public static Summary summary() {
        Store store;
        synchronized (LOCK) {
            store = read();
        }
        long now = System.currentTimeMillis();
        // Work on copies: never persist from the reader side
        Map<String, ProfileStats> profiles = new HashMap<>();
        for (Map.Entry<String, ProfileStats> e : store.profiles.entrySet()) profiles.put(e.getKey(), copy(e.getValue()));
        Store view = new Store();
        view.profiles = profiles;
        Summary summary = new Summary();
        for (Session s : store.sessions) {
            if (!s.closed) {
                // Orphaned or live: count it in the view as if it ended at the last heartbeat
                Session folded = copySession(s);
                folded.closed = true;
                boolean live = s.isLive(now);
                // A live session counts up to this very moment, a dead one up to its last heartbeat
                folded.end = live ? Math.max(now, s.start) : s.effectiveEnd();
                fold(view, folded);
                if (live) summary.liveSession = s;
            }
        }
        long dayStart = startOfDay(now);
        long weekStart = dayStart - 6L * 24 * 3600 * 1000;
        for (ProfileStats p : profiles.values()) {
            summary.totalMs += p.totalMs;
            summary.sessionCount += p.sessions;
            summary.longestMs = Math.max(summary.longestMs, p.longestMs);
            if (p.firstPlayed > 0 && (summary.firstPlayed == 0 || p.firstPlayed < summary.firstPlayed)) summary.firstPlayed = p.firstPlayed;
            if (p.lastPlayed > summary.lastPlayed) {
                summary.lastPlayed = p.lastPlayed;
                summary.lastProfileKey = p.profileKey;
                summary.lastProfileName = p.profileName;
            }
            summary.profiles.add(p);
        }
        Collections.sort(summary.profiles, new Comparator<ProfileStats>() {
            @Override public int compare(ProfileStats a, ProfileStats b) {
                if (a.totalMs != b.totalMs) return a.totalMs > b.totalMs ? -1 : 1;
                return a.lastPlayed > b.lastPlayed ? -1 : (a.lastPlayed < b.lastPlayed ? 1 : 0);
            }
        });
        summary.averageMs = summary.sessionCount == 0 ? 0 : summary.totalMs / summary.sessionCount;
        for (int i = store.sessions.size() - 1; i >= 0; i--) {
            Session s = store.sessions.get(i);
            long end = s.effectiveEnd();
            long start = s.start;
            // Portion of the session inside today / the last 7 days
            if (end > dayStart) summary.todayMs += end - Math.max(start, dayStart);
            if (end > weekStart) summary.weekMs += end - Math.max(start, weekStart);
            if (s.duration() >= MIN_SESSION_MS || !s.closed) summary.recent.add(s);
        }
        return summary;
    }

    private static ProfileStats copy(ProfileStats p) {
        ProfileStats c = new ProfileStats();
        c.profileKey = p.profileKey; c.profileName = p.profileName; c.versionId = p.versionId;
        c.totalMs = p.totalMs; c.sessions = p.sessions; c.firstPlayed = p.firstPlayed;
        c.lastPlayed = p.lastPlayed; c.longestMs = p.longestMs;
        return c;
    }

    private static Session copySession(Session s) {
        Session c = new Session();
        c.id = s.id; c.profileKey = s.profileKey; c.profileName = s.profileName; c.versionId = s.versionId;
        c.start = s.start; c.end = s.end; c.heartbeat = s.heartbeat; c.closed = s.closed;
        return c;
    }

    private static long startOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ─── Storage ─────────────────────────────────────────────────────────────

    private static File file() {
        return new File(Tools.DIR_GAME_HOME, FILE_NAME);
    }

    @NonNull
    private static Store read() {
        File file = file();
        if (!file.isFile()) return new Store();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Store store = GSON.fromJson(reader, Store.class);
            if (store == null) return new Store();
            if (store.profiles == null) store.profiles = new HashMap<>();
            if (store.sessions == null) store.sessions = new ArrayList<>();
            return store;
        } catch (Exception e) {
            Log.w(TAG, "Play time file unreadable, starting fresh", e);
            return new Store();
        }
    }

    /** Atomic: written next to the file then renamed, so a reader never sees a half file */
    private static void write(Store store) {
        File file = file();
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.w(TAG, "Cannot create " + parent);
                return;
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(store, writer);
            }
            if (!tmp.renameTo(file)) {
                // Rename across the same directory should not fail; fall back to a direct write
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    GSON.toJson(store, writer);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot save play time", e);
        }
    }

    // ─── Formatting ──────────────────────────────────────────────────────────

    /** "2h 14m", "48m 10s", "12s" */
    @NonNull
    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000);
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /** Long form for the stats screen: "2 hours 14 minutes" */
    @NonNull
    public static String formatDurationLong(@NonNull Context context, long ms) {
        Resources res = context.getResources();
        long totalSeconds = Math.max(0L, ms / 1000);
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        int parts = 0;
        if (days > 0) { sb.append(res.getQuantityString(R.plurals.pt_days, (int) days, (int) days)); parts++; }
        if (hours > 0 && parts < 2) { if (parts > 0) sb.append(' '); sb.append(res.getQuantityString(R.plurals.pt_hours, (int) hours, (int) hours)); parts++; }
        if (minutes > 0 && parts < 2) { if (parts > 0) sb.append(' '); sb.append(res.getQuantityString(R.plurals.pt_minutes, (int) minutes, (int) minutes)); parts++; }
        if (parts == 0 || (parts < 2 && days == 0 && hours == 0)) {
            if (parts > 0) sb.append(' ');
            sb.append(res.getQuantityString(R.plurals.pt_seconds, (int) seconds, (int) seconds));
        }
        return sb.toString();
    }

    /** "just now", "35 seconds ago", "4 minutes ago", "3 hours ago", "2 days ago" */
    @NonNull
    public static String formatAgo(@NonNull Context context, long timestamp) {
        Resources res = context.getResources();
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 0) diff = 0;
        long seconds = diff / 1000;
        if (seconds < 5) return context.getString(R.string.pt_just_now);
        if (seconds < 60) return res.getQuantityString(R.plurals.pt_ago_seconds, (int) seconds, (int) seconds);
        long minutes = seconds / 60;
        if (minutes < 60) return res.getQuantityString(R.plurals.pt_ago_minutes, (int) minutes, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24) return res.getQuantityString(R.plurals.pt_ago_hours, (int) hours, (int) hours);
        long days = hours / 24;
        if (days < 30) return res.getQuantityString(R.plurals.pt_ago_days, (int) days, (int) days);
        long months = days / 30;
        if (months < 12) return res.getQuantityString(R.plurals.pt_ago_months, (int) months, (int) months);
        long years = days / 365;
        return res.getQuantityString(R.plurals.pt_ago_years, (int) Math.max(1, years), (int) Math.max(1, years));
    }

    /** How often a relative label for this timestamp should be refreshed to stay accurate */
    public static long refreshIntervalFor(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60_000L) return 1_000L;
        if (diff < 3_600_000L) return 15_000L;
        return 60_000L;
    }
}
