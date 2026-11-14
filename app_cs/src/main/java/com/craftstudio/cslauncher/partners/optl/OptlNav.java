package com.craftstudio.cslauncher.partners.optl;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;

/** Opens OPTL screens in the home right pane when there is one, full screen otherwise; back pops the same way. */
public final class OptlNav {
    private OptlNav() {}

    public static void open(@NonNull Fragment from, @NonNull Class<? extends Fragment> cls, @NonNull String tag, @Nullable Bundle args) {
        Fragment parent = from.getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openPane(cls, tag, args);
        } else if (from.getActivity() != null) {
            Tools.swapFragment(from.requireActivity(), cls, tag, args);
        }
    }

    public static void back(@NonNull Fragment from) {
        Fragment parent = from.getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).popRightPane();
        } else if (from.getActivity() != null) {
            from.requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }
}
