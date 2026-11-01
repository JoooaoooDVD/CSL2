package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;

/**
 * Small popup shown by "Create New Instance": pick what kind of profile to create.
 * <ul>
 *   <li>Normal: opens the {@link CreateInstanceDialog} form;</li>
 *   <li>CS Client: not available yet (badge + shake + toast);</li>
 *   <li>Modpack: opens the modpack browser, where a modpack is installed as a profile.</li>
 * </ul>
 * Every loader (OptiFine, BTA, Legacy Fabric too) lives in the Normal form, there is no extra page.
 */
public class CreateChooserDialog extends CSPopupDialog {

    public interface Callback {
        void onNormal();
        void onModpack();
        void onHorizon();
    }

    private final Callback mCallback;

    public CreateChooserDialog(@NonNull Context context, @NonNull Callback callback) {
        super(context);
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_create_chooser);
        View card = findViewById(R.id.cc_card);
        setupPopup(findViewById(R.id.cc_scrim), card, true);
        sizeCard(card);

        View normal = findViewById(R.id.cc_opt_normal);
        View cs = findViewById(R.id.cc_opt_cs);
        View modpack = findViewById(R.id.cc_opt_modpack);
        View horizon = findViewById(R.id.cc_opt_horizon);
        View close = findViewById(R.id.cc_close);
        for (View option : new View[]{normal, cs, modpack, horizon}) option.setClipToOutline(true);
        findViewById(R.id.cc_normal_tile).setClipToOutline(true);
        findViewById(R.id.cc_cs_tile).setClipToOutline(true);
        findViewById(R.id.cc_modpack_tile).setClipToOutline(true);
        findViewById(R.id.cc_horizon_tile).setClipToOutline(true);
        // Horizon Engine: only offered when this APK ships the engine; greyed out on unsupported devices
        final boolean horizonBundled = com.craftstudio.cslauncher.engine.horizon.HorizonEngine.isBundled(getContext());
        final boolean horizonSupported = horizonBundled
                && com.craftstudio.cslauncher.engine.horizon.HorizonEngine.isSupported(getContext());
        if (!horizonBundled) {
            horizon.setVisibility(View.GONE);
            modpack.setLayoutParams(withEndMargin(modpack, 0));
        } else if (!horizonSupported) {
            horizon.setAlpha(0.55f);
        }

        close.setOnClickListener(v -> close(null));
        normal.setOnClickListener(v -> {
            if (isClosing()) return;
            CreatePopupMotion.optionChosen(normal, () -> close(mCallback::onNormal));
        });
        modpack.setOnClickListener(v -> {
            if (isClosing()) return;
            CreatePopupMotion.optionChosen(modpack, () -> close(mCallback::onModpack));
        });
        cs.setOnClickListener(v -> {
            CreatePopupMotion.comingSoon(cs, findViewById(R.id.cc_cs_badge));
            Toast.makeText(getContext(), R.string.cc_cs_toast, Toast.LENGTH_SHORT).show();
        });
        horizon.setOnClickListener(v -> {
            if (isClosing()) return;
            if (!horizonSupported) {
                CreatePopupMotion.comingSoon(horizon, findViewById(R.id.cc_horizon_badge));
                Toast.makeText(getContext(), R.string.cc_horizon_unsupported, Toast.LENGTH_LONG).show();
                return;
            }
            CreatePopupMotion.optionChosen(horizon, () -> close(mCallback::onHorizon));
        });
        CSAnimationEngine.attachPressFeedback(close);

        CreatePopupMotion.enter(mScrim, mCard);
        CreatePopupMotion.chooserContent(findViewById(R.id.cc_header), new View[]{normal, cs, modpack, horizon}, null);
        CreatePopupMotion.optionArts(new View[]{findViewById(R.id.cc_normal_art),
                findViewById(R.id.cc_cs_art), findViewById(R.id.cc_modpack_art), findViewById(R.id.cc_horizon_art)});
    }

    private static ViewGroup.LayoutParams withEndMargin(@NonNull View view, int margin) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) params).setMarginEnd(margin);
        return params;
    }

    /** Wide on tablets, never touching the edges; option cards shrink on short screens */
    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        boolean four = com.craftstudio.cslauncher.engine.horizon.HorizonEngine.isBundled(getContext());
        params.width = Math.min((int) ((four ? 800 : 640) * d), metrics.widthPixels - (int) (48 * d));
        card.setLayoutParams(params);
        // header ~44 + margin 14 + padding 36 + window margin ~28; the art area takes what is left
        View options = findViewById(R.id.cc_options);
        ViewGroup.LayoutParams optionParams = options.getLayoutParams();
        int available = metrics.heightPixels - (int) (134 * d);
        optionParams.height = Math.max((int) (150 * d), Math.min((int) (236 * d), available));
        options.setLayoutParams(optionParams);
    }
}
