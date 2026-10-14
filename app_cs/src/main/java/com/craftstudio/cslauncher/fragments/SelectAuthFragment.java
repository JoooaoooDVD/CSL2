package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.discord.DiscordAccount;
import com.craftstudio.cslauncher.discord.DiscordLoginActivity;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Add account: a banner, one clean card per sign-in method (Microsoft, Ely.by, Local) and a
 * "Link with Discord" row that connects the Discord session used for the game status.
 */
public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    private final ActivityResultLauncher<Intent> mDiscordLoginLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> refreshDiscordRow());

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View banner = view.findViewById(R.id.select_auth_banner);
        View microsoft = view.findViewById(R.id.button_microsoft_authentication);
        View local = view.findViewById(R.id.button_local_authentication);
        View elyBy = view.findViewById(R.id.button_elyby_authentication);
        View discord = view.findViewById(R.id.button_discord_link);
        View[] options = {microsoft, elyBy, local, discord};
        View[] tiles = {view.findViewById(R.id.auth_microsoft_tile), view.findViewById(R.id.auth_elyby_tile),
                view.findViewById(R.id.auth_local_tile), view.findViewById(R.id.auth_discord_tile)};

        for (View option : options) {
            option.setClipToOutline(true);
            CSAnimationEngine.attachPressFeedback(option);
        }
        banner.setClipToOutline(true);

        bind(microsoft, tiles[0], MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG);
        bind(elyBy, tiles[1], ElyByLoginFragment.class, ElyByLoginFragment.TAG);
        bind(local, tiles[2], LocalLoginFragment.class, LocalLoginFragment.TAG);
        discord.setOnClickListener(v -> {
            Motion.bounce(tiles[3]);
            onDiscordTapped();
        });
        refreshDiscordRow();

        // Entrance: banner settles, sections and cards rise in one after another
        Motion.riseIn(banner, 40, 12);
        Motion.settleZoom(view.findViewById(R.id.select_auth_banner_art), 60);
        Motion.popIn(view.findViewById(R.id.select_auth_banner_icon), 160, 0.5f);
        Motion.fadeIn(view.findViewById(R.id.select_auth_section_signin), 160, Motion.MEDIUM);
        Motion.cascadeChildren(view.findViewById(R.id.select_auth_cards_container), 200, 70, 18f);
        Motion.fadeIn(view.findViewById(R.id.select_auth_section_discord), 440, Motion.MEDIUM);
        Motion.riseIn(discord, 480, 18f);
        Motion.fadeIn(view.findViewById(R.id.select_auth_footnote), 600, Motion.MEDIUM);
        for (int i = 0; i < tiles.length; i++) Motion.popIn(tiles[i], 260 + i * 70L, 0.4f);
    }

    private void bind(@NonNull View option, @NonNull View tile, Class<? extends Fragment> fragmentClass, String tag) {
        option.setOnClickListener(v -> {
            Motion.bounce(tile);
            CreatePopupMotion.optionChosen(option, () -> launchAuthFragment(fragmentClass, tag));
        });
    }

    private void refreshDiscordRow() {
        View root = getView();
        if (root == null) return;
        TextView status = root.findViewById(R.id.auth_discord_status);
        Context context = root.getContext();
        if (DiscordAccount.isLoggedIn(context)) {
            status.setText(getString(R.string.auth_discord_linked, DiscordAccount.getDisplayName(context)));
        } else {
            status.setText(R.string.auth_desc_discord);
        }
    }

    private void onDiscordTapped() {
        Context context = requireContext();
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_discord_mark);
        if (DiscordAccount.isLoggedIn(context)) {
            new SettingsPopup(context)
                    .title(R.string.preference_discord_logout_title)
                    .icon(icon)
                    .message(getString(R.string.preference_discord_logout_message, DiscordAccount.getDisplayName(context)))
                    .positive(R.string.preference_discord_logout, () -> {
                        DiscordAccount.logout(context);
                        refreshDiscordRow();
                    })
                    .negative(android.R.string.cancel, null)
                    .show();
        } else {
            new SettingsPopup(context)
                    .title(R.string.preference_discord_login_title)
                    .subtitle(getString(R.string.preference_discord_login_subtitle))
                    .icon(icon)
                    .message(R.string.preference_discord_login_message)
                    .positive(R.string.preference_discord_login, () -> mDiscordLoginLauncher.launch(
                            new Intent(context, DiscordLoginActivity.class)))
                    .negative(android.R.string.cancel, null)
                    .show();
        }
    }

    private void launchAuthFragment(Class<? extends  Fragment> fragmentClass, String fragmentTag) {
        if (getActivity() == null) return;
        if(ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        Tools.swapFragment(requireActivity(), fragmentClass, fragmentTag, null);
    }
}
