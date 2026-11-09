package com.craftstudio.cslauncher.ads;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Adsterra 320×50 banner in a WebView. Hidden until the ad page has rendered; every navigation
 * the ad triggers (a tap, a window.open) leaves the app and opens in the system browser, the
 * WebView itself only ever shows the banner document.
 */
public class AdsterraBannerView extends FrameLayout {
    public static final String KEY = "a6a8416a29fd89911e916f6e21ba20f1";
    private static final String SCRIPT_HOST = "https://bauval.org/";
    private static final int WIDTH = 320, HEIGHT = 50;

    private static final String HTML = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=320, initial-scale=1\">"
            + "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;width:320px;height:50px}</style></head>"
            + "<body><script>atOptions={'key':'" + KEY + "','format':'iframe','height':" + HEIGHT + ",'width':" + WIDTH + ",'params':{}};</script>"
            + "<script src=\"" + SCRIPT_HOST + "22/" + KEY + "\"></script></body></html>";

    @Nullable private WebView mWeb;

    public AdsterraBannerView(@NonNull Context context) { this(context, null); }
    public AdsterraBannerView(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public AdsterraBannerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setVisibility(GONE);
        if (isInEditMode()) return;
        try {
            setUp(context);
        } catch (Throwable t) {
            // No WebView on this device (or it is being updated): the launcher simply has no banner
            android.util.Log.w("AdsterraBanner", "webview unavailable", t);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUp(Context context) {
        WebView web = new WebView(context);
        float d = getResources().getDisplayMetrics().density;
        LayoutParams lp = new LayoutParams(Math.round(WIDTH * d), Math.round(HEIGHT * d));
        lp.gravity = android.view.Gravity.CENTER;
        web.setLayoutParams(lp);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Anything after the banner document itself is the ad's click-through
                return openOutside(request.getUrl());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openOutside(Uri.parse(url));
            }
            @Override public void onPageFinished(WebView view, String url) {
                setVisibility(VISIBLE);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open: a throw-away WebView receives the target and we hand it to the browser
                WebView probe = new WebView(view.getContext());
                probe.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        openOutside(r.getUrl());
                        v.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }
        });
        addView(web);
        mWeb = web;
        web.loadDataWithBaseURL(SCRIPT_HOST, HTML, "text/html", "utf-8", null);
    }

    private boolean openOutside(@Nullable Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if (scheme == null) return true;
        if (("about".equals(scheme) || "data".equals(scheme))) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    public void onResume() { if (mWeb != null) mWeb.onResume(); }
    public void onPause() { if (mWeb != null) mWeb.onPause(); }

    public void destroy() {
        if (mWeb == null) return;
        removeView(mWeb);
        mWeb.destroy();
        mWeb = null;
    }

    /** Reloads the banner (a fresh impression) — the host calls it when it comes back to the front. */
    public void refresh() {
        if (mWeb != null) mWeb.loadDataWithBaseURL(SCRIPT_HOST, HTML, "text/html", "utf-8", null);
    }

    @Override public void setVisibility(int visibility) {
        super.setVisibility(mWeb == null && visibility == VISIBLE ? GONE : visibility);
    }

    @Nullable public View webView() { return mWeb; }
}
