package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.about.PolicyBinder;

/** Read-only privacy policy & terms, opened from the About page */
public class PolicyFragment extends Fragment {
    public static final String TAG = "PolicyFragment";

    public PolicyFragment() {
        super(R.layout.fragment_policy);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        PolicyBinder.bind(view, false, () -> {
            if (getActivity() != null) Tools.removeCurrentFragment(requireActivity());
        });
    }
}
