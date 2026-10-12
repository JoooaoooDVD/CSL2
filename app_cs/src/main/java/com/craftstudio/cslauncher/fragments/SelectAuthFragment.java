package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Add account: a banner and one artwork card per sign-in method (Microsoft, Ely.by, Local).
 * The cards sit in one row; on narrow panes they stack vertically.
 */
public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    private static final float NARROW_DP = 540f;

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View banner = view.findViewById(R.id.select_auth_banner);
        LinearLayout cards = view.findViewById(R.id.select_auth_cards_container);
        View microsoft = view.findViewById(R.id.button_microsoft_authentication);
        View local = view.findViewById(R.id.button_local_authentication);
        View elyBy = view.findViewById(R.id.button_elyby_authentication);
        View[] options = {microsoft, elyBy, local};
        View[] tiles = {view.findViewById(R.id.auth_microsoft_tile), view.findViewById(R.id.auth_elyby_tile),
                view.findViewById(R.id.auth_local_tile)};
        View[] arts = {view.findViewById(R.id.auth_microsoft_art), view.findViewById(R.id.auth_elyby_art),
                view.findViewById(R.id.auth_local_art)};

        for (View option : options) option.setClipToOutline(true);
        for (View tile : tiles) tile.setClipToOutline(true);
        banner.setClipToOutline(true);

        bind(microsoft, tiles[0], MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG);
        bind(elyBy, tiles[1], ElyByLoginFragment.class, ElyByLoginFragment.TAG);
        bind(local, tiles[2], LocalLoginFragment.class, LocalLoginFragment.TAG);

        view.post(() -> adaptToWidth(view, cards));

        Motion.riseIn(banner, 40, 12);
        Motion.settleZoom(view.findViewById(R.id.select_auth_banner_art), 60);
        CreatePopupMotion.chooserContent(null, options, view.findViewById(R.id.select_auth_footnote));
        CreatePopupMotion.optionArts(arts);
        for (int i = 0; i < tiles.length; i++) Motion.popIn(tiles[i], 240 + i * 70L, 0.4f);
    }

    private void bind(@NonNull View option, @NonNull View tile, Class<? extends Fragment> fragmentClass, String tag) {
        option.setOnClickListener(v -> {
            Motion.bounce(tile);
            CreatePopupMotion.optionChosen(option, () -> launchAuthFragment(fragmentClass, tag));
        });
    }

    /** Narrow panes (portrait phones): the three cards stack, each keeping a fixed height */
    private void adaptToWidth(@NonNull View root, @NonNull LinearLayout cards) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp <= 0 || widthDp >= NARROW_DP) return;
        cards.setOrientation(LinearLayout.VERTICAL);
        ViewGroup.LayoutParams rowParams = cards.getLayoutParams();
        rowParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        cards.setLayoutParams(rowParams);
        int cardHeight = Math.round(196 * density);
        int gap = Math.round(10 * density);
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cardHeight);
            if (i < cards.getChildCount() - 1) params.bottomMargin = gap;
            card.setLayoutParams(params);
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
