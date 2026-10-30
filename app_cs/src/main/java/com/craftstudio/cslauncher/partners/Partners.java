package com.craftstudio.cslauncher.partners;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of official partners. The first entry is the one featured on the home screen; the
 * partner screen can show any of them by id. To add a partner: add strings, a logo and one
 * {@link Partner} below - nothing else.
 */
public final class Partners {
    public static final String OPTL = "optl";

    private static final List<Partner> ALL;

    static {
        List<Partner> list = new ArrayList<>();
        List<Partner.Benefit> optlBenefits = new ArrayList<>();
        optlBenefits.add(new Partner.Benefit(R.string.partner_optl_benefit_cooldown, R.string.partner_optl_benefit_cooldown_detail,
                true, "5", R.string.partner_days, "7"));
        optlBenefits.add(new Partner.Benefit(R.string.partner_optl_benefit_recognition, R.string.partner_optl_benefit_recognition_detail));
        optlBenefits.add(new Partner.Benefit(R.string.partner_optl_benefit_role, R.string.partner_optl_benefit_role_detail));
        optlBenefits.add(new Partner.Benefit(R.string.partner_optl_benefit_ranks, R.string.partner_optl_benefit_ranks_detail));
        optlBenefits.add(new Partner.Benefit(R.string.partner_optl_benefit_community, R.string.partner_optl_benefit_community_detail));
        list.add(new Partner(OPTL,
                R.string.partner_optl_name, R.string.partner_optl_full_name, R.string.partner_optl_section_title,
                R.string.partner_badge_official, R.string.partner_optl_description, R.string.partner_optl_explore,
                R.drawable.partner_optl_logo, optlBenefits,
                R.string.partner_optl_join, "https://discord.gg/1-21-official-pojav-tierlist-1501790037316993144",
                R.string.partner_optl_join_note));
        ALL = Collections.unmodifiableList(list);
    }

    private Partners() {}

    @NonNull public static List<Partner> all() { return ALL; }

    /** The partner showcased on the home screen (null when there is none) */
    @Nullable public static Partner featured() { return ALL.isEmpty() ? null : ALL.get(0); }

    @Nullable public static Partner byId(@Nullable String id) {
        if (id == null) return null;
        for (Partner p : ALL) if (p.id.equals(id)) return p;
        return null;
    }
}
