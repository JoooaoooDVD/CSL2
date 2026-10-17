package com.craftstudio.cslauncher.multiplayer.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;

/** Launcher screen: "Virtual LAN Multiplayer" (right pane on wide layouts, full screen on phones) */
public class VirtualLanFragment extends Fragment implements VirtualLanPanel.Host {
    public static final String TAG = "VirtualLanFragment";
    @Nullable private VirtualLanPanel mPanel;

    public VirtualLanFragment() {
        super(R.layout.fragment_virtual_lan);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mPanel = new VirtualLanPanel(view.findViewById(R.id.vl_panel), this);
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
