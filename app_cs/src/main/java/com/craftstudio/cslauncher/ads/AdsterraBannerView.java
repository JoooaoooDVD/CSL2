package com.craftstudio.cslauncher.ads;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Adsterra 320×50 banner in a WebView, scaled to the width this view is given (the ad document
 * declares a 320 px viewport, the WebView fits it to its own width). Every navigation the ad
 * triggers (a tap, a window.open) leaves the app for the system browser; the WebView itself only
 * ever shows the banner document. {@link #setListener} tells the host when the ad has rendered.
 */
public class AdsterraBannerView extends FrameLayout {
    private static final String TAG = "AdsterraBanner";
    public static final String KEY = "a6a8416a29fd89911e916f6e21ba20f1";
    private static final String SCRIPT_HOST = "https://bauval.org/";
    public static final int AD_WIDTH = 320, AD_HEIGHT = 50;

    private static final String HTML = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=320\">"
            + "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;width:320px;height:50px}"
            + "iframe{display:block}</style></head>"
            + "<body><script>atOptions={'key':'" + KEY + "','format':'iframe','height':" + AD_HEIGHT + ",'width':" + AD_WIDTH + ",'params':{}};</script>"
            + "<script src=\"" + SCRIPT_HOST + "22/" + KEY + "\"></script></body></html>";

    public interface Listener { void onAdRendered(); }

    @Nullable private WebView mWeb;
    @Nullable private Listener mListener;
    private boolean mRendered;

    public AdsterraBannerView(@NonNull Context context) { this(context, null); }
    public AdsterraBannerView(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public AdsterraBannerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (isInEditMode()) return;
        try {
            prepareProcess(context);
            setUp(context);
        } catch (Throwable t) {
            // No WebView on this device (or it is being updated): there is simply no banner
            Log.w(TAG, "webview unavailable", t);
            mWeb = null;
        }
    }

    /** The game runs in its own process: WebView needs a distinct data directory per process (API 28+). */
    private static boolean sProcessPrepared;
    private static void prepareProcess(Context context) {
        if (sProcessPrepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        sProcessPrepared = true;
        String process = Application.getProcessName();
        String pkg = context.getPackageName();
        if (process == null || process.equals(pkg)) return;
        String suffix = process.startsWith(pkg + ":") ? process.substring(pkg.length() + 1) : process.replace(':', '_');
        try {
            WebView.setDataDirectorySuffix(suffix);
        } catch (IllegalStateException alreadyUsed) {
            Log.w(TAG, "webview already initialised in " + process, alreadyUsed);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUp(Context context) {
        WebView web = new WebView(context);
        web.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
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
        // Fit the 320 px viewport to whatever width the card gives us (that is the "scale")
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
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
                if (mRendered || mWeb == null) return;
                mRendered = true;
                if (mListener != null) mListener.onAdRendered();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open: a throw-away WebView receives the target and we hand it to the browser
                WebView probe = new WebView(view.getContext());
                probe.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        openOutside(r.getUrl());
                        v.post(v::destroy);
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
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme)) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
        if (listener != null && mRendered) listener.onAdRendered();
    }

    public boolean isAvailable() { return mWeb != null; }
    public boolean isRendered() { return mRendered; }

    public void onResume() { if (mWeb != null) mWeb.onResume(); }
    public void onPause() { if (mWeb != null) mWeb.onPause(); }

    public void destroy() {
        mListener = null;
        if (mWeb == null) return;
        WebView w = mWeb;
        mWeb = null;
        removeView(w);
        w.stopLoading();
        w.destroy();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        destroy();
    }
}
