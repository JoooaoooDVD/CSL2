package com.craftstudio.cslauncher.friends.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.e4mc.E4mcPublisher;
import com.craftstudio.cslauncher.friends.CsAccount;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.friends.JoinWorld;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * The CS Friends panel: account, world sharing (e4mc), invites, requests, friends and chat.
 * Hosted by {@link FriendsFragment} (launcher) and {@link FriendsPopup} (Java game process).
 */
public final class FriendsPanel implements FriendsClient.Listener, E4mcPublisher.Listener {

    public interface Host {
        @NonNull Activity activity();
        /** True inside the running Java game (sharing controls live, Join disabled). */
        boolean inGame();
        void onCloseRequested();
    }

    private final View mRoot;
    private final Host mHost;
    private final Context mCtx;
    private final LinearLayout mContent;
    private final ScrollView mScroll;
    private final View mBack, mClose, mComposer;
    private final TextView mTitle, mSubtitle, mSend;
    private final EditText mInput;
    private final FriendsClient mClient = FriendsClient.get();
    @Nullable private String mChatWith, mChatName;
    private boolean mShown, mBusy;
    private int mLastChatCount = -1;
    private final float mDp;

    public FriendsPanel(@NonNull View root, @NonNull Host host) {
        mRoot = root;
        mHost = host;
        mCtx = root.getContext();
        mDp = mCtx.getResources().getDisplayMetrics().density;
        mContent = root.findViewById(R.id.fp_content);
        mScroll = root.findViewById(R.id.fp_scroll);
        mBack = root.findViewById(R.id.fp_back);
        mClose = root.findViewById(R.id.fp_close);
        mComposer = root.findViewById(R.id.fp_composer);
        mTitle = root.findViewById(R.id.fp_title);
        mSubtitle = root.findViewById(R.id.fp_subtitle);
        mSend = root.findViewById(R.id.fp_send);
        mInput = root.findViewById(R.id.fp_input);
        mBack.setOnClickListener(v -> closeChat());
        mClose.setOnClickListener(v -> host.onCloseRequested());
        root.findViewById(R.id.fp_refresh).setOnClickListener(v -> {
            v.animate().rotationBy(360f).setDuration(500).setInterpolator(Motion.STANDARD).start();
            mClient.refreshNow();
            render();
        });
        mSend.setOnClickListener(v -> sendChat());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendChat();
                return true;
            }
            return false;
        });
    }

    public void showCloseButton() { mClose.setVisibility(View.VISIBLE); }

    /** Opens the conversation with a friend as soon as the snapshot knows them. */
    public void openChat(@NonNull String friendId) {
        FriendsClient.Friend f = mClient.snapshot().friend(friendId);
        openChat(friendId, f == null ? mCtx.getString(R.string.friends_chat) : f.name);
    }

    public void onShow() {
        if (mShown) return;
        mShown = true;
        mClient.addListener(this);
        E4mcPublisher.get().addListener(this);
        if (mChatWith != null) mClient.setFast(true);
        render();
    }

    public void onHide() {
        if (!mShown) return;
        mShown = false;
        mClient.setFast(false);
        mClient.removeListener(this);
        E4mcPublisher.get().removeListener(this);
    }

    /** True when the back press was consumed (chat → list). */
    public boolean onBackPressed() {
        if (mChatWith != null) { closeChat(); return true; }
        return false;
    }

    @Override public void onFriendsChanged(@NonNull FriendsClient.Snapshot snapshot) { render(); }
    @Override public void onFriendsError(@NonNull String message) { mSubtitle.setText(message); }
    @Override public void onE4mcChanged() { if (mChatWith == null) render(); }

    // ------------------------------------------------------------------ rendering

    private void render() {
        if (mChatWith != null) { renderChat(); return; }
        mTitle.setText(R.string.friends_title);
        mSubtitle.setText(mClient.lastError() == null ? mCtx.getString(R.string.friends_subtitle) : mCtx.getString(R.string.friends_offline_hint));
        mBack.setVisibility(View.GONE);
        mComposer.setVisibility(View.GONE);
        int scrollY = mScroll.getScrollY();
        mContent.removeAllViews();
        renderAccount();
        if (!CsAccount.isSignedIn()) return;
        renderSharing();
        FriendsClient.Snapshot s = mClient.snapshot();
        renderInvites();
        renderRequests(s);
        renderAddFriend();
        renderFriends(s);
        mScroll.post(() -> mScroll.scrollTo(0, scrollY));
    }

    private void renderAccount() {
        LinearLayout card = card();
        if (!CsAccount.isSignedIn()) {
            card.addView(heading(mCtx.getString(R.string.friends_account_title)));
            card.addView(body(mCtx.getString(mHost.inGame() ? R.string.friends_sign_in_game : R.string.friends_account_hint)));
            if (!mHost.inGame()) {
                LinearLayout row = hrow();
                row.addView(button(mCtx.getString(R.string.friends_sign_in), R.drawable.bg_vl_btn_primary, v -> AccountDialog.show(mHost.activity(), false, this::render)));
                row.addView(gap());
                row.addView(button(mCtx.getString(R.string.friends_create_account), R.drawable.bg_vl_btn_ghost, v -> AccountDialog.show(mHost.activity(), true, this::render)));
                card.addView(row);
            }
            mContent.addView(card);
            return;
        }
        LinearLayout top = hrow();
        LinearLayout names = new LinearLayout(mCtx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = heading(CsAccount.displayName().isEmpty() ? CsAccount.username() : CsAccount.displayName());
        names.addView(name);
        names.addView(dim("@" + CsAccount.username()));
        top.addView(names);
        // Friend code pill — tap to copy
        TextView code = new TextView(mCtx);
        code.setText(mCtx.getString(R.string.friends_code) + "  " + CsAccount.code());
        code.setTextColor(color(R.color.vl_code));
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        code.setBackgroundResource(R.drawable.bg_vl_code);
        code.setPadding(dp(12), dp(8), dp(12), dp(8));
        code.setOnClickListener(v -> copy(CsAccount.code(), R.string.friends_code_copied));
        top.addView(code);
        card.addView(top);
        if (!mHost.inGame()) {
            LinearLayout row = hrow();
            row.addView(button(mCtx.getString(R.string.friends_change_password), R.drawable.bg_vl_btn_ghost, v -> AccountDialog.showChangePassword(mHost.activity())));
            row.addView(gap());
            row.addView(button(mCtx.getString(R.string.friends_sign_out), R.drawable.bg_vl_btn_ghost, v -> {
                mClient.signOut();
                render();
            }));
            card.addView(row);
        }
        mContent.addView(card);
    }

    private void renderSharing() {
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.friends_sharing_title)));
        if (!mHost.inGame()) {
            card.addView(body(mCtx.getString(R.string.friends_sharing_launcher)));
            mContent.addView(card);
            return;
        }
        E4mcPublisher p = E4mcPublisher.get();
        Switch sw = new Switch(mCtx);
        sw.setText(R.string.friends_sharing_switch);
        sw.setTextColor(color(R.color.vl_text));
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sw.setChecked(p.isEnabled());
        sw.setOnCheckedChangeListener((b, on) -> p.setEnabled(on));
        card.addView(sw);
        switch (p.phase()) {
            case STARTING:
                card.addView(statusLine(mCtx.getString(R.string.friends_sharing_connecting), R.color.vl_warn));
                break;
            case PUBLISHED: {
                card.addView(statusLine(mCtx.getString(R.string.friends_sharing_live), R.color.vl_ok));
                TextView addr = new TextView(mCtx);
                addr.setText(p.address());
                addr.setTextColor(color(R.color.vl_code));
                addr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                addr.setBackgroundResource(R.drawable.bg_vl_code);
                addr.setPadding(dp(14), dp(10), dp(14), dp(10));
                addr.setOnClickListener(v -> copy(p.address(), R.string.friends_address_copied));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                card.addView(addr, lp);
                card.addView(dim(mCtx.getString(R.string.friends_sharing_guests, p.guests())));
                LinearLayout row = hrow();
                row.addView(button(mCtx.getString(R.string.friends_invite_all), R.drawable.bg_friends_btn_join, v -> inviteAll()));
                row.addView(gap());
                row.addView(button(mCtx.getString(R.string.friends_sharing_stop), R.drawable.bg_vl_btn_danger, v -> p.stop()));
                card.addView(row);
                break;
            }
            case FAILED: {
                card.addView(statusLine(p.problem() == null ? "e4mc failed" : p.problem(), R.color.vl_bad));
                if (p.port() > 0) {
                    LinearLayout row = hrow();
                    row.addView(button(mCtx.getString(R.string.friends_sharing_retry), R.drawable.bg_vl_btn_primary, v -> p.start(p.port())));
                    card.addView(row);
                }
                break;
            }
            default:
                card.addView(body(mCtx.getString(R.string.friends_sharing_idle)));
        }
        mContent.addView(card);
    }

    private void renderInvites() {
        List<FriendsClient.Invite> invites = mClient.openInvites();
        if (invites.isEmpty()) return;
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.friends_invites)));
        for (FriendsClient.Invite v : invites) {
            LinearLayout row = listRow();
            LinearLayout text = vcol();
            text.addView(strong(v.from));
            String world = v.world == null || v.world.isEmpty() ? v.address : v.world + "  ·  " + v.address;
            text.addView(dim(world + (isHorizon(v.engine) ? "  ·  CS Engine" : "  ·  Java")));
            row.addView(text);
            if (mHost.inGame()) {
                row.addView(smallButton(mCtx.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, b -> mClient.declineInvite(v)));
                text.addView(dim(mCtx.getString(R.string.friends_join_in_launcher)));
            } else {
                row.addView(smallButton(mCtx.getString(R.string.friends_join), R.drawable.bg_friends_btn_join, b -> {
                    mClient.declineInvite(v); // consumed
                    JoinWorld.join(mHost.activity(), v.address, v.engine);
                }));
                row.addView(gap());
                row.addView(smallButton(mCtx.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, b -> mClient.declineInvite(v)));
            }
            card.addView(row);
        }
        mContent.addView(card);
    }

    private void renderRequests(FriendsClient.Snapshot s) {
        if (s.requests.isEmpty()) return;
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.friends_requests)));
        for (FriendsClient.Request q : s.requests) {
            LinearLayout row = listRow();
            LinearLayout text = vcol();
            text.addView(strong(q.name));
            text.addView(dim(q.code));
            row.addView(text);
            if (q.incoming) {
                row.addView(smallButton(mCtx.getString(R.string.friends_accept), R.drawable.bg_friends_btn_join, b -> act(() -> mClient.respond(q.id, true, this::done))));
                row.addView(gap());
                row.addView(smallButton(mCtx.getString(R.string.friends_decline), R.drawable.bg_vl_btn_danger, b -> act(() -> mClient.respond(q.id, false, this::done))));
            } else {
                row.addView(pill(mCtx.getString(R.string.friends_pending)));
            }
            card.addView(row);
        }
        mContent.addView(card);
    }

    private void renderAddFriend() {
        LinearLayout row = hrow();
        row.setPadding(0, dp(2), 0, dp(8));
        EditText field = new EditText(mCtx);
        field.setHint(R.string.friends_add_hint);
        field.setSingleLine(true);
        field.setBackgroundResource(R.drawable.bg_vl_field);
        field.setTextColor(color(R.color.vl_text));
        field.setHintTextColor(color(R.color.vl_text_faint));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        field.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        row.addView(field);
        row.addView(gap());
        TextView add = button(mCtx.getString(R.string.friends_add), R.drawable.bg_vl_btn_primary, v -> {
            String code = field.getText().toString().trim();
            if (code.isEmpty()) return;
            hideKeyboard(field);
            act(() -> mClient.addFriend(code, e -> { done(e); if (e == null) field.setText(""); }));
        });
        add.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        row.addView(add);
        mContent.addView(row);
    }

    private void renderFriends(FriendsClient.Snapshot s) {
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.friends_list) + (s.friends.isEmpty() ? "" : "  ·  " + s.friends.size())));
        if (s.friends.isEmpty()) {
            card.addView(body(mCtx.getString(R.string.friends_empty)));
            mContent.addView(card);
            return;
        }
        E4mcPublisher p = E4mcPublisher.get();
        boolean hosting = mHost.inGame() && p.isPublished();
        for (FriendsClient.Friend f : s.friends) {
            LinearLayout row = listRow();
            View dot = new View(mCtx);
            dot.setBackgroundResource(R.drawable.bg_vl_dot);
            dot.getBackground().mutate().setTint(color(f.hosting() ? R.color.vl_info : f.online() ? R.color.vl_ok : R.color.vl_text_faint));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dlp.rightMargin = dp(10);
            row.addView(dot, dlp);
            LinearLayout text = vcol();
            text.addView(strong(f.name));
            String status;
            if (f.hosting()) {
                status = mCtx.getString(R.string.friends_hosting) + (f.world == null || f.world.isEmpty() ? "" : "  ·  " + f.world) + (isHorizon(f.engine) ? "  ·  CS Engine" : "  ·  Java");
            } else if ("ingame".equals(f.status) || "playing".equals(f.status)) status = mCtx.getString(R.string.friends_in_game);
            else status = mCtx.getString(f.online() ? R.string.friends_online : R.string.friends_offline);
            text.addView(dim(status));
            row.addView(text);
            if (f.hosting() && !mHost.inGame()) {
                row.addView(smallButton(mCtx.getString(R.string.friends_join), R.drawable.bg_friends_btn_join, b -> JoinWorld.join(mHost.activity(), f.address, f.engine)));
                row.addView(gap());
            }
            if (hosting && f.online()) {
                row.addView(smallButton(mCtx.getString(R.string.friends_invite), R.drawable.bg_vl_btn_primary, b -> act(() -> mClient.invite(f.id, p.address(), null, "java", e -> {
                    done(e);
                    if (e == null) toast(R.string.friends_invite_sent);
                }))));
                row.addView(gap());
            }
            int unread = mClient.unreadMessagesFrom(f.id);
            TextView chat = smallButton(mCtx.getString(R.string.friends_chat) + (unread > 0 ? " (" + unread + ")" : ""), R.drawable.bg_vl_btn_ghost, b -> openChat(f.id, f.name));
            if (unread > 0) chat.setTextColor(color(R.color.vl_ok));
            row.addView(chat);
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(mHost.activity())
                        .setTitle(R.string.friends_remove)
                        .setMessage(mCtx.getString(R.string.friends_remove_confirm, f.name))
                        .setPositiveButton(R.string.friends_remove, (d, w) -> act(() -> mClient.removeFriend(f.id, this::done)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            card.addView(row);
        }
        mContent.addView(card);
    }

    // ------------------------------------------------------------------ chat

    private void openChat(String id, String name) {
        mChatWith = id;
        mChatName = name;
        mLastChatCount = -1;
        mClient.markConversationRead(id);
        mClient.setFast(true);
        renderChat();
        if (Motion.content(mRoot)) {
            mContent.setAlpha(0f);
            mContent.setTranslationX(dp(24));
            mContent.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
    }

    private void closeChat() {
        if (mChatWith == null) return;
        mChatWith = null;
        mClient.setFast(false);
        hideKeyboard(mInput);
        render();
        if (Motion.content(mRoot)) {
            mContent.setAlpha(0f);
            mContent.setTranslationX(-dp(24));
            mContent.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
    }

    private void renderChat() {
        FriendsClient.Friend f = mClient.snapshot().friend(mChatWith);
        mTitle.setText(mChatName);
        mSubtitle.setText(f == null ? "" : f.hosting() ? mCtx.getString(R.string.friends_hosting) : mCtx.getString(f.online() ? R.string.friends_online : R.string.friends_offline));
        mBack.setVisibility(View.VISIBLE);
        mComposer.setVisibility(View.VISIBLE);
        List<FriendsClient.Message> msgs = mClient.conversation(mChatWith);
        if (msgs.size() == mLastChatCount) return;
        mLastChatCount = msgs.size();
        mClient.markConversationRead(mChatWith);
        mContent.removeAllViews();
        for (FriendsClient.Message m : msgs) {
            TextView t = new TextView(mCtx);
            t.setText(m.body);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(color(m.mine ? R.color.vl_platinum_text : R.color.vl_text));
            t.setBackgroundResource(m.mine ? R.drawable.bg_friends_bubble_out : R.drawable.bg_friends_bubble_in);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setMaxWidth((int) (mRoot.getWidth() * 0.78f) > 0 ? (int) (mRoot.getWidth() * 0.78f) : dp(280));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = m.mine ? Gravity.END : Gravity.START;
            lp.topMargin = dp(4);
            mContent.addView(t, lp);
        }
        if (msgs.isEmpty()) mContent.addView(body(mCtx.getString(R.string.friends_chat_hint)));
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendChat() {
        if (mChatWith == null) return;
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        mInput.setText("");
        final String to = mChatWith;
        mClient.send(to, text, e -> {
            if (e != null) toast(e);
        });
    }

    // ------------------------------------------------------------------ actions

    private void inviteAll() {
        E4mcPublisher p = E4mcPublisher.get();
        if (!p.isPublished()) return;
        act(() -> mClient.invite(null, p.address(), null, "java", e -> {
            done(e);
            if (e == null) toast(R.string.friends_invite_sent);
        }));
    }

    private void act(Runnable r) {
        if (mBusy) return;
        mBusy = true;
        r.run();
    }

    private void done(@Nullable String error) {
        mBusy = false;
        if (error != null) toast(error);
        render();
    }

    // ------------------------------------------------------------------ widgets

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(mCtx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_vl_card);
        int pad = mCtx.getResources().getDimensionPixelSize(R.dimen.vl_card_pad);
        c.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = mCtx.getResources().getDimensionPixelSize(R.dimen.vl_gap);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(mCtx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout listRow() {
        LinearLayout r = hrow();
        r.setBackgroundResource(R.drawable.bg_vl_row);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setClickable(true);
        return r;
    }

    private LinearLayout vcol() {
        LinearLayout c = new LinearLayout(mCtx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    private View gap() {
        View v = new View(mCtx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private TextView heading(String text) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setTextColor(color(R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(mCtx, R.font.noto_sans_bold));
        return t;
    }

    private TextView strong(String text) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(color(R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setTextColor(color(R.color.vl_text_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView dim(String text) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(color(R.color.vl_text_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        return t;
    }

    private TextView statusLine(String text, int colorRes) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setTextColor(color(colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView pill(String text) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setTextColor(color(R.color.vl_text_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setBackgroundResource(R.drawable.bg_vl_pill);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private TextView button(String text, int bg, View.OnClickListener l) {
        TextView t = new TextView(mCtx);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(color(R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackgroundResource(bg);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        t.setOnClickListener(l);
        return t;
    }

    private TextView smallButton(String text, int bg, View.OnClickListener l) {
        TextView t = button(text, bg, l);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        return t;
    }

    private int dp(int v) { return Math.round(v * mDp); }
    private int color(int res) { return ContextCompat.getColor(mCtx, res); }
    private static boolean isHorizon(@Nullable String engine) { return engine != null && engine.toLowerCase().contains("horizon"); }

    private void copy(@Nullable String text, int toastRes) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) mCtx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CS", text));
        toast(toastRes);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) mCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void toast(int res) { Toast.makeText(mCtx, res, Toast.LENGTH_SHORT).show(); }
    private void toast(String s) { Toast.makeText(mCtx, s, Toast.LENGTH_SHORT).show(); }
}
