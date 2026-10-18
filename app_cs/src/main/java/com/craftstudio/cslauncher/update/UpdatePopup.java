package com.craftstudio.cslauncher.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.MaxHeightScrollView;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.kdt.mcgui.CSPopupDialog;

import java.io.File;
import java.util.Locale;

/**
 * "Update available" popup: installed vs latest build, release notes, Update now with live
 * download progress, verification, then the system installer. Later snoozes for a day, Skip
 * hides this exact build from the automatic check.
 */
public final class UpdatePopup extends CSPopupDialog implements UpdateDownloader.Listener {
    private enum State { AVAILABLE, DOWNLOADING, VERIFYING, READY, NEED_PERMISSION, ERROR }

    private final Activity mActivity;
    private final UpdateInfo mInfo;
    @Nullable private final UpdateInfo.Apk mApk;
    private State mState = State.AVAILABLE;
    @Nullable private UpdateDownloader mDownloader;
    @Nullable private File mReadyApk;
    private String mError = "";

    private TextView mPrimary, mLater, mSkip, mStatus, mPercent, mDetail;
    private View mProgressBox, mBar, mTrack;

    public UpdatePopup(@NonNull Activity activity, @NonNull UpdateInfo info) {
        super(activity);
        mActivity = activity;
        mInfo = info;
        mApk = info.apkNamed(UpdateManager.expectedApkName());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_update);
        View card = findViewById(R.id.upd_card);
        setupPopup(findViewById(R.id.upd_scrim), card, false);

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (460 * d), metrics.widthPixels - (int) (32 * d));
        card.setLayoutParams(params);
        MaxHeightScrollView notesScroll = findViewById(R.id.upd_notes_scroll);
        notesScroll.setMaxHeight(Math.max((int) (90 * d), metrics.heightPixels - (int) (330 * d)));

        ((TextView) findViewById(R.id.upd_current)).setText(UpdateManager.currentLabel());
        ((TextView) findViewById(R.id.upd_new)).setText(mInfo.label());
        TextView newLabel = findViewById(R.id.upd_new_label);
        newLabel.setText(mInfo.prerelease ? R.string.upd_latest_build : R.string.upd_latest);

        TextView meta = findViewById(R.id.upd_meta);
        StringBuilder sb = new StringBuilder();
        if (mInfo.publishedAt > 0) {
            sb.append(getContext().getString(R.string.upd_published_fmt,
                    DateUtils.getRelativeTimeSpanString(mInfo.publishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
        }
        if (mApk != null && mApk.size > 0) {
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append(formatSize(mApk.size));
        }
        if (!TextUtils.isEmpty(mInfo.tag)) {
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append(mInfo.tag);
        }
        meta.setText(sb);
        meta.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);

        TextView notes = findViewById(R.id.upd_notes);
        String body = mInfo.notes;
        if (TextUtils.isEmpty(body)) body = getContext().getString(R.string.upd_no_notes);
        notes.setText(renderMarkdown(body));
        notes.setOnClickListener(v -> openRelease());

        mPrimary = findViewById(R.id.upd_primary);
        mLater = findViewById(R.id.upd_later);
        mSkip = findViewById(R.id.upd_skip);
        mStatus = findViewById(R.id.upd_status);
        mPercent = findViewById(R.id.upd_percent);
        mDetail = findViewById(R.id.upd_detail);
        mProgressBox = findViewById(R.id.upd_progress_box);
        mBar = findViewById(R.id.upd_bar);
        mTrack = findViewById(R.id.upd_track);
        for (View v : new View[]{mPrimary, mLater, mSkip, findViewById(R.id.upd_close)}) CSAnimationEngine.attachPressFeedback(v);

        findViewById(R.id.upd_close).setOnClickListener(v -> onDismissRequested());
        mLater.setOnClickListener(v -> onLater());
        mSkip.setOnClickListener(v -> {
            if (isClosing()) return;
            UpdateManager.skip(getContext(), mInfo.versionCode);
            close(null);
        });
        mPrimary.setOnClickListener(v -> onPrimary());

        if (mApk == null) {
            // Release exists but has no APK for this installation (e.g. release-signed vs debug)
            mState = State.ERROR;
            mError = getContext().getString(R.string.upd_no_asset_fmt, UpdateManager.expectedApkName());
        }
        render();
        CreatePopupMotion.enter(mScrim, mCard);
    }

    // ------------------------------------------------------------ actions

    private void onPrimary() {
        if (isClosing()) return;
        switch (mState) {
            case AVAILABLE:
                startDownload();
                break;
            case DOWNLOADING:
            case VERIFYING:
                if (mDownloader != null) mDownloader.cancel();
                break;
            case READY:
                tryInstall();
                break;
            case NEED_PERMISSION:
                UpdateInstaller.requestInstallPermission(mActivity);
                break;
            case ERROR:
                if (mApk == null) openRelease();
                else startDownload();
                break;
        }
    }

    private void onLater() {
        if (isClosing()) return;
        if (mDownloader != null && (mState == State.DOWNLOADING || mState == State.VERIFYING)) mDownloader.cancel();
        UpdateManager.snooze(getContext());
        close(null);
    }

    @Override
    protected void onDismissRequested() {
        // Back / scrim while working: ignore (the buttons make the choice explicit)
        if (mState == State.DOWNLOADING || mState == State.VERIFYING) {
            CreatePopupMotion.nudge(mCard);
            return;
        }
        onLater();
    }

    private void startDownload() {
        if (mApk == null) return;
        mState = State.DOWNLOADING;
        mError = "";
        render();
        setProgress(0, mApk.size);
        mDownloader = new UpdateDownloader(getContext(), mInfo, mApk, this);
        mDownloader.start();
    }

    private void tryInstall() {
        if (mReadyApk == null) return;
        if (!UpdateInstaller.canInstall(mActivity)) {
            mState = State.NEED_PERMISSION;
            render();
            return;
        }
        try {
            UpdateInstaller.install(mActivity, mReadyApk);
            mStatus.setText(R.string.upd_installer_open);
        } catch (Exception e) {
            mState = State.ERROR;
            mError = getContext().getString(R.string.upd_install_failed_fmt, String.valueOf(e.getMessage()));
            render();
        }
    }

    /** Back from the "install unknown apps" settings page */
    void onPermissionReturned() {
        if (mState != State.NEED_PERMISSION) return;
        if (UpdateInstaller.canInstall(mActivity)) {
            mState = State.READY;
            render();
            tryInstall();
        }
    }

    void onInstallCancelled() {
        if (mState == State.READY) {
            mStatus.setText(R.string.upd_install_cancelled);
            mDetail.setText(R.string.upd_ready_detail);
        }
    }

    void onInstallFailed(String why) {
        mState = State.ERROR;
        mError = getContext().getString(R.string.upd_install_failed_fmt, why);
        render();
    }

    private void openRelease() {
        try {
            getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mInfo.htmlUrl)));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ downloader callbacks

    @Override
    public void onProgress(long done, long total) {
        if (mState != State.DOWNLOADING) return;
        setProgress(done, total);
    }

    @Override
    public void onVerifying() {
        mState = State.VERIFYING;
        render();
        setBar(1f);
        mPercent.setText("");
    }

    @Override
    public void onReady(@NonNull File apk) {
        mReadyApk = apk;
        mState = State.READY;
        render();
        tryInstall();
    }

    @Override
    public void onError(@NonNull String message, boolean cancelled) {
        if (cancelled) {
            mState = State.AVAILABLE;
            mError = "";
        } else {
            mState = State.ERROR;
            mError = message;
        }
        render();
    }

    // ------------------------------------------------------------ rendering

    private void render() {
        boolean working = mState == State.DOWNLOADING || mState == State.VERIFYING;
        mProgressBox.setVisibility(mState == State.AVAILABLE ? View.GONE : View.VISIBLE);
        mSkip.setVisibility(mState == State.AVAILABLE ? View.VISIBLE : View.GONE);
        mLater.setVisibility(working ? View.GONE : View.VISIBLE);
        mTrack.setVisibility(mState == State.ERROR || mState == State.NEED_PERMISSION ? View.GONE : View.VISIBLE);
        mPrimary.setEnabled(true);
        switch (mState) {
            case AVAILABLE:
                mPrimary.setText(R.string.upd_update_now);
                break;
            case DOWNLOADING:
                mPrimary.setText(R.string.pe_cancel);
                mStatus.setText(R.string.upd_downloading);
                mDetail.setText(mApk != null ? mApk.file : "");
                break;
            case VERIFYING:
                mPrimary.setText(R.string.pe_cancel);
                mPrimary.setEnabled(false);
                mStatus.setText(R.string.upd_verifying);
                mDetail.setText(R.string.upd_verifying_detail);
                break;
            case READY:
                mPrimary.setText(R.string.upd_install);
                mStatus.setText(R.string.upd_ready);
                mPercent.setText("100%");
                setBar(1f);
                mDetail.setText(R.string.upd_ready_detail);
                break;
            case NEED_PERMISSION:
                mPrimary.setText(R.string.upd_allow);
                mStatus.setText(R.string.upd_need_permission);
                mPercent.setText("");
                mDetail.setText(R.string.upd_need_permission_detail);
                break;
            case ERROR:
                mPrimary.setText(mApk == null ? R.string.upd_open_github : R.string.upd_retry);
                mStatus.setText(R.string.upd_error_title);
                mPercent.setText("");
                mDetail.setText(mError);
                break;
        }
    }

    private void setProgress(long done, long total) {
        if (total > 0) {
            float f = Math.max(0f, Math.min(1f, done / (float) total));
            setBar(f);
            mPercent.setText(String.format(Locale.US, "%d%%", Math.round(f * 100)));
            mDetail.setText(formatSize(done) + " / " + formatSize(total));
        } else {
            setBar(0.05f);
            mPercent.setText("");
            mDetail.setText(formatSize(done));
        }
    }

    private void setBar(float fraction) {
        int w = mTrack.getWidth();
        if (w <= 0) {
            mTrack.post(() -> setBar(fraction));
            return;
        }
        int inner = w - 2;
        mBar.setClipBounds(new android.graphics.Rect(0, 0, Math.max(0, Math.round(inner * fraction)), mBar.getHeight() > 0 ? mBar.getHeight() : 64));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Tiny markdown subset used by the release notes: headings, bullets, bold, code. */
    private static CharSequence renderMarkdown(String md) {
        StringBuilder html = new StringBuilder();
        for (String raw : md.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) { html.append("<br>"); continue; }
            if (line.startsWith("<!--")) continue;
            if (line.startsWith("### ")) line = "<b>" + esc(line.substring(4)) + "</b>";
            else if (line.startsWith("## ")) line = "<b>" + esc(line.substring(3)) + "</b>";
            else if (line.startsWith("# ")) line = "<b>" + esc(line.substring(2)) + "</b>";
            else if (line.startsWith("- ") || line.startsWith("* ")) line = "&#8226;&nbsp; " + inline(esc(line.substring(2)));
            else line = inline(esc(line));
            html.append(line).append("<br>");
        }
        String out = html.toString().replaceAll("(<br>)+$", "").replaceAll("(<br>){3,}", "<br><br>");
        return Html.fromHtml(out);
    }

    private static String inline(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("`(.+?)`", "<tt>$1</tt>");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
