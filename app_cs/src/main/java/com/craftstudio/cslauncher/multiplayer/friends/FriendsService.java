package com.craftstudio.cslauncher.multiplayer.friends;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CS Friends presence: one heartbeat every {@link #PERIOD_MS} while something listens (the launcher
 * home, the Friends page, the CS Engine activity while hosting). The device's friend token is made
 * once and kept in the launcher preferences; the friend code comes from the server. Each process
 * (launcher, engine) runs its own instance over the same token.
 */
public final class FriendsService {
    private static final String TAG = "CSFriends";
    private static final long PERIOD_MS = 20_000L;
    private static final String PREFS = "cs_friends";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CODE = "code";
    private static final String KEY_NAME = "name";
    public static final String KEY_MOTD = "motd";
    public static final String KEY_INVITES_SEEN = "invites_seen";

    public interface Listener {
        /** Fresh state (main thread). */
        void onFriendsUpdated(@NonNull FriendsApi.Snapshot snapshot);
        /** A new invite arrived (main thread); each invite is delivered once per device. */
        void onInvite(@NonNull FriendsApi.Invite invite);
        /** The last heartbeat failed (main thread); {@code message} is user-readable. */
        void onFriendsError(@NonNull String message);
    }

    private static FriendsService sInstance;

    public static synchronized FriendsService get(@NonNull Context context) {
        if (sInstance == null) sInstance = new FriendsService(context.getApplicationContext());
        return sInstance;
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final List<Listener> mListeners = new ArrayList<>();
    private final Set<Long> mSeenInvites = new HashSet<>();
    private boolean mRunning;
    private boolean mBusy;
    private String mStatus = "online";
    private String mHostAddress, mHostWorld, mHostEngine;
    @Nullable private FriendsApi.Snapshot mLast;
    private final Runnable mTick = this::tick;

    private FriendsService(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String seen = mPrefs.getString(KEY_INVITES_SEEN, "");
        for (String s : seen.split(",")) {
            if (s.isEmpty()) continue;
            try { mSeenInvites.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        }
    }

    // ---- identity ----

    /** The device's secret friend token (made on first use). */
    @NonNull
    public synchronized String token() {
        String t = mPrefs.getString(KEY_TOKEN, null);
        if (t == null || t.length() < 24) {
            byte[] raw = new byte[24];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder("csf_");
            for (byte b : raw) sb.append(String.format("%02x", b & 0xff));
            t = sb.toString();
            mPrefs.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    /** The last friend code the server gave us (null until the first heartbeat). */
    @Nullable public String code() { return mPrefs.getString(KEY_CODE, null); }
    @Nullable public FriendsApi.Snapshot last() { return mLast; }
    @Nullable public String motd() { return mPrefs.getString(KEY_MOTD, null); }
    public void setMotd(@Nullable String motd) { mPrefs.edit().putString(KEY_MOTD, motd).apply(); }

    /** The name friends see: the current account's. */
    @NonNull
    public static String displayName() {
        try {
            Account a = Accounts.getCurrent();
            if (a != null && a.username != null && !a.username.trim().isEmpty()) return a.username.trim();
        } catch (Throwable ignored) {
            // The engine process may not have the account store initialised; the server keeps the last name.
        }
        return "Player";
    }

    // ---- lifecycle ----

    public void addListener(@NonNull Listener l) {
        if (!mListeners.contains(l)) mListeners.add(l);
        start();
        if (mLast != null) l.onFriendsUpdated(mLast);
    }

    public void removeListener(@NonNull Listener l) {
        mListeners.remove(l);
        if (mListeners.isEmpty() && !"hosting".equals(mStatus) && !"in_game".equals(mStatus)) stop();
    }

    /** Presence while a game runs: {@code in_game}, or {@code hosting} with the public address. */
    public void setPresence(@NonNull String status, @Nullable String address, @Nullable String world, @Nullable String engine) {
        boolean changed = !status.equals(mStatus) || !eq(address, mHostAddress);
        mStatus = status;
        mHostAddress = address;
        mHostWorld = world;
        mHostEngine = engine;
        if ("offline".equals(status)) {
            stop();
            mWorker.execute(() -> {
                try { FriendsApi.heartbeat(token(), "offline", null, null, null); } catch (IOException ignored) {}
            });
            return;
        }
        start();
        if (changed) refresh();
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
    }

    public synchronized void stop() {
        mRunning = false;
        mMain.removeCallbacks(mTick);
    }

    /** One heartbeat now (the next regular one follows {@link #PERIOD_MS} later). */
    public void refresh() {
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
    }

    private void tick() {
        if (!mRunning) return;
        mMain.removeCallbacks(mTick);
        mMain.postDelayed(mTick, PERIOD_MS);
        if (mBusy) return;
        mBusy = true;
        final String status = mStatus, address = mHostAddress, world = mHostWorld, engine = mHostEngine;
        final String name = displayName();
        mWorker.execute(() -> {
            try {
                String token = token();
                if (code() == null || !name.equals(mPrefs.getString(KEY_NAME, null))) {
                    FriendsApi.register(token, name);
                    mPrefs.edit().putString(KEY_NAME, name).apply();
                }
                final FriendsApi.Snapshot s = FriendsApi.heartbeat(token, status, address, world, engine);
                if (s.myCode != null && !s.myCode.isEmpty()) mPrefs.edit().putString(KEY_CODE, s.myCode).apply();
                mMain.post(() -> deliver(s));
            } catch (final IOException e) {
                Log.w(TAG, "heartbeat failed: " + e.getMessage());
                mMain.post(() -> {
                    mBusy = false;
                    for (Listener l : new ArrayList<>(mListeners)) l.onFriendsError(message(e));
                });
            }
        });
    }

    private void deliver(@NonNull FriendsApi.Snapshot s) {
        mBusy = false;
        mLast = s;
        List<FriendsApi.Invite> fresh = new ArrayList<>();
        for (FriendsApi.Invite in : s.invites) {
            if (mSeenInvites.add(in.id)) fresh.add(in);
        }
        if (!fresh.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Long id : mSeenInvites) {
                if (n++ > 200) break;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            mPrefs.edit().putString(KEY_INVITES_SEEN, sb.toString()).apply();
        }
        for (Listener l : new ArrayList<>(mListeners)) {
            l.onFriendsUpdated(s);
            for (FriendsApi.Invite in : fresh) l.onInvite(in);
        }
    }

    // ---- actions (results on the main thread) ----

    public interface Result {
        void done(@Nullable String error, @Nullable String info);
    }

    public void addFriend(@NonNull final String code, @NonNull final Result result) {
        mWorker.execute(() -> {
            String error = null, info = null;
            try {
                org.json.JSONObject o = FriendsApi.addFriend(token(), code);
                if (!o.optBoolean("ok")) error = o.optString("error", "failed");
                else info = o.optString("state", "requested") + "|" + o.optString("name", "");
            } catch (IOException e) {
                error = message(e);
            }
            final String fe = error, fi = info;
            mMain.post(() -> { result.done(fe, fi); refresh(); });
        });
    }

    public void respond(@NonNull final String otherId, final boolean accept, @NonNull final Result result) {
        mWorker.execute(() -> {
            String error = null;
            try { FriendsApi.respond(token(), otherId, accept); } catch (IOException e) { error = message(e); }
            final String fe = error;
            mMain.post(() -> { result.done(fe, null); refresh(); });
        });
    }

    public void removeFriend(@NonNull final String otherId, @NonNull final Result result) {
        mWorker.execute(() -> {
            String error = null;
            try { FriendsApi.removeFriend(token(), otherId); } catch (IOException e) { error = message(e); }
            final String fe = error;
            mMain.post(() -> { result.done(fe, null); refresh(); });
        });
    }

    public void invite(@Nullable final String toId, @NonNull final String address, @Nullable final String world,
                       @Nullable final String engine, @NonNull final Result result) {
        mWorker.execute(() -> {
            String error = null, info = null;
            try { info = String.valueOf(FriendsApi.invite(token(), toId, address, world, engine)); }
            catch (IOException e) { error = message(e); }
            final String fe = error, fi = info;
            mMain.post(() -> result.done(fe, fi));
        });
    }

    private static String message(IOException e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) m = "No internet connection";
        return m;
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
