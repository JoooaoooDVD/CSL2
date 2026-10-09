package com.craftstudio.cslauncher.services;

import android.content.Context;

import com.craftstudio.cslauncher.progresskeeper.TaskCountListener;

public class ProgressServiceKeeper implements TaskCountListener {
    private final Context context;
    public ProgressServiceKeeper(Context ctx) {
        this.context = ctx;
    }

    @Override
    public boolean onUpdateTaskCount(int taskCount) {
        if(taskCount > 0) ProgressService.startService(context);
        return false;
    }
}
