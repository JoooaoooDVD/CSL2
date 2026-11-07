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
import com.craftstudio.cslauncher.e4mc.HostSettings;
import com.craftstudio.cslauncher.friends.CsAccount;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.friends.JoinWorld;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * The CS Friends panel: account, world sharing (e4mc), invites, requests, friends and chat.
 * Hosted by {@link FriendsFragment} (launcher) and {@link FriendsPopup} (Java game process).
 */
public final class FriendsPanel implements FriendsClient.Listener, E4mcPublisher.Listener, com.craftstudio.cslauncher.friends.RemoteSkins.HeadListener {

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
    private final View mReplyBar, mAttach;
    private final TextView mReplyTitle, mReplyBody;
    @Nullable private FriendsClient.Message mReplyTo;
    private boolean mSendingPhoto;
    /** Photo picker result code (activities forward their onActivityResult here). */
    public static final int REQ_PHOTO = 0x5C50;
    @Nullable private static java.lang.ref.WeakReference<FriendsPanel> sActive;
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
        mReplyBar = root.findViewById(R.id.fp_reply_bar);
        mReplyTitle = root.findViewById(R.id.fp_reply_title);
        mReplyBody = root.findViewById(R.id.fp_reply_body);
        mAttach = root.findViewById(R.id.fp_attach);
        root.findViewById(R.id.fp_reply_close).setOnClickListener(v -> setReply(null));
        mAttach.setOnClickListener(v -> showAttachMenu());
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
        com.craftstudio.cslauncher.friends.RemoteSkins.addHeadListener(this);
        if (mChatWith != null) mClient.setFast(true);
        com.craftstudio.cslauncher.friends.FriendsNotifier.setOpenChat(mChatWith);
        render();
    }

    public void onHide() {
        if (!mShown) return;
        mShown = false;
        mClient.setFast(false);
        mClient.removeListener(this);
        E4mcPublisher.get().removeListener(this);
        com.craftstudio.cslauncher.friends.FriendsNotifier.setOpenChat(null);
        com.craftstudio.cslauncher.friends.RemoteSkins.removeHeadListener(this);
    }

    /** True when the back press was consumed (chat → list). */
    public boolean onBackPressed() {
        if (mChatWith != null) { closeChat(); return true; }
        return false;
    }

    @Override public void onFriendsChanged(@NonNull FriendsClient.Snapshot snapshot) { render(); }
    @Override public void onFriendsError(@NonNull String message) { mSubtitle.setText(message); }
    @Override public void onE4mcChanged() { if (mChatWith == null) render(); }
    @Override public void onHeadsChanged() { if (mChatWith == null) render(); }

    /** The player's head icon (launcher skin shared through CS Friends), Steve until known. */
    private ImageView headView(@Nullable String name, int sizeDp) {
        ImageView iv = new ImageView(mCtx);
        android.graphics.Bitmap b = com.craftstudio.cslauncher.friends.RemoteSkins.head(name);
        if (b != null) iv.setImageBitmap(b); else iv.setImageResource(R.drawable.ic_default_head);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.rightMargin = dp(10);
        iv.setLayoutParams(lp);
        return iv;
    }

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
                TextView signIn = button(mCtx.getString(R.string.friends_sign_in), R.drawable.bg_vl_btn_primary, v -> AccountDialog.show(mHost.activity(), false, this::render));
                TextView create = button(mCtx.getString(R.string.friends_create_account), R.drawable.bg_vl_btn_ghost, v -> AccountDialog.show(mHost.activity(), true, this::render));
                // Two equal columns: the labels never clip on narrow panels (the ghost button used to)
                for (TextView b : new TextView[]{signIn, create}) {
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                    b.setLayoutParams(blp);
                    b.setPadding(dp(8), 0, dp(8), 0);
                    b.setMaxLines(1);
                    b.setEllipsize(TextUtils.TruncateAt.END);
                    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                }
                row.addView(signIn);
                row.addView(gap());
                row.addView(create);
                card.addView(row);
            }
            mContent.addView(card);
            return;
        }
        LinearLayout top = hrow();
        com.craftstudio.cslauncher.authenticator.accounts.Account me = com.craftstudio.cslauncher.authenticator.accounts.Accounts.getCurrent();
        android.graphics.Bitmap myFace = me == null ? null : me.getSkinFace();
        ImageView myHead = headView(null, 36);
        if (myFace != null) myHead.setImageBitmap(myFace);
        top.addView(myHead);
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
            renderHostSettings();
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
                for (String line : p.recentRefusals()) card.addView(statusLine(mCtx.getString(R.string.host_refused, line), R.color.vl_warn));
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
        renderHostSettings();
    }

    /**
     * World hosting settings (Java worlds through e4mc): who can join, the player limit and the
     * relay. Live while a world is published; otherwise the values the next publish starts with.
     */
    private void renderHostSettings() {
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.host_settings_title)));
        // Who can join.
        int access = HostSettings.access();
        LinearLayout accessRow = hrow();
        LinearLayout accessText = vcol();
        accessText.addView(strong(mCtx.getString(R.string.host_access)));
        accessText.addView(dim(HostSettings.accessLabel(access)));
        accessRow.addView(accessText);
        accessRow.addView(gap());
        accessRow.addView(smallButton(mCtx.getString(R.string.host_change), R.drawable.bg_vl_btn_ghost, v -> {
            HostSettings.setAccess((HostSettings.access() + 1) % 3);
            render();
        }));
        card.addView(accessRow);
        if (access == HostSettings.ACCESS_INVITED) {
            int n = HostSettings.invitedCount();
            card.addView(dim(n == 0 ? mCtx.getString(R.string.host_invited_none) : mCtx.getString(R.string.host_invited_count, n)));
        } else if (access == HostSettings.ACCESS_FRIENDS) {
            card.addView(dim(mCtx.getString(R.string.host_friends_hint)));
        }
        // Player limit (the Java integrated server stops at 8).
        LinearLayout playersRow = hrow();
        LinearLayout playersText = vcol();
        playersText.addView(strong(mCtx.getString(R.string.host_players, HostSettings.maxPlayersLabel())));
        playersText.addView(dim(mCtx.getString(R.string.host_players_java_cap)));
        playersRow.addView(playersText);
        playersRow.addView(gap());
        playersRow.addView(smallButton("−", R.drawable.bg_vl_btn_ghost, v -> { HostSettings.setMaxPlayers(HostSettings.stepMaxPlayers(-1)); render(); }));
        playersRow.addView(gap());
        playersRow.addView(smallButton("+", R.drawable.bg_vl_btn_ghost, v -> { HostSettings.setMaxPlayers(HostSettings.stepMaxPlayers(1)); render(); }));
        card.addView(playersRow);
        // Server icon (server-icon.png in the game folder, written at game start).
        LinearLayout iconRow = hrow();
        LinearLayout iconText = vcol();
        iconText.addView(strong(mCtx.getString(R.string.host_icon)));
        iconText.addView(dim(HostSettings.iconLabel(HostSettings.icon()) + (HostSettings.icon() == HostSettings.ICON_CUSTOM ? "\n" + mCtx.getString(R.string.host_icon_custom_hint) : "")));
        iconRow.addView(iconText);
        iconRow.addView(gap());
        iconRow.addView(smallButton(mCtx.getString(R.string.host_change), R.drawable.bg_vl_btn_ghost, v -> {
            HostSettings.setIcon((HostSettings.icon() + 1) % 3);
            render();
        }));
        card.addView(iconRow);
        // Cheats: the game decides at Open to LAN.
        card.addView(strong(mCtx.getString(R.string.host_cheats)));
        card.addView(dim(mCtx.getString(R.string.host_cheats_java)));
        // The relay.
        card.addView(strong(mCtx.getString(R.string.host_relay)));
        LinearLayout relayRow = hrow();
        relayRow.setPadding(0, dp(2), 0, 0);
        EditText relay = new EditText(mCtx);
        relay.setHint(R.string.host_relay_hint);
        relay.setSingleLine(true);
        relay.setText(HostSettings.relay());
        relay.setBackgroundResource(R.drawable.bg_vl_field);
        relay.setTextColor(color(R.color.vl_text));
        relay.setHintTextColor(color(R.color.vl_text_faint));
        relay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        relay.setPadding(dp(14), 0, dp(14), 0);
        relay.setImeOptions(EditorInfo.IME_ACTION_DONE);
        relay.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        relay.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        relayRow.addView(relay);
        relayRow.addView(gap());
        relayRow.addView(button(mCtx.getString(R.string.host_save), R.drawable.bg_vl_btn_primary, v -> {
            String text = relay.getText().toString().trim();
            hideKeyboard(relay);
            if (!text.isEmpty() && !isHostPort(text)) {
                toast(R.string.host_relay_bad);
                return;
            }
            HostSettings.setRelay(text);
            toast(mHost.inGame() && E4mcPublisher.get().isActive() ? R.string.host_relay_saved_next : R.string.host_saved);
            render();
        }));
        card.addView(relayRow);
        card.addView(dim(mCtx.getString(R.string.host_relay_note)));
        mContent.addView(card);
    }

    private static boolean isHostPort(String text) {
        int i = text.lastIndexOf(':');
        if (i <= 0 || i == text.length() - 1) return false;
        try {
            int port = Integer.parseInt(text.substring(i + 1));
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void renderInvites() {
        List<FriendsClient.Invite> invites = mClient.openInvites();
        if (invites.isEmpty()) return;
        LinearLayout card = card();
        card.addView(heading(mCtx.getString(R.string.friends_invites)));
        for (FriendsClient.Invite v : invites) {
            LinearLayout row = listRow();
            row.addView(headView(v.from, 28));
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
                    JoinWorld.join(mHost.activity(), v.address, v.engine, v.version);
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
            row.addView(headView(q.name, 28));
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
            row.addView(headView(f.name, 28));
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
                row.addView(smallButton(mCtx.getString(R.string.friends_join), R.drawable.bg_friends_btn_join, b -> JoinWorld.join(mHost.activity(), f.address, f.engine, f.version)));
                row.addView(gap());
            }
            if (hosting && f.online()) {
                row.addView(smallButton(mCtx.getString(R.string.friends_invite), R.drawable.bg_vl_btn_primary, b -> act(() -> mClient.invite(f.id, p.address(), null, "java", e -> {
                    done(e);
                    if (e == null) { HostSettings.noteInvited(f.name); toast(R.string.friends_invite_sent); }
                }))));
                row.addView(gap());
            }
            int unread = mClient.unreadMessagesFrom(f.id);
            TextView chat = smallButton(mCtx.getString(R.string.friends_chat) + (unread > 0 ? " (" + unread + ")" : ""), R.drawable.bg_vl_btn_ghost, b -> openChat(f.id, f.name));
            if (unread > 0) chat.setTextColor(color(R.color.vl_ok));
            row.addView(chat);
            row.addView(gap());
            row.addView(pinButton(f.id));
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
        com.craftstudio.cslauncher.friends.FriendsNotifier.setOpenChat(id);
        mLastChatCount = -1;
        setReply(null);
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
        com.craftstudio.cslauncher.friends.FriendsNotifier.setOpenChat(null);
        setReply(null);
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
        int maxWidth = (int) (mRoot.getWidth() * 0.78f) > 0 ? (int) (mRoot.getWidth() * 0.78f) : dp(280);
        for (FriendsClient.Message m : msgs) mContent.addView(bubble(m, maxWidth), bubbleParams(m));
        if (msgs.isEmpty()) mContent.addView(body(mCtx.getString(R.string.friends_chat_hint)));
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout.LayoutParams bubbleParams(FriendsClient.Message m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = m.mine ? Gravity.END : Gravity.START;
        lp.topMargin = dp(4);
        return lp;
    }

    /** One message: optional quoted reply, optional photo, text. Long-press → Copy / Reply (/ Save). */
    private View bubble(FriendsClient.Message m, int maxWidth) {
        LinearLayout box = new LinearLayout(mCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(m.mine ? R.drawable.bg_friends_bubble_out : R.drawable.bg_friends_bubble_in);
        box.setPadding(dp(10), dp(6), dp(10), dp(7));
        int textColor = color(m.mine ? R.color.vl_platinum_text : R.color.vl_text);
        if (m.replyTo != null) {
            LinearLayout quote = new LinearLayout(mCtx);
            quote.setOrientation(LinearLayout.VERTICAL);
            quote.setBackgroundResource(R.drawable.bg_vl_pill);
            quote.setPadding(dp(10), dp(5), dp(10), dp(5));
            TextView who = new TextView(mCtx);
            who.setText(replyAuthor(m.replyFrom));
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            who.setTypeface(null, Typeface.BOLD);
            who.setTextColor(color(R.color.vl_text));
            TextView what = new TextView(mCtx);
            what.setText(m.replyBody == null || m.replyBody.isEmpty() ? mCtx.getString(R.string.chat_photo) : m.replyBody);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            what.setMaxLines(2);
            what.setEllipsize(TextUtils.TruncateAt.END);
            what.setTextColor(color(R.color.vl_text_dim));
            quote.addView(who);
            quote.addView(what);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.bottomMargin = dp(6);
            box.addView(quote, qlp);
            quote.setOnClickListener(v -> scrollToMessage(m.replyTo));
        }
        if (m.image != null) {
            ImageView iv = new ImageView(mCtx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_vl_pill);
            iv.setAdjustViewBounds(true);
            int w = Math.min(maxWidth - dp(20), dp(240));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(w, (int) (w * 0.62f));
            ilp.bottomMargin = m.hasText() ? dp(6) : 0;
            box.addView(iv, ilp);
            com.craftstudio.cslauncher.friends.ChatMedia.into(m.image, iv);
            iv.setOnClickListener(v -> showPhoto(m.image));
            iv.setOnLongClickListener(v -> { showMessageMenu(m, v); return true; });
        }
        if (m.isInvite()) {
            // A world invite in the chat: who / which world, and Join while it is still good
            TextView title = new TextView(mCtx);
            String world = m.inviteWorld == null || m.inviteWorld.isEmpty() ? mCtx.getString(R.string.friends_a_world) : m.inviteWorld;
            title.setText(m.mine ? mCtx.getString(R.string.chat_invite_sent, world) : mCtx.getString(R.string.chat_invite_received, world));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(textColor);
            title.setMaxWidth(maxWidth - dp(20));
            box.addView(title);
            TextView sub = new TextView(mCtx);
            String engine = isHorizon(m.inviteEngine) ? "CS Engine" : (m.inviteVersion == null || m.inviteVersion.isEmpty() ? "Java" : "Java " + m.inviteVersion);
            boolean live = m.inviteLive(System.currentTimeMillis());
            sub.setText(live ? engine : mCtx.getString(R.string.chat_invite_expired, engine));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(m.mine ? color(R.color.vl_platinum_text) : color(R.color.vl_text_dim));
            sub.setAlpha(m.mine ? 0.72f : 1f);
            box.addView(sub);
            if (!m.mine && live) {
                TextView join = smallButton(mCtx.getString(R.string.friends_join), R.drawable.bg_friends_btn_join,
                        v -> JoinWorld.join(mHost.activity(), m.inviteAddress, m.inviteEngine == null ? "java" : m.inviteEngine, m.inviteVersion));
                LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
                jlp.topMargin = dp(8);
                box.addView(join, jlp);
            }
        } else if (m.hasText()) {
            TextView t = new TextView(mCtx);
            t.setText(m.body);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(textColor);
            t.setMaxWidth(maxWidth - dp(20));
            box.addView(t);
        }
        box.setOnLongClickListener(v -> { showMessageMenu(m, v); return true; });
        box.setMinimumWidth(dp(48));
        return box;
    }

    /**
     * Pin: this friend's messages become phone notifications (invites and requests always do).
     * Shown as a small pin that is filled when active.
     */
    private TextView pinButton(String friendId) {
        boolean pinned = com.craftstudio.cslauncher.friends.FriendsNotifier.isPinned(friendId);
        TextView pin = smallButton(pinned ? "\uD83D\uDCCC" : "\uD83D\uDCCD", R.drawable.bg_vl_btn_ghost, null);
        pin.setPadding(dp(8), 0, dp(8), 0);
        pin.setAlpha(pinned ? 1f : 0.55f);
        pin.setContentDescription(mCtx.getString(pinned ? R.string.friends_unpin : R.string.friends_pin));
        pin.setOnClickListener(v -> {
            boolean now = !com.craftstudio.cslauncher.friends.FriendsNotifier.isPinned(friendId);
            com.craftstudio.cslauncher.friends.FriendsNotifier.setPinned(friendId, now);
            if (now) com.craftstudio.cslauncher.friends.FriendsNotifier.ensurePermission(mHost.activity());
            toast(now ? R.string.friends_pinned : R.string.friends_unpinned);
            if (Motion.buttons(v)) {
                v.animate().rotation(now ? -20f : 0f).scaleX(1.15f).scaleY(1.15f).setDuration(90).withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Motion.SPRING).start()).start();
            }
            render();
        });
        return pin;
    }

    private String replyAuthor(@Nullable String id) {
        if (id == null) return "";
        if (id.equals(CsAccount.id())) return mCtx.getString(R.string.friends_you);
        FriendsClient.Friend f = mClient.snapshot().friend(id);
        return f != null ? f.name : (mChatName == null ? "" : mChatName);
    }

    private void scrollToMessage(String id) {
        List<FriendsClient.Message> msgs = mClient.conversation(mChatWith);
        for (int i = 0; i < msgs.size() && i < mContent.getChildCount(); i++) {
            if (id.equals(msgs.get(i).id)) {
                View v = mContent.getChildAt(i);
                mScroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(24)));
                if (Motion.content(mRoot)) v.animate().alpha(0.4f).setDuration(120).withEndAction(() -> v.animate().alpha(1f).setDuration(240).start()).start();
                return;
            }
        }
    }

    private void showMessageMenu(FriendsClient.Message m, View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(mCtx, anchor, Gravity.END);
        final int COPY = 1, REPLY = 2, SAVE = 3;
        if (m.hasText()) menu.getMenu().add(0, COPY, 0, R.string.chat_copy);
        menu.getMenu().add(0, REPLY, 1, R.string.chat_reply);
        if (m.image != null) menu.getMenu().add(0, SAVE, 2, R.string.chat_save_photo);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case COPY: copy(m.body, R.string.chat_copied); return true;
                case REPLY: setReply(m); return true;
                case SAVE:
                    if (m.image != null) com.craftstudio.cslauncher.friends.ChatMedia.save(mCtx, m.image, (url, err) ->
                            toast(err == null ? R.string.chat_photo_saved : R.string.chat_photo_failed));
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void setReply(@Nullable FriendsClient.Message m) {
        mReplyTo = m;
        if (m == null) { mReplyBar.setVisibility(View.GONE); return; }
        mReplyTitle.setText(mCtx.getString(R.string.chat_replying_to, m.mine ? mCtx.getString(R.string.friends_you) : (mChatName == null ? "" : mChatName)));
        mReplyBody.setText(m.hasText() ? m.body : mCtx.getString(R.string.chat_photo));
        if (mReplyBar.getVisibility() != View.VISIBLE) {
            mReplyBar.setVisibility(View.VISIBLE);
            if (Motion.content(mRoot)) {
                mReplyBar.setAlpha(0f);
                mReplyBar.setTranslationY(dp(8));
                mReplyBar.animate().alpha(1f).translationY(0f).setDuration(Motion.SHORT).setInterpolator(Motion.ENTER).start();
            }
        }
        mInput.requestFocus();
    }

    /** Full-size view of a received photo. */
    private void showPhoto(String url) {
        ImageView iv = new ImageView(mCtx);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(0xFF0B0B0E);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        com.craftstudio.cslauncher.friends.ChatMedia.into(url, iv);
        AlertDialog d = new AlertDialog.Builder(mHost.activity()).setView(iv)
                .setPositiveButton(R.string.chat_save_photo, (dlg, w) -> com.craftstudio.cslauncher.friends.ChatMedia.save(mCtx, url, (u, err) ->
                        toast(err == null ? R.string.chat_photo_saved : R.string.chat_photo_failed)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.show();
        iv.setOnClickListener(v -> d.dismiss());
    }

    private void sendChat() {
        if (mChatWith == null) return;
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        mInput.setText("");
        final String to = mChatWith;
        final String reply = mReplyTo == null ? null : mReplyTo.id;
        setReply(null);
        mClient.send(to, text, reply, null, e -> {
            if (e != null) toast(e);
        });
    }

    // ------------------------------------------------------------------ photos

    private void showAttachMenu() {
        if (mChatWith == null || mSendingPhoto) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(mCtx, mAttach, Gravity.START);
        menu.getMenu().add(0, 1, 0, R.string.chat_attach_screenshots);
        menu.getMenu().add(0, 2, 1, R.string.chat_attach_gallery);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showScreenshotPicker(); else pickFromGallery();
            return true;
        });
        menu.show();
    }

    private void pickFromGallery() {
        android.content.Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        } else {
            intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        sActive = new java.lang.ref.WeakReference<>(this);
        try {
            mHost.activity().startActivityForResult(intent, REQ_PHOTO);
        } catch (android.content.ActivityNotFoundException e) {
            toast(R.string.chat_photo_failed);
        }
    }

    /** Activities forward {@code onActivityResult} here; true when consumed. */
    public static boolean onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        if (requestCode != REQ_PHOTO) return false;
        FriendsPanel p = sActive == null ? null : sActive.get();
        sActive = null;
        if (p == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        p.sendPhoto(data.getData(), null);
        return true;
    }

    private void showScreenshotPicker() {
        final List<java.io.File> shots = com.craftstudio.cslauncher.friends.ChatMedia.screenshots(60);
        if (shots.isEmpty()) { toast(R.string.chat_no_screenshots); return; }
        final int cell = dp(96);
        final int columns = Math.max(3, Math.min(6, (mRoot.getWidth() > 0 ? mRoot.getWidth() : dp(360)) / (cell + dp(8))));
        android.widget.GridView grid = new android.widget.GridView(mCtx);
        grid.setNumColumns(columns);
        grid.setColumnWidth(cell);
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(dp(12), dp(8), dp(12), dp(8));
        grid.setClipToPadding(false);
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return shots.size(); }
            @Override public Object getItem(int i) { return shots.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                ImageView iv = convert instanceof ImageView ? (ImageView) convert : new ImageView(mCtx);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(cell, (int) (cell * 0.62f)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_vl_pill);
                iv.setImageDrawable(null);
                final java.io.File f = shots.get(i);
                iv.setTag(f);
                com.craftstudio.cslauncher.friends.ChatMedia.thumbnailAsync(f, cell, b -> { if (f.equals(iv.getTag()) && b != null) iv.setImageBitmap(b); });
                return iv;
            }
        });
        AlertDialog d = new AlertDialog.Builder(mHost.activity())
                .setTitle(R.string.chat_attach_screenshots)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> { d.dismiss(); sendPhoto(null, shots.get(position)); });
        d.show();
    }

    private void sendPhoto(@Nullable android.net.Uri uri, @Nullable java.io.File file) {
        if (mChatWith == null || mSendingPhoto) return;
        final String to = mChatWith;
        final String reply = mReplyTo == null ? null : mReplyTo.id;
        final String caption = mInput.getText().toString().trim();
        mSendingPhoto = true;
        mAttach.setAlpha(0.4f);
        toast(R.string.chat_sending_photo);
        com.craftstudio.cslauncher.friends.ChatMedia.Callback cb = (url, err) -> {
            mSendingPhoto = false;
            mAttach.setAlpha(1f);
            if (url == null) { toast("unreadable".equals(err) ? R.string.chat_photo_too_large : R.string.chat_photo_failed); return; }
            mInput.setText("");
            setReply(null);
            mClient.send(to, caption, reply, url, e -> { if (e != null) toast(e); });
        };
        if (uri != null) com.craftstudio.cslauncher.friends.ChatMedia.send(mCtx, uri, cb);
        else if (file != null) com.craftstudio.cslauncher.friends.ChatMedia.send(file, cb);
        else { mSendingPhoto = false; mAttach.setAlpha(1f); }
    }

    // ------------------------------------------------------------------ actions

    private void inviteAll() {
        E4mcPublisher p = E4mcPublisher.get();
        if (!p.isPublished()) return;
        act(() -> mClient.invite(null, p.address(), null, "java", e -> {
            done(e);
            if (e == null) { HostSettings.noteInvitedAll(); toast(R.string.friends_invite_sent); }
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
        // Platinum buttons take dark text; every other button is light text on a raised tile
        boolean platinum = bg == R.drawable.bg_vl_btn_primary;
        t.setTextColor(color(platinum ? R.color.vl_platinum_text : R.color.vl_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackgroundResource(bg);
        t.setMinWidth(dp(44));
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(t);
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
