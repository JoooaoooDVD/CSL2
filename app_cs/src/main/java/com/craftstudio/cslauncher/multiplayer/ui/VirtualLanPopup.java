package com.craftstudio.cslauncher.multiplayer.ui;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * In-game "Virtual LAN" popup: a plain view overlay on the game activity's content frame (no
 * Dialog window, so the immersive surface is untouched), hosting {@link VirtualLanPanel}.
 */
public final class VirtualLanPopup implements VirtualLanPanel.Host {
    private final Activity mActivity;
    private final ViewGroup mContainer;
    private View mRoot, mSheet;
    private VirtualLanPanel mPanel;
    private boolean mDismissed;
    @Nullable private final Runnable mOnDismiss;

    private VirtualLanPopup(@NonNull Activity activity, @NonNull ViewGroup container, @Nullable Runnable onDismiss) {
        mActivity = activity;
        mContainer = container;
        mOnDismiss = onDismiss;
    }

    /** Builds, attaches and animates the popup in */
    @NonNull
    public static VirtualLanPopup show(@NonNull Activity activity, @Nullable Runnable onDismiss) {
        ViewGroup container = activity.findViewById(android.R.id.content);
        VirtualLanPopup popup = new VirtualLanPopup(activity, container, onDismiss);
        popup.build();
        popup.animateIn();
        return popup;
    }

    private void build() {
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        mRoot = inflater.inflate(R.layout.popup_virtual_lan, mContainer, false);
        mSheet = mRoot.findViewById(R.id.vlp_sheet);
        mPanel = new VirtualLanPanel(mRoot.findViewById(R.id.vl_panel), this);
        mPanel.showCloseButton();
        mRoot.setOnClickListener(v -> dismiss());
        mSheet.setOnClickListener(v -> { /* swallow */ });
        mRoot.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                onBackPressed();
                return true;
            }
            return false;
        });
        mContainer.addView(mRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRoot.requestFocus();
        mPanel.onShow();
    }

    private void animateIn() {
        if (!Motion.content(mRoot)) return;
        mRoot.setAlpha(0f);
        mSheet.setScaleX(0.96f);
        mSheet.setScaleY(0.96f);
        mSheet.setAlpha(0f);
        mRoot.animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        mSheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(Motion.ENTER).start();
    }

    public boolean isShowing() { return !mDismissed; }

    /** Back key from the activity: inline boxes close first, then the popup */
    public void onBackPressed() {
        if (mPanel.onBackPressed()) return;
        dismiss();
    }

    /** Animates out and detaches; the session keeps running in the service. Safe to call twice. */
    public void dismiss() {
        if (mDismissed) return;
        mDismissed = true;
        mPanel.onHide();
        if (!Motion.content(mRoot)) {
            detach();
            return;
        }
        mRoot.animate().alpha(0f).setDuration(160).start();
        mSheet.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(160).setInterpolator(Motion.EXIT)
                .withEndAction(this::detach).start();
    }

    private void detach() {
        mRoot.animate().cancel();
        mSheet.animate().cancel();
        mContainer.removeView(mRoot);
        if (mOnDismiss != null) mOnDismiss.run();
    }

    @NonNull @Override public Activity activity() { return mActivity; }
    @Override public boolean inGame() { return true; }
    @Override public void onCloseRequested() { dismiss(); }
}
