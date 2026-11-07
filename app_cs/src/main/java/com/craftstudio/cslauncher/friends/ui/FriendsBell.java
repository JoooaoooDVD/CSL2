package com.craftstudio.cslauncher.friends.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.friends.CsAccount;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.friends.JoinWorld;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * The top-bar bell: unread badge, live ring on new notes, and the dropdown inbox with
 * Join World / Decline, Accept / Decline and message previews that open the chat.
 */
public final class FriendsBell implements FriendsClient.Listener {
    private final FragmentActivity mActivity;
    private final View mButton;
    private final TextView mBadge;
    private final FriendsClient mClient = FriendsClient.get();
    private PopupWindow mPopup;
    private boolean mAttached;

    public FriendsBell(@NonNull FragmentActivity activity, @NonNull View button, @NonNull TextView badge) {
        mActivity = activity;
        mButton = button;
        mBadge = badge;
        button.setOnClickListener(v -> toggle());
    }

    public void attach() {
        if (mAttached) return;
        mAttached = true;
        mClient.addListener(this);
        updateBadge();
    }

    public void detach() {
        if (!mAttached) return;
        mAttached = false;
        mClient.removeListener(this);
        if (mPopup != null && mPopup.isShowing()) mPopup.dismiss();
    }

    @Override public void onFriendsChanged(@NonNull FriendsClient.Snapshot snapshot) {
        updateBadge();
        if (mPopup != null && mPopup.isShowing()) fill((LinearLayout) mPopup.getContentView().findViewById(R.id.fi_list));
    }

    @Override public void onNote(@NonNull FriendsClient.Note note) {
        FriendsBanner.show(mActivity, note, true);
        if (!Motion.buttons(mButton)) return;
        mButton.animate().rotation(-14f).setDuration(70).withEndAction(() ->
                mButton.animate().rotation(12f).setDuration(110).withEndAction(() ->
                        mButton.animate().rotation(0f).setDuration(140).setInterpolator(Motion.SPRING).start()).start()).start();
    }

    private void updateBadge() {
        int n = CsAccount.isSignedIn() ? mClient.unread() : 0;
        if (n <= 0) { mBadge.setVisibility(View.GONE); return; }
        String text = n > 9 ? "9+" : String.valueOf(n);
        boolean grew = mBadge.getVisibility() != View.VISIBLE || !text.contentEquals(mBadge.getText());
        mBadge.setText(text);
        mBadge.setVisibility(View.VISIBLE);
        if (grew && Motion.buttons(mBadge)) {
            mBadge.setScaleX(0.4f); mBadge.setScaleY(0.4f);
            mBadge.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.SPRING).start();
        }
    }

    private void toggle() {
        if (mPopup != null && mPopup.isShowing()) { mPopup.dismiss(); return; }
        View content = LayoutInflater.from(mActivity).inflate(R.layout.popup_friends_inbox, null, false);
        int width = Math.min(dp(360), mActivity.getResources().getDisplayMetrics().widthPixels - dp(24));
        mPopup = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopup.setOutsideTouchable(true);
        mPopup.setElevation(dp(12));
        mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        if (Motion.content(content)) mPopup.setAnimationStyle(android.R.style.Animation_Dialog);
        content.findViewById(R.id.fi_open).setOnClickListener(v -> {
            mPopup.dismiss();
            openFriends(null);
        });
        fill(content.findViewById(R.id.fi_list));
        mPopup.showAsDropDown(mButton, 0, dp(6), Gravity.END);
        mClient.refreshNow();
        mPopup.setOnDismissListener(() -> { mClient.markAllRead(); updateBadge(); });
    }

    private void fill(LinearLayout list) {
        list.removeAllViews();
        if (!CsAccount.isSignedIn()) { list.addView(dim(mActivity.getString(R.string.friends_sign_in_first))); return; }
        List<FriendsClient.Note> notes = mClient.inbox();
        if (notes.isEmpty()) { list.addView(dim(mActivity.getString(R.string.friends_inbox_empty))); return; }
        int shown = 0;
        for (FriendsClient.Note n : notes) {
            if (shown++ >= 30) break;
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_vl_row);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            TextView title = new TextView(mActivity);
            title.setText(n.title);
            title.setTextColor(ContextCompat.getColor(mActivity, n.read ? R.color.vl_text_dim : R.color.vl_text));
            title.setTypeface(null, Typeface.BOLD);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);
            TextView body = dim(n.body);
            body.setMaxLines(2);
            row.addView(body);
            final String key = n.key;
            if ((n.kind == FriendsClient.Note.INVITE || n.kind == FriendsClient.Note.HOSTING) && n.address != null) {
                LinearLayout actions = actions();
                actions.addView(button(mActivity.getString(R.string.friends_join), R.drawable.bg_friends_btn_join, v -> {
                    mPopup.dismiss();
                    mClient.dismiss(key);
                    JoinWorld.joinFriend(mActivity, n.fromId, n.address, n.engine, n.version);
                }));
                actions.addView(gap());
                actions.addView(button(mActivity.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, v -> {
                    for (FriendsClient.Invite inv : mClient.openInvites()) if (("inv:" + inv.id).equals(key)) mClient.declineInvite(inv);
                    mClient.dismiss(key);
                }));
                row.addView(actions);
            } else if (n.kind == FriendsClient.Note.REQUEST && n.fromId != null) {
                LinearLayout actions = actions();
                actions.addView(button(mActivity.getString(R.string.friends_accept), R.drawable.bg_friends_btn_join, v -> mClient.respond(n.fromId, true, e -> { if (e != null) toast(e); else mClient.dismiss(key); })));
                actions.addView(gap());
                actions.addView(button(mActivity.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, v -> mClient.respond(n.fromId, false, e -> { if (e != null) toast(e); else mClient.dismiss(key); })));
                row.addView(actions);
            } else if (n.kind == FriendsClient.Note.MESSAGE && n.fromId != null) {
                row.setOnClickListener(v -> {
                    mPopup.dismiss();
                    mClient.markRead(key);
                    openFriends(n.fromId);
                });
            }
            list.addView(row);
        }
    }

    private void openFriends(String chatWith) {
        Bundle args = null;
        if (chatWith != null) { args = new Bundle(); args.putString(FriendsFragment.ARG_CHAT, chatWith); }
        com.craftstudio.cslauncher.fragments.MainMenuFragment menu = (com.craftstudio.cslauncher.fragments.MainMenuFragment)
                mActivity.getSupportFragmentManager().findFragmentByTag(com.craftstudio.cslauncher.fragments.MainMenuFragment.TAG);
        if (menu != null && menu.isAdded()) menu.openPane(FriendsFragment.class, FriendsFragment.TAG, args);
        else Tools.swapFragment(mActivity, FriendsFragment.class, FriendsFragment.TAG, args);
    }

    private LinearLayout actions() {
        LinearLayout a = new LinearLayout(mActivity);
        a.setOrientation(LinearLayout.HORIZONTAL);
        a.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        a.setLayoutParams(lp);
        return a;
    }

    private TextView button(String text, int bg, View.OnClickListener l) {
        TextView t = new TextView(mActivity);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(mActivity, R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackgroundResource(bg);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        t.setOnClickListener(l);
        return t;
    }

    private View gap() {
        View v = new View(mActivity);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private TextView dim(String s) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(mActivity, R.color.vl_text_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private void toast(String s) { android.widget.Toast.makeText(mActivity, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * mActivity.getResources().getDisplayMetrics().density); }
}
