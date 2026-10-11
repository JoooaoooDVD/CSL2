package com.craftstudio.cslauncher.discord;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.BaseActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Official Discord login page in a WebView. After the user logs in (password, 2FA, QR or
 * captcha are all handled by Discord itself), the session token is read once, verified,
 * stored privately ({@link DiscordAccount}) and the web session is wiped from the WebView.
 * The token is only ever sent to discord.com.
 */
public class DiscordLoginActivity extends BaseActivity {
    private static final String TAG = "DiscordLogin";
    private static final String LOGIN_URL = "https://discord.com/login";
    private static final long POLL_MS = 1200L;
    /** Reads the token through a fresh iframe: discord.com removes window.localStorage from the page */
    private static final String READ_TOKEN_JS = "(function(){try{"
            + "var f=document.createElement('iframe');f.style.display='none';"
            + "(document.body||document.documentElement).appendChild(f);"
            + "var t=f.contentWindow.localStorage.getItem('token');f.remove();return t;"
            + "}catch(e){return null;}})()";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WebView mWebView;
    private TextView mStatus;
    private ProgressBar mProgress;
    private volatile boolean mVerifying;
    private volatile boolean mDone;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (mDone || mWebView == null) return;
            if (!mVerifying) {
                mWebView.evaluateJavascript(READ_TOKEN_JS, value -> {
                    String token = unwrapToken(value);
                    if (token != null) onTokenFound(token);
                });
            }
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    /** Not fullscreen: the keyboard must be able to resize the login page */
    @Override
    public boolean setFullscreen() {
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discord_login);
        mStatus = findViewById(R.id.dl_status);
        mProgress = findViewById(R.id.dl_progress);
        View close = findViewById(R.id.dl_close);
        CSAnimationEngine.attachPressFeedback(close);
        close.setOnClickListener(v -> finish());

        mWebView = new WebView(this);
        mWebView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mWebView.setBackgroundColor(0);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        mWebView.setWebViewClient(new LoginClient());
        ((FrameLayout) findViewById(R.id.dl_web_holder)).addView(mWebView);

        // Entrance after the first layout pass (translation entrances before attach can stick)
        View root = findViewById(R.id.dl_root);
        root.post(() -> {
            Motion.riseIn(findViewById(R.id.dl_bar), 0, 10);
            Motion.fadeIn(findViewById(R.id.dl_web_holder), 120, Motion.LONG);
        });

        mWebView.loadUrl(LOGIN_URL);
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    private final class LoginClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            // Stay on https Discord pages; anything else (apps, downloads, other sites) is blocked
            return !"https".equals(uri.getScheme()) || !isDiscordHost(uri.getHost());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!mVerifying) mProgress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mVerifying) mProgress.setVisibility(View.INVISIBLE);
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Fallback: the page's own API calls carry the session token
            Uri uri = request.getUrl();
            if (!mDone && !mVerifying && isDiscordHost(uri.getHost())
                    && uri.getPath() != null && uri.getPath().startsWith("/api/")) {
                Map<String, String> headers = request.getRequestHeaders();
                if (headers != null) {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        if ("authorization".equalsIgnoreCase(header.getKey())) {
                            String token = header.getValue();
                            if (isPlausibleToken(token)) mHandler.post(() -> onTokenFound(token));
                            break;
                        }
                    }
                }
            }
            return null;
        }
    }

    private static boolean isDiscordHost(@Nullable String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("discord.com") || host.endsWith(".discord.com");
    }

    private static boolean isPlausibleToken(@Nullable String token) {
        return token != null && token.length() >= 50 && token.length() <= 200
                && !token.startsWith("Bot ") && !token.startsWith("Bearer ") && !token.contains(" ");
    }

    /** evaluateJavascript gives a JSON value; the stored token is itself a JSON string */
    @Nullable
    private static String unwrapToken(@Nullable String jsResult) {
        try {
            if (jsResult == null || "null".equals(jsResult)) return null;
            JsonElement outer = JsonParser.parseString(jsResult);
            if (!outer.isJsonPrimitive()) return null;
            String stored = outer.getAsString();
            if (stored.startsWith("\"")) stored = JsonParser.parseString(stored).getAsString();
            return isPlausibleToken(stored) ? stored : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void onTokenFound(@NonNull String token) {
        if (mDone || mVerifying) return;
        mVerifying = true;
        mProgress.setVisibility(View.VISIBLE);
        Motion.swapText(mStatus, getString(R.string.discord_login_checking));
        new Thread(() -> {
            JsonObject user = fetchUser(token);
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (user == null) {
                    // Not a working session yet (e.g. still in 2FA): keep waiting
                    mVerifying = false;
                    mProgress.setVisibility(View.INVISIBLE);
                    Motion.swapText(mStatus, getString(R.string.discord_login_subtitle));
                    return;
                }
                mDone = true;
                String name = string(user, "username");
                String display = string(user, "global_name");
                DiscordAccount.save(this, token, string(user, "id"), name, display);
                clearWebSession();
                Toast.makeText(this, getString(R.string.discord_login_done,
                        TextUtils.isEmpty(display) ? name : display), Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        }, "DiscordLogin-verify").start();
    }

    @Nullable
    private static JsonObject fetchUser(@NonNull String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://discord.com/api/v10/users/@me").openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Authorization", token);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                JsonObject user = JsonParser.parseString(IOUtils.toString(in, StandardCharsets.UTF_8)).getAsJsonObject();
                return user.has("id") ? user : null;
            }
        } catch (Exception e) {
            Log.i(TAG, "Token check failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Nullable
    private static String string(@NonNull JsonObject object, @NonNull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private void clearWebSession() {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack() && !mVerifying) {
            mWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mWebView != null) {
            mWebView.stopLoading();
            ((ViewGroup) mWebView.getParent()).removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
