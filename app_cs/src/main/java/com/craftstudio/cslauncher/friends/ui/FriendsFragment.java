package com.craftstudio.cslauncher.friends.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;

/** Launcher screen: CS Friends (right pane on wide layouts, full screen on phones). */
public class FriendsFragment extends Fragment implements FriendsPanel.Host {
    public static final String TAG = "FriendsFragment";
    /** Optional argument: open the chat with this friend id right away. */
    public static final String ARG_CHAT = "chat_with";

    @Nullable private FriendsPanel mPanel;

    public FriendsFragment() {
        super(R.layout.fragment_friends);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mPanel = new FriendsPanel(view.findViewById(R.id.fp_root), this);
        Bundle args = getArguments();
        if (args != null && args.getString(ARG_CHAT) != null && savedInstanceState == null) {
            mPanel.openChat(args.getString(ARG_CHAT));
        }
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mPanel != null && mPanel.onBackPressed()) return;
                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mPanel != null) mPanel.onShow();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mPanel != null) mPanel.onHide();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mPanel = null;
    }

    @NonNull @Override public Activity activity() { return requireActivity(); }
    @Override public boolean inGame() { return false; }
    @Override public void onCloseRequested() { requireActivity().getOnBackPressedDispatcher().onBackPressed(); }
}
