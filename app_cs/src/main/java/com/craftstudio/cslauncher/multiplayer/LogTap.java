package com.craftstudio.cslauncher.multiplayer;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The native logger has a single listener slot. This tap owns that slot and fans the lines out
 * to the log view (when open) and to any background sink, such as the Virtual LAN host session
 * that watches for "Started serving on <port>".
 */
public final class LogTap implements Logger.eventLogListener {
    private static final LogTap INSTANCE = new LogTap();
    private static volatile boolean sInstalled;

    private volatile Logger.eventLogListener mUiListener;
    private final CopyOnWriteArrayList<Logger.eventLogListener> mSinks = new CopyOnWriteArrayList<>();

    private LogTap() {}

    /** Call once the native logger library is loaded (game activity); harmless elsewhere */
    public static void install() { ensureInstalled(); }

    private static void ensureInstalled() {
        if (sInstalled) return;
        synchronized (LogTap.class) {
            if (sInstalled) return;
            try {
                Logger.setLogListener(INSTANCE);
                sInstalled = true;
            } catch (UnsatisfiedLinkError ignored) {
                // Not the game process: no native logger here, sinks simply never fire
            }
        }
    }

    /** The log view: replaces the previous UI listener, null detaches it */
    public static void setUiListener(@Nullable Logger.eventLogListener listener) {
        INSTANCE.mUiListener = listener;
        if (listener != null) ensureInstalled();
    }

    public static void addSink(Logger.eventLogListener sink) {
        INSTANCE.mSinks.addIfAbsent(sink);
        ensureInstalled();
    }

    public static void removeSink(Logger.eventLogListener sink) {
        INSTANCE.mSinks.remove(sink);
    }

    @Override
    public void onEventLogged(String text) {
        Logger.eventLogListener ui = mUiListener;
        if (ui != null) ui.onEventLogged(text);
        for (Logger.eventLogListener sink : mSinks) {
            try { sink.onEventLogged(text); } catch (Throwable ignored) {}
        }
    }
}
