package com.craftstudio.cslauncher.fragments;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.BuildConfig;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Settings → Updates: the installed build, the Discord where builds are announced, the releases
 * page, and the features coming in CS Launcher Pro. Nothing is downloaded or checked here.
 */
public class UpdatesFragment extends Fragment {
    public static final String TAG = "UpdatesFragment";
    public static final String URL_RELEASES = "https://github.com/craftstudioteam/CS-LAUNCHER-PLUS/releases";

    public UpdatesFragment() { super(R.layout.fragment_updates); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView version = view.findViewById(R.id.up_version);
        version.setText(getString(R.string.updates_version, BuildConfig.VERSION_NAME));

        View discord = view.findViewById(R.id.up_discord);
        View github = view.findViewById(R.id.up_github);
        CSAnimationEngine.attachPressFeedback(discord);
        CSAnimationEngine.attachPressFeedback(github);
        discord.setOnClickListener(v -> open(AboutFragment.URL_DISCORD));
        github.setOnClickListener(v -> open(URL_RELEASES));

        LinearLayout list = view.findViewById(R.id.up_pro_list);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        int[][] features = {
                {R.string.pro_feature_recorder, R.string.pro_feature_recorder_body},
                {R.string.pro_feature_cursor, R.string.pro_feature_cursor_body},
                {R.string.pro_feature_core, R.string.pro_feature_core_body},
                {R.string.pro_feature_more, R.string.pro_feature_more_body},
        };
        for (int i = 0; i < features.length; i++) {
            View row = inflater.inflate(R.layout.item_pro_feature, list, false);
            ((TextView) row.findViewById(R.id.pf_number)).setText(i == features.length - 1 ? "+" : String.format(java.util.Locale.ROOT, "%02d", i + 1));
            ((TextView) row.findViewById(R.id.pf_title)).setText(features[i][0]);
            ((TextView) row.findViewById(R.id.pf_body)).setText(features[i][1]);
            list.addView(row);
            if (savedInstanceState == null) Motion.riseIn(row, 120 + i * 60L, 14);
        }
        if (savedInstanceState == null && Motion.content(view)) {
            Motion.riseIn(view.findViewById(R.id.up_card_build), 0, 18);
            Motion.riseIn(view.findViewById(R.id.up_card_pro), 60, 18);
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show();
        }
    }
}
