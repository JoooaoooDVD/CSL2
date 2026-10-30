package com.craftstudio.cslauncher.partners;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.List;

/**
 * One official CS Launcher partner. Pure data: everything the home strip and the partner screen
 * show comes from here, so adding a partner means adding an entry to {@link Partners} - no UI
 * code touches a partner by name.
 */
public final class Partner {
    /** A benefit line on the partner screen; {@code highlight} rows get the big featured treatment */
    public static final class Benefit {
        @StringRes public final int title;
        @StringRes public final int detail;      // 0 = none
        public final boolean highlight;
        @Nullable public final String bigValue;   // e.g. "5" days, shown large on highlighted rows
        @StringRes public final int bigUnit;      // e.g. "days"
        @Nullable public final String oldValue;   // e.g. "7" (struck through)

        public Benefit(@StringRes int title, @StringRes int detail) {
            this(title, detail, false, null, 0, null);
        }

        public Benefit(@StringRes int title, @StringRes int detail, boolean highlight,
                       @Nullable String bigValue, @StringRes int bigUnit, @Nullable String oldValue) {
            this.title = title;
            this.detail = detail;
            this.highlight = highlight;
            this.bigValue = bigValue;
            this.bigUnit = bigUnit;
            this.oldValue = oldValue;
        }
    }

    @NonNull public final String id;
    @StringRes public final int name;            // "OPTL"
    @StringRes public final int fullName;        // "Official Pojav Tierlist"
    @StringRes public final int sectionTitle;    // "Official Partner – OPTL"
    @StringRes public final int badge;           // "OFFICIAL PARTNER"
    @StringRes public final int description;
    @StringRes public final int exploreLabel;    // "Explore OPTL"
    @DrawableRes public final int logo;
    @NonNull public final List<Benefit> benefits;
    @StringRes public final int joinLabel;       // "JOIN OFFICIAL OPTL DISCORD"
    @NonNull public final String joinUrl;
    @StringRes public final int joinNote;        // small note under the button (0 = none)

    public Partner(@NonNull String id, int name, int fullName, int sectionTitle, int badge, int description,
                   int exploreLabel, int logo, @NonNull List<Benefit> benefits, int joinLabel,
                   @NonNull String joinUrl, int joinNote) {
        this.id = id;
        this.name = name;
        this.fullName = fullName;
        this.sectionTitle = sectionTitle;
        this.badge = badge;
        this.description = description;
        this.exploreLabel = exploreLabel;
        this.logo = logo;
        this.benefits = Collections.unmodifiableList(benefits);
        this.joinLabel = joinLabel;
        this.joinUrl = joinUrl;
        this.joinNote = joinNote;
    }
}
