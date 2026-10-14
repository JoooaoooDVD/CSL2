package com.kdt.mcgui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.McLogsApi;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.File;
import java.io.IOException;

/**
 * "Share log" popup. Two option cards (upload to mclo.gs / share the file); the upload runs
 * inside the popup and ends in a link row with copy / open / share, so the user never loses
 * the link. {@code onFinished} runs once the popup is gone (used by ExitActivity).
 */
public class ShareLogDialog extends CSPopupDialog {

    private final File mLogFile;
    @Nullable private final Runnable mOnFinished;
    private boolean mBusy;
    private boolean mHandled;

    private View mOptions, mProgress, mResult;
    private TextView mError, mLink, mSubtitle;
    private String mUrl;

    public ShareLogDialog(@NonNull Context context, @Nullable Runnable onFinished) {
        super(context);
        mLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        mOnFinished = onFinished;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_share_log_v2);
        View card = findViewById(R.id.sl_card);
        setupPopup(findViewById(R.id.sl_scrim), card, true);
        sizeCard(card);

        mOptions = findViewById(R.id.sl_options);
        mProgress = findViewById(R.id.sl_progress);
        mResult = findViewById(R.id.sl_result);
        mError = findViewById(R.id.sl_error);
        mLink = findViewById(R.id.sl_link);
        mSubtitle = findViewById(R.id.sl_subtitle);

        View close = findViewById(R.id.sl_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        TextView size = findViewById(R.id.sl_fact_size);
        TextView time = findViewById(R.id.sl_fact_time);
        if (mLogFile.isFile()) {
            size.setText(getContext().getString(R.string.sl_size_fmt, android.text.format.Formatter.formatShortFileSize(getContext(), mLogFile.length())));
            time.setText(DateUtils.getRelativeTimeSpanString(mLogFile.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            size.setText(R.string.sl_no_log);
            time.setVisibility(View.GONE);
        }

        View cloud = findViewById(R.id.sl_opt_cloud);
        View file = findViewById(R.id.sl_opt_file);
        View cloudTile = findViewById(R.id.sl_cloud_tile);
        View fileTile = findViewById(R.id.sl_file_tile);
        cloud.setClipToOutline(true);
        file.setClipToOutline(true);
        cloud.setOnClickListener(v -> {
            if (mBusy || isClosing()) return;
            if (!mLogFile.isFile()) { Motion.shake(v); return; }
            Motion.bounce(cloudTile);
            startUpload();
        });
        file.setOnClickListener(v -> {
            if (mBusy || isClosing()) return;
            if (!mLogFile.isFile()) { Motion.shake(v); return; }
            Motion.bounce(fileTile);
            mHandled = true;
            CreatePopupMotion.optionChosen(file, () -> close(() -> {
                Tools.openPath(getContext(), mLogFile, true);
                if (mOnFinished != null) mOnFinished.run();
            }));
        });

        findViewById(R.id.sl_copy).setOnClickListener(v -> copyLink());
        findViewById(R.id.sl_open).setOnClickListener(v -> {
            if (mUrl == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(mUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { getContext().startActivity(intent); } catch (Exception ignored) {}
        });
        findViewById(R.id.sl_share).setOnClickListener(v -> {
            if (mUrl == null) return;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, mUrl);
            Intent chooserIntent = Intent.createChooser(shareIntent, mUrl);
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooserIntent);
        });
        for (int id : new int[]{R.id.sl_copy, R.id.sl_open, R.id.sl_share}) {
            CSAnimationEngine.attachPressFeedback(findViewById(id));
        }

        CreatePopupMotion.chooserContent(findViewById(R.id.sl_header), new View[]{cloud, file}, findViewById(R.id.sl_facts));
        setOnDismissListener(d -> {
            if (!mHandled && mOnFinished != null) mOnFinished.run();
        });
    }

    @Override
    protected void onDismissRequested() {
        if (mBusy) {
            CreatePopupMotion.nudge(mCard);
            return;
        }
        close(null);
    }

    private void startUpload() {
        mBusy = true;
        mError.setVisibility(View.GONE);
        swap(mOptions, mProgress);
        Motion.swapText(mSubtitle, getContext().getString(R.string.share_log_mclogs_uploading));
        CSLauncherApplication.sExecutorService.execute(() -> {
            try {
                String url = McLogsApi.upload(mLogFile);
                Tools.runOnUiThread(() -> onUploaded(url));
            } catch (IOException e) {
                Tools.runOnUiThread(() -> onUploadFailed(e));
            }
        });
    }

    private void onUploaded(String url) {
        if (!isShowing()) return;
        mBusy = false;
        mHandled = true;
        mUrl = url;
        mLink.setText(url);
        copyLinkSilently();
        swap(mProgress, mResult);
        Motion.swapText(mSubtitle, getContext().getString(R.string.share_log_mclogs_copied));
        Motion.popChildren((ViewGroup) mResult, 60L, 60L, 0.92f);
        setOnDismissListener(d -> {
            if (mOnFinished != null) mOnFinished.run();
        });
    }

    private void onUploadFailed(IOException e) {
        if (!isShowing()) return;
        mBusy = false;
        swap(mProgress, mOptions);
        mError.setText(getContext().getString(R.string.share_log_mclogs_error) + ": " + e.getMessage());
        mError.setVisibility(View.VISIBLE);
        Motion.shake(mCard);
        Motion.swapText(mSubtitle, getContext().getString(R.string.sl_subtitle));
    }

    private void copyLinkSilently() {
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && mUrl != null) clipboard.setPrimaryClip(ClipData.newPlainText("mclo.gs", mUrl));
    }

    private void copyLink() {
        copyLinkSilently();
        Motion.pulse(mLink);
        Toast.makeText(getContext(), R.string.share_log_mclogs_copied, Toast.LENGTH_SHORT).show();
    }

    private void swap(View out, View in) {
        Motion.hide(out, true);
        in.setVisibility(View.VISIBLE);
        Motion.riseIn(in, 60L, 12f);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (520 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }
}
