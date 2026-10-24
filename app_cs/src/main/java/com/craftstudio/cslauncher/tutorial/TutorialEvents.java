package com.craftstudio.cslauncher.tutorial;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny event bus that connects tutorial steps to real launcher state changes: a dialog opened,
 * a screen resumed, the game started launching... Emitting is a no-op (one list check) while no
 * tutorial is running, so instrumented code paths cost nothing in normal use.
 */
public final class TutorialEvents {
    public interface Listener {
        void onTutorialEvent(@NonNull String event);
    }

    // Events emitted by instrumented launcher code
    public static final String PROFILE_PICKER_OPENED = "profile_picker_opened";
    public static final String PROFILE_PICKER_CLOSED = "profile_picker_closed";
    public static final String APPEARANCE_DIALOG_OPENED = "appearance_dialog_opened";
    public static final String APPEARANCE_DIALOG_CLOSED = "appearance_dialog_closed";
    public static final String VERSION_DIALOG_OPENED = "version_dialog_opened";
    public static final String VERSION_DIALOG_CLOSED = "version_dialog_closed";
    public static final String MANAGE_CHOOSER_OPENED = "manage_chooser_opened";
    public static final String MANAGE_CHOOSER_CLOSED = "manage_chooser_closed";
    public static final String ACCOUNT_MENU_OPENED = "account_menu_opened";
    public static final String GAME_LAUNCHING = "game_launching";
    public static final String PROFILE_SAVED = "profile_saved";
    /** Prefix of the events emitted for every resumed fragment: {@code screen:<simple class name>} */
    public static final String SCREEN_PREFIX = "screen:";

    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private TutorialEvents() {}

    public static void addListener(@NonNull Listener listener) {
        sListeners.addIfAbsent(listener);
    }

    public static void removeListener(@Nullable Listener listener) {
        if (listener != null) sListeners.remove(listener);
    }

    /** Safe from any thread; listeners always run on the main thread */
    public static void emit(@NonNull final String event) {
        if (sListeners.isEmpty()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch(event);
        else sMain.post(() -> dispatch(event));
    }

    /** Screen (fragment) resumed */
    public static void screen(@NonNull Class<?> fragmentClass) {
        emit(SCREEN_PREFIX + fragmentClass.getSimpleName());
    }

    private static void dispatch(@NonNull String event) {
        for (Listener l : sListeners) {
            try {
                l.onTutorialEvent(event);
            } catch (Throwable ignored) {
                // a listener must never break the launcher
            }
        }
    }
}
