package com.craftstudio.cslauncher.partners;

import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.MainMenuFragment;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Generic "Official Partner" screen (right pane). Shows whichever {@link Partner} id it is given
 * (default: the featured one): partnership branding, the exclusive benefits with the highlighted
 * one on top, and the join button that opens the partner's invite link.
 */
public class PartnerFragment extends Fragment {
    public static final String TAG = "PartnerFragment";
    public static final String ARG_PARTNER_ID = "partner_id";

    public PartnerFragment() {
        super(R.layout.fragment_partner);
    }

    @NonNull
    public static Bundle args(@NonNull Partner partner) {
        Bundle b = new Bundle();
        b.putString(ARG_PARTNER_ID, partner.id);
        return b;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Partner partner = Partners.byId(getArguments() == null ? null : getArguments().getString(ARG_PARTNER_ID));
        if (partner == null) partner = Partners.featured();
        view.findViewById(R.id.pn_back).setOnClickListener(v -> goBack());
        if (partner == null) {
            goBack();
            return;
        }
        final Partner p = partner;
        String name = getString(p.name);
        String cross = getString(R.string.partner_cross_fmt, name);

        ((TextView) view.findViewById(R.id.pn_banner_title)).setText(p.sectionTitle);
        ((TextView) view.findViewById(R.id.pn_banner_subtitle)).setText(p.fullName);
        ((TextView) view.findViewById(R.id.pn_banner_badge)).setText(p.badge);
        ((ImageView) view.findViewById(R.id.pn_logo)).setImageResource(p.logo);
        view.findViewById(R.id.pn_logo_wrap).setClipToOutline(true);
        view.findViewById(R.id.pn_logo_cs_wrap).setClipToOutline(true);
        ((TextView) view.findViewById(R.id.pn_title)).setText(cross);
        ((TextView) view.findViewById(R.id.pn_full_name)).setText(getString(R.string.partner_full_name_fmt, getString(p.fullName)));
        ((TextView) view.findViewById(R.id.pn_description)).setText(p.description);

        // Benefits: the highlighted one in the featured box, the rest as rows
        LinearLayout list = view.findViewById(R.id.pn_benefit_list);
        View highlight = view.findViewById(R.id.pn_highlight);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (Partner.Benefit b : p.benefits) {
            if (b.highlight && highlight.getVisibility() != View.VISIBLE) {
                highlight.setVisibility(View.VISIBLE);
                ((TextView) view.findViewById(R.id.pn_highlight_title)).setText(b.title);
                TextView detail = view.findViewById(R.id.pn_highlight_detail);
                if (b.detail != 0) detail.setText(b.detail); else detail.setVisibility(View.GONE);
                TextView value = view.findViewById(R.id.pn_highlight_value);
                TextView unit = view.findViewById(R.id.pn_highlight_unit);
                value.setText(b.bigValue == null ? "" : b.bigValue);
                if (b.bigUnit != 0) unit.setText(getString(b.bigUnit).toUpperCase(java.util.Locale.ROOT)); else unit.setVisibility(View.GONE);
                TextView old = view.findViewById(R.id.pn_highlight_old);
                if (b.oldValue != null && b.bigUnit != 0) {
                    String standard = b.oldValue + " " + getString(b.bigUnit);
                    android.text.SpannableString s = new android.text.SpannableString(getString(R.string.partner_standard_fmt, standard));
                    int start = s.toString().indexOf(standard);
                    if (start >= 0) s.setSpan(new android.text.style.StrikethroughSpan(), start, start + standard.length(), 0);
                    old.setText(s);
                    old.setPaintFlags(old.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    old.setVisibility(View.GONE);
                }
                continue;
            }
            View row = inflater.inflate(R.layout.item_partner_benefit, list, false);
            ((TextView) row.findViewById(R.id.pb_title)).setText(b.title);
            TextView detail = row.findViewById(R.id.pb_detail);
            if (b.detail != 0) detail.setText(b.detail); else detail.setVisibility(View.GONE);
            list.addView(row);
        }

        // Join
        TextView join = view.findViewById(R.id.pn_join);
        join.setText(p.joinLabel);
        join.setOnClickListener(v -> openLink(p.joinUrl));
        TextView note = view.findViewById(R.id.pn_join_note);
        if (p.joinNote != 0) note.setText(p.joinNote); else note.setVisibility(View.GONE);
        TextView url = view.findViewById(R.id.pn_join_url);
        url.setText(p.joinUrl.replaceFirst("^https?://", ""));
        url.setOnClickListener(v -> openLink(p.joinUrl));

        // Entrance
        Motion.riseIn(view.findViewById(R.id.pn_banner), 0L, 8f);
        Motion.riseIn(view.findViewById(R.id.pn_hero), 60L, 14f);
        Motion.popIn(view.findViewById(R.id.pn_logo_wrap), 200L, 0.7f);
        Motion.popIn(view.findViewById(R.id.pn_logo_cs_wrap), 140L, 0.7f);
        Motion.popIn(view.findViewById(R.id.pn_banner_badge), 260L, 0.8f);
        Motion.riseIn(view.findViewById(R.id.pn_benefits_card), 150L, 14f);
        Motion.popIn(highlight, 320L, 0.92f);
        Motion.cascadeChildren(list, 380L, 50L, 10f);
        Motion.riseIn(view.findViewById(R.id.pn_join_card), 260L, 14f);
    }

    private void openLink(@NonNull String url) {
        try {
            Tools.openURL(requireActivity(), url);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.partner_link_failed, Toast.LENGTH_SHORT).show();
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
