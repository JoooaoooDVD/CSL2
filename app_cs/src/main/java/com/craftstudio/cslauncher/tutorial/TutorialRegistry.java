package com.craftstudio.cslauncher.tutorial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.fragments.InstanceEditorFragment;
import com.craftstudio.cslauncher.fragments.ModsSearchFragment;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.tutorial.TutorialDefinition.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five guides of the launcher. Only the essentials are covered, every target is a stable
 * view id of a real layout, every sentence describes behaviour that exists in this build. All
 * guides are "guided": the overlay explains, Next moves on, the user never has to tap the real
 * controls while a guide is running.
 */
public final class TutorialRegistry {
    public static final String HOME_TOUR = "home";
    public static final String PROFILES = "profiles";
    public static final String LOOK = "look";
    public static final String CONTENT = "content";
    public static final String SETTINGS = "settings";

    private static Map<String, TutorialDefinition> sModules;

    private TutorialRegistry() {}

    /** The first-launch tour is the home tour alone: one screen, one minute */
    @NonNull
    public static List<String> onboardingSequence() {
        List<String> ids = new ArrayList<>();
        ids.add(HOME_TOUR);
        return ids;
    }

    @NonNull
    public static List<TutorialDefinition> all() {
        return new ArrayList<>(modules().values());
    }

    @NonNull
    public static List<String> allIds() {
        return new ArrayList<>(modules().keySet());
    }

    @Nullable
    public static TutorialDefinition get(@Nullable String id) {
        return id == null ? null : modules().get(id);
    }

    /** The guide that explains the screen the user is looking at (the "?" button) */
    @NonNull
    public static String tutorialForScreen(@NonNull FragmentActivity activity) {
        Fragment top = TutorialScreen.top(activity);
        Fragment pane = TutorialScreen.rightPane(activity);
        if (top instanceof InstanceEditorFragment || pane instanceof InstanceEditorFragment) return PROFILES;
        if (top instanceof SettingsHostFragment) return SETTINGS;
        if (top instanceof ModsSearchFragment || pane instanceof ModsSearchFragment) return CONTENT;
        return HOME_TOUR;
    }

    private static final TutorialStep.Condition HAS_PROFILE = c -> {
        try {
            return Instances.loadSelectedInstance() != null;
        } catch (Throwable t) {
            return false;
        }
    };

    @NonNull
    private static synchronized Map<String, TutorialDefinition> modules() {
        if (sModules != null) return sModules;
        Map<String, TutorialDefinition> m = new LinkedHashMap<>();
        for (TutorialDefinition d : build()) m.put(d.id, d);
        sModules = Collections.unmodifiableMap(m);
        return sModules;
    }

    private static TutorialStep.Builder step(String id, int title, int body) {
        return new TutorialStep.Builder(id, title, body);
    }

    private static List<TutorialDefinition> build() {
        List<TutorialDefinition> list = new ArrayList<>();
        final TutorialScreen HOME = TutorialScreen.HOME;
        final TutorialScreen EDITOR = TutorialScreen.PROFILE_EDITOR;
        final TutorialScreen BROWSE = TutorialScreen.BROWSE;
        final TutorialScreen HUB = TutorialScreen.SETTINGS_HUB;

        // ── 1. Launcher tour (first launch) ─────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(HOME_TOUR, R.string.tut_home_title, R.string.tut_home_summary, R.string.tut_home_desc)
                .icon(R.drawable.ic_px_home).category(Category.BASICS).beginner().recommended()
                .step(step("intro", R.string.tut_home_intro_t, R.string.tut_home_intro_b).on(HOME))
                .step(step("account", R.string.tut_home_account_t, R.string.tut_home_account_b).on(HOME).target(R.id.account_spinner))
                .step(step("profile", R.string.tut_home_profile_t, R.string.tut_home_profile_b).on(HOME).target(R.id.home_profile_card))
                .step(step("version", R.string.tut_home_version_t, R.string.tut_home_version_b).on(HOME).target(R.id.mc_version_spinner))
                .step(step("edit", R.string.tut_home_edit_t, R.string.tut_home_edit_b).on(HOME).target(R.id.edit_profile_button))
                .step(step("play", R.string.tut_home_play_t, R.string.tut_home_play_b).on(HOME).target(R.id.play_button))
                .step(step("browse", R.string.tut_home_browse_t, R.string.tut_home_browse_b).on(HOME).target(R.id.home_card_browser))
                .step(step("manage", R.string.tut_home_manage_t, R.string.tut_home_manage_b).on(HOME).target(R.id.home_card_manager))
                .step(step("settings", R.string.tut_home_settings_t, R.string.tut_home_settings_b).on(HOME).target(R.id.setting_button))
                .step(step("done", R.string.tut_home_done_t, R.string.tut_home_done_b).on(HOME))
                .build());

        // ── 2. Profiles & versions ──────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(PROFILES, R.string.tut_prof_title, R.string.tut_prof_summary, R.string.tut_prof_desc)
                .icon(R.drawable.ic_px_layers_buffer).category(Category.PLAY).beginner().recommended()
                .step(step("what", R.string.tut_prof_what_t, R.string.tut_prof_what_b).on(HOME).target(R.id.home_profile_card))
                .step(step("version", R.string.tut_prof_version_t, R.string.tut_prof_version_b).on(HOME).target(R.id.mc_version_spinner))
                .step(step("switch", R.string.tut_prof_switch_t, R.string.tut_prof_switch_b).on(HOME).target(R.id.home_play_more))
                .step(step("edit", R.string.tut_prof_edit_t, R.string.tut_prof_edit_b).on(HOME).target(R.id.edit_profile_button).when(HAS_PROFILE))
                .step(step("editor", R.string.tut_prof_editor_t, R.string.tut_prof_editor_b).on(EDITOR).target(R.id.pe_card_banner).when(HAS_PROFILE))
                .step(step("row_version", R.string.tut_prof_row_version_t, R.string.tut_prof_row_version_b).on(EDITOR).target(R.id.pe_row_version).when(HAS_PROFILE))
                .step(step("row_jvm", R.string.tut_prof_row_jvm_t, R.string.tut_prof_row_jvm_b).on(EDITOR).target(R.id.pe_row_jvm).when(HAS_PROFILE))
                .step(step("save", R.string.tut_prof_save_t, R.string.tut_prof_save_b).on(EDITOR).target(R.id.pe_action_bar).when(HAS_PROFILE))
                .build());

        // ── 3. Minecraft look: Vanilla vs CS Custom ─────────────────────────────────
        list.add(new TutorialDefinition.Builder(LOOK, R.string.tut_look_title, R.string.tut_look_summary, R.string.tut_look_desc)
                .icon(R.drawable.ic_px_palette).category(Category.PLAY).recommended().availableWhen(HAS_PROFILE)
                .step(step("intro", R.string.tut_look_intro_t, R.string.tut_look_intro_b).on(HOME).target(R.id.edit_profile_button))
                .step(step("row", R.string.tut_look_row_t, R.string.tut_look_row_b).on(EDITOR).target(R.id.pe_row_appearance))
                .step(step("vanilla", R.string.tut_look_vanilla_t, R.string.tut_look_vanilla_b).on(EDITOR).target(R.id.pe_row_appearance))
                .step(step("custom", R.string.tut_look_custom_t, R.string.tut_look_custom_b).on(EDITOR).target(R.id.pe_row_appearance))
                .step(step("how", R.string.tut_look_how_t, R.string.tut_look_how_b).on(EDITOR))
                .step(step("done", R.string.tut_look_done_t, R.string.tut_look_done_b).on(EDITOR).target(R.id.pe_action_bar))
                .build());

        // ── 4. Mods & resources ─────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(CONTENT, R.string.tut_br_title, R.string.tut_br_summary, R.string.tut_br_desc)
                .icon(R.drawable.ic_px_book).category(Category.CONTENT).beginner()
                .step(step("open", R.string.tut_br_open_t, R.string.tut_br_open_b).on(HOME).target(R.id.home_card_browser))
                .step(step("tabs", R.string.tut_br_tabs_t, R.string.tut_br_tabs_b).on(BROWSE).target(R.id.library_type_tabs_frame))
                .step(step("source", R.string.tut_br_source_t, R.string.tut_br_source_b).on(BROWSE).target(R.id.library_source_frame).optional())
                .step(step("search", R.string.tut_br_search_t, R.string.tut_br_search_b).on(BROWSE).target(R.id.library_toolbar))
                .step(step("grid", R.string.tut_br_grid_t, R.string.tut_br_grid_b).on(BROWSE).target(R.id.library_grid))
                .step(step("import", R.string.tut_br_import_t, R.string.tut_br_import_b).on(BROWSE).target(R.id.library_import_button).optional())
                .step(step("manage", R.string.tut_br_manage_t, R.string.tut_br_manage_b).on(HOME).target(R.id.home_card_manager))
                .build());

        // ── 5. Settings & themes ────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(SETTINGS, R.string.tut_set_title, R.string.tut_set_summary, R.string.tut_set_desc)
                .icon(R.drawable.ic_px_sliders).category(Category.SETTINGS).beginner()
                .step(step("open", R.string.tut_set_open_t, R.string.tut_set_open_b).on(HOME).target(R.id.setting_button))
                .step(step("appearance", R.string.tut_set_appearance_t, R.string.tut_set_appearance_b).on(HUB).target(R.id.st_card_appearance))
                .step(step("video", R.string.tut_set_video_t, R.string.tut_set_video_b).on(HUB).target(R.id.st_card_video))
                .step(step("java", R.string.tut_set_java_t, R.string.tut_set_java_b).on(HUB).target(R.id.st_card_java))
                .step(step("control", R.string.tut_set_control_t, R.string.tut_set_control_b).on(HUB).target(R.id.st_card_control))
                .step(step("misc", R.string.tut_set_misc_t, R.string.tut_set_misc_b).on(HUB).target(R.id.st_card_misc))
                .step(step("language", R.string.tut_set_language_t, R.string.tut_set_language_b).on(HUB).target(R.id.st_language_row).optional())
                .build());
        return list;
    }
}
