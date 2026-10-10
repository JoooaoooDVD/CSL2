package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;

import com.craftstudio.cslauncher.utils.CSAnimationEngine;

public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        CSAnimationEngine.animateDialogContentView(view.findViewById(R.id.select_auth_banner));
        CSAnimationEngine.staggerChildren(view.findViewById(R.id.select_auth_cards_container));

        View mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        View mLocalButton = view.findViewById(R.id.button_local_authentication);
        View mElyByButton = view.findViewById(R.id.button_elyby_authentication);

        CSAnimationEngine.attachPressFeedback(mMicrosoftButton);
        CSAnimationEngine.attachPressFeedback(mLocalButton);
        CSAnimationEngine.attachPressFeedback(mElyByButton);

        mMicrosoftButton.setOnClickListener(v -> launchAuthFragment(MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG));
        mLocalButton.setOnClickListener(v -> launchAuthFragment(LocalLoginFragment.class, LocalLoginFragment.TAG));
        mElyByButton.setOnClickListener(v -> launchAuthFragment(ElyByLoginFragment.class, ElyByLoginFragment.TAG));
    }

    private void launchAuthFragment(Class<? extends  Fragment> fragmentClass, String fragmentTag) {
        if(ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        Tools.swapFragment(requireActivity(), fragmentClass, fragmentTag, null);
    }
}
