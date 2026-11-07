package com.craftstudio.cslauncher.friends.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.friends.JoinWorld;
import com.craftstudio.cslauncher.friends.RemoteSkins;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * In-app notification for CS Friends events (invite, request, message): a compact card slides in
 * at the top of the current activity — launcher or Java game — with the sender's head and the
 * invite actions, and leaves by itself. One banner at a time; a new one replaces the old.
 */
public final class FriendsBanner {
    private static View sCurrent;

    private FriendsBanner() {}

    public static void show(@NonNull Activity activity, @NonNull FriendsClient.Note note, boolean canJoin) {
        if (activity.isFinishing()) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        dismiss();
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_friends_popup);
        card.setElevation(12 * dp);
        card.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
        card.setClickable(true);

        ImageView head = new ImageView(activity);
        Bitmap b = RemoteSkins.head(note.title);
        if (b != null) head.setImageBitmap(b); else head.setImageResource(R.drawable.ic_default_head);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams((int) (30 * dp), (int) (30 * dp));
        hlp.rightMargin = (int) (10 * dp);
        card.addView(head, hlp);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(activity);
        title.setText(note.title);
        title.setTextColor(ContextCompat.getColor(activity, R.color.vl_text));
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        TextView body = new TextView(activity);
        body.setText(note.body);
        body.setTextColor(ContextCompat.getColor(activity, R.color.vl_text_dim));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setMaxLines(2);
        text.addView(title);
        text.addView(body);
        card.addView(text);

        if ((note.kind == FriendsClient.Note.INVITE || note.kind == FriendsClient.Note.HOSTING) && note.address != null) {
            if (canJoin) {
                card.addView(button(activity, activity.getString(R.string.friends_join), R.drawable.bg_friends_btn_join, v -> {
                    dismiss();
                    FriendsClient.get().dismiss(note.key);
                    JoinWorld.joinFriend(activity, note.fromId, note.address, note.engine, note.version);
                }));
            }
            card.addView(button(activity, activity.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, v -> {
                for (FriendsClient.Invite inv : FriendsClient.get().openInvites()) if (("inv:" + inv.id).equals(note.key)) FriendsClient.get().declineInvite(inv);
                FriendsClient.get().dismiss(note.key);
                dismiss();
            }));
        } else {
            card.setOnClickListener(v -> dismiss());
        }

        int width = Math.min((int) (420 * dp), activity.getResources().getDisplayMetrics().widthPixels - (int) (24 * dp));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) (12 * dp);
        root.addView(card, lp);
        sCurrent = card;
        if (Motion.content(card)) {
            card.setTranslationY(-40 * dp);
            card.setAlpha(0f);
            card.animate().translationY(0f).alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
        final View me = card;
        card.postDelayed(() -> { if (sCurrent == me) dismiss(); }, note.kind == FriendsClient.Note.INVITE || note.kind == FriendsClient.Note.HOSTING ? 12000 : 6000);
    }

    public static void dismiss() {
        final View v = sCurrent;
        sCurrent = null;
        if (v == null || !(v.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) v.getParent();
        if (!Motion.content(v)) { parent.removeView(v); return; }
        v.animate().translationY(-30 * v.getResources().getDisplayMetrics().density).alpha(0f).setDuration(Motion.SHORT)
                .setInterpolator(Motion.EXIT).withEndAction(() -> parent.removeView(v)).start();
    }

    private static TextView button(Activity a, String text, int bg, View.OnClickListener l) {
        float dp = a.getResources().getDisplayMetrics().density;
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(a, R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackgroundResource(bg);
        t.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (32 * dp));
        lp.leftMargin = (int) (8 * dp);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }
}
