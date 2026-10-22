package com.craftstudio.cslauncher.discord;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;

/**
 * Dedicated manager for official Discord OAuth2 Account Linking using Social SDK Client::Authorize().
 */
public final class DiscordAuthManager {

    private static final String TAG = "DiscordAuthManager";

    public interface AuthCallback {
        void onSuccess(String username, String userId, @Nullable String avatarUrl);
        void onError(String error);
    }

    private final Context mContext;
    private final Handler mMainHandler;

    public DiscordAuthManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Starts official Discord Social SDK Client::Authorize() OAuth authorization flow.
     */
    public void startAuthorization(@NonNull Activity activity, @Nullable AuthCallback callback) {
        try {
            com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity(activity);
        } catch (Throwable ignored) {}

        long clientId = Long.parseLong(DiscordPresenceManager.APPLICATION_ID);

        // Timeout safeguard: if authorization hangs or gets cancelled without response
        final boolean[] finished = new boolean[]{false};
        mMainHandler.postDelayed(() -> {
            synchronized (finished) {
                if (!finished[0]) {
                    finished[0] = true;
                    Log.w(TAG, "Discord authorization timed out after 60s");
                    if (callback != null) callback.onError("Authorization timed out. Please try again.");
                }
            }
        }, 60000);

        DiscordSdkBridge.authorize(clientId, "identify", new DiscordSdkBridge.NativeAuthCallback() {
            @Override
            public void onSuccess(String code, String redirectUri, String verifier) {
                Log.i(TAG, "Official Client::Authorize completed with code: " + (code != null ? "[REDACTED]" : "null")
                        + ", redirectUri: " + redirectUri);

                // Offload to worker executor so ticker thread is never blocked or re-entered
                CSLauncherApplication.sExecutorService.execute(() -> {
                    String effectiveRedirectUri = (redirectUri != null && !redirectUri.trim().isEmpty())
                            ? redirectUri.trim()
                            : ("discord-" + clientId + ":/authorize/callback");

                    // If code & verifier available, exchange via official GetToken API first
                    if (code != null && !code.isEmpty() && verifier != null && !verifier.isEmpty()) {
                        DiscordSdkBridge.getToken(clientId, code, verifier, effectiveRedirectUri, new DiscordSdkBridge.TokenCallback() {
                            @Override
                            public void onSuccess(String accessToken, String refreshToken) {
                                Log.i(TAG, "PKCE token exchange succeeded. Fetching user info via HTTP API...");
                                fetchUserProfileHttp(accessToken, (uId, uName, dName, avUrl) -> {
                                    handleProfileSuccess(uId, uName, dName, avUrl, accessToken, finished, callback);
                                }, err -> {
                                    Log.w(TAG, "HTTP user profile fetch failed: " + err + ". Trying connected user fallback...");
                                    tryConnectedUserFallback(clientId, accessToken, finished, callback);
                                });
                            }

                            @Override
                            public void onError(String tokenErr) {
                                Log.w(TAG, "PKCE token exchange failed: " + tokenErr + ". Trying connected user fallback...");
                                tryConnectedUserFallback(clientId, null, finished, callback);
                            }
                        });
                    } else {
                        tryConnectedUserFallback(clientId, null, finished, callback);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Official Client::Authorize error: " + error);
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(error);
                });
            }
        });
    }

    private void tryConnectedUserFallback(
            long clientId,
            @Nullable String accessToken,
            boolean[] finished,
            @Nullable AuthCallback callback) {
        DiscordSdkBridge.fetchConnectedUser(clientId, new DiscordSdkBridge.ConnectedUserCallback() {
            @Override
            public void onSuccess(String userId, String username, String displayName, String avatarUrl) {
                handleProfileSuccess(userId, username, displayName, avatarUrl, accessToken, finished, callback);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "fetchConnectedUser returned: " + error);
                synchronized (finished) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError("Failed to retrieve Discord profile: " + error);
                });
            }
        });
    }

    private void handleProfileSuccess(
            @Nullable String userId,
            @Nullable String username,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable String accessToken,
            boolean[] finished,
            @Nullable AuthCallback callback) {

        synchronized (finished) {
            if (finished[0]) return;
            finished[0] = true;
        }

        String finalName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim()
                : ((username != null && !username.trim().isEmpty()) ? username.trim() : "Discord User");
        String finalId = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "0";

        DiscordAccountStorage.saveAccount(mContext, finalId, username, finalName, avatarUrl, accessToken);

        mMainHandler.post(() -> {
            if (callback != null) callback.onSuccess(finalName, finalId, avatarUrl);
            DiscordPresenceManager.getInstance(mContext).refresh();
        });
    }

    private interface HttpProfileCallback {
        void onSuccess(String userId, String username, String displayName, @Nullable String avatarUrl);
    }

    private interface HttpErrorCallback {
        void onError(String error);
    }

    private void fetchUserProfileHttp(String accessToken, HttpProfileCallback successCb, HttpErrorCallback errorCb) {
        CSLauncherApplication.sExecutorService.execute(() -> {
            java.io.InputStream is = null;
            try {
                java.net.URL url = new java.net.URL("https://discord.com/api/v10/users/@me");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    is = conn.getInputStream();
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                    String id = obj.optString("id", null);
                    String username = obj.optString("username", "Discord User");
                    String globalName = obj.optString("global_name", username);
                    String avatarHash = obj.optString("avatar", null);
                    String avatarUrl = null;
                    if (id != null && avatarHash != null && !avatarHash.isEmpty() && !"null".equals(avatarHash)) {
                        String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                        avatarUrl = "https://cdn.discordapp.com/avatars/" + id + "/" + avatarHash + "." + ext + "?size=256";
                    }
                    successCb.onSuccess(id, username, globalName, avatarUrl);
                } else {
                    errorCb.onError("HTTP " + code);
                }
            } catch (Throwable t) {
                errorCb.onError(t.getMessage());
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /**
     * Refreshes the connected Discord profile information in background.
     */
    public void refreshConnectedProfile(@Nullable Runnable onDone) {
        String token = DiscordAccountStorage.getAccessToken(mContext);
        if (token != null && !token.trim().isEmpty()) {
            fetchUserProfileHttp(token, (uId, uName, dName, avUrl) -> {
                DiscordAccountStorage.saveAccount(mContext, uId, uName, dName, avUrl, token);
                if (onDone != null) mMainHandler.post(onDone);
            }, err -> {
                Log.w(TAG, "Background HTTP profile refresh failed: " + err);
                if (onDone != null) mMainHandler.post(onDone);
            });
            return;
        }

        if (onDone != null) mMainHandler.post(onDone);
    }

    /**
     * Unlinks the Discord account from persistent storage and active profile without disabling Rich Presence.
     */
    public void unlinkCurrentAccount() {
        try {
            DiscordAccountStorage.clearAccount(mContext);
            // Rich Presence remains active via unauthenticated Android RPC
            DiscordPresenceManager.getInstance(mContext).refresh();
        } catch (Throwable t) {
            Log.w(TAG, "Error unlinking Discord account", t);
        }
    }
}
