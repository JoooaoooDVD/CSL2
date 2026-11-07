package com.craftstudio.cslauncher.friends;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's live link to the CS friends backend: periodic {@code cs_sync} (friends, requests,
 * invites, messages), presence (online / hosting with address), and the actions of the Friends
 * page. One instance per process; the game process uses it only to publish "hosting".
 * Listeners are called on the main thread.
 */
public final class FriendsClient {
    private static final String TAG = "FriendsClient";
    private static final FriendsClient INSTANCE = new FriendsClient();
    private static final long POLL_FAST = 3000, POLL_NORMAL = 8000, POLL_SLOW = 20000;
    private static final String K_SEEN = "cs.friends.seen";
    private static final String K_INBOX = "cs.friends.inbox";

    public static final class Friend {
        public String id, name, code, status, address, world, engine, version;
        public boolean online() { return status != null && !"offline".equals(status) && !status.isEmpty(); }
        public boolean hosting() { return address != null && !address.isEmpty(); }
    }
    public static final class Request { public String id, name, code; public boolean incoming; public long at; }
    public static final class Invite { public String id, from, fromId, address, world, engine, version; public long at; }
    public static final class Message {
        public String id, from, to, body; public long at; public boolean mine;
        /** Photo URL (public cs-chat object) or null. */
        @Nullable public String image;
        /** Quoted message: id, sender id and a short snippet; null when not a reply. */
        @Nullable public String replyTo, replyFrom, replyBody;
        /** "invite" for a world invite sent as a chat message; null for an ordinary message. */
        @Nullable public String kind;
        /** Invite details (kind == "invite"): where to join. */
        @Nullable public String inviteAddress, inviteWorld, inviteEngine, inviteVersion;
        public boolean hasText() { return body != null && !body.isEmpty(); }
        public boolean isInvite() { return "invite".equals(kind) && inviteAddress != null && !inviteAddress.isEmpty(); }
        /** Invites are good for ten minutes (the server's invite lifetime). */
        public boolean inviteLive(long now) { return isInvite() && now - at < 10 * 60_000L; }
    }

    /** Immutable snapshot of the last successful sync. */
    public static final class Snapshot {
        public final List<Friend> friends = new ArrayList<>();
        public final List<Request> requests = new ArrayList<>();
        public final List<Invite> invites = new ArrayList<>();
        public final List<Message> messages = new ArrayList<>();
        public long now;
        @Nullable public Friend friend(String id) {
            for (Friend f : friends) if (f.id.equals(id)) return f;
            return null;
        }
        public int incomingRequests() {
            int n = 0;
            for (Request r : requests) if (r.incoming) n++;
            return n;
        }
    }

    /** One line of the bell inbox (persisted, newest first). */
    public static final class Note {
        public static final int INVITE = 0, REQUEST = 1, MESSAGE = 2, INFO = 3;
        public int kind; public String key, title, body, fromId, address, engine, world, version; public long at; public boolean read;
    }

    public interface Listener {
        void onFriendsChanged(@NonNull Snapshot snapshot);
        /** A new invite / request / message arrived (already in the inbox). */
        default void onNote(@NonNull Note note) {}
        default void onFriendsError(@NonNull String message) {}
    }

    public interface Result { void done(@Nullable String error); }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cs-friends");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Set<String> mSeen = new HashSet<>();
    private final List<Note> mInbox = new ArrayList<>();
    private final List<Message> mHistory = new ArrayList<>();
    @NonNull private volatile Snapshot mSnapshot = new Snapshot();
    private volatile String mStatus = "online", mAddress, mWorld, mEngine, mVersion;
    private volatile boolean mRunning, mFast, mLoaded;
    private int mWatchers;
    private long mSince;
    @Nullable private volatile String mLastError;
    private final Runnable mTick = this::tick;

    private FriendsClient() {}

    @NonNull public static FriendsClient get() { return INSTANCE; }

    @NonNull public Snapshot snapshot() { return mSnapshot; }
    @Nullable public String lastError() { return mLastError; }

    /** Starts polling while at least one watcher (page / bell / popup) is attached. */
    public void addListener(@NonNull Listener l) {
        load();
        mListeners.addIfAbsent(l);
        mWatchers++;
        ensureRunning();
    }

    public void removeListener(@NonNull Listener l) {
        if (mListeners.remove(l)) mWatchers = Math.max(0, mWatchers - 1);
        if (mWatchers == 0 && mAddress == null && !mBackground) stopPolling();
    }

    private volatile boolean mBackground;

    /**
     * A listener that keeps a slow poll (every {@link #POLL_SLOW} ms) alive while no page is
     * open, so pinned friends' messages and invites reach the system notifications while the
     * launcher sits in the background or the game is running. Not counted as a watcher.
     */
    public void addBackgroundListener(@NonNull Listener l) {
        load();
        mListeners.addIfAbsent(l);
        mBackground = true;
        ensureRunning();
    }

    public void removeBackgroundListener(@NonNull Listener l) {
        mListeners.remove(l);
        mBackground = false;
        if (mWatchers == 0 && mAddress == null) stopPolling();
    }

    /** Chat open: poll faster. */
    public void setFast(boolean fast) {
        mFast = fast;
        if (fast) { mMain.removeCallbacks(mTick); mMain.post(mTick); }
    }

    public void refreshNow() {
        if (!CsAccount.isSignedIn()) return;
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
    }

    private void ensureRunning() {
        if (mRunning || !CsAccount.isSignedIn()) return;
        mRunning = true;
        mMain.removeCallbacks(mTick);
        mMain.post(mTick);
    }

    private void stopPolling() {
        mRunning = false;
        mMain.removeCallbacks(mTick);
    }

    /** The account changed (sign in / out): drops the snapshot and resyncs. */
    public void onAccountChanged() {
        mSince = 0;
        mSnapshot = new Snapshot();
        synchronized (mHistory) { mHistory.clear(); }
        mLastError = null;
        if (CsAccount.isSignedIn()) { mRunning = false; ensureRunning(); }
        else stopPolling();
        notifySnapshot();
        FriendsNotifier.onAccountChanged();
    }

    // --- presence -------------------------------------------------------------------------

    /** Publishes "hosting" with the public address (game process, e4mc published). */
    public void setHosting(@NonNull String address, @Nullable String world, @NonNull String engine) {
        setHosting(address, world, engine, null);
    }

    /** Same, with the game version hosted (Java: lets guests prepare a cross-version join). */
    public void setHosting(@NonNull String address, @Nullable String world, @NonNull String engine, @Nullable String version) {
        mVersion = version;
        mStatus = "hosting";
        mAddress = address;
        mWorld = world;
        mEngine = engine;
        ensureRunning();
        refreshNow();
    }

    public void clearHosting() {
        boolean was = mAddress != null;
        mStatus = "online";
        mAddress = null;
        mWorld = null;
        mEngine = null;
        mVersion = null;
        if (was) {
            refreshNow();
            if (mWatchers == 0 && !mBackground) mMain.postDelayed(this::stopPolling, 4000);
        }
    }

    /** Process exit: one synchronous sync that clears the address (max ~3 s). */
    public void clearHostingBlocking() {
        if (mAddress == null) return;
        mAddress = null;
        mWorld = null;
        mStatus = "online";
        String token = CsAccount.token();
        if (token == null) return;
        Thread t = new Thread(() -> {
            try { FriendsApi.call("cs_sync", syncArgs(token)); } catch (IOException ignored) {}
        });
        t.start();
        try { t.join(3000); } catch (InterruptedException ignored) {}
    }

    private JSONObject syncArgs(String token) {
        JSONObject a = new JSONObject();
        try {
            a.put("p_token", token);
            a.put("p_status", mStatus);
            a.put("p_address", mAddress == null ? JSONObject.NULL : mAddress);
            a.put("p_world", mWorld == null ? JSONObject.NULL : mWorld);
            a.put("p_engine", mEngine == null ? "launcher" : mEngine);
            a.put("p_since", mSince);
            a.put("p_version", mVersion == null ? JSONObject.NULL : mVersion);
        } catch (JSONException ignored) {}
        return a;
    }

    // --- sync loop -------------------------------------------------------------------------

    private void tick() {
        if (!mRunning) return;
        final String token = CsAccount.token();
        if (token == null) { stopPolling(); return; }
        mIo.execute(() -> {
            Snapshot snap = null;
            String error = null;
            try {
                JSONObject r = FriendsApi.call("cs_sync", syncArgs(token));
                snap = parse(r);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final Snapshot fs = snap;
            final String fe = error;
            mMain.post(() -> {
                if (fs != null) {
                    mLastError = null;
                    apply(fs);
                } else {
                    mLastError = fe;
                    for (Listener l : mListeners) l.onFriendsError(fe == null ? "Offline" : fe);
                }
                if (mRunning) {
                    long delay = fs == null ? POLL_SLOW : (mFast ? POLL_FAST : (mWatchers > 0 ? POLL_NORMAL : POLL_SLOW));
                    mMain.removeCallbacks(mTick);
                    mMain.postDelayed(mTick, delay);
                }
            });
        });
    }

    private Snapshot parse(JSONObject r) {
        Snapshot s = new Snapshot();
        JSONObject me = r.optJSONObject("me");
        if (me != null) CsAccount.updateMe(me);
        String myId = CsAccount.id();
        JSONArray a = r.optJSONArray("friends");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Friend f = new Friend();
            f.id = o.optString("id"); f.name = o.optString("name"); f.code = o.optString("code");
            f.status = o.optString("status", "offline");
            f.address = o.isNull("address") ? null : o.optString("address", null);
            f.world = o.isNull("world") ? null : o.optString("world", null);
            f.engine = o.isNull("engine") ? null : o.optString("engine", null);
            f.version = o.isNull("version") ? null : o.optString("version", null);
            s.friends.add(f);
        }
        a = r.optJSONArray("requests");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Request q = new Request();
            q.id = o.optString("id"); q.name = o.optString("name"); q.code = o.optString("code");
            q.incoming = o.optBoolean("incoming"); q.at = when(o.opt("at"));
            s.requests.add(q);
        }
        a = r.optJSONArray("invites");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Invite v = new Invite();
            v.id = o.optString("id"); v.from = o.optString("from"); v.fromId = o.optString("from_id");
            v.address = o.optString("address"); v.world = o.isNull("world") ? null : o.optString("world", null);
            v.engine = o.optString("engine", "java"); v.at = when(o.opt("at"));
            v.version = o.isNull("version") ? null : o.optString("version", null);
            s.invites.add(v);
        }
        a = r.optJSONArray("messages");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Message m = new Message();
            m.id = o.optString("id"); m.from = o.optString("from"); m.to = o.optString("to");
            m.body = o.optString("body"); m.at = when(o.opt("at")); m.mine = m.from.equals(myId);
            m.image = o.isNull("image") ? null : o.optString("image", null);
            m.kind = o.isNull("kind") ? null : o.optString("kind", null);
            JSONObject meta = o.optJSONObject("meta");
            if (meta != null) {
                m.inviteAddress = meta.isNull("address") ? null : meta.optString("address", null);
                m.inviteWorld = meta.isNull("world") ? null : meta.optString("world", null);
                m.inviteEngine = meta.isNull("engine") ? null : meta.optString("engine", "java");
                m.inviteVersion = meta.isNull("version") ? null : meta.optString("version", null);
            }
            if (!o.isNull("reply_to")) {
                m.replyTo = String.valueOf(o.optLong("reply_to"));
                m.replyFrom = o.isNull("reply_from") ? null : o.optString("reply_from", null);
                m.replyBody = o.isNull("reply_body") ? null : o.optString("reply_body", null);
            }
            s.messages.add(m);
        }
        s.now = when(r.opt("now"));
        return s;
    }

    private void apply(Snapshot s) {
        Snapshot merged = new Snapshot();
        merged.friends.addAll(s.friends);
        merged.requests.addAll(s.requests);
        merged.invites.addAll(s.invites);
        merged.now = s.now;
        synchronized (mHistory) {
            for (Message m : s.messages) {
                boolean dup = false;
                for (Message h : mHistory) if (h.id.equals(m.id)) { dup = true; break; }
                if (!dup) mHistory.add(m);
                try { long id = Long.parseLong(m.id); if (id > mSince) mSince = id; } catch (NumberFormatException ignored) {}
            }
            while (mHistory.size() > 600) mHistory.remove(0);
            merged.messages.addAll(mHistory);
        }
        mSnapshot = merged;
        // New things since last time → inbox + onNote
        boolean inboxChanged = false;
        for (Invite v : s.invites) {
            String key = "inv:" + v.id;
            if (mSeen.add(key)) {
                Note n = new Note();
                n.kind = Note.INVITE; n.key = key; n.title = v.from;
                n.body = v.world == null || v.world.isEmpty() ? "invited you to play" : "invited you to \"" + v.world + "\"";
                n.fromId = v.fromId; n.address = v.address; n.engine = v.engine; n.world = v.world; n.version = v.version; n.at = v.at;
                push(n); inboxChanged = true;
            }
        }
        for (Request q : s.requests) {
            if (!q.incoming) continue;
            String key = "req:" + q.id;
            if (mSeen.add(key)) {
                Note n = new Note();
                n.kind = Note.REQUEST; n.key = key; n.title = q.name; n.body = "sent you a friend request"; n.fromId = q.id; n.at = q.at;
                push(n); inboxChanged = true;
            }
        }
        for (Message m : s.messages) {
            if (m.mine || m.isInvite()) continue;
            String key = "msg:" + m.id;
            if (mSeen.add(key)) {
                Friend f = merged.friend(m.from);
                Note n = new Note();
                n.kind = Note.MESSAGE; n.key = key; n.title = f == null ? "Friend" : f.name;
                n.body = m.hasText() ? m.body : (m.image != null ? "sent a photo" : ""); n.fromId = m.from; n.at = m.at;
                push(n); inboxChanged = true;
            }
        }
        // Invites that were answered / expired leave the inbox
        Set<String> live = new HashSet<>();
        for (Invite v : s.invites) live.add("inv:" + v.id);
        for (Request q : s.requests) if (q.incoming) live.add("req:" + q.id);
        synchronized (mInbox) {
            for (int i = mInbox.size() - 1; i >= 0; i--) {
                Note n = mInbox.get(i);
                if ((n.kind == Note.INVITE || n.kind == Note.REQUEST) && !live.contains(n.key)) { mInbox.remove(i); inboxChanged = true; }
            }
        }
        if (inboxChanged) save();
        notifySnapshot();
    }

    private void push(Note n) {
        synchronized (mInbox) {
            mInbox.add(0, n);
            while (mInbox.size() > 80) mInbox.remove(mInbox.size() - 1);
        }
        for (Listener l : mListeners) l.onNote(n);
    }

    private void notifySnapshot() {
        Snapshot s = mSnapshot;
        for (Listener l : mListeners) {
            try { l.onFriendsChanged(s); } catch (RuntimeException e) { Log.w(TAG, "listener", e); }
        }
    }

    // --- inbox -------------------------------------------------------------------------------

    @NonNull public List<Note> inbox() {
        synchronized (mInbox) { return new ArrayList<>(mInbox); }
    }

    public int unread() {
        int n = 0;
        synchronized (mInbox) { for (Note x : mInbox) if (!x.read) n++; }
        return n;
    }

    public void markAllRead() {
        synchronized (mInbox) { for (Note x : mInbox) x.read = true; }
        save();
        notifySnapshot();
    }

    public void markRead(String key) {
        synchronized (mInbox) { for (Note x : mInbox) if (x.key.equals(key)) x.read = true; }
        save();
    }

    public void dismiss(String key) {
        synchronized (mInbox) {
            for (int i = mInbox.size() - 1; i >= 0; i--) if (mInbox.get(i).key.equals(key)) mInbox.remove(i);
        }
        save();
        notifySnapshot();
    }

    /** Messages exchanged with {@code friendId}, oldest first. */
    @NonNull public List<Message> conversation(String friendId) {
        List<Message> out = new ArrayList<>();
        synchronized (mHistory) {
            for (Message m : mHistory) if (m.from.equals(friendId) || m.to.equals(friendId)) out.add(m);
        }
        Collections.sort(out, (x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    public int unreadMessagesFrom(String friendId) {
        int n = 0;
        synchronized (mInbox) { for (Note x : mInbox) if (x.kind == Note.MESSAGE && !x.read && friendId.equals(x.fromId)) n++; }
        return n;
    }

    public void markConversationRead(String friendId) {
        synchronized (mInbox) { for (Note x : mInbox) if (x.kind == Note.MESSAGE && friendId.equals(x.fromId)) x.read = true; }
        save();
        notifySnapshot();
    }

    private void load() {
        if (mLoaded) return;
        mLoaded = true;
        try {
            JSONArray seen = new JSONArray(LauncherPreferences.DEFAULT_PREF.getString(K_SEEN, "[]"));
            for (int i = 0; i < seen.length(); i++) mSeen.add(seen.getString(i));
            JSONArray inbox = new JSONArray(LauncherPreferences.DEFAULT_PREF.getString(K_INBOX, "[]"));
            for (int i = 0; i < inbox.length(); i++) {
                JSONObject o = inbox.getJSONObject(i);
                Note n = new Note();
                n.kind = o.optInt("kind"); n.key = o.optString("key"); n.title = o.optString("title"); n.body = o.optString("body");
                n.fromId = o.optString("fromId", null); n.address = o.optString("address", null); n.engine = o.optString("engine", null);
                n.world = o.optString("world", null); n.at = o.optLong("at"); n.read = o.optBoolean("read");
                mInbox.add(n);
            }
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "inbox load", e);
        }
    }

    private void save() {
        try {
            JSONArray seen = new JSONArray();
            List<String> keys;
            synchronized (mInbox) { keys = new ArrayList<>(mSeen); }
            // Keep the seen-set bounded: the newest 400 keys are plenty for de-duplication
            int from = Math.max(0, keys.size() - 400);
            for (int i = from; i < keys.size(); i++) seen.put(keys.get(i));
            JSONArray inbox = new JSONArray();
            synchronized (mInbox) {
                for (Note n : mInbox) {
                    JSONObject o = new JSONObject();
                    o.put("kind", n.kind).put("key", n.key).put("title", n.title).put("body", n.body).put("at", n.at).put("read", n.read);
                    if (n.fromId != null) o.put("fromId", n.fromId);
                    if (n.address != null) o.put("address", n.address);
                    if (n.engine != null) o.put("engine", n.engine);
                    if (n.world != null) o.put("world", n.world);
                    inbox.put(o);
                }
            }
            LauncherPreferences.DEFAULT_PREF.edit().putString(K_SEEN, seen.toString()).putString(K_INBOX, inbox.toString()).apply();
        } catch (JSONException ignored) {}
    }

    // --- actions ------------------------------------------------------------------------------

    public void addFriend(@NonNull String code, @Nullable Result cb) {
        rpc("cs_add_friend", kv("p_code", code.trim().toUpperCase()), cb);
    }

    public void respond(@NonNull String otherId, boolean accept, @Nullable Result cb) {
        JSONObject a = kv("p_other", otherId);
        try { a.put("p_accept", accept); } catch (JSONException ignored) {}
        rpc("cs_respond", a, cb);
    }

    public void removeFriend(@NonNull String otherId, @Nullable Result cb) {
        rpc("cs_remove_friend", kv("p_other", otherId), cb);
    }

    /** Invites one friend (or everyone when {@code toId} is null) to {@code address}. */
    public void invite(@Nullable String toId, @NonNull String address, @Nullable String world, @NonNull String engine, @Nullable Result cb) {
        JSONObject a = new JSONObject();
        try {
            a.put("p_to", toId == null ? JSONObject.NULL : toId);
            a.put("p_address", address);
            a.put("p_world", world == null ? JSONObject.NULL : world);
            a.put("p_engine", engine);
            a.put("p_version", mVersion == null ? JSONObject.NULL : mVersion);
        } catch (JSONException ignored) {}
        rpc("cs_invite", a, cb);
    }

    /** Declines an invite locally: it leaves the inbox; the sender is not notified (same as the engine). */
    public void declineInvite(@NonNull Invite v) {
        dismiss("inv:" + v.id);
        Snapshot s = mSnapshot;
        for (int i = s.invites.size() - 1; i >= 0; i--) if (s.invites.get(i).id.equals(v.id)) s.invites.remove(i);
        mDeclined.add(v.id);
        notifySnapshot();
    }

    private final Set<String> mDeclined = new HashSet<>();

    /** Invites currently open, minus the ones declined on this device. */
    @NonNull public List<Invite> openInvites() {
        List<Invite> out = new ArrayList<>();
        for (Invite v : mSnapshot.invites) if (!mDeclined.contains(v.id)) out.add(v);
        return out;
    }

    public void send(@NonNull String toId, @NonNull String text, @Nullable Result cb) {
        send(toId, text, null, null, cb);
    }

    /** Text and/or photo URL, optionally quoting {@code replyToId}. */
    public void send(@NonNull String toId, @NonNull String text, @Nullable String replyToId, @Nullable String imageUrl, @Nullable Result cb) {
        JSONObject a = kv("p_to", toId);
        try {
            a.put("p_text", text);
            if (replyToId != null) { try { a.put("p_reply_to", Long.parseLong(replyToId)); } catch (NumberFormatException ignored) {} }
            if (imageUrl != null) a.put("p_image", imageUrl);
        } catch (JSONException ignored) {}
        rpc("cs_send", a, cb);
    }

    private static JSONObject kv(String k, String v) {
        JSONObject a = new JSONObject();
        try { a.put(k, v); } catch (JSONException ignored) {}
        return a;
    }

    private void rpc(String fn, JSONObject args, @Nullable Result cb) {
        String token = CsAccount.token();
        if (token == null) { if (cb != null) cb.done("Sign in to your CS account first"); return; }
        try { args.put("p_token", token); } catch (JSONException ignored) {}
        mIo.execute(() -> {
            String err = null;
            try { FriendsApi.call(fn, args); } catch (IOException e) { err = e.getMessage() == null ? "Network error" : e.getMessage(); }
            final String fe = err;
            mMain.post(() -> {
                if (cb != null) cb.done(fe);
                if (fe == null) refreshNow();
            });
        });
    }

    // --- account ------------------------------------------------------------------------------

    /** Signs in (or registers when {@code register}) on the IO thread; result on the main thread. */
    public void account(boolean register, @NonNull String username, @NonNull String password, @Nullable String display, @NonNull Result cb) {
        mIo.execute(() -> {
            String err = null;
            try {
                JSONObject a = new JSONObject();
                a.put("p_username", username.trim());
                a.put("p_password", password);
                if (register) a.put("p_display", display == null || display.trim().isEmpty() ? username.trim() : display.trim());
                JSONObject r = FriendsApi.call(register ? "cs_account_register" : "cs_account_login", a);
                if (!r.has("token")) throw new IOException("Unexpected reply from the account service");
                CsAccount.store(r);
            } catch (IOException | JSONException e) {
                err = e.getMessage() == null ? "Network error" : e.getMessage();
            }
            final String fe = err;
            mMain.post(() -> {
                if (fe == null) {
                    onAccountChanged();
                    publishLocalSkin();
                }
                cb.done(fe);
            });
        });
    }

    /** Shares the current local account's launcher skin with CS Launcher friends (background). */
    public void publishLocalSkin() {
        final com.craftstudio.cslauncher.authenticator.accounts.Account me = com.craftstudio.cslauncher.authenticator.accounts.Accounts.getCurrent();
        if (me == null || !CsAccount.isSignedIn()) return;
        mIo.execute(() -> {
            try {
                com.craftstudio.cslauncher.skins.SkinLibrary.Entry skin = com.craftstudio.cslauncher.skins.LocalSkinStore.selectedSkin(me);
                com.craftstudio.cslauncher.skins.LocalSkinStore.Cape cape = com.craftstudio.cslauncher.skins.LocalSkinStore.selectedCape(me);
                byte[] skinPng = skin == null ? null : com.craftstudio.cslauncher.skins.SkinLibrary.readBytes(skin);
                byte[] capePng = cape == null ? null : com.craftstudio.cslauncher.skins.LocalSkinStore.readBytes(com.craftstudio.cslauncher.skins.LocalSkinStore.capeFile(cape));
                if (skinPng == null && capePng == null) return;
                RemoteSkins.publish(me.username, skinPng, skin != null && skin.slim, capePng);
            } catch (RuntimeException | java.io.IOException e) {
                Log.w(TAG, "skin publish", e);
            }
        });
    }

    public void changePassword(@NonNull String oldPw, @NonNull String newPw, @NonNull Result cb) {
        String token = CsAccount.token();
        if (token == null) { cb.done("Not signed in"); return; }
        mIo.execute(() -> {
            String err = null;
            try {
                JSONObject a = new JSONObject();
                a.put("p_token", token).put("p_old", oldPw).put("p_new", newPw);
                FriendsApi.call("cs_account_password", a);
            } catch (IOException | JSONException e) {
                err = e.getMessage() == null ? "Network error" : e.getMessage();
            }
            final String fe = err;
            mMain.post(() -> cb.done(fe));
        });
    }

    public void signOut() {
        String token = CsAccount.token();
        if (token != null) {
            // Best effort: tell friends we're offline before dropping the token
            mIo.execute(() -> {
                try {
                    JSONObject a = new JSONObject();
                    a.put("p_token", token).put("p_status", "offline").put("p_address", JSONObject.NULL)
                            .put("p_world", JSONObject.NULL).put("p_engine", "launcher").put("p_since", 0);
                    FriendsApi.call("cs_sync", a);
                } catch (IOException | JSONException ignored) {}
            });
        }
        CsAccount.signOut();
        synchronized (mInbox) { mInbox.clear(); }
        save();
        onAccountChanged();
    }

    private static long when(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v);
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        // ISO-8601 from Postgres: 2026-11-06T08:15:30.123456+00:00
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(s.length() >= 19 ? s.substring(0, 19) : s);
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }
}
