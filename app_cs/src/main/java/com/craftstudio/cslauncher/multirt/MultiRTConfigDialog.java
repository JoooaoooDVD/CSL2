package com.craftstudio.cslauncher.multirt;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.utils.AnimationManager;

/** Java runtimes list, shown in the launcher's XML settings popup */
public class MultiRTConfigDialog {
    private RecyclerView mDialogView;
    private RTRecyclerViewAdapter mAdapter;
    private ActivityResultLauncher<Object> mInstallLauncher;
    private Context mContext;
    private SettingsPopup mPopup;

    /** Show the popup, refreshes the adapter data before showing it */
    public void show() {
        if (mPopup != null && mPopup.isShowing()) return;
        refresh();
        if (mAdapter.getIsEditing()) mAdapter.setIsEditing(false);
        // A popup is single-use (its exit animation leaves it hidden): a fresh one every time
        mPopup = new SettingsPopup(mContext)
                .title(R.string.multirt_config_title)
                .icon(ContextCompat.getDrawable(mContext, R.drawable.ic_px_runtime_mgr))
                .customView(mDialogView)
                .positive(R.string.multirt_config_add, () -> mInstallLauncher.launch(null))
                .neutral(mContext.getText(R.string.multirt_delete_runtime), this::toggleEditing, false);
        mPopup.show();
    }

    private void toggleEditing() {
        boolean isEditing = !mAdapter.getIsEditing();
        mAdapter.setIsEditing(isEditing);
        if (mPopup != null) {
            mPopup.setNeutralText(mContext.getText(isEditing ? R.string.multirt_config_setdefault : R.string.multirt_delete_runtime));
        }
    }

    @SuppressLint("NotifyDataSetChanged") //only used to completely refresh the list, it is necessary
    public void refresh() {
        RecyclerView.Adapter<?> adapter = mDialogView.getAdapter();
        if(adapter != null) adapter.notifyDataSetChanged();
    }

    /** Build the list behavior */
    public void prepare(Context activity, ActivityResultLauncher<Object> installJvmLauncher) {
        mContext = activity;
        mInstallLauncher = installJvmLauncher;
        mDialogView = new RecyclerView(activity);
        mDialogView.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
        mDialogView.setOverScrollMode(RecyclerView.OVER_SCROLL_NEVER);
        mDialogView.setNestedScrollingEnabled(false);
        mAdapter = new RTRecyclerViewAdapter();
        if(activity instanceof Activity) {
            mAdapter.setActivity((Activity) activity);
        }
        mDialogView.setAdapter(mAdapter);
        AnimationManager.applyListAnimation(mDialogView);
    }
}
