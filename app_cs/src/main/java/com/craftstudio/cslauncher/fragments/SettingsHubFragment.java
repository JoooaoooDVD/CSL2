package com.craftstudio.cslauncher.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.SettingsMotion;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Settings hub (landscape settings home): a header and one artwork card per settings
 * category, laid out in two columns, plus the general toggles (force English,
 * notification permission) that used to live at the bottom of the category list.
 * Cards open their category through {@link SettingsHostFragment#openCategory}.
 */
public class SettingsHubFragment extends Fragment {
    public static final String TAG = "SettingsHubFragment";

    private static final String PKG = "com.craftstudio.cslauncher.prefs.screens.";
    public static final String JAVA = PKG + "LauncherPreferenceJavaFragment";
    public static final String MISC = PKG + "LauncherPreferenceMiscellaneousFragment";
    public static final String VIDEO = PKG + "LauncherPreferenceVideoFragment";
    public static final String EXPERIMENTAL = PKG + "LauncherPreferenceExperimentalFragment";
    public static final String APPEARANCE = PKG + "LauncherPreferenceAppearanceFragment";
    public static final String CONTROL = PKG + "LauncherPreferenceControlFragment";

    private boolean mEntrancePlayed;
    private boolean mOpening;

    public SettingsHubFragment() {
        super(R.layout.fragment_settings_hub);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mOpening = false;
        View java = bindCard(view, R.id.st_card_java, R.drawable.ic_pe_java, R.string.preference_java_title,
                R.string.preference_java_description, R.drawable.settings_art_java, JAVA);
        View video = bindCard(view, R.id.st_card_video, R.drawable.ic_st_monitor, R.string.preference_video_title,
                R.string.preference_video_description, R.drawable.settings_art_video, VIDEO);
        View appearance = bindCard(view, R.id.st_card_appearance, R.drawable.ic_pe_image, R.string.preference_appearance_title,
                R.string.preference_appearance_description, R.drawable.settings_art_appearance, APPEARANCE);
        View misc = bindCard(view, R.id.st_card_misc, R.drawable.ic_pe_cube, R.string.preference_misc_title,
                R.string.preference_misc_description, R.drawable.settings_art_misc, MISC);
        View experimental = bindCard(view, R.id.st_card_experimental, R.drawable.ic_st_flask, R.string.preference_experimental_title,
                R.string.preference_experimental_description, R.drawable.settings_art_experimental, EXPERIMENTAL);
        View control = bindCard(view, R.id.st_card_control, R.drawable.ic_pe_gamepad, R.string.preference_control_title,
                R.string.st_control_description, R.drawable.settings_art_control, CONTROL);

        bindGeneral(view);

        if (!mEntrancePlayed) {
            mEntrancePlayed = true;
            SettingsMotion.playHubEntrance(view.findViewById(R.id.st_header_tile), view.findViewById(R.id.st_title),
                    view.findViewById(R.id.st_subtitle), view.findViewById(R.id.st_panel),
                    new View[][]{{java, video, appearance}, {misc, experimental, control}},
                    view.findViewById(R.id.st_general));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateNotificationRow();
    }

    private View bindCard(View root, int id, int icon, int title, int summary, int art, String screen) {
        View card = root.findViewById(id);
        ((ImageView) card.findViewById(R.id.st_card_icon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.st_card_title)).setText(title);
        ((TextView) card.findViewById(R.id.st_card_summary)).setText(summary);
        ((ImageView) card.findViewById(R.id.st_card_art)).setImageResource(art);
        card.setContentDescription(getString(title));
        // Rounded corners also clip the artwork
        card.setClipToOutline(true);
        CSAnimationEngine.attachPressFeedback(card);
        card.setOnClickListener(v -> {
            if (mOpening) return;
            mOpening = true;
            SettingsMotion.cardTapped(v, () -> open(screen));
        });
        return card;
    }

    private void open(String screen) {
        if (!isAdded()) return;
        Fragment parent = getParentFragment();
        if (parent instanceof SettingsHostFragment && ((SettingsHostFragment) parent).openCategory(screen, null)) {
            return;
        }
        mOpening = false;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Fragment> cls = (Class<? extends Fragment>) Class.forName(screen);
            Tools.swapFragment(requireActivity(), cls, screen, null);
        } catch (Exception e) {
            Tools.showError(requireContext(), e);
        }
    }

    // ─── General toggles ─────────────────────────────────────────────────────

    /** Language list in the launcher's own popup; picking one restarts the launcher */
    private void showLanguagePicker(android.content.Context context) {
        String current = com.craftstudio.cslauncher.utils.LauncherLanguages.current(context);
        new com.craftstudio.cslauncher.prefs.SettingsPopup(context)
                .title(getString(R.string.preference_language_title))
                .subtitle(getString(R.string.language_restart_note))
                .icon(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_st_translate))
                .singleChoice(com.craftstudio.cslauncher.utils.LauncherLanguages.labels(context),
                        com.craftstudio.cslauncher.utils.LauncherLanguages.indexOf(current), index -> {
                            String tag = com.craftstudio.cslauncher.utils.LauncherLanguages.LANGUAGES[index][0];
                            if (tag.equals(current)) return;
                            Activity activity = getActivity();
                            if (activity != null) com.craftstudio.cslauncher.utils.LocaleUtils.changeLanguage(activity, tag);
                        })
                .show();
    }

    private void bindGeneral(View view) {
        View languageRow = view.findViewById(R.id.st_language_row);
        TextView languageValue = view.findViewById(R.id.st_language_value);
        languageValue.setText(com.craftstudio.cslauncher.utils.LauncherLanguages.labelOf(
                view.getContext(), com.craftstudio.cslauncher.utils.LauncherLanguages.current(view.getContext())));
        languageRow.setOnClickListener(v -> showLanguagePicker(v.getContext()));
        CSAnimationEngine.attachPressFeedback(languageRow);

        View notificationRow = view.findViewById(R.id.st_notification_row);
        notificationRow.setOnClickListener(v -> {
            Activity activity = getActivity();
            if (activity instanceof LauncherActivity) {
                ((LauncherActivity) activity).askForPermission(33, Manifest.permission.POST_NOTIFICATIONS);
            }
        });
        CSAnimationEngine.attachPressFeedback(notificationRow);
        updateNotificationRow();

        View aboutRow = view.findViewById(R.id.st_about_row);
        if (aboutRow != null) {
            aboutRow.setOnClickListener(v -> {
                if (getActivity() != null) {
                    com.craftstudio.cslauncher.Tools.swapFragmentFullScreen(requireActivity(),
                            AboutFragment.class, AboutFragment.TAG, null);
                }
            });
            CSAnimationEngine.attachPressFeedback(aboutRow);
        }
    }

    /** The permission row only shows while notifications are not allowed */
    private void updateNotificationRow() {
        View view = getView();
        if (view == null) return;
        View row = view.findViewById(R.id.st_notification_row);
        Activity activity = getActivity();
        boolean needed = activity instanceof LauncherActivity
                && !((LauncherActivity) activity).checkForPermission(33, Manifest.permission.POST_NOTIFICATIONS);
        row.setVisibility(needed ? View.VISIBLE : View.INVISIBLE);
        ViewGroup general = view.findViewById(R.id.st_general);
        if (general != null) general.setVisibility(View.VISIBLE);
    }
}
