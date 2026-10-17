package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Outline;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.game.branding.BrandingMode;
import com.craftstudio.cslauncher.game.branding.BrandingPart;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.EnumSet;

/**
 * "Minecraft Appearance" picker for the profile editor.
 * <p>
 * Two mode cards with previews (Vanilla / CS Custom Experience); when Custom is selected a
 * scope section lets the user pick exactly which parts the custom look replaces (presets
 * "Everything" / "Home screen only" / "Custom" + one row per {@link BrandingPart}). Nothing is
 * committed until Apply; at least one part must stay selected.
 */
public class AppearancePickerDialog extends CSPopupDialog {

    public interface Callback {
        void onApplied(@NonNull BrandingMode mode, @NonNull EnumSet<BrandingPart> parts);
    }

    /** Display metadata per part; order == display order. */
    private static final Object[][] PART_ROWS = {
            {BrandingPart.MENU, R.drawable.ic_px_home, R.string.branding_part_menu, R.string.branding_part_menu_desc},
            {BrandingPart.WIDGETS, R.drawable.ic_px_sliders, R.string.branding_part_widgets, R.string.branding_part_widgets_desc},
            {BrandingPart.SCREENS, R.drawable.ic_px_layers_buffer, R.string.branding_part_screens, R.string.branding_part_screens_desc},
            {BrandingPart.TEXTS, R.drawable.ic_px_letters, R.string.branding_part_texts, R.string.branding_part_texts_desc},
            {BrandingPart.DARK_LOADING, R.drawable.ic_px_transparency, R.string.branding_part_dark, R.string.branding_part_dark_desc},
    };

    private final Callback mCallback;
    private BrandingMode mMode;
    private final EnumSet<BrandingPart> mParts;

    private View mVanillaCard, mCustomCard, mScope, mApply;
    private TextView mSummary, mScopeCount;
    private View mPresetAll, mPresetMenu, mPresetCustom;
    private final View[] mPartRows = new View[PART_ROWS.length];

    public AppearancePickerDialog(@NonNull Context context, @NonNull BrandingMode selected,
                                  @NonNull EnumSet<BrandingPart> parts, @NonNull Callback callback) {
        super(context);
        mMode = selected;
        mParts = parts.isEmpty() ? BrandingPart.all() : EnumSet.copyOf(parts);
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

        Context ctx = getContext();
        mVanillaCard = findViewById(R.id.ap_mode_vanilla);
        mCustomCard = findViewById(R.id.ap_mode_custom);
        bindModeCard(mVanillaCard, R.drawable.ap_preview_vanilla, ctx.getString(R.string.branding_badge_vanilla),
                ctx.getString(R.string.branding_option_vanilla), ctx.getString(R.string.branding_option_vanilla_desc),
                ctx.getString(R.string.branding_mode_preview_vanilla), BrandingMode.VANILLA);
        bindModeCard(mCustomCard, R.drawable.ap_preview_custom, ctx.getString(R.string.branding_badge_custom),
                ctx.getString(R.string.branding_option_custom), ctx.getString(R.string.branding_option_custom_desc)
                        + "\n" + ctx.getString(R.string.branding_option_custom_versions),
                ctx.getString(R.string.branding_mode_preview_custom), BrandingMode.CUSTOM);

        mScope = findViewById(R.id.ap_scope);
        mScopeCount = findViewById(R.id.ap_scope_count);
        LinearLayout presets = findViewById(R.id.ap_presets);
        mPresetAll = addPreset(presets, R.string.branding_preset_all, () -> setParts(BrandingPart.all()));
        mPresetMenu = addPreset(presets, R.string.branding_preset_menu, () -> setParts(BrandingPart.menuOnly()));
        mPresetCustom = addPreset(presets, R.string.branding_preset_custom, null);

        LinearLayout parts = findViewById(R.id.ap_parts);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (int i = 0; i < PART_ROWS.length; i++) {
            final BrandingPart part = (BrandingPart) PART_ROWS[i][0];
            View row = inflater.inflate(R.layout.item_appearance_part, parts, false);
            ((ImageView) row.findViewById(R.id.app_icon)).setImageResource((Integer) PART_ROWS[i][1]);
            ((TextView) row.findViewById(R.id.app_title)).setText((Integer) PART_ROWS[i][2]);
            ((TextView) row.findViewById(R.id.app_desc)).setText((Integer) PART_ROWS[i][3]);
            row.setOnClickListener(v -> togglePart(part, row));
            CSAnimationEngine.attachPressFeedback(row);
            parts.addView(row);
            mPartRows[i] = row;
        }

        mSummary = findViewById(R.id.ap_summary);
        View cancel = findViewById(R.id.ap_cancel);
        cancel.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(cancel);
        mApply = findViewById(R.id.ap_apply);
        mApply.setOnClickListener(v -> {
            if (isClosing() || !mApply.isEnabled()) return;
            final BrandingMode mode = mMode;
            final EnumSet<BrandingPart> picked = EnumSet.copyOf(mParts);
            close(() -> mCallback.onApplied(mode, picked));
        });
        CSAnimationEngine.attachPressFeedback(mApply);

        refresh(false);

        CreatePopupMotion.chooserContent(findViewById(R.id.ap_header), new View[0], findViewById(R.id.ap_subtitle));
        Motion.cascadeChildren(findViewById(R.id.ap_modes), 120L, 60L, 14f);
        Motion.riseIn(mScope, 240L, 12f);
        Motion.fadeIn(findViewById(R.id.ap_footer), 320L, 260L);
        Motion.fadeIn(findViewById(R.id.ap_actions), 300L, 260L);
    }

    private void bindModeCard(View card, int preview, String badge, String title, String desc, String contentDesc,
                              final BrandingMode mode) {
        ImageView image = card.findViewById(R.id.am_preview);
        image.setImageResource(preview);
        image.setContentDescription(contentDesc);
        final float radius = 12f * dp();
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        ((TextView) card.findViewById(R.id.am_badge)).setText(badge);
        ((TextView) card.findViewById(R.id.am_title)).setText(title);
        ((TextView) card.findViewById(R.id.am_desc)).setText(desc);
        card.setOnClickListener(v -> {
            if (isClosing() || mMode == mode) return;
            mMode = mode;
            Motion.bounce(card.findViewById(R.id.am_check));
            refresh(true);
        });
        CSAnimationEngine.attachPressFeedback(card);
    }

    private View addPreset(LinearLayout container, int label, @Nullable final Runnable action) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTextSize(12f);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_ap_chip);
        int ph = (int) (14 * dp());
        chip.setPadding(ph, 0, ph, 0);
        chip.setMinHeight((int) (34 * dp()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (34 * dp()));
        lp.setMarginEnd((int) (8 * dp()));
        chip.setLayoutParams(lp);
        if (action != null) {
            chip.setOnClickListener(v -> {
                if (isClosing()) return;
                action.run();
                Motion.bounce(chip);
            });
            CSAnimationEngine.attachPressFeedback(chip);
        } else {
            chip.setClickable(false);
        }
        container.addView(chip);
        return chip;
    }

    private void setParts(EnumSet<BrandingPart> parts) {
        mParts.clear();
        mParts.addAll(parts);
        refresh(true);
    }

    private void togglePart(BrandingPart part, View row) {
        if (isClosing()) return;
        if (mParts.contains(part)) {
            if (mParts.size() == 1) {
                // Never allow an empty selection: shake instead of unchecking the last part
                Motion.shake(row);
                return;
            }
            mParts.remove(part);
        } else {
            mParts.add(part);
            Motion.bounce(row.findViewById(R.id.app_check));
        }
        refresh(true);
    }

    /** Pushes model → views (cards, scope visibility, chips, rows, counter, summary, Apply). */
    private void refresh(boolean animate) {
        boolean custom = mMode == BrandingMode.CUSTOM;
        setCardSelected(mVanillaCard, !custom);
        setCardSelected(mCustomCard, custom);

        if (custom && mScope.getVisibility() != View.VISIBLE) {
            mScope.setVisibility(View.VISIBLE);
            if (animate) Motion.riseIn(mScope, 0L, 10f);
        } else if (!custom && mScope.getVisibility() == View.VISIBLE) {
            if (animate) Motion.hide(mScope, true);
            else mScope.setVisibility(View.GONE);
        }

        boolean all = mParts.containsAll(BrandingPart.all());
        boolean menuOnly = mParts.equals(BrandingPart.menuOnly());
        mPresetAll.setActivated(all);
        mPresetMenu.setActivated(menuOnly);
        mPresetCustom.setActivated(!all && !menuOnly);
        tintChip((TextView) mPresetAll);
        tintChip((TextView) mPresetMenu);
        tintChip((TextView) mPresetCustom);

        for (int i = 0; i < PART_ROWS.length; i++) {
            boolean on = mParts.contains((BrandingPart) PART_ROWS[i][0]);
            View row = mPartRows[i];
            row.setActivated(on);
            View check = row.findViewById(R.id.app_check);
            check.setActivated(on);
            row.findViewById(R.id.app_check_icon).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            row.setAlpha(on ? 1f : 0.78f);
        }
        mScopeCount.setText(getContext().getString(R.string.branding_scope_count, mParts.size(), PART_ROWS.length));

        CharSequence summary = summaryText(getContext(), mMode, mParts);
        if (animate) Motion.swapText(mSummary, summary);
        else mSummary.setText(summary);
        boolean valid = !custom || !mParts.isEmpty();
        mApply.setEnabled(valid);
        mApply.setAlpha(valid ? 1f : 0.5f);
    }

    private void tintChip(TextView chip) {
        chip.setTextColor(getContext().getColor(chip.isActivated() ? R.color.hm_dialog : R.color.hm_text));
    }

    private void setCardSelected(View card, boolean selected) {
        card.setActivated(selected);
        View check = card.findViewById(R.id.am_check);
        check.setActivated(selected);
        card.findViewById(R.id.am_check_icon).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }

    /** Short, human readable description of a selection (used by the dialog and the editor row). */
    @NonNull
    public static String summaryText(@NonNull Context ctx, @NonNull BrandingMode mode, @NonNull EnumSet<BrandingPart> parts) {
        if (mode != BrandingMode.CUSTOM) return ctx.getString(R.string.branding_summary_vanilla);
        if (parts.containsAll(BrandingPart.all())) return ctx.getString(R.string.branding_summary_all);
        return ctx.getString(R.string.branding_summary_parts, partNames(ctx, parts));
    }

    /** "Main menu, Buttons & controls" style list in display order. */
    @NonNull
    public static String partNames(@NonNull Context ctx, @NonNull EnumSet<BrandingPart> parts) {
        if (parts.equals(BrandingPart.menuOnly())) return ctx.getString(R.string.branding_preset_menu);
        StringBuilder sb = new StringBuilder();
        for (Object[] row : PART_ROWS) {
            if (!parts.contains((BrandingPart) row[0])) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(ctx.getString((Integer) row[2]));
        }
        return sb.toString();
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (600 * d), metrics.widthPixels - (int) (32 * d));
        // wrap_content: the weighted scroll area shrinks to the screen when the parts list is tall
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(params);
    }
}
