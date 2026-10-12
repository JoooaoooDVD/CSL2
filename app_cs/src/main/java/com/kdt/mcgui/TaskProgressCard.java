package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * One row of the download console: a progress ring, the running task name and
 * its live detail line (sizes, counts, speed - whatever the task reported).
 */
public class TaskProgressCard extends LinearLayout {

    private final TaskProgressRing mRing;
    private final TextView mTitle;
    private final TextView mDetail;
    private final TextView mPercent;

    public TaskProgressCard(@NonNull Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_progress_task, this, true);
        mRing = findViewById(R.id.task_ring);
        mTitle = findViewById(R.id.task_title);
        mDetail = findViewById(R.id.task_detail);
        mPercent = findViewById(R.id.task_percent);
    }

    public void setTitle(@Nullable String text) {
        mTitle.setText(text == null ? "" : text);
    }

    public void setDetail(@Nullable String text) {
        mDetail.setText(text == null ? "" : text);
        mDetail.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setProgress(int progress, boolean indeterminate) {
        mRing.setIndeterminate(indeterminate);
        if (progress >= 0) {
            mRing.setProgress(progress);
            mPercent.setText(progress + "%");
            mPercent.setVisibility(VISIBLE);
        } else {
            mRing.setProgress(0);
            mPercent.setVisibility(INVISIBLE);
        }
    }
}
