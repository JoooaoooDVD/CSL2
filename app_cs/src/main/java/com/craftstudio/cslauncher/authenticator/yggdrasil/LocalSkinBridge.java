package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.AuthType;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.skins.LocalSkinStore;
import com.craftstudio.cslauncher.skins.SkinLibrary;

import java.io.File;
import java.util.List;

/**
 * Glue between the game launch and {@link LocalYggdrasilServer}: for a local account with a skin
 * or cape picked in the launcher, starts the server and adds the authlib-injector arguments.
 * <p>
 * Must run in the game process, before the game arguments are built ({@link #prepare}), because
 * the player then gets the offline-mode UUID of its name: that is the UUID offline servers use
 * too, so the skin also shows on the player while playing on offline-mode servers.
 */
public final class LocalSkinBridge {
    private static final String TAG = "LocalSkinBridge";
    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    @Nullable private static LocalYggdrasilServer sServer;

    private LocalSkinBridge() {}

    /** Starts the local skin server when it is needed. Never throws: the game starts either way. */
    public static void prepare(@NonNull Account account) {
        sServer = null;
        if (account.authType != AuthType.LOCAL || !LauncherPreferences.PREF_LOCAL_SKINS) return;
        try {
            SkinLibrary.Entry skin = LocalSkinStore.selectedSkin(account);
            LocalSkinStore.Cape cape = LocalSkinStore.selectedCape(account);
            if (skin == null && cape == null) return; // nothing custom: launch exactly like before

            byte[] skinPng = skin == null ? null : SkinLibrary.readBytes(skin);
            byte[] capePng = cape == null ? null : LocalSkinStore.readBytes(LocalSkinStore.capeFile(cape));
            if (account.profileId == null || NIL_UUID.equals(account.profileId)) {
                // In memory only (the game process never saves the account)
                account.profileId = LocalYggdrasilServer.offlineUuid(account.username);
            }
            sServer = LocalYggdrasilServer.start(new File(Tools.DIR_DATA, "yggdrasil"), account.username,
                    account.profileId, skinPng, skin != null && skin.slim, capePng);
        } catch (Throwable t) {
            Log.w(TAG, "Local skin server not started, the default skin will be used", t);
            sServer = null;
        }
    }

    /** Adds {@code -javaagent:authlib-injector.jar=...} when the server is running */
    public static boolean appendJvmArgs(@NonNull List<String> javaArgs) {
        LocalYggdrasilServer server = sServer;
        if (server == null) return false;
        File injector = new File(Tools.DIR_DATA, "authlib-injector/authlib-injector.jar");
        if (!injector.isFile()) {
            Log.w(TAG, "authlib-injector.jar is missing, skipping the local skin");
            server.stop();
            sServer = null;
            return false;
        }
        javaArgs.add("-javaagent:" + injector.getAbsolutePath() + "=" + server.getRootUrl());
        javaArgs.add("-Dauthlibinjector.yggdrasil.prefetched=" + server.getPrefetchedMetadata());
        // Keep the plain window title (no "- CS Launcher Plus" suffix)
        javaArgs.add("-Dauthlibinjector.noShowServerName");
        return true;
    }
}
