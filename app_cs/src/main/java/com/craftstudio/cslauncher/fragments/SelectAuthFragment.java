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
        View header = view.findViewById(R.id.select_auth_banner);
        View back = view.findViewById(R.id.select_auth_back);
        View microsoft = view.findViewById(R.id.button_microsoft_authentication);
        View local = view.findViewById(R.id.button_local_authentication);
        View elyBy = view.findViewById(R.id.button_elyby_authentication);
        View discord = view.findViewById(R.id.button_discord_link);
        mOptions = new View[]{microsoft, elyBy, local};
        View[] tiles = {view.findViewById(R.id.auth_microsoft_tile), view.findViewById(R.id.auth_elyby_tile),
                view.findViewById(R.id.auth_local_tile)};
        View[] badges = {view.findViewById(R.id.ac_badge_ms), view.findViewById(R.id.ac_badge_ely), view.findViewById(R.id.ac_badge_local)};
        View[] shines = {view.findViewById(R.id.button_microsoft_authentication_shine),
                view.findViewById(R.id.button_elyby_authentication_shine), view.findViewById(R.id.button_local_authentication_shine)};

        for (View option : mOptions) {
            option.setClipToOutline(true);
            CSAnimationEngine.attachPressFeedback(option);
        }
        CSAnimationEngine.attachPressFeedback(discord);
        CSAnimationEngine.attachPressFeedback(back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));

        bind(microsoft, tiles[0], MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG);
        bind(elyBy, tiles[1], ElyByLoginFragment.class, ElyByLoginFragment.TAG);
        bind(local, tiles[2], LocalLoginFragment.class, LocalLoginFragment.TAG);
        discord.setOnClickListener(v -> {
            Motion.bounce(view.findViewById(R.id.auth_discord_tile));
            onDiscordTapped();
        });
        refreshDiscordRow();
        view.post(() -> adaptToWidth(view));

        // Entrance: header slides in, the three cards rise one after another with their icon
        // popping and a light sweep across the coloured top, then the Discord row and footnote.
        Motion.riseIn(header, 30L, 10f);
        for (int i = 0; i < mOptions.length; i++) {
            final int index = i;
            Motion.riseIn(mOptions[i], 120L + i * 90L, 28f, 520L);
            Motion.popIn(tiles[i], 300L + i * 90L, 0.3f);
            Motion.popIn(badges[i], 420L + i * 90L, 0.6f);
            mOptions[i].postDelayed(() -> sweep(shines[index], mOptions[index]), 520L + i * 110L);
        }
        Motion.riseIn(discord, 480L, 16f);
        Motion.fadeIn(view.findViewById(R.id.select_auth_footnote), 640L, Motion.MEDIUM);
        // The recommended card gets a gentle idle breathing of its icon
        tiles[0].postDelayed(() -> breathe(tiles[0]), 1400L);
    }

    private View[] mOptions = new View[0];

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from a sign-in screen: the cards that faded out return
        for (View option : mOptions) {
            if (option.getAlpha() < 1f && option.getAlpha() > 0f) {
                option.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start();
            }
        }
    }

    /** A highlight travels once across the card header */
    private static void sweep(@Nullable View shine, @NonNull View card) {
        if (shine == null || card.getWidth() == 0) return;
        shine.setTranslationX(-shine.getWidth());
        shine.setAlpha(1f);
        shine.animate().translationX(card.getWidth() + shine.getWidth()).setDuration(700L)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> shine.setAlpha(0f)).start();
    }

    private static void breathe(@NonNull View tile) {
        if (!tile.isAttachedToWindow()) return;
        android.animation.ObjectAnimator x = android.animation.ObjectAnimator.ofFloat(tile, View.SCALE_X, 1f, 1.06f, 1f);
        android.animation.ObjectAnimator y = android.animation.ObjectAnimator.ofFloat(tile, View.SCALE_Y, 1f, 1.06f, 1f);
        for (android.animation.ObjectAnimator animator : new android.animation.ObjectAnimator[]{x, y}) {
            animator.setDuration(2400L);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animator.start();
        }
    }

    /** Phones in portrait / narrow windows: cards stack vertically */
    private void adaptToWidth(@NonNull View root) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp <= 0 || widthDp >= 560f) return;
        android.widget.LinearLayout container = root.findViewById(R.id.select_auth_cards_container);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams cp = (android.widget.LinearLayout.LayoutParams) container.getLayoutParams();
        cp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        cp.weight = 0f;
        container.setLayoutParams(cp);
        container.setMinimumHeight(0);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = Math.round(10 * density);
            child.setLayoutParams(params);
        }
        root.findViewById(R.id.select_auth_sub).setVisibility(View.GONE);
    }

    private void bind(@NonNull View option, @NonNull View tile, Class<? extends Fragment> fragmentClass, String tag) {
        option.setOnClickListener(v -> {
            Motion.bounce(tile);
            for (View other : mOptions) {
                if (other != option) other.animate().alpha(0.35f).scaleX(0.97f).scaleY(0.97f).setDuration(180L).start();
            }
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
