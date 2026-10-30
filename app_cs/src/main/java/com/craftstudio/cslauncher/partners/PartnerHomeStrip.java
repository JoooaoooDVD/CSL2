package com.craftstudio.cslauncher.partners;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;

/**
 * Binds the featured partner into the home screen strip ({@code view_partner_strip.xml}). The
 * home fragment only knows "there is a strip, tapping it opens the partner screen"; which partner
 * and what it says lives in {@link Partners}.
 */
public final class PartnerHomeStrip {
    public interface Opener { void open(@NonNull Partner partner); }

    private PartnerHomeStrip() {}

    /** @return the strip view when a partner is shown, null when the strip was hidden */
    @Nullable
    public static View bind(@NonNull View root, @NonNull Opener opener) {
        View strip = root.findViewById(R.id.partner_strip);
        if (strip == null) return null;
        Partner partner = Partners.featured();
        if (partner == null) {
            strip.setVisibility(View.GONE);
            return null;
        }
        ((ImageView) strip.findViewById(R.id.partner_strip_logo)).setImageResource(partner.logo);
        ((TextView) strip.findViewById(R.id.partner_strip_badge)).setText(partner.badge);
        ((TextView) strip.findViewById(R.id.partner_strip_eyebrow)).setText(partner.sectionTitle);
        ((TextView) strip.findViewById(R.id.partner_strip_title)).setText(
                strip.getContext().getString(R.string.partner_cross_fmt, strip.getContext().getString(partner.name)));
        ((TextView) strip.findViewById(R.id.partner_strip_explore)).setText(partner.exploreLabel);
        View logoWrap = strip.findViewById(R.id.partner_strip_logo_wrap);
        logoWrap.setClipToOutline(true);
        strip.setClipToOutline(true);
        strip.setOnClickListener(v -> opener.open(partner));
        strip.setContentDescription(strip.getContext().getString(partner.sectionTitle));
        try { CSAnimationEngine.attachPressFeedback(strip); } catch (Throwable ignored) {}
        strip.setVisibility(View.VISIBLE);
        return strip;
    }
}
