package com.craftstudio.cslauncher.partners.optl;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.friends.CsAccount;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/** OPTL "Request a test" form. Stored by Craft Studio, pulled by OPTL's website. */
public class OptlRequestFragment extends Fragment {
    public static final String TAG = "OptlRequestFragment";

    private boolean mSending;

    public OptlRequestFragment() {
        super(R.layout.fragment_optl_request);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.oq_back).setOnClickListener(v -> OptlNav.back(this));

        EditText username = view.findViewById(R.id.oq_username);
        Account me = Accounts.getCurrent();
        if (me != null && username.getText().length() == 0) username.setText(me.username);

        ChipGroup regions = view.findViewById(R.id.oq_regions);
        for (String r : OptlApi.REGIONS) regions.addView(chip(r, true));
        ChipGroup modes = view.findViewById(R.id.oq_gamemodes);
        for (String g : OptlApi.GAMEMODES) modes.addView(chip(g, false));

        view.findViewById(R.id.oq_submit).setOnClickListener(v -> submit());

        Motion.riseIn(view.findViewById(R.id.oq_banner), 0L, 8f);
        Motion.riseIn(view.findViewById(R.id.oq_form), 80L, 14f);

        // Status of a request sent earlier from this device
        String last = OptlApi.lastRequestUser(requireContext());
        if (!last.isEmpty()) {
            OptlApi.status(last, (statuses, error) -> {
                if (!isAdded() || statuses == null || statuses.isEmpty()) return;
                showStatus(statuses.get(0));
            });
        }
    }

    private Chip chip(String text, boolean single) {
        Chip c = new Chip(requireContext());
        c.setText(text);
        c.setCheckable(true);
        c.setCheckedIconVisible(false);
        c.setChipBackgroundColorResource(android.R.color.transparent);
        c.setChipStrokeWidth(0f);
        c.setBackgroundResource(R.drawable.bg_optl_chip);
        c.setTextColor(getResources().getColorStateList(R.color.optl_chip_text));
        c.setTextSize(12f);
        c.setEnsureMinTouchTargetSize(false);
        c.setChipMinHeight(32f * getResources().getDisplayMetrics().density);
        c.setChipStartPadding(10f * getResources().getDisplayMetrics().density);
        c.setChipEndPadding(10f * getResources().getDisplayMetrics().density);
        c.setTag(text);
        return c;
    }

    private void submit() {
        if (mSending) return;
        View view = requireView();
        TextView error = view.findViewById(R.id.oq_error);
        error.setVisibility(View.GONE);

        String username = text(R.id.oq_username);
        String discord = text(R.id.oq_discord);
        String discordId = text(R.id.oq_discord_id);
        String previous = text(R.id.oq_previous);
        String experience = text(R.id.oq_experience);
        ChipGroup regions = view.findViewById(R.id.oq_regions);
        ChipGroup modes = view.findViewById(R.id.oq_gamemodes);
        String region = null;
        for (int i = 0; i < regions.getChildCount(); i++) {
            Chip c = (Chip) regions.getChildAt(i);
            if (c.isChecked()) { region = (String) c.getTag(); break; }
        }
        List<String> gamemodes = new ArrayList<>();
        for (int i = 0; i < modes.getChildCount(); i++) {
            Chip c = (Chip) modes.getChildAt(i);
            if (c.isChecked()) gamemodes.add((String) c.getTag());
        }
        if (username.length() < 3) { fail(error, R.string.optl_q_username_required); return; }
        if (discord.length() < 2) { fail(error, R.string.optl_q_discord_required); return; }
        if (region == null) { fail(error, R.string.optl_q_region); return; }
        if (gamemodes.isEmpty()) { fail(error, R.string.optl_q_gamemodes_required); return; }
        if (!((CheckBox) view.findViewById(R.id.oq_consent)).isChecked()) { fail(error, R.string.optl_q_consent_required); return; }

        mSending = true;
        TextView submit = view.findViewById(R.id.oq_submit);
        submit.setText(R.string.optl_sending);
        submit.setAlpha(0.6f);
        String cs = CsAccount.isSignedIn() ? CsAccount.username() : null;
        OptlApi.submit(requireContext(), username, discord, discordId, region, gamemodes, previous, experience, cs,
                "launcher", (id, err) -> {
            if (!isAdded()) return;
            mSending = false;
            submit.setText(R.string.optl_submit);
            submit.setAlpha(1f);
            if (err != null) {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
                Motion.riseIn(error, 0L, 6f);
                return;
            }
            OptlApi.Status st = new OptlApi.Status();
            st.id = id;
            showStatus(st);
            View form = requireView().findViewById(R.id.oq_form);
            form.animate().alpha(0.45f).setDuration(240L).start();
            form.setEnabled(false);
            submit.setEnabled(false);
            ((androidx.core.widget.NestedScrollView) requireView().findViewById(R.id.oq_scroll)).smoothScrollTo(0, 0);
        });
    }

    private void showStatus(@NonNull OptlApi.Status st) {
        View view = getView();
        if (view == null) return;
        View card = view.findViewById(R.id.oq_status_card);
        int label;
        switch (st.status) {
            case "accepted": label = R.string.optl_status_accepted; break;
            case "tested": label = R.string.optl_status_tested; break;
            case "rejected": label = R.string.optl_status_rejected; break;
            default: label = R.string.optl_status_pending;
        }
        ((TextView) view.findViewById(R.id.oq_status_title)).setText(getString(R.string.optl_status_title_fmt, st.id, getString(label)));
        ((TextView) view.findViewById(R.id.oq_status_body)).setText(st.note == null || st.note.isEmpty()
                ? getString(R.string.optl_sent_body) : st.note);
        if (card.getVisibility() != View.VISIBLE) {
            card.setVisibility(View.VISIBLE);
            Motion.riseIn(card, 0L, 12f);
        }
    }

    private void fail(TextView error, int res) {
        error.setText(res);
        error.setVisibility(View.VISIBLE);
        Motion.riseIn(error, 0L, 6f);
    }

    private String text(int id) {
        EditText e = requireView().findViewById(id);
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
