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
 * Settings → Update to Pro: what CS Launcher Pro adds and how to get it (through the Craft
 * Studio Discord), plus the installed Plus build. Nothing is downloaded or checked here.
 */
public class UpdatesFragment extends Fragment {
    public static final String TAG = "UpdatesFragment";
    public static final String URL_RELEASES = "https://github.com/craftstudioteam/CS-LAUNCHER-PLUS/releases";

    public UpdatesFragment() { super(R.layout.fragment_updates); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView version = view.findViewById(R.id.up_version);
        version.setText(getString(R.string.updates_version, BuildConfig.VERSION_NAME));

        TextView installed = view.findViewById(R.id.up_installed_line);
        installed.setText(getString(R.string.upd_current_fmt, com.craftstudio.cslauncher.update.UpdateManager.currentLabel()));
        View check = view.findViewById(R.id.up_check);
        TextView channel = view.findViewById(R.id.up_channel);
        CSAnimationEngine.attachPressFeedback(check);
        refreshChannel(channel);
        check.setOnClickListener(v -> { if (getActivity() != null) com.craftstudio.cslauncher.update.UpdateManager.manualCheck(getActivity()); });
        channel.setOnClickListener(v -> showChannelPopup(channel));

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

    private void refreshChannel(TextView line) {
        if (getContext() == null) return;
        boolean stable = com.craftstudio.cslauncher.update.UpdateManager.stableOnly(getContext());
        line.setText(getString(R.string.upd_channel_line, getString(stable ? R.string.upd_channel_stable : R.string.upd_channel_latest)));
    }

    /** Stable releases (v* tags) or every build from main; picking a channel checks it right away. */
    private void showChannelPopup(TextView line) {
        final android.app.Activity act = getActivity();
        if (act == null) return;
        final android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(act);
        boolean stable = com.craftstudio.cslauncher.update.UpdateManager.stableOnly(act);
        CharSequence[] entries = { getString(R.string.upd_channel_stable), getString(R.string.upd_channel_latest) };
        new com.craftstudio.cslauncher.prefs.SettingsPopup(act)
                .title(R.string.upd_channel_title)
                .subtitle(getString(R.string.upd_current_fmt, com.craftstudio.cslauncher.update.UpdateManager.currentLabel()))
                .icon(androidx.core.content.ContextCompat.getDrawable(act, R.drawable.ic_cs_update))
                .message(R.string.upd_channel_hint)
                .singleChoice(entries, stable ? 0 : 1, which -> {
                    prefs.edit().putString(com.craftstudio.cslauncher.update.UpdateManager.PREF_CHANNEL, which == 0 ? "stable" : "latest").apply();
                    refreshChannel(line);
                    com.craftstudio.cslauncher.update.UpdateManager.manualCheck(act);
                })
                .negative(android.R.string.cancel, null)
                .show();
    }
}
