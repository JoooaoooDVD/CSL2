package com.craftstudio.cslauncher.partners;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.style.StrikethroughSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.kdt.mcgui.CSPopupDialog;

import java.util.Locale;

/**
 * Small dismissible "official partnership" announcement. Shows the featured {@link Partner};
 * "Explore" opens the dedicated partner screen, "Not now" postpones it (a few launches later),
 * and it stops for good once explored or dismissed {@link #MAX_SHOWS} times.
 */
public class PartnerPopupDialog extends CSPopupDialog {
    private static final String PREF_LAUNCHES = "cs_partner_popup_launches";
    private static final String PREF_SHOWN = "cs_partner_popup_shown_";
    private static final String PREF_DONE = "cs_partner_popup_done_";
    private static final String PREF_LAST = "cs_partner_popup_last_";
    private static final int MAX_SHOWS = 3;
    private static final int LAUNCH_GAP = 4;
    private static final long MIN_GAP = 20L * 60L * 60L * 1000L;
    private static boolean sCountedThisProcess;

    private final Partner mPartner;

    private static SharedPreferences prefs(@NonNull Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    /** Shows the popup when it is due (once per process). Returns true when it was shown. */
    public static boolean showIfDue(@NonNull Activity activity) {
        Partner partner = Partners.featured();
        if (partner == null || activity.isFinishing() || activity.isDestroyed()) return false;
        SharedPreferences p = prefs(activity);
        int launches = p.getInt(PREF_LAUNCHES, 0);
        if (!sCountedThisProcess) {
            sCountedThisProcess = true;
            launches++;
            p.edit().putInt(PREF_LAUNCHES, launches).apply();
        } else {
            return false;
        }
        if (p.getBoolean(PREF_DONE + partner.id, false)) return false;
        int shown = p.getInt(PREF_SHOWN + partner.id, 0);
        if (shown >= MAX_SHOWS) return false;
        long last = p.getLong(PREF_LAST + partner.id, 0L);
        if (shown > 0 && System.currentTimeMillis() - last < MIN_GAP) return false;
        // 1st launch: show. Afterwards every LAUNCH_GAP launches until MAX_SHOWS
        if (shown > 0 && (launches - 1) % LAUNCH_GAP != 0) return false;
        p.edit().putInt(PREF_SHOWN + partner.id, shown + 1)
                .putLong(PREF_LAST + partner.id, System.currentTimeMillis()).apply();
        new PartnerPopupDialog(activity, partner).show();
        return true;
    }

    public PartnerPopupDialog(@NonNull Context context, @NonNull Partner partner) {
        super(context);
        mPartner = partner;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_partner);
        View card = findViewById(R.id.pp_card);
        setupPopup(findViewById(R.id.pp_scrim), card, true);
        Context c = getContext();
        Partner p = mPartner;

        // Fit narrow (portrait phone) screens
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = Math.min(lp.width, m.widthPixels - (int) (24 * m.density));
        card.setLayoutParams(lp);

        ((ImageView) findViewById(R.id.pp_logo)).setImageResource(p.logo);
        findViewById(R.id.pp_logo_wrap).setClipToOutline(true);
        findViewById(R.id.pp_logo_cs_wrap).setClipToOutline(true);
        ((TextView) findViewById(R.id.pp_badge)).setText(p.badge);
        ((TextView) findViewById(R.id.pp_title)).setText(c.getString(R.string.partner_cross_fmt, c.getString(p.name)));
        ((TextView) findViewById(R.id.pp_subtitle)).setText(p.fullName);
        ((TextView) findViewById(R.id.pp_description)).setText(p.description);

        View highlight = findViewById(R.id.pp_highlight);
        Partner.Benefit hb = null;
        int others = 0;
        for (Partner.Benefit b : p.benefits) {
            if (b.highlight && hb == null) hb = b; else others++;
        }
        if (hb == null) {
            highlight.setVisibility(View.GONE);
        } else {
            ((TextView) findViewById(R.id.pp_highlight_title)).setText(hb.title);
            TextView value = findViewById(R.id.pp_highlight_value);
            TextView unit = findViewById(R.id.pp_highlight_unit);
            value.setText(hb.bigValue == null ? "" : hb.bigValue);
            if (hb.bigUnit != 0) unit.setText(c.getString(hb.bigUnit).toUpperCase(Locale.ROOT)); else unit.setVisibility(View.GONE);
            TextView old = findViewById(R.id.pp_highlight_old);
            if (hb.oldValue != null && hb.bigUnit != 0) {
                String standard = hb.oldValue + " " + c.getString(hb.bigUnit);
                SpannableString s = new SpannableString(c.getString(R.string.partner_standard_fmt, standard));
                int start = s.toString().indexOf(standard);
                if (start >= 0) s.setSpan(new StrikethroughSpan(), start, start + standard.length(), 0);
                old.setText(s);
            } else {
                old.setVisibility(View.GONE);
            }
        }
        TextView more = findViewById(R.id.pp_more);
        if (others > 0) more.setText(c.getResources().getQuantityString(R.plurals.partner_popup_more, others, others));
        else more.setVisibility(View.GONE);

        View close = findViewById(R.id.pp_close);
        View later = findViewById(R.id.pp_later);
        TextView explore = findViewById(R.id.pp_explore);
        explore.setText(p.exploreLabel);
        close.setOnClickListener(v -> onDismissRequested());
        later.setOnClickListener(v -> onDismissRequested());
        explore.setOnClickListener(v -> {
            prefs(c).edit().putBoolean(PREF_DONE + p.id, true).apply();
            CreatePopupMotion.optionChosen(explore, () -> close(() -> openPartner(c, p)));
        });
        for (View v : new View[]{close, later, explore}) CSAnimationEngine.attachPressFeedback(v);

        // Entrance: card enters, logos pop, badge/title/body cascade
        CreatePopupMotion.enter(mScrim, card);
        Motion.popIn(findViewById(R.id.pp_logo_cs_wrap), 160L, 0.6f);
        Motion.popIn(findViewById(R.id.pp_logo_wrap), 230L, 0.6f);
        Motion.popIn(findViewById(R.id.pp_badge), 300L, 0.8f);
        Motion.riseIn(findViewById(R.id.pp_title), 260L, 8f);
        Motion.riseIn(findViewById(R.id.pp_subtitle), 300L, 8f);
        Motion.cascadeChildren(findViewById(R.id.pp_body), 320L, 60L, 10f);
    }

    /** Opens the dedicated partner screen (in the home right pane when available) */
    public static void openPartner(@NonNull Context context, @NonNull Partner partner) {
        Activity activity = findActivity(context);
        if (activity instanceof FragmentActivity && !activity.isFinishing()) {
            Tools.swapFragment((FragmentActivity) activity, PartnerFragment.class, PartnerFragment.TAG, PartnerFragment.args(partner));
        }
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
