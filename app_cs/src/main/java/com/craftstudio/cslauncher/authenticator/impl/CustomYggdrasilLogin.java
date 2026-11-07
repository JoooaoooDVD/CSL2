package com.craftstudio.cslauncher.authenticator.impl;

import static com.craftstudio.cslauncher.CSLauncherApplication.sExecutorService;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.AuthType;
import com.craftstudio.cslauncher.authenticator.BackgroundLogin;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.authenticator.listener.LoginListener;
import com.craftstudio.cslauncher.authenticator.yggdrasil.YggdrasilClient;
import com.craftstudio.cslauncher.utils.PresentableException;

import java.io.IOException;
import java.util.UUID;

/**
 * Sign-in on any Yggdrasil / authlib-injector server (Ely.by auth server, LittleSkin, Blessing
 * Skin, a friend's own server ...). The {@code code} handed to {@link #createAccount} is the JSON
 * {@code {"server":..., "username":..., "password":...}} written by the login screen; the password
 * is used once and never stored. The account keeps the API root and client token so the token can
 * be refreshed on later launches.
 */
public class CustomYggdrasilLogin implements BackgroundLogin {
    public static final BackgroundLogin.Creator CREATOR = CustomYggdrasilLogin::new;
    private static final String TAG = "CustomYggdrasil";

    private CustomYggdrasilLogin() {}

    @Override
    public void createAccount(@NonNull LoginListener loginListener, String code) {
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, 0);
        sExecutorService.execute(() -> {
            loginListener.setMaxLoginProgress(3);
            try {
                JsonObject request = JsonParser.parseString(code).getAsJsonObject();
                String typedServer = request.get("server").getAsString();
                String username = request.get("username").getAsString();
                String password = request.get("password").getAsString();

                progress(loginListener, 1);
                YggdrasilClient.Server server = YggdrasilClient.resolveApiRoot(typedServer);
                progress(loginListener, 2);
                String clientToken = UUID.randomUUID().toString().replace("-", "");
                YggdrasilClient.Session session = YggdrasilClient.authenticate(server.apiRoot, username, password, clientToken);
                progress(loginListener, 3);

                Account account = Accounts.create(acc -> fill(acc, server, session));
                Tools.runOnUiThread(() -> loginListener.onLoginDone(account));
            } catch (YggdrasilClient.ServerException e) {
                Log.w(TAG, "Server refused the login: " + e.error + " " + e.getMessage());
                String message = e.getMessage() == null ? "" : e.getMessage();
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                boolean noProfile = "NoProfile".equals(e.error) || lower.contains("missing player")
                        || lower.contains("create a player") || lower.contains("no profile") || message.contains("角色");
                PresentableException shown = noProfile
                        ? new PresentableException(e, R.string.cl_no_profile_hint, serverLabel(typedServerOf(code)))
                        : new PresentableException(e, R.string.auth_custom_error_server, message);
                Tools.runOnUiThread(() -> loginListener.onLoginError(shown));
            } catch (Exception e) {
                Log.w(TAG, "Login failed", e);
                Tools.runOnUiThread(() -> loginListener.onLoginError(e));
            }
            ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE);
        });
    }

    private static String typedServerOf(String code) {
        try {
            return JsonParser.parseString(code).getAsJsonObject().get("server").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /** "littleskin.cn" for a typed or resolved address */
    private static String serverLabel(String typed) {
        com.craftstudio.cslauncher.authenticator.yggdrasil.AuthServers.Entry saved =
                com.craftstudio.cslauncher.authenticator.yggdrasil.AuthServers.find(typed);
        if (saved != null) return saved.name;
        String s = typed.replaceFirst("^authlib-injector:", "").replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    @Override
    public void refreshAccount(@NonNull LoginListener loginListener, Account account) {
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, 0);
        sExecutorService.execute(() -> {
            loginListener.setMaxLoginProgress(2);
            try {
                String root = account.authServer;
                if (root == null || root.isEmpty()) throw new IOException("This account has no server saved; remove it and sign in again");
                String clientToken = account.clientToken == null ? UUID.randomUUID().toString().replace("-", "") : account.clientToken;
                progress(loginListener, 1);
                if (!YggdrasilClient.validate(root, account.accessToken, clientToken)) {
                    YggdrasilClient.Session session = YggdrasilClient.refresh(root, account.accessToken, clientToken);
                    account.accessToken = session.accessToken;
                    account.clientToken = session.clientToken;
                    account.profileId = YggdrasilClient.hyphenate(session.profileId);
                    account.username = session.profileName;
                }
                progress(loginListener, 2);
                account.expiresAt = System.currentTimeMillis() + 12L * 60 * 60 * 1000;
                account.updateSkinFace();
                account.save();
                Tools.runOnUiThread(() -> loginListener.onLoginDone(account));
            } catch (YggdrasilClient.ServerException e) {
                PresentableException shown = new PresentableException(e, R.string.auth_custom_error_server, e.getMessage());
                Tools.runOnUiThread(() -> loginListener.onLoginError(shown));
            } catch (Exception e) {
                Log.w(TAG, "Refresh failed", e);
                Tools.runOnUiThread(() -> loginListener.onLoginError(e));
            }
            ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE);
        });
    }

    private static void fill(@NonNull Account acc, @NonNull YggdrasilClient.Server server, @NonNull YggdrasilClient.Session session) {
        acc.authType = AuthType.CUSTOM;
        acc.authServer = server.apiRoot;
        acc.authServerName = server.name;
        acc.clientToken = session.clientToken;
        acc.accessToken = session.accessToken;
        acc.refreshToken = "0";
        acc.username = session.profileName;
        acc.profileId = YggdrasilClient.hyphenate(session.profileId);
        acc.xuid = null;
        // Yggdrasil tokens have no fixed lifetime; validate() decides at launch, this just means "check"
        acc.expiresAt = System.currentTimeMillis() + 12L * 60 * 60 * 1000;
        acc.updateSkinFace();
    }

    private static void progress(@NonNull LoginListener listener, int step) {
        Tools.runOnUiThread(() -> listener.onLoginProgress(step));
    }
}
