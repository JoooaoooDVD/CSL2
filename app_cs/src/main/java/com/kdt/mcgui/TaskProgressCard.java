package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/**
 * One row of the download console: a type icon, the running task name, its
 * live detail line (sizes, counts, speed) and the current percent.
 */
public class TaskProgressCard extends LinearLayout {

    private final ImageView mIcon;
    private final TextView mTitle;
    private final TextView mDetail;
    private final TextView mPercent;

    public TaskProgressCard(@NonNull Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_progress_task, this, true);
        mIcon = findViewById(R.id.task_icon);
        mTitle = findViewById(R.id.task_title);
        mDetail = findViewById(R.id.task_detail);
        mPercent = findViewById(R.id.task_percent);
    }

    public void setIcon(@DrawableRes int resId) {
        mIcon.setImageResource(resId);
    }

    public void setTitle(@Nullable String text) {
        mTitle.setText(text == null ? "" : text);
    }

    public void setDetail(@Nullable String text) {
        mDetail.setText(text == null ? "" : text);
        mDetail.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setProgress(int progress, boolean indeterminate) {
        if (progress >= 0) {
            mPercent.setText(progress + "%");
            mPercent.setVisibility(VISIBLE);
        } else {
            mPercent.setVisibility(INVISIBLE);
        }
    }
}
