package com.craftstudio.cslauncher.partners.optl;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;
import java.util.Map;

/** OPTL Tier List: Top 10 from the public feed, the current account's own rank, and the test-request entry. */
public class OptlRankingsFragment extends Fragment {
    public static final String TAG = "OptlRankingsFragment";

    public OptlRankingsFragment() {
        super(R.layout.fragment_optl_rankings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.ok_back).setOnClickListener(v -> OptlNav.back(this));
        view.findViewById(R.id.ok_logo_wrap).setClipToOutline(true);
        view.findViewById(R.id.ok_refresh).setOnClickListener(v -> load(true));
        view.findViewById(R.id.ok_full).setOnClickListener(v -> {
            if (getActivity() != null) Tools.openURL(requireActivity(), OptlApi.WEBSITE_URL);
        });
        view.findViewById(R.id.ok_request).setOnClickListener(v ->
                OptlNav.open(this, OptlRequestFragment.class, OptlRequestFragment.TAG, null));

        Motion.riseIn(view.findViewById(R.id.ok_banner), 0L, 8f);
        Motion.riseIn(view.findViewById(R.id.ok_request_card), 80L, 14f);
        Motion.riseIn(view.findViewById(R.id.ok_top_card), 140L, 14f);
        load(false);
        refreshRequestCard();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRequestCard();
    }

    private void load(boolean force) {
        View view = getView();
        if (view == null) return;
        TextView status = view.findViewById(R.id.ok_status);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.optl_loading);
        OptlApi.rankings(force, (players, error) -> {
            if (!isAdded() || getView() == null) return;
            if (players == null) {
                status.setText(getString(R.string.optl_error_fmt, error == null ? "" : error));
                return;
            }
            status.setVisibility(View.GONE);
            fill(players);
        });
    }

    private void fill(@NonNull List<OptlApi.Player> players) {
        View view = requireView();
        ((TextView) view.findViewById(R.id.ok_count)).setText(getString(R.string.optl_count_fmt,
                java.text.NumberFormat.getIntegerInstance().format(players.size())));
        ViewGroup list = view.findViewById(R.id.ok_list);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        int n = Math.min(10, players.size());
        for (int i = 0; i < n; i++) {
            View row = inflater.inflate(R.layout.item_optl_rank, list, false);
            bindRow(row, i + 1, players.get(i));
            list.addView(row);
        }
        Motion.cascadeChildren(list, 60L, 40L, 10f);

        // The signed-in account's own rank, matched by Minecraft username
        Account me = Accounts.getCurrent();
        OptlApi.Player mine = OptlApi.find(players, me == null ? null : me.username);
        View meCard = view.findViewById(R.id.ok_me_card);
        View requestCard = view.findViewById(R.id.ok_request_card);
        if (mine != null) {
            bindRow(view.findViewById(R.id.ok_me_row), OptlApi.indexOf(players, mine) + 1, mine);
            if (meCard.getVisibility() != View.VISIBLE) {
                meCard.setVisibility(View.VISIBLE);
                Motion.riseIn(meCard, 0L, 12f);
            }
            ((ViewGroup.MarginLayoutParams) requestCard.getLayoutParams()).topMargin =
                    Math.round(8f * getResources().getDisplayMetrics().density);
            requestCard.requestLayout();
        } else {
            meCard.setVisibility(View.GONE);
        }
    }

    private void bindRow(@NonNull View row, int rank, @NonNull OptlApi.Player p) {
        ((TextView) row.findViewById(R.id.or_num)).setText(String.valueOf(rank));
        ((TextView) row.findViewById(R.id.or_name)).setText(p.username);
        TextView region = row.findViewById(R.id.or_region);
        if (p.region.isEmpty() || "Unknown".equalsIgnoreCase(p.region)) {
            region.setVisibility(View.GONE);
        } else {
            region.setVisibility(View.VISIBLE);
            region.setText(p.region.toUpperCase(java.util.Locale.ROOT));
        }
        StringBuilder kits = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, String> e : p.ranks.entrySet()) {
            if (shown == 5) { kits.append(" · +").append(p.ranks.size() - shown); break; }
            if (shown > 0) kits.append(" · ");
            kits.append(e.getKey()).append(' ').append(e.getValue());
            shown++;
        }
        ((TextView) row.findViewById(R.id.or_kits)).setText(kits);
        ((TextView) row.findViewById(R.id.or_points)).setText(String.valueOf(p.points));
        ((TextView) row.findViewById(R.id.or_tier)).setText(p.overallTier);
    }

    /** The request card reflects a pending request sent from this device. */
    private void refreshRequestCard() {
        View view = getView();
        if (view == null) return;
        long id = OptlApi.lastRequestId(requireContext());
        TextView title = view.findViewById(R.id.ok_request_title);
        TextView sub = view.findViewById(R.id.ok_request_sub);
        if (id > 0) {
            title.setText(R.string.optl_request_card_pending);
            sub.setText(getString(R.string.optl_request_card_pending_sub, id));
        } else {
            title.setText(R.string.optl_request_card_title);
            sub.setText(R.string.optl_request_card_sub);
        }
    }
}
