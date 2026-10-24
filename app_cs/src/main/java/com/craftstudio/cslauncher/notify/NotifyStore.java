package com.craftstudio.cslauncher.notify;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** What was already shown / delivered on this device, plus an anonymous device hash for stats */
final class NotifyStore {
    private static final String FILE = "cs_notify";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_SHOWN = "shown";           // announcement ids
    private static final String KEY_DISMISSED = "dismissed";   // announcement ids (show_once=false items the user closed)
    private static final String KEY_DELIVERED = "delivered";   // notification ids
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_FIRST_SEEN = "first_seen";

    private NotifyStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @NonNull
    static String deviceHash(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString(KEY_DEVICE, null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            p.edit().putString(KEY_DEVICE, id).putLong(KEY_FIRST_SEEN, System.currentTimeMillis()).apply();
        }
        return id;
    }

    /** Install time of this launcher (first run with the notify module): older notifications are not replayed */
    static long firstSeen(Context c) {
        SharedPreferences p = prefs(c);
        long t = p.getLong(KEY_FIRST_SEEN, 0L);
        if (t == 0L) {
            t = System.currentTimeMillis();
            p.edit().putLong(KEY_FIRST_SEEN, t).apply();
        }
        return t;
    }

    static boolean wasShown(Context c, String id) { return set(c, KEY_SHOWN).contains(id); }
    static boolean wasDismissed(Context c, String id) { return set(c, KEY_DISMISSED).contains(id); }
    static boolean wasDelivered(Context c, String id) { return set(c, KEY_DELIVERED).contains(id); }

    static void markShown(Context c, String id) { add(c, KEY_SHOWN, id); }
    static void markDismissed(Context c, String id) { add(c, KEY_DISMISSED, id); }
    static void markDelivered(Context c, String id) { add(c, KEY_DELIVERED, id); }

    static long lastSync(Context c) { return prefs(c).getLong(KEY_LAST_SYNC, 0L); }
    static void setLastSync(Context c, long t) { prefs(c).edit().putLong(KEY_LAST_SYNC, t).apply(); }

    private static Set<String> set(Context c, String key) {
        return new HashSet<>(prefs(c).getStringSet(key, new HashSet<String>()));
    }

    private static void add(Context c, String key, String id) {
        Set<String> s = set(c, key);
        if (s.add(id)) {
            // keep the sets bounded
            if (s.size() > 400) {
                Set<String> trimmed = new HashSet<>();
                int skip = s.size() - 300;
                for (String v : s) { if (skip-- > 0) continue; trimmed.add(v); }
                trimmed.add(id);
                s = trimmed;
            }
            prefs(c).edit().putStringSet(key, s).apply();
        }
    }
}
