package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Shows the tail of the launcher's own log file (latestlog.txt) so the user can read crash
 * output without leaving the launcher, and share it with one tap. The read happens on the
 * shared executor; only the last chunk of the file is ever loaded.
 */
public class LogsFragment extends Fragment {
    public static final String TAG = "LogsFragment";
    /** Read at most this many trailing bytes, enough for any crash report */
    private static final int MAX_LOG_BYTES = 512 * 1024;

    public LogsFragment() {
        super(R.layout.fragment_logs);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.logs_reload_button).setOnClickListener(v -> loadLog());
        view.findViewById(R.id.logs_share_button).setOnClickListener(v -> Tools.shareLog(requireContext()));
        loadLog();
    }

    private void loadLog() {
        View root = getView();
        if (root == null) return;
        TextView text = root.findViewById(R.id.logs_text);
        ScrollView scroll = root.findViewById(R.id.logs_scroll);
        text.setText(R.string.home_logs_empty);

        File logFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!logFile.isFile()) return;

        CSLauncherApplication.sExecutorService.execute(() -> {
            String content = readTail(logFile);
            if (content == null) return;
            Tools.runOnUiThread(() -> {
                TextView live = getView() == null ? null : getView().findViewById(R.id.logs_text);
                if (live == null) return;
                live.setText(content);
                if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    /** The trailing bytes of the file as text, null when it can't be read */
    @Nullable
    private static String readTail(@NonNull File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int read = (int) Math.min(length, MAX_LOG_BYTES);
            raf.seek(length - read);
            byte[] buffer = new byte[read];
            raf.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
