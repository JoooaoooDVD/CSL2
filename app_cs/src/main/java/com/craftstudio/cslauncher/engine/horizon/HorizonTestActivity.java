package com.craftstudio.cslauncher.engine.horizon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.io.File;

/**
 * Engineering test screen for the Horizon engine (Settings → Experimental → Horizon engine test).
 * Not the final UI: it exists so the install / account hand-off / launch / CS controls path can be
 * verified on real devices before the Horizon version card and settings pages are designed.
 */
public class HorizonTestActivity extends Activity {
    private static final String[] RENDERERS = {"", HorizonLaunch.RENDERER_VULKAN, HorizonLaunch.RENDERER_VULKAN_COMPAT, HorizonLaunch.RENDERER_OPENGL};
    private static final String[] RENDERER_LABELS = {"Engine default", "Vulkan", "Vulkan (compatibility)", "OpenGL ES"};

    private TextView mStatus, mLog;
    private Button mInstall, mPlay, mPlayOffline, mRenderer;
    private int mRendererIndex = 0;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mProgress = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Tools.DIR_GAME_HOME == null && Tools.checkStorageRoot(this)) {
            Tools.initStorageConstants(this);
            LauncherPreferences.loadPreferences(this);
        }
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101014);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("Horizon engine · test console", 20, 0xFFF2F5FA, true);
        root.addView(title);
        root.addView(text("Minecraft Java " + HorizonEngine.MINECRAFT_VERSION + " · " + HorizonEngine.bundledLabel(this), 12, 0xFF9C9CA4, false));
        mStatus = text("", 14, 0xFFF2F5FA, false);
        mStatus.setPadding(0, pad, 0, pad);
        root.addView(mStatus);

        mInstall = button("Install / verify resources", v -> onInstall());
        mPlay = button("Play (current account)", v -> onPlay(false));
        mPlayOffline = button("Play offline (local name)", v -> onPlay(true));
        mRenderer = button("Renderer: " + RENDERER_LABELS[0], v -> {
            mRendererIndex = (mRendererIndex + 1) % RENDERERS.length;
            mRenderer.setText("Renderer: " + RENDERER_LABELS[mRendererIndex]);
        });
        Button diag = button("Diagnostics / last exit", v -> showDiagnostics());
        Button cancel = button("Cancel install", v -> HorizonInstallService.cancel(this));
        for (Button b : new Button[]{mInstall, cancel, mRenderer, mPlay, mPlayOffline, diag}) root.addView(b);

        mLog = text("", 11, 0xFF9C9CA4, false);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setPadding(0, pad, 0, pad);
        root.addView(mLog);
        setContentView(scroll);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFF2F5FA);
        b.setBackgroundColor(0xFF16161A);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(HorizonInstallService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mProgress, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(mProgress, filter);
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        try { unregisterReceiver(mProgress); } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        boolean bundled = HorizonEngine.isBundled(this);
        boolean supported = HorizonEngine.isSupported(this);
        sb.append("Engine in APK: ").append(bundled ? "yes" : "no").append('\n');
        sb.append("Device supported: ").append(supported ? "yes" : "no")
                .append(" (API ").append(Build.VERSION.SDK_INT).append(", ")
                .append(HorizonEngine.is64Bit() ? "64-bit" : "32-bit").append(")\n");
        boolean installed = supported && HorizonEngine.isInstalled(this);
        sb.append("Resources ").append(HorizonEngine.MINECRAFT_VERSION).append(": ").append(installed ? "ready" : "not installed").append('\n');
        HorizonInstallState st = HorizonInstallState.get(this);
        switch (st.status) {
            case HorizonInstallState.STATUS_RUNNING:
                sb.append("Installing: ").append(st.stage);
                if (st.total > 0) sb.append(" · ").append(st.percent()).append("% (").append(st.done).append('/').append(st.total).append(')');
                sb.append('\n');
                break;
            case HorizonInstallState.STATUS_FAILED:
                sb.append("Install failed: ").append(st.error).append('\n');
                break;
            case HorizonInstallState.STATUS_DONE:
                sb.append("Last install: done\n");
                break;
            case HorizonInstallState.STATUS_CANCELLED:
                sb.append("Last install: cancelled\n");
                break;
        }
        Account account = Accounts.getCurrent();
        sb.append("Account: ").append(account == null ? "none" : account.username + (account.isMicrosoft ? " (Microsoft" + (HorizonLaunch.hasProfile(account) ? ", profile ok)" : ", no profile)") : " (local)"));
        mStatus.setText(sb);
        mInstall.setEnabled(supported && !st.isRunning());
        mPlay.setEnabled(installed && account != null);
        mPlayOffline.setEnabled(installed);

        File exit = new File(HorizonEngine.home(), HorizonGameActivity.EXIT_FILE);
        File error = new File(HorizonEngine.home(), HorizonGameActivity.ERROR_FILE);
        StringBuilder log = new StringBuilder();
        try {
            if (exit.isFile()) log.append("cs-exit.json: ").append(Tools.read(exit)).append('\n');
            if (error.isFile()) log.append("android-error.txt: ").append(Tools.read(error)).append('\n');
        } catch (Exception e) {
            log.append(e).append('\n');
        }
        mLog.setText(log);
    }

    private void onInstall() {
        if (!HorizonEngine.isSupported(this)) {
            Toast.makeText(this, getString(com.craftstudio.cslauncher.R.string.horizon_not_available), Toast.LENGTH_LONG).show();
            return;
        }
        HorizonInstallState.clear(this);
        HorizonInstallService.start(this);
        Toast.makeText(this, "Install started (see notification)", Toast.LENGTH_SHORT).show();
    }

    private void onPlay(boolean offline) {
        try {
            Account account = offline ? null : Accounts.getCurrent();
            HorizonLaunch launch = new HorizonLaunch("horizon-test");
            launch.renderer = RENDERERS[mRendererIndex];
            launch.controlsPath = LauncherPreferences.PREF_DEFAULTCTRL_PATH;
            Intent intent;
            if (offline) {
                Account local = Accounts.getCurrent();
                intent = launch.intent(this, null);
                String name = local == null || TextUtils.isEmpty(local.username) ? "Player" : local.username;
                // Offline name without a session: rebuild the request with offline_name only.
                org.json.JSONObject json = new org.json.JSONObject(intent.getStringExtra(HorizonLaunch.EXTRA));
                json.remove("session_file");
                json.put("offline_name", name);
                intent.putExtra(HorizonLaunch.EXTRA, json.toString());
            } else {
                intent = launch.intent(this, account);
            }
            startActivity(intent);
        } catch (Exception e) {
            Tools.showError(this, e);
        }
    }

    private void showDiagnostics() {
        String summary = HorizonNative.load() ? HorizonNative.nativeDiagnosticsSummary(HorizonEngine.home().getAbsolutePath()) : null;
        String version = HorizonNative.load() ? HorizonNative.nativeVersion() : "library not loaded";
        new AlertDialog.Builder(this)
                .setTitle("Horizon diagnostics")
                .setMessage("Engine: " + version + "\n\n" + (summary == null || summary.isEmpty() ? "No previous session summary." : summary))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
