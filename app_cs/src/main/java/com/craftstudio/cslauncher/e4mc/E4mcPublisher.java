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
        long h = E4mcNative.nativeStart(port, null);
        if (h == 0) {
            mPhase = Phase.FAILED;
            mProblem = E4mcNative.nativeError();
            Log.w(TAG, "e4mc start failed: " + mProblem);
            notifyChanged();
            return;
        }
        mHandle = h;
        mPhase = Phase.STARTING;
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
            FriendsClient.get().setHosting(domain, mWorld, "java");
        }
        if (problem != null && !problem.equals(mProblem)) {
            mProblem = problem;
            if (mAddress == null) mPhase = Phase.FAILED;
            changed = true;
        }
        if (guests != mGuests) { mGuests = guests; changed = true; }
        if (changed) notifyChanged();
        mMain.postDelayed(mPoll, mAddress == null ? 500 : 2000);
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
