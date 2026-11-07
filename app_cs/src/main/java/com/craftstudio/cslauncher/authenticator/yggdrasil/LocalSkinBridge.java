package com.craftstudio.cslauncher.authenticator.yggdrasil;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Logger;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.AuthType;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.skins.LocalSkinStore;
import com.craftstudio.cslauncher.skins.SkinLibrary;
import com.craftstudio.cslauncher.skins.SkinTextures;

import java.io.ByteArrayOutputStream;
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

    /** Ely.by / custom-server accounts: the real server stays in charge, the launcher only adds a cape */
    public static boolean isUpstreamAccount(@NonNull Account account) {
        return account.authType == AuthType.ELY_BY || account.authType == AuthType.CUSTOM;
    }

    /** The account's authlib-injector API root (Ely.by resolved through its ALI header), or null */
    @Nullable
    public static String upstreamApiRoot(@NonNull Account account) {
        String url = account.injectorUrl();
        if (url == null) return null;
        try {
            return YggdrasilClient.resolveApiRoot(url).apiRoot;
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve the API root of " + url, e);
            return null;
        }
    }

    public static final class UpstreamSkin {
        @Nullable public byte[] png;
        public boolean slim;
        @Nullable public byte[] capePng;
    }

    /** Downloads the skin (and the server's own cape) of an Ely.by / custom account. Blocking. */
    @Nullable
    public static UpstreamSkin fetchUpstreamSkin(@NonNull Account account) {
        String root = upstreamApiRoot(account);
        if (root == null) return null;
        YggdrasilClient.Textures textures = YggdrasilClient.profileTextures(root, account.profileId);
        if (textures == null) return null;
        UpstreamSkin skin = new UpstreamSkin();
        skin.slim = textures.slim;
        if (textures.skinUrl != null) skin.png = YggdrasilClient.download(textures.skinUrl);
        if (textures.capeUrl != null) skin.capePng = YggdrasilClient.download(textures.capeUrl);
        return skin;
    }

    /** Starts the local skin server when it is needed. Never throws: the game starts either way. */
    public static void prepare(@NonNull Account account) {
        sServer = null;
        if (isUpstreamAccount(account)) {
            prepareUpstream(account);
            return;
        }
        if (account.authType != AuthType.LOCAL) return;
        if (!LauncherPreferences.PREF_LOCAL_SKINS) {
            gameLog("[CS Skins] Launcher skins for local accounts are turned off in Settings, using the default skin");
            return;
        }
        try {
            SkinLibrary.Entry skin = LocalSkinStore.selectedSkin(account);
            LocalSkinStore.Cape cape = LocalSkinStore.selectedCape(account);
            if (skin == null && cape == null) {
                gameLog("[CS Skins] No skin or cape picked for " + account.username
                        + ", using the default skin (pick one in the launcher: account, edit skin)");
                // The server still runs: friends need it to join a hosted world (see below)
            }

            byte[] skinPng = skin == null ? null : cleanSkin(SkinLibrary.readBytes(skin));
            byte[] capePng = cape == null ? null : cleanCape(LocalSkinStore.readBytes(LocalSkinStore.capeFile(cape)));
            // CS Friends: share this skin with other CS Launcher players (needs a CS account)
            if (skin != null && skinPng == null) gameLog("[CS Skins] ERROR the skin \"" + skin.name + "\" is not a valid 64x64 or 64x32 PNG, skipped");
            if (cape != null && capePng == null) gameLog("[CS Skins] ERROR the cape \"" + cape.name + "\" is not a valid cape PNG, skipped");

            if (account.profileId == null || NIL_UUID.equals(account.profileId)) {
                // In memory only (the game process never saves the account)
                account.profileId = LocalYggdrasilServer.offlineUuid(account.username);
            }
            // CS Friends: share this skin with other CS Launcher players (needs a CS account).
            // Synchronous here (worker thread) so the public texture URLs are known before the game asks.
            com.craftstudio.cslauncher.friends.RemoteSkins.publish(account.username, account.profileId, skinPng, skinPng != null && skin != null && skin.slim, capePng);
            // The session server always runs for local accounts — even with the default skin — because
            // it is also what lets friends join a hosted world (LAN worlds verify every guest through
            // the host's session server) and what shows their shared skins.
            LocalYggdrasilServer.setEventLog(LocalSkinBridge::gameLog);
            sServer = LocalYggdrasilServer.start(new File(Tools.DIR_DATA, "yggdrasil"), account.username,
                    account.profileId, skinPng, skinPng != null && skin != null && skin.slim, capePng);
            gameLog("[CS Skins] Skin server ready on port " + sServer.getPort() + " for " + account.username
                    + " (" + account.profileId + ")"
                    + " | skin: " + (skinPng == null || skin == null ? "default" : "\"" + skin.name + "\" " + (skin.slim ? "slim" : "classic"))
                    + " | cape: " + (capePng == null || cape == null ? "none" : "\"" + cape.name + "\""));
        } catch (Throwable t) {
            Log.w(TAG, "Local skin server not started, the default skin will be used", t);
            gameLog("[CS Skins] ERROR skin server failed to start, using the default skin: " + t);
            sServer = null;
        }
    }

    /**
     * Ely.by / custom server account with a cape picked in the launcher: the CS skin server runs as a
     * proxy in front of the real server — joins and other players' profiles are forwarded to it, the
     * player's own profile is the server's skin plus the launcher cape. Without a cape picked, the
     * game talks to the real server directly (nothing to add).
     */
    private static void prepareUpstream(@NonNull Account account) {
        try {
            LocalSkinStore.Cape cape = LocalSkinStore.selectedCape(account);
            if (cape == null) return;
            byte[] capePng = cleanCape(LocalSkinStore.readBytes(LocalSkinStore.capeFile(cape)));
            if (capePng == null) {
                gameLog("[CS Skins] ERROR the cape \"" + cape.name + "\" is not a valid cape PNG, skipped");
                return;
            }
            String root = upstreamApiRoot(account);
            if (root == null) {
                gameLog("[CS Skins] ERROR the account's server did not answer, the cape can't be added this time");
                return;
            }
            UpstreamSkin upstream = fetchUpstreamSkin(account);
            byte[] skinPng = upstream == null ? null : cleanSkin(upstream.png);
            boolean slim = upstream != null && upstream.slim;
            com.craftstudio.cslauncher.friends.RemoteSkins.publish(account.username, account.profileId, skinPng, slim, capePng);
            LocalYggdrasilServer.setEventLog(LocalSkinBridge::gameLog);
            sServer = LocalYggdrasilServer.start(new File(Tools.DIR_DATA, "yggdrasil"), account.username,
                    account.profileId, skinPng, slim, capePng, root);
            gameLog("[CS Skins] Skin server ready on port " + sServer.getPort() + " for " + account.username
                    + " (" + account.profileId + ") in front of " + root
                    + " | skin: " + (skinPng == null ? "server default" : "from the server, " + (slim ? "slim" : "classic"))
                    + " | cape: \"" + cape.name + "\"");
        } catch (Throwable t) {
            Log.w(TAG, "Skin proxy not started, the account's server is used directly", t);
            gameLog("[CS Skins] ERROR skin server failed to start, the cape is skipped this time: " + t);
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
            gameLog("[CS Skins] ERROR authlib-injector.jar is missing, the skin can't be shown (reinstall the launcher)");
            server.stop();
            sServer = null;
            return false;
        }
        javaArgs.add("-javaagent:" + injector.getAbsolutePath() + "=" + server.getRootUrl());
        javaArgs.add("-Dauthlibinjector.yggdrasil.prefetched=" + server.getPrefetchedMetadata());
        // Keep the plain window title (no "- CS Launcher Plus" suffix)
        javaArgs.add("-Dauthlibinjector.noShowServerName");
        String version = AuthlibInjectorUpdater.installedVersionName();
        gameLog("[CS Skins] authlib-injector " + (version == null ? "" : version + " ") + "enabled successfully");
        return true;
    }

    /** The skin bytes when they are a usable 64x64 / 64x32 PNG, else null */
    @Nullable
    private static byte[] cleanSkin(@Nullable byte[] png) {
        Bitmap bitmap = decode(png);
        if (bitmap == null || !SkinTextures.isValidSkinSize(bitmap.getWidth(), bitmap.getHeight())) return null;
        bitmap.recycle();
        return png;
    }

    /** Pads old 22x17 OptiFine capes to the 64x32 layout and re-encodes as a plain PNG */
    @Nullable
    private static byte[] cleanCape(@Nullable byte[] png) {
        Bitmap bitmap = decode(png);
        if (bitmap == null || bitmap.getWidth() < 22 || bitmap.getHeight() < 17) return null;
        int scale = Math.max(1, bitmap.getWidth() / 64);
        if (bitmap.getWidth() == 64 * scale && bitmap.getHeight() == 32 * scale) return png; // already right
        return encode(SkinTextures.normalizeCape(bitmap));
    }

    @Nullable
    private static Bitmap decode(@Nullable byte[] png) {
        if (png == null || png.length == 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        return BitmapFactory.decodeByteArray(png, 0, png.length, options);
    }

    @Nullable
    private static byte[] encode(@NonNull Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
        return out.toByteArray();
    }

    /** Writes a line into the game log (latestlog.txt and the in-game log page) */
    private static void gameLog(@NonNull String message) {
        Log.i(TAG, message);
        try {
            Logger.appendToLog(message);
        } catch (Throwable ignored) {
            // Logger not started (or native library missing): logcat only
        }
    }
}
