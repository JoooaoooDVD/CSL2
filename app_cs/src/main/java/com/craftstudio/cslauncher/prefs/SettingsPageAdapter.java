package com.craftstudio.cslauncher.prefs;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.craftstudio.cslauncher.R;

/**
 * Adapter of the settings category pages. Consecutive rows (everything that is not a section
 * heading, the page head or the back row) are drawn as one rounded card: the first row gets the
 * top corners, the last one the bottom corners and a hairline separates the rows in between.
 * List rows additionally show their current value as a chip next to the chevron.
 */
@android.annotation.SuppressLint("RestrictedApi")
public class SettingsPageAdapter extends PreferenceGroupAdapter {
    public SettingsPageAdapter(@NonNull PreferenceScreen screen) {
        super(screen);
    }

    private static boolean isRow(Preference p) {
        return p != null && !(p instanceof PreferenceCategory) && !(p instanceof BackButtonPreference)
                && !(p instanceof SettingsHeaderPreference);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        Preference preference = getItem(position);
        if (!isRow(preference)) return;
        View item = holder.itemView;
        float density = item.getResources().getDisplayMetrics().density;

        boolean prevRow = position > 0 && isRow(getItem(position - 1));
        boolean nextRow = position + 1 < getItemCount() && isRow(getItem(position + 1));
        int bg;
        if (!prevRow && !nextRow) bg = R.drawable.bg_sr_row_single;
        else if (!prevRow) bg = R.drawable.bg_sr_row_top;
        else if (!nextRow) bg = R.drawable.bg_sr_row_bottom;
        else bg = R.drawable.bg_sr_row_mid;
        Object tag = item.getTag(R.id.sr_divider);
        if (!(tag instanceof Integer) || (Integer) tag != bg) {
            item.setBackgroundResource(bg);
            item.setTag(R.id.sr_divider, bg);
        }

        View divider = item.findViewById(R.id.sr_divider);
        if (divider != null) divider.setVisibility(nextRow ? View.VISIBLE : View.GONE);

        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            int bottom = nextRow ? 0 : Math.round(12 * density);
            if (mlp.bottomMargin != bottom || mlp.leftMargin != 0) {
                mlp.bottomMargin = bottom;
                mlp.leftMargin = 0;
                mlp.rightMargin = 0;
                item.setLayoutParams(mlp);
            }
        }

        // Rows without an icon: the text starts at the card padding, not after an empty tile
        View icon = holder.findViewById(android.R.id.icon);
        View title = holder.findViewById(android.R.id.title);
        if (title != null && title.getParent() instanceof View) {
            View column = (View) title.getParent();
            ViewGroup.LayoutParams clp = column.getLayoutParams();
            if (clp instanceof ViewGroup.MarginLayoutParams) {
                int want = (icon == null || icon.getVisibility() == View.GONE) ? 0 : Math.round(13 * density);
                if (((ViewGroup.MarginLayoutParams) clp).leftMargin != want) {
                    ((ViewGroup.MarginLayoutParams) clp).leftMargin = want;
                    column.setLayoutParams(clp);
                }
            }
        }
        View seek = holder.findViewById(R.id.seekbar);
        if (seek != null && seek.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams slp = (ViewGroup.MarginLayoutParams) seek.getLayoutParams();
            int want = (icon == null || icon.getVisibility() == View.GONE) ? 0 : Math.round(53 * density);
            if (slp.leftMargin != want) {
                slp.leftMargin = want;
                seek.setLayoutParams(slp);
            }
        }
        if (divider != null && divider.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams dlp = (ViewGroup.MarginLayoutParams) divider.getLayoutParams();
            int want = Math.round(((icon == null || icon.getVisibility() == View.GONE) ? 14 : 67) * density);
            if (dlp.leftMargin != want) {
                dlp.leftMargin = want;
                divider.setLayoutParams(dlp);
            }
        }

        // List rows: current value as a chip; the plain summary provider would only repeat it
        View chip = holder.findViewById(R.id.sr_value);
        if (chip instanceof TextView && preference instanceof ListPreference) {
            ListPreference list = (ListPreference) preference;
            CharSequence entry = list.getEntry();
            ((TextView) chip).setText(entry);
            chip.setVisibility(entry == null || entry.length() == 0 ? View.GONE : View.VISIBLE);
            View summary = holder.findViewById(android.R.id.summary);
            if (summary != null && list.getSummaryProvider() != null) summary.setVisibility(View.GONE);
        }
    }
}
