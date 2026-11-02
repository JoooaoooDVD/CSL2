package com.craftstudio.cslauncher.multiplayer.friends;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

/** CS Friends page: friend code, add friends, invites, requests, the friends list and the world MOTD. */
public class FriendsFragment extends Fragment implements FriendsService.Listener {
    public static final String TAG = "FriendsFragment";

    private TextView mMyCode, mMyName, mStatus, mOnlineCount, mInvitesTitle, mRequestsTitle, mEmpty;
    private LinearLayout mInvites, mRequests, mList;
    private EditText mAddCode, mMotd;
    private FriendsService mService;

    public FriendsFragment() {
        super(R.layout.fragment_friends);
    }

    public static void open(@NonNull FragmentActivity activity) {
        if (activity.isFinishing()) return;
        Tools.swapFragment(activity, FriendsFragment.class, TAG, null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mService = FriendsService.get(view.getContext());
        view.findViewById(R.id.fr_back).setOnClickListener(v -> goBack());
        mMyCode = view.findViewById(R.id.fr_my_code);
        mMyName = view.findViewById(R.id.fr_my_name);
        mStatus = view.findViewById(R.id.fr_status);
        mOnlineCount = view.findViewById(R.id.fr_online_count);
        mInvitesTitle = view.findViewById(R.id.fr_invites_title);
        mRequestsTitle = view.findViewById(R.id.fr_requests_title);
        mEmpty = view.findViewById(R.id.fr_empty);
        mInvites = view.findViewById(R.id.fr_invites);
        mRequests = view.findViewById(R.id.fr_requests);
        mList = view.findViewById(R.id.fr_list);
        mAddCode = view.findViewById(R.id.fr_add_code);
        mMotd = view.findViewById(R.id.fr_motd);

        String code = mService.code();
        mMyCode.setText(code == null ? "CS-······" : code);
        mMyName.setText(getString(R.string.friends_my_name_fmt, FriendsService.displayName()));
        mOnlineCount.setText(getString(R.string.friends_online_count, 0));
        setStatus(getString(R.string.friends_connecting));

        View copy = view.findViewById(R.id.fr_copy);
        View share = view.findViewById(R.id.fr_share);
        View add = view.findViewById(R.id.fr_add);
        CSAnimationEngine.attachPressFeedback(copy);
        CSAnimationEngine.attachPressFeedback(share);
        CSAnimationEngine.attachPressFeedback(add);
        copy.setOnClickListener(v -> {
            String c = mService.code();
            if (c == null) return;
            ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CS friend code", c));
            Toast.makeText(v.getContext(), R.string.friends_copied, Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            String c = mService.code();
            if (c == null) return;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, getString(R.string.friends_share_text, c));
            try { startActivity(Intent.createChooser(i, getString(R.string.friends_share))); } catch (Throwable ignored) {}
        });
        add.setOnClickListener(v -> addFriend());
        mAddCode.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { addFriend(); return true; }
            return false;
        });

        String motd = mService.motd();
        if (motd != null) mMotd.setText(motd);
        mMotd.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                mService.setMotd(t.isEmpty() ? null : t);
            }
        });

        Motion.riseIn(view.findViewById(R.id.fr_banner), 0L, 8f);
        Motion.popIn(view.findViewById(R.id.fr_icon_box), 120L, 0.7f);
        Motion.riseIn(view.findViewById(R.id.fr_me_card), 60L, 10f);
    }

    @Override
    public void onStart() {
        super.onStart();
        mService.addListener(this);
        mService.refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        mService.removeListener(this);
    }

    private void addFriend() {
        String code = mAddCode.getText().toString().trim().toUpperCase();
        if (code.isEmpty()) return;
        if (!code.startsWith("CS-") && code.length() == 6) code = "CS-" + code;
        mAddCode.setEnabled(false);
        final String fcode = code;
        mService.addFriend(fcode, (error, info) -> {
            if (!isAdded()) return;
            mAddCode.setEnabled(true);
            if (error != null) {
                int res = "no_such_code".equals(error) ? R.string.friends_no_such_code
                        : "self".equals(error) ? R.string.friends_self : 0;
                toast(res != 0 ? getString(res) : getString(R.string.friends_error_fmt, error));
                return;
            }
            mAddCode.setText("");
            String state = info == null ? "requested" : info.substring(0, Math.max(0, info.indexOf('|')));
            String name = info == null || info.indexOf('|') < 0 ? "" : info.substring(info.indexOf('|') + 1);
            if ("accepted".equals(state)) toast(getString(R.string.friends_now_friends_fmt, name));
            else if ("already".equals(state)) toast(getString(R.string.friends_already_fmt, name));
            else toast(getString(R.string.friends_request_sent_fmt, name));
        });
    }

    // ---- FriendsService.Listener ----

    @Override
    public void onFriendsUpdated(@NonNull FriendsApi.Snapshot s) {
        if (!isAdded()) return;
        if (s.myCode != null && !s.myCode.isEmpty()) mMyCode.setText(s.myCode);
        setStatus(null);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        mInvites.removeAllViews();
        for (final FriendsApi.Invite in : s.invites) {
            View row = inflater.inflate(R.layout.item_friend, mInvites, false);
            dot(row, "#5EC8FF");
            ((TextView) row.findViewById(R.id.fi_name)).setText(in.from);
            ((TextView) row.findViewById(R.id.fi_sub)).setText(in.world == null || in.world.isEmpty()
                    ? in.address : in.world + " · " + in.address);
            TextView join = row.findViewById(R.id.fi_primary);
            join.setVisibility(View.VISIBLE);
            join.setText(R.string.friends_join);
            join.setOnClickListener(v -> FriendsJoin.join(requireActivity(), in.address));
            mInvites.addView(row);
        }
        mInvitesTitle.setVisibility(s.invites.isEmpty() ? View.GONE : View.VISIBLE);

        mRequests.removeAllViews();
        for (final FriendsApi.Request r : s.requests) {
            View row = inflater.inflate(R.layout.item_friend, mRequests, false);
            dot(row, r.incoming ? "#F2C94C" : "#5C5C66");
            ((TextView) row.findViewById(R.id.fi_name)).setText(r.name);
            ((TextView) row.findViewById(R.id.fi_sub)).setText(r.code);
            TextView primary = row.findViewById(R.id.fi_primary);
            TextView secondary = row.findViewById(R.id.fi_secondary);
            if (r.incoming) {
                primary.setVisibility(View.VISIBLE);
                primary.setText(R.string.friends_accept);
                primary.setOnClickListener(v -> mService.respond(r.id, true, (e, i) -> { if (e != null) toast(getString(R.string.friends_error_fmt, e)); }));
                secondary.setVisibility(View.VISIBLE);
                secondary.setText(R.string.friends_decline);
                secondary.setOnClickListener(v -> mService.respond(r.id, false, (e, i) -> {}));
            } else {
                secondary.setVisibility(View.VISIBLE);
                secondary.setText(R.string.friends_cancel);
                secondary.setOnClickListener(v -> mService.respond(r.id, false, (e, i) -> {}));
                ((TextView) row.findViewById(R.id.fi_sub)).setText(getString(R.string.friends_sent) + " · " + r.code);
            }
            mRequests.addView(row);
        }
        mRequestsTitle.setVisibility(s.requests.isEmpty() ? View.GONE : View.VISIBLE);

        mList.removeAllViews();
        int online = 0;
        for (final FriendsApi.Friend f : s.friends) {
            if (f.online()) online++;
            View row = inflater.inflate(R.layout.item_friend, mList, false);
            dot(row, f.hosting() ? "#5EC8FF" : "in_game".equals(f.status) ? "#7FD4A8" : f.online() ? "#4CC38A" : "#5C5C66");
            ((TextView) row.findViewById(R.id.fi_name)).setText(f.name);
            String sub;
            if (f.hosting()) sub = getString(R.string.friends_status_hosting_fmt, f.world == null || f.world.isEmpty() ? f.address : f.world);
            else if ("in_game".equals(f.status)) sub = getString(R.string.friends_status_in_game);
            else if (f.online()) sub = getString(R.string.friends_status_online);
            else sub = getString(R.string.friends_status_offline);
            ((TextView) row.findViewById(R.id.fi_sub)).setText(sub + " · " + f.code);
            if (f.hosting()) {
                TextView join = row.findViewById(R.id.fi_primary);
                join.setVisibility(View.VISIBLE);
                join.setText(R.string.friends_join);
                join.setOnClickListener(v -> FriendsJoin.join(requireActivity(), f.address));
            }
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.friends_remove_title)
                        .setMessage(getString(R.string.friends_remove_msg_fmt, f.name))
                        .setPositiveButton(R.string.friends_remove, (d, w) -> mService.removeFriend(f.id, (e, i) -> {}))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            mList.addView(row);
        }
        mEmpty.setVisibility(s.friends.isEmpty() ? View.VISIBLE : View.GONE);
        mOnlineCount.setText(getString(R.string.friends_online_count, online));
    }

    @Override
    public void onInvite(@NonNull FriendsApi.Invite invite) {
        // The list above already shows it with a Join button; the launcher activity shows the popup.
    }

    @Override
    public void onFriendsError(@NonNull String message) {
        if (!isAdded()) return;
        setStatus(getString(R.string.friends_error_fmt, message));
    }

    private static void dot(View row, String color) {
        View dot = row.findViewById(R.id.fi_dot);
        dot.getBackground().mutate().setColorFilter(android.graphics.Color.parseColor(color), android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private void setStatus(@Nullable String text) {
        mStatus.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text != null) mStatus.setText(text);
    }

    private void toast(String text) {
        if (getContext() != null) Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void goBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).popRightPane();
        } else if (getActivity() != null) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }
}
