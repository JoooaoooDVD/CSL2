package com.craftstudio.cslauncher.e4mc;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Logger;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.multiplayer.LogTap;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-edition "play with friends" (game process). Watches the game log for the Open-to-LAN line
 * ("Local game hosted on port N" / "Started serving on N"), tunnels that port through e4mc and
 * publishes the public address to the player's CS friends (status "hosting"), exactly like the
 * CS Engine's in-game sharing. Stops when the world closes or the game exits.
 */
public final class E4mcPublisher {
    private static final String TAG = "E4mcPublisher";
    private static final E4mcPublisher INSTANCE = new E4mcPublisher();
    private static final Pattern PORT = Pattern.compile("(?:Local game hosted on port|Started serving on)\\s+(\\d{2,5})");
    private static final Pattern LEVEL = Pattern.compile("Preparing level \"(.+?)\"");

    public enum Phase { IDLE, STARTING, PUBLISHED, FAILED }

    public interface Listener { void onE4mcChanged(); }

    /** A guest the relay turned away (name, reason): shown as a toast/notice by whoever listens. */
    public interface RefusalListener { void onGuestRefused(@NonNull String name, @NonNull String reason); }
    private final CopyOnWriteArrayList<RefusalListener> mRefusals = new CopyOnWriteArrayList<>();
    public void addRefusalListener(RefusalListener l) { mRefusals.addIfAbsent(l); }
    public void removeRefusalListener(RefusalListener l) { mRefusals.remove(l); }
    /** The last refusal lines ("name — reason"), newest first, for the sharing card. */
    private final java.util.ArrayDeque<String> mRecentRefusals = new java.util.ArrayDeque<>();
    @NonNull public java.util.List<String> recentRefusals() {
        synchronized (mRecentRefusals) { return new java.util.ArrayList<>(mRecentRefusals); }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private volatile long mHandle;
    private volatile Phase mPhase = Phase.IDLE;
    @Nullable private volatile String mAddress, mProblem, mWorld;
    private volatile int mPort, mGuests;
    private boolean mInstalled, mEnabled = true;
    private final Logger.eventLogListener mSink = this::onLogLine;
    private final Runnable mPoll = this::poll;

    private E4mcPublisher() {}

    @NonNull public static E4mcPublisher get() { return INSTANCE; }

    /** Call once in the game process after the native logger exists. */
    public void install() {
        synchronized (this) {
            if (mInstalled) return;
            mInstalled = true;
        }
        LogTap.addSink(mSink);
    }

    public void addListener(Listener l) { mListeners.addIfAbsent(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }

    public Phase phase() { return mPhase; }
    @Nullable public String address() { return mAddress; }
    @Nullable public String problem() { return mProblem; }
    public int port() { return mPort; }
    public int guests() { return mGuests; }
    public boolean isPublished() { return mPhase == Phase.PUBLISHED && mAddress != null; }
    public boolean isActive() { return mHandle != 0; }
    /** When false the LAN line is ignored (player turned sharing off for this session). */
    public boolean isEnabled() { return mEnabled; }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) stop();
        else if (mPort > 0 && mHandle == 0) start(mPort);
        notifyChanged();
    }

    /** Plain game version of the running profile ("1.21.1"), or null when unknown. */
    @Nullable
    public static String hostedVersion() {
        try {
            com.craftstudio.cslauncher.instances.Instance i = com.craftstudio.cslauncher.instances.Instances.loadSelectedInstance();
            if (i == null || i.versionId == null) return null;
            String v = com.craftstudio.cslauncher.fragments.InstanceVersionResolver.shortVersion(i.versionId);
            return v == null || v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private void onLogLine(String text) {
        if (text == null) return;
        Matcher lv = LEVEL.matcher(text);
        if (lv.find()) { mWorld = lv.group(1); return; }
        Matcher m = PORT.matcher(text);
        if (m.find()) {
            // Chat echo of the same message carries "[CHAT]"; the first (plain) line wins and a
            // repeated line for the same port is a no-op.
            int port;
            try { port = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return; }
            if (port <= 0 || port > 65535) return;
            if (port == mPort && mHandle != 0) return;
            mPort = port;
            if (mEnabled) mMain.post(() -> start(port));
            return;
        }
        if (mHandle != 0 && (text.contains("Stopping server") || text.contains("Stopping singleplayer server"))) {
            mMain.post(this::stop);
        }
    }

    /** Tunnels {@code port}; safe to call again (restarts). Main thread. */
    public void start(int port) {
        stopInternal(false);
        mPort = port;
        mProblem = null;
        if (!E4mcNative.load()) {
            mPhase = Phase.FAILED;
            mProblem = "This build has no e4mc library";
            notifyChanged();
            return;
        }
        long h = E4mcNative.nativeStart(port, HostSettings.relayOrNull());
        if (h == 0) {
            mPhase = Phase.FAILED;
            mProblem = E4mcNative.nativeError();
            Log.w(TAG, "e4mc start failed: " + mProblem);
            notifyChanged();
            return;
        }
        mHandle = h;
        mPhase = Phase.STARTING;
        HostSettings.clearInvited();
        applyPolicy();
        notifyChanged();
        mMain.removeCallbacks(mPoll);
        mMain.postDelayed(mPoll, 400);
    }

    private void poll() {
        long h = mHandle;
        if (h == 0) return;
        String domain = E4mcNative.nativeDomain(h);
        String problem = E4mcNative.nativeProblem(h);
        int guests = E4mcNative.nativeGuests(h);
        boolean changed = false;
        if (domain != null && !domain.equals(mAddress)) {
            mAddress = domain;
            mPhase = Phase.PUBLISHED;
            changed = true;
            Log.i(TAG, "published " + domain + " -> 127.0.0.1:" + mPort);
            FriendsClient.get().setHosting(domain, mWorld, "java", hostedVersion());
        }
        if (problem != null && !problem.equals(mProblem)) {
            mProblem = problem;
            if (mAddress == null) mPhase = Phase.FAILED;
            changed = true;
        }
        if (guests != mGuests) { mGuests = guests; changed = true; }
        String news = E4mcNative.nativePoll(h);
        if (news != null) {
            for (String line : news.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String name = line.substring(0, tab), reason = line.substring(tab + 1);
                synchronized (mRecentRefusals) {
                    mRecentRefusals.addFirst(name + " — " + reason);
                    while (mRecentRefusals.size() > 5) mRecentRefusals.removeLast();
                }
                for (RefusalListener l : mRefusals) {
                    try { l.onGuestRefused(name, reason); } catch (RuntimeException e) { Log.w(TAG, "refusal listener", e); }
                }
            }
            changed = true;
        }
        // Friends come and go: "friends only" follows the current list.
        if (HostSettings.access() == HostSettings.ACCESS_FRIENDS) applyPolicy();
        if (changed) notifyChanged();
        mMain.postDelayed(mPoll, mAddress == null ? 500 : 2000);
    }

    /**
     * Hands the relay the current {@link HostSettings}: the guest limit (the host counted out)
     * and the names allowed. Cheap; safe to call whenever a setting or the friends list changes.
     */
    public void applyPolicy() {
        long h = mHandle;
        if (h == 0) return;
        java.util.List<String> names = HostSettings.allowedNames();
        String allowed = null;
        if (names != null) {
            StringBuilder sb = new StringBuilder();
            for (String n : names) { if (sb.length() > 0) sb.append('\n'); sb.append(n); }
            allowed = sb.toString();
        }
        int guests = Math.max(1, HostSettings.maxPlayers() - 1);
        try {
            E4mcNative.nativeSetPolicy(h, guests, allowed, HostSettings.closedReason());
        } catch (Throwable t) {
            Log.w(TAG, "policy", t);
        }
    }

    /** Ends the tunnel and clears the hosting presence. Main thread. */
    public void stop() { stopInternal(true); }

    private void stopInternal(boolean notify) {
        mMain.removeCallbacks(mPoll);
        long h = mHandle;
        mHandle = 0;
        if (h != 0) {
            try { E4mcNative.nativeStop(h); } catch (Throwable t) { Log.w(TAG, "stop", t); }
            FriendsClient.get().clearHosting();
        }
        mAddress = null;
        mGuests = 0;
        mPhase = Phase.IDLE;
        synchronized (mRecentRefusals) { mRecentRefusals.clear(); }
        if (notify) notifyChanged();
    }

    /** Game process is going away: synchronous best-effort shutdown (any thread). */
    public static void shutdownForProcessExit() {
        E4mcPublisher p = INSTANCE;
        long h = p.mHandle;
        p.mHandle = 0;
        if (h != 0) {
            try { E4mcNative.nativeStop(h); } catch (Throwable ignored) {}
            FriendsClient.get().clearHostingBlocking();
        }
    }

    private void notifyChanged() {
        for (Listener l : mListeners) {
            try { l.onE4mcChanged(); } catch (RuntimeException e) { Log.w(TAG, "listener", e); }
        }
    }
}
