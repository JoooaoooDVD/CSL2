package com.craftstudio.cslauncher.partners.optl;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.skins.SkinModelView;
import com.craftstudio.cslauncher.utils.motion.Motion;

/** One OPTL player: 3D skin, rank, points, overall tier and every gamemode tier. */
public class OptlPlayerFragment extends Fragment {
    public static final String TAG = "OptlPlayerFragment";
    static final String ARG_NAME = "name", ARG_REGION = "region", ARG_TIER = "tier", ARG_POINTS = "points",
            ARG_RANK = "rank", ARG_MODES = "modes", ARG_MODE_TIERS = "mode_tiers";

    public OptlPlayerFragment() { super(R.layout.fragment_optl_player); }

    @NonNull
    static Bundle args(@NonNull OptlApi.Player p, int rank) {
        Bundle b = new Bundle();
        b.putString(ARG_NAME, p.username);
        b.putString(ARG_REGION, p.region);
        b.putString(ARG_TIER, p.overallTier);
        b.putInt(ARG_POINTS, p.points);
        b.putInt(ARG_RANK, rank);
        String[] modes = new String[p.ranks.size()], tiers = new String[p.ranks.size()];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : p.ranks.entrySet()) { modes[i] = e.getKey(); tiers[i] = e.getValue(); i++; }
        b.putStringArray(ARG_MODES, modes);
        b.putStringArray(ARG_MODE_TIERS, tiers);
        return b;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = getArguments() == null ? new Bundle() : getArguments();
        final String name = a.getString(ARG_NAME, "");
        String region = a.getString(ARG_REGION, "");
        String tier = a.getString(ARG_TIER, "");
        int points = a.getInt(ARG_POINTS, 0), rank = a.getInt(ARG_RANK, 0);
        String[] modes = a.getStringArray(ARG_MODES), tiers = a.getStringArray(ARG_MODE_TIERS);

        view.findViewById(R.id.op_back).setOnClickListener(v -> OptlNav.back(this));
        ((TextView) view.findViewById(R.id.op_name)).setText(name);
        TextView regionView = view.findViewById(R.id.op_region);
        if (region.isEmpty() || "Unknown".equalsIgnoreCase(region)) regionView.setVisibility(View.GONE);
        else regionView.setText(region.toUpperCase(java.util.Locale.ROOT));
        ((TextView) view.findViewById(R.id.op_rank)).setText(rank > 0 ? "#" + rank : "—");
        ((TextView) view.findViewById(R.id.op_points)).setText(String.valueOf(points));
        ((TextView) view.findViewById(R.id.op_tier)).setText(tier.isEmpty() ? "—" : tier);
        view.findViewById(R.id.op_site).setOnClickListener(v -> {
            if (getActivity() != null) Tools.openURL(requireActivity(), OptlApi.WEBSITE_URL);
        });

        // Skin: 3D model + head, from the shared resolver (CS backend → Visage → default)
        SkinModelView model = view.findViewById(R.id.op_model);
        ImageView head = view.findViewById(R.id.op_head);
        TextView skinNote = view.findViewById(R.id.op_skin_note);
        OptlSkins.Skin skin = OptlSkins.get(view.getContext(), name, (n, s) -> {
            if (isAdded() && getView() != null) applySkin(model, head, skinNote, s);
        });
        if (skin != null) applySkin(model, head, skinNote, skin);
        else { OptlSkins.Skin d = OptlSkins.defaultSkin(view.getContext()); model.setSkin(d.texture, d.slim); head.setImageBitmap(d.head); }

        // Gamemode tiers
        ViewGroup list = view.findViewById(R.id.op_modes);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        int n = modes == null ? 0 : modes.length;
        ((TextView) view.findViewById(R.id.op_modes_count)).setText(getResources().getQuantityString(R.plurals.optl_modes_count, n, n));
        for (int i = 0; i < n; i++) {
            View row = inflater.inflate(R.layout.item_optl_mode, list, false);
            ((TextView) row.findViewById(R.id.om_mode)).setText(modes[i]);
            ((TextView) row.findViewById(R.id.om_tier)).setText(tiers == null || i >= tiers.length ? "" : tiers[i]);
            list.addView(row);
        }
        if (n == 0) view.findViewById(R.id.op_modes_empty).setVisibility(View.VISIBLE);

        Motion.riseIn(view.findViewById(R.id.op_banner), 0L, 8f);
        Motion.riseIn(view.findViewById(R.id.op_hero), 60L, 14f);
        Motion.riseIn(view.findViewById(R.id.op_modes_card), 140L, 14f);
        Motion.cascadeChildren(list, 180L, 35L, 8f);
    }

    private void applySkin(SkinModelView model, ImageView head, TextView note, OptlSkins.Skin s) {
        model.setSkin(s.texture, s.slim);
        if (s.head != null) head.setImageBitmap(s.head);
        note.setText(s.isDefault ? R.string.optl_skin_default : R.string.optl_skin_hint);
    }
}
