package com.craftstudio.cslauncher.multiplayer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.multiplayer.core.SessionCode;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Small persistent store for the multiplayer screen: recent sessions (codes only, never member
 * tokens or secrets beyond the code the user already shares), the display name and the optional
 * custom relay list. Backed by SharedPreferences; all values are tiny.
 */
public final class VirtualLanRepository {
    private static final String PREFS = "virtual_lan";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_NAME = "player_name";
    private static final String KEY_RELAYS = "custom_relays";
    private static final String KEY_HELP_SEEN = "help_seen";
    private static final int MAX_RECENT = 6;

    public static final class Recent {
        public final String code;
        public final boolean hosted;
        public final String hostName;
        public final String worldName;
        public final long time;

        Recent(String code, boolean hosted, String hostName, String worldName, long time) {
            this.code = code;
            this.hosted = hosted;
            this.hostName = hostName;
            this.worldName = worldName;
            this.time = time;
        }
    }

    private final SharedPreferences mPrefs;

    public VirtualLanRepository(@NonNull Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ───────────── player name ─────────────

    /** Custom name if set, else the selected account's username */
    @NonNull
    public String playerName() {
        String custom = mPrefs.getString(KEY_NAME, null);
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        try {
            Account account = Accounts.getCurrent();
            if (account != null && account.username != null && !account.username.trim().isEmpty()) return account.username.trim();
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    public void setPlayerName(@Nullable String name) {
        mPrefs.edit().putString(KEY_NAME, name == null ? "" : name.trim()).apply();
    }

    // ───────────── relays ─────────────

    @NonNull
    public List<String> customRelays() {
        List<String> out = new ArrayList<>();
        String raw = mPrefs.getString(KEY_RELAYS, "");
        if (raw == null) return out;
        for (String line : raw.split("[\\n,\\s]+")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void setCustomRelays(@NonNull String raw) {
        mPrefs.edit().putString(KEY_RELAYS, raw.trim()).apply();
    }

    public boolean helpSeen() { return mPrefs.getBoolean(KEY_HELP_SEEN, false); }
    public void setHelpSeen() { mPrefs.edit().putBoolean(KEY_HELP_SEEN, true).apply(); }

    // ───────────── recent sessions ─────────────

    @NonNull
    public List<Recent> recent() {
        List<Recent> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(mPrefs.getString(KEY_RECENT, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String code = o.optString("code", "");
                if (SessionCode.parse(code) == null) continue;
                out.add(new Recent(code, o.optBoolean("hosted"), o.optString("host", ""), o.optString("world", ""), o.optLong("time")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Records the session the moment a code is known; later calls refresh host/world names */
    public void remember(@NonNull VirtualLanState state) {
        if (state.sessionCode == null || state.sessionCode.isEmpty()) return;
        List<Recent> list = recent();
        Recent previous = null;
        for (Recent r : list) if (r.code.equals(state.sessionCode)) { previous = r; break; }
        if (previous != null) list.remove(previous);
        String host = state.isHost() ? "" : (state.hostName.isEmpty() && previous != null ? previous.hostName : state.hostName);
        String world = state.worldName.isEmpty() && previous != null ? previous.worldName : state.worldName;
        list.add(0, new Recent(state.sessionCode, state.isHost(), host, world, System.currentTimeMillis()));
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        save(list);
    }

    public void forget(@NonNull String code) {
        List<Recent> list = recent();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).code.equals(code)) list.remove(i);
        save(list);
    }

    public void clearRecent() { save(new ArrayList<Recent>()); }

    private void save(List<Recent> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Recent r : list) {
                arr.put(new JSONObject().put("code", r.code).put("hosted", r.hosted).put("host", r.hostName)
                        .put("world", r.worldName).put("time", r.time));
            }
        } catch (JSONException ignored) {
        }
        mPrefs.edit().putString(KEY_RECENT, arr.toString()).apply();
    }
}
