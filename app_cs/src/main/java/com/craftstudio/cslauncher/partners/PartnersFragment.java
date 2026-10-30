package com.craftstudio.cslauncher.partners;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/** "Official Partners" list: every {@link Partner} from {@link Partners}, featured one first. */
public class PartnersFragment extends Fragment {
    public static final String TAG = "PartnersFragment";

    public PartnersFragment() {
        super(R.layout.fragment_partners);
    }

    /** Opens the list in the home right pane when available, otherwise full screen */
    public static void open(@NonNull FragmentActivity activity) {
        if (activity.isFinishing()) return;
        Tools.swapFragment(activity, PartnersFragment.class, TAG, null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.ps_back).setOnClickListener(v -> goBack());
        List<Partner> partners = Partners.all();
        ((TextView) view.findViewById(R.id.ps_count)).setText(getString(R.string.partners_count_fmt, partners.size()));

        LinearLayout list = view.findViewById(R.id.ps_list);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        boolean first = true;
        for (Partner p : partners) {
            View card = inflater.inflate(R.layout.item_partner, list, false);
            ((ImageView) card.findViewById(R.id.pi_logo)).setImageResource(p.logo);
            card.findViewById(R.id.pi_logo_wrap).setClipToOutline(true);
            ((TextView) card.findViewById(R.id.pi_badge)).setText(p.badge);
            card.findViewById(R.id.pi_featured).setVisibility(first ? View.VISIBLE : View.GONE);
            ((TextView) card.findViewById(R.id.pi_title)).setText(getString(R.string.partner_cross_fmt, getString(p.name)));
            ((TextView) card.findViewById(R.id.pi_subtitle)).setText(p.fullName);
            ((TextView) card.findViewById(R.id.pi_description)).setText(p.description);
            card.setClipToOutline(true);
            card.setOnClickListener(v -> openPartner(p));
            CSAnimationEngine.attachPressFeedback(card);
            list.addView(card);
            first = false;
        }

        Motion.riseIn(view.findViewById(R.id.ps_banner), 0L, 8f);
        Motion.popIn(view.findViewById(R.id.ps_icon_box), 120L, 0.7f);
        Motion.cascadeChildren(list, 90L, 70L, 16f);
    }

    private void openPartner(@NonNull Partner partner) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openPane(PartnerFragment.class, PartnerFragment.TAG, PartnerFragment.args(partner));
        } else if (getActivity() != null) {
            Tools.swapFragment(requireActivity(), PartnerFragment.class, PartnerFragment.TAG, PartnerFragment.args(partner));
        }
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
