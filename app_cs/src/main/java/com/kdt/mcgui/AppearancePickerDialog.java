package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.game.branding.BrandingMode;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * "Minecraft Appearance" picker for the profile editor: one card per {@link BrandingMode}
 * (Vanilla Experience / CS Custom Experience), same visual language as the renderer picker.
 */
public class AppearancePickerDialog extends CSPopupDialog {

    public interface Callback {
        void onModePicked(@NonNull BrandingMode mode);
    }

    private final BrandingMode mSelected;
    private final Callback mCallback;

    public AppearancePickerDialog(@NonNull Context context, @NonNull BrandingMode selected, @NonNull Callback callback) {
        super(context);
        mSelected = selected;
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_appearance_picker);
        View card = findViewById(R.id.ap_card);
        setupPopup(findViewById(R.id.ap_scrim), card, true);
        sizeCard(card);

        View close = findViewById(R.id.ap_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        ViewGroup list = findViewById(R.id.ap_list);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        Context ctx = getContext();

        addCard(inflater, list, ctx.getString(R.string.branding_badge_vanilla), ctx.getString(R.string.branding_option_vanilla),
                ctx.getString(R.string.branding_option_vanilla_desc), null, ctx.getString(R.string.branding_tag_recommended),
                true, mSelected == BrandingMode.VANILLA, BrandingMode.VANILLA);
        addCard(inflater, list, ctx.getString(R.string.branding_badge_custom), ctx.getString(R.string.branding_option_custom),
                ctx.getString(R.string.branding_option_custom_desc), ctx.getString(R.string.branding_option_custom_versions),
                ctx.getString(R.string.branding_tag_pack), false, mSelected == BrandingMode.CUSTOM, BrandingMode.CUSTOM);

        CreatePopupMotion.chooserContent(findViewById(R.id.ap_header), new View[0], findViewById(R.id.ap_subtitle));
        Motion.cascadeChildren(list, 120L, 40L, 14f);
        Motion.fadeIn(findViewById(R.id.ap_footer), 320L, 260L);
    }

    private void addCard(LayoutInflater inflater, ViewGroup list, String badge, String name, String desc,
                         @Nullable String versions, @Nullable String tag, boolean neutralTag, boolean selected,
                         final BrandingMode mode) {
        View row = inflater.inflate(R.layout.item_renderer_card, list, false);
        ((TextView) row.findViewById(R.id.rc_badge)).setText(badge);
        ((TextView) row.findViewById(R.id.rc_name)).setText(name);
        ((TextView) row.findViewById(R.id.rc_desc)).setText(desc);
        TextView versionsView = row.findViewById(R.id.rc_versions);
        if (versions == null || versions.isEmpty()) versionsView.setVisibility(View.GONE);
        else versionsView.setText(versions);
        TextView tagView = row.findViewById(R.id.rc_tag);
        if (tag != null) {
            tagView.setVisibility(View.VISIBLE);
            tagView.setText(tag);
            if (neutralTag) {
                tagView.setBackgroundResource(R.drawable.bg_lib_badge_neutral);
                tagView.setTextColor(getContext().getColor(R.color.hm_text_dim));
            }
        }
        row.setActivated(selected);
        row.findViewById(R.id.rc_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            if (isClosing()) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View other = list.getChildAt(i);
                other.setActivated(other == row);
                other.findViewById(R.id.rc_check).setVisibility(other == row ? View.VISIBLE : View.INVISIBLE);
            }
            Motion.bounce(row.findViewById(R.id.rc_badge));
            Motion.popIn(row.findViewById(R.id.rc_check), 0L, 0.4f);
            row.postDelayed(() -> close(() -> mCallback.onModePicked(mode)), 220L);
        });
        CSAnimationEngine.attachPressFeedback(row);
        list.addView(row);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (560 * d), metrics.widthPixels - (int) (40 * d));
        int maxHeight = metrics.heightPixels - (int) (24 * d);
        int wanted = (int) (2 * 96 * d + 220 * d);
        params.height = wanted > maxHeight ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(params);
    }
}
