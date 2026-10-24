package com.craftstudio.cslauncher.tutorial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.authenticator.accounts.Accounts;
import com.craftstudio.cslauncher.fragments.InstanceEditorFragment;
import com.craftstudio.cslauncher.fragments.LogsFragment;
import com.craftstudio.cslauncher.fragments.ManageWorldsFragment;
import com.craftstudio.cslauncher.fragments.ModsSearchFragment;
import com.craftstudio.cslauncher.fragments.SettingsHostFragment;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.multiplayer.ui.VirtualLanFragment;
import com.craftstudio.cslauncher.tutorial.TutorialDefinition.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.craftstudio.cslauncher.tutorial.TutorialScreen.*;

/**
 * The feature inventory of the launcher as tutorial modules. Every target is a stable view id
 * from the real layouts (or a preference key); every explanation describes the behaviour that
 * exists in this build. Modules whose feature needs something that is not there (a selected
 * profile for the editor…) declare an availability condition and are hidden / skipped.
 */
public final class TutorialRegistry {
    public static final String HOME_TOUR = "home";
    public static final String ACCOUNTS = "accounts";
    public static final String PROFILES = "profiles";
    public static final String APPEARANCE = "appearance";
    public static final String VERSIONS = "versions";
    public static final String PLAY = "play";
    public static final String BROWSER = "browser";
    public static final String MANAGER = "manager";
    public static final String WORLDS = "worlds";
    public static final String SETTINGS = "settings";
    public static final String PERFORMANCE = "performance";
    public static final String CONTROLS = "controls";
    public static final String LOGS = "logs";
    public static final String MULTIPLAYER = "multiplayer";
    public static final String DISCORD = "discord";
    public static final String TOOLS = "tools";

    private static Map<String, TutorialDefinition> sModules;

    private TutorialRegistry() {}

    /** Modules of the first-time setup, in order */
    @NonNull
    public static List<String> onboardingSequence(boolean complete) {
        List<String> ids = new ArrayList<>();
        ids.add(HOME_TOUR);
        if (complete) ids.add(ACCOUNTS);
        ids.add(PROFILES);
        if (complete) ids.add(APPEARANCE);
        ids.add(VERSIONS);
        ids.add(PLAY);
        if (complete) {
            ids.add(BROWSER);
            ids.add(MANAGER);
            ids.add(SETTINGS);
            ids.add(PERFORMANCE);
            ids.add(CONTROLS);
            ids.add(LOGS);
            ids.add(MULTIPLAYER);
            ids.add(DISCORD);
            ids.add(TOOLS);
        }
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

    /** The tutorial that explains the screen the user is looking at (contextual help) */
    @NonNull
    public static String tutorialForScreen(@NonNull FragmentActivity activity) {
        Fragment top = activity.getSupportFragmentManager().findFragmentById(R.id.container_fragment);
        if (top instanceof InstanceEditorFragment) return PROFILES;
        if (top instanceof SettingsHostFragment) {
            String screen = ((SettingsHostFragment) top).getCurrentScreenClass();
            if (screen != null && (screen.endsWith("JavaFragment") || screen.endsWith("VideoFragment"))) return PERFORMANCE;
            if (screen != null && screen.endsWith("ControlFragment")) return CONTROLS;
            if (screen != null && screen.endsWith("MiscellaneousFragment")) return DISCORD;
            return SETTINGS;
        }
        if (top instanceof ModsSearchFragment) return BROWSER;
        if (top instanceof LogsFragment) return LOGS;
        if (top instanceof VirtualLanFragment) return MULTIPLAYER;
        if (top instanceof ManageWorldsFragment) return WORLDS;
        if (TutorialScreen.BROWSE.isOn(activity)) return BROWSER;
        if (TutorialScreen.LOGS.isOn(activity)) return LOGS;
        if (TutorialScreen.MULTIPLAYER.isOn(activity)) return MULTIPLAYER;
        if (TutorialScreen.WORLDS.isOn(activity)) return WORLDS;
        return HOME_TOUR;
    }

    // ───────────────────────────── conditions ─────────────────────────────

    private static final TutorialStep.Condition HAS_PROFILE = c -> selected() != null;
    private static final TutorialStep.Condition HAS_ACCOUNT = c -> {
        try {
            return Accounts.getCurrent() != null;
        } catch (Throwable t) {
            return false;
        }
    };
    private static final TutorialStep.Condition NO_ACCOUNT = c -> !HAS_ACCOUNT.isMet(c);

    @Nullable
    private static Instance selected() {
        try {
            return Instances.loadSelectedInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────────── modules ─────────────────────────────

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

        // ── Home screen ──────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(HOME_TOUR, R.string.tut_home_title, R.string.tut_home_summary, R.string.tut_home_desc)
                .icon(R.drawable.ic_px_home).category(Category.BASICS).beginner().recommended()
                .step(step("intro", R.string.tut_home_intro_t, R.string.tut_home_intro_b).on(HOME))
                .step(step("banner", R.string.tut_home_banner_t, R.string.tut_home_banner_b).on(HOME).target(R.id.home_banner_frame))
                .step(step("profile", R.string.tut_home_profile_t, R.string.tut_home_profile_b).on(HOME).target(R.id.home_profile_card))
                .step(step("edit", R.string.tut_home_edit_t, R.string.tut_home_edit_b).on(HOME).target(R.id.edit_profile_button))
                .step(step("play", R.string.tut_home_play_t, R.string.tut_home_play_b).on(HOME).target(R.id.play_button))
                .step(step("browse", R.string.tut_home_browse_t, R.string.tut_home_browse_b).on(HOME).target(R.id.home_card_browser))
                .step(step("manage", R.string.tut_home_manage_t, R.string.tut_home_manage_b).on(HOME).target(R.id.home_card_manager))
                .step(step("dock", R.string.tut_home_dock_t, R.string.tut_home_dock_b).on(HOME).target(R.id.top_nav_dock))
                .step(step("account", R.string.tut_home_account_t, R.string.tut_home_account_b).on(HOME).target(R.id.account_spinner))
                .step(step("settings", R.string.tut_home_settings_t, R.string.tut_home_settings_b).on(HOME).target(R.id.setting_button))
                .step(step("progress", R.string.tut_home_progress_t, R.string.tut_home_progress_b).on(HOME).target(R.id.progress_layout).optional())
                .step(step("done", R.string.tut_home_done_t, R.string.tut_home_done_b).on(HOME))
                .build());

        // ── Accounts ─────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(ACCOUNTS, R.string.tut_acc_title, R.string.tut_acc_summary, R.string.tut_acc_desc)
                .icon(R.drawable.ic_px_account_edit).category(Category.BASICS).beginner().recommended()
                .step(step("intro", R.string.tut_acc_intro_t, R.string.tut_acc_intro_b).on(HOME).target(R.id.account_spinner))
                .step(step("open", R.string.tut_acc_open_t, R.string.tut_acc_open_b).instruction(R.string.tut_acc_open_i)
                        .on(HOME).target(R.id.account_spinner).waitEvent(TutorialEvents.ACCOUNT_MENU_OPENED))
                .step(step("types", R.string.tut_acc_types_t, R.string.tut_acc_types_b).on(HOME).target(R.id.account_spinner))
                .step(step("switch", R.string.tut_acc_switch_t, R.string.tut_acc_switch_b).on(HOME).target(R.id.account_spinner).when(HAS_ACCOUNT))
                .step(step("none", R.string.tut_acc_none_t, R.string.tut_acc_none_b).on(HOME).target(R.id.account_spinner).when(NO_ACCOUNT))
                .step(step("skins", R.string.tut_acc_skins_t, R.string.tut_acc_skins_b).on(HOME).target(R.id.account_spinner))
                .step(step("privacy", R.string.tut_acc_privacy_t, R.string.tut_acc_privacy_b).on(HOME))
                .build());

        // ── Profiles ─────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(PROFILES, R.string.tut_prof_title, R.string.tut_prof_summary, R.string.tut_prof_desc)
                .icon(R.drawable.ic_px_layers_buffer).category(Category.PLAY).beginner().recommended()
                .step(step("what", R.string.tut_prof_what_t, R.string.tut_prof_what_b).on(HOME).target(R.id.home_profile_card))
                .step(step("picker", R.string.tut_prof_picker_t, R.string.tut_prof_picker_b).instruction(R.string.tut_prof_picker_i)
                        .on(HOME).target(R.id.home_profile_card)
                        .waitEvent(TutorialEvents.PROFILE_PICKER_OPENED).resumeOn(TutorialEvents.PROFILE_PICKER_CLOSED))
                .step(step("picked", R.string.tut_prof_picked_t, R.string.tut_prof_picked_b).on(HOME).target(R.id.home_profile_card))
                .step(step("create", R.string.tut_prof_create_t, R.string.tut_prof_create_b).on(HOME).target(R.id.home_banner_version_chip))
                .step(step("edit", R.string.tut_prof_edit_t, R.string.tut_prof_edit_b).instruction(R.string.tut_prof_edit_i)
                        .on(HOME).target(R.id.edit_profile_button).waitScreen(InstanceEditorFragment.class).essential().when(HAS_PROFILE))
                .step(step("name", R.string.tut_prof_name_t, R.string.tut_prof_name_b).on(PROFILE_EDITOR).target(R.id.pe_card_identity))
                .step(step("icon", R.string.tut_prof_icon_t, R.string.tut_prof_icon_b).on(PROFILE_EDITOR).target(R.id.pe_card_icon).optional())
                .step(step("version", R.string.tut_prof_version_t, R.string.tut_prof_version_b).on(PROFILE_EDITOR).target(R.id.pe_row_version))
                .step(step("runtime", R.string.tut_prof_runtime_t, R.string.tut_prof_runtime_b).on(PROFILE_EDITOR).target(R.id.pe_row_runtime))
                .step(step("jvm", R.string.tut_prof_jvm_t, R.string.tut_prof_jvm_b).on(PROFILE_EDITOR).target(R.id.pe_row_jvm))
                .step(step("renderer", R.string.tut_prof_renderer_t, R.string.tut_prof_renderer_b).on(PROFILE_EDITOR).target(R.id.pe_row_renderer))
                .step(step("controls", R.string.tut_prof_controls_t, R.string.tut_prof_controls_b).on(PROFILE_EDITOR).target(R.id.pe_row_control).optional())
                .step(step("shared", R.string.tut_prof_shared_t, R.string.tut_prof_shared_b).on(PROFILE_EDITOR).target(R.id.pe_row_shared).optional())
                .step(step("files", R.string.tut_prof_files_t, R.string.tut_prof_files_b).on(PROFILE_EDITOR).target(R.id.pe_row_files).optional())
                .step(step("shortcut", R.string.tut_prof_shortcut_t, R.string.tut_prof_shortcut_b).on(PROFILE_EDITOR).target(R.id.pe_row_shortcut).optional())
                .step(step("save", R.string.tut_prof_save_t, R.string.tut_prof_save_b).on(PROFILE_EDITOR).target(R.id.vprof_editor_save_button))
                .step(step("delete", R.string.tut_prof_delete_t, R.string.tut_prof_delete_b).on(PROFILE_EDITOR).target(R.id.vprof_editor_delete_button).optional())
                .build());

        // ── Minecraft Appearance: Vanilla vs CS Custom (per profile) + launcher theme ──
        list.add(new TutorialDefinition.Builder(APPEARANCE, R.string.tut_app_title, R.string.tut_app_summary, R.string.tut_app_desc)
                .icon(R.drawable.ic_px_palette).category(Category.PLAY).recommended().after(PROFILES)
                .availableWhen(HAS_PROFILE)
                .step(step("intro", R.string.tut_app_intro_t, R.string.tut_app_intro_b).on(PROFILE_EDITOR).target(R.id.pe_row_appearance))
                .step(step("vanilla", R.string.tut_app_vanilla_t, R.string.tut_app_vanilla_b).on(PROFILE_EDITOR).target(R.id.pe_appearance_value))
                .step(step("open", R.string.tut_app_open_t, R.string.tut_app_open_b).instruction(R.string.tut_app_open_i)
                        .on(PROFILE_EDITOR).target(R.id.pe_row_appearance).essential()
                        .waitEvent(TutorialEvents.APPEARANCE_DIALOG_OPENED).resumeOn(TutorialEvents.APPEARANCE_DIALOG_CLOSED))
                .step(step("result", R.string.tut_app_result_t, R.string.tut_app_result_b).on(PROFILE_EDITOR).target(R.id.pe_appearance_value))
                .step(step("save", R.string.tut_app_save_t, R.string.tut_app_save_b).on(PROFILE_EDITOR).target(R.id.vprof_editor_save_button))
                .step(step("back", R.string.tut_app_back_t, R.string.tut_app_back_b).instruction(R.string.tut_app_back_i)
                        .on(PROFILE_EDITOR).target(R.id.pe_row_appearance)
                        .waitEvent(TutorialEvents.APPEARANCE_DIALOG_OPENED).resumeOn(TutorialEvents.APPEARANCE_DIALOG_CLOSED))
                .step(step("theme", R.string.tut_app_theme_t, R.string.tut_app_theme_b).on(SETTINGS_APPEARANCE).targetPref("theme_mode"))
                .step(step("theme_pick", R.string.tut_app_theme_pick_t, R.string.tut_app_theme_pick_b).instruction(R.string.tut_app_theme_pick_i)
                        .on(SETTINGS_APPEARANCE).targetPref("theme_mode").waitPref("theme_mode"))
                .step(step("color", R.string.tut_app_color_t, R.string.tut_app_color_b).on(SETTINGS_APPEARANCE).targetPref("color_source"))
                .step(step("anim", R.string.tut_app_anim_t, R.string.tut_app_anim_b).on(SETTINGS_APPEARANCE).targetPref("animation_types").optional())
                .build());

        // ── Versions & installation ──────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(VERSIONS, R.string.tut_ver_title, R.string.tut_ver_summary, R.string.tut_ver_desc)
                .icon(R.drawable.ic_px_file_dl).category(Category.PLAY).beginner().recommended().after(PROFILES)
                .availableWhen(HAS_PROFILE)
                .step(step("intro", R.string.tut_ver_intro_t, R.string.tut_ver_intro_b).on(HOME).target(R.id.home_banner_version_chip))
                .step(step("row", R.string.tut_ver_row_t, R.string.tut_ver_row_b).on(PROFILE_EDITOR).target(R.id.pe_row_version))
                .step(step("pick", R.string.tut_ver_pick_t, R.string.tut_ver_pick_b).instruction(R.string.tut_ver_pick_i)
                        .on(PROFILE_EDITOR).target(R.id.pe_row_version)
                        .waitEvent(TutorialEvents.VERSION_DIALOG_OPENED).resumeOn(TutorialEvents.VERSION_DIALOG_CLOSED))
                .step(step("loaders", R.string.tut_ver_loaders_t, R.string.tut_ver_loaders_b).on(PROFILE_EDITOR).target(R.id.pe_row_version))
                .step(step("install", R.string.tut_ver_install_t, R.string.tut_ver_install_b).on(HOME).target(R.id.play_button))
                .step(step("progress", R.string.tut_ver_progress_t, R.string.tut_ver_progress_b).on(HOME).target(R.id.progress_layout).optional())
                .step(step("failed", R.string.tut_ver_failed_t, R.string.tut_ver_failed_b).on(HOME).target(R.id.nav_logs))
                .build());

        // ── Play ─────────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(PLAY, R.string.tut_play_title, R.string.tut_play_summary, R.string.tut_play_desc)
                .icon(R.drawable.ic_px_java_run).category(Category.PLAY).beginner().recommended().after(VERSIONS)
                .step(step("checklist", R.string.tut_play_check_t, R.string.tut_play_check_b).on(HOME).target(R.id.home_profile_card))
                .step(step("account", R.string.tut_play_account_t, R.string.tut_play_account_b).on(HOME).target(R.id.account_spinner).when(NO_ACCOUNT))
                .step(step("stages", R.string.tut_play_stages_t, R.string.tut_play_stages_b).on(HOME).target(R.id.play_button))
                .step(step("after", R.string.tut_play_after_t, R.string.tut_play_after_b).on(HOME).target(R.id.nav_logs))
                .step(step("go", R.string.tut_play_go_t, R.string.tut_play_go_b).instruction(R.string.tut_play_go_i)
                        .on(HOME).target(R.id.play_button).waitEvent(TutorialEvents.GAME_LAUNCHING).essential())
                .build());

        // ── Browse resources ─────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(BROWSER, R.string.tut_br_title, R.string.tut_br_summary, R.string.tut_br_desc)
                .icon(R.drawable.ic_px_book).category(Category.CONTENT).recommended()
                .step(step("open", R.string.tut_br_open_t, R.string.tut_br_open_b).instruction(R.string.tut_br_open_i)
                        .on(HOME).target(R.id.home_card_browser).waitScreen(ModsSearchFragment.class))
                .step(step("tabs", R.string.tut_br_tabs_t, R.string.tut_br_tabs_b).on(BROWSE).target(R.id.library_type_tabs))
                .step(step("context", R.string.tut_br_context_t, R.string.tut_br_context_b).on(BROWSE).target(R.id.library_context_pill).optional())
                .step(step("source", R.string.tut_br_source_t, R.string.tut_br_source_b).on(BROWSE).target(R.id.library_source_row).optional())
                .step(step("search", R.string.tut_br_search_t, R.string.tut_br_search_b).on(BROWSE).target(R.id.library_search_box))
                .step(step("filters", R.string.tut_br_filters_t, R.string.tut_br_filters_b).on(BROWSE).target(R.id.library_chips_row).optional())
                .step(step("sort", R.string.tut_br_sort_t, R.string.tut_br_sort_b).on(BROWSE).target(R.id.library_sort_button).optional())
                .step(step("grid", R.string.tut_br_grid_t, R.string.tut_br_grid_b).on(BROWSE).target(R.id.library_grid))
                .step(step("install", R.string.tut_br_install_t, R.string.tut_br_install_b).on(BROWSE))
                .step(step("import", R.string.tut_br_import_t, R.string.tut_br_import_b).on(BROWSE).target(R.id.library_import_button).optional())
                .step(step("where", R.string.tut_br_where_t, R.string.tut_br_where_b).on(BROWSE))
                .build());

        // ── Manage content ───────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(MANAGER, R.string.tut_mg_title, R.string.tut_mg_summary, R.string.tut_mg_desc)
                .icon(R.drawable.ic_px_folder).category(Category.CONTENT).after(BROWSER)
                .step(step("intro", R.string.tut_mg_intro_t, R.string.tut_mg_intro_b).on(HOME).target(R.id.home_card_manager))
                .step(step("open", R.string.tut_mg_open_t, R.string.tut_mg_open_b).instruction(R.string.tut_mg_open_i)
                        .on(HOME).target(R.id.home_card_manager)
                        .waitEvent(TutorialEvents.MANAGE_CHOOSER_OPENED).resumeOn(TutorialEvents.MANAGE_CHOOSER_CLOSED))
                .step(step("types", R.string.tut_mg_types_t, R.string.tut_mg_types_b).on(HOME).target(R.id.home_card_manager))
                .step(step("dock", R.string.tut_mg_dock_t, R.string.tut_mg_dock_b).on(HOME).target(R.id.open_files_button))
                .step(step("worlds", R.string.tut_mg_worlds_t, R.string.tut_mg_worlds_b).on(HOME).target(R.id.home_card_manager))
                .build());

        // ── Worlds ───────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(WORLDS, R.string.tut_wd_title, R.string.tut_wd_summary, R.string.tut_wd_desc)
                .icon(R.drawable.ic_px_image).category(Category.CONTENT).availableWhen(HAS_PROFILE)
                .step(step("intro", R.string.tut_wd_intro_t, R.string.tut_wd_intro_b).on(WORLDS))
                .step(step("list", R.string.tut_wd_list_t, R.string.tut_wd_list_b).on(WORLDS))
                .step(step("safety", R.string.tut_wd_safety_t, R.string.tut_wd_safety_b).on(WORLDS))
                .build());

        // ── Settings tour ────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(SETTINGS, R.string.tut_set_title, R.string.tut_set_summary, R.string.tut_set_desc)
                .icon(R.drawable.ic_px_sliders).category(Category.SETTINGS).beginner().recommended()
                .step(step("open", R.string.tut_set_open_t, R.string.tut_set_open_b).instruction(R.string.tut_set_open_i)
                        .on(HOME).target(R.id.setting_button).waitScreen(SettingsHostFragment.class))
                .step(step("rail", R.string.tut_set_rail_t, R.string.tut_set_rail_b).on(SETTINGS_HUB).target(R.id.settings_left_pane_container))
                .step(step("video", R.string.tut_set_video_t, R.string.tut_set_video_b).on(SETTINGS_VIDEO).targetPref("renderer"))
                .step(step("resolution", R.string.tut_set_res_t, R.string.tut_set_res_b).on(SETTINGS_VIDEO).targetPref("resolutionRatio"))
                .step(step("appearance", R.string.tut_set_app_t, R.string.tut_set_app_b).on(SETTINGS_APPEARANCE).targetPref("theme_mode"))
                .step(step("control", R.string.tut_set_ctl_t, R.string.tut_set_ctl_b).on(SETTINGS_CONTROL).targetPref("buttonscale"))
                .step(step("java", R.string.tut_set_java_t, R.string.tut_set_java_b).on(SETTINGS_JAVA).targetPref("allocation"))
                .step(step("misc", R.string.tut_set_misc_t, R.string.tut_set_misc_b).on(SETTINGS_MISC).targetPref("checkUpdates"))
                .step(step("language", R.string.tut_set_lang_t, R.string.tut_set_lang_b).on(SETTINGS_HUB).targetPref("launcher_language").optional())
                .step(step("experimental", R.string.tut_set_exp_t, R.string.tut_set_exp_b).on(SETTINGS_EXPERIMENTAL))
                .step(step("tutorials", R.string.tut_set_tut_t, R.string.tut_set_tut_b).on(SETTINGS_MISC).targetPref("tutorialCenter"))
                .build());

        // ── Performance & troubleshooting ────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(PERFORMANCE, R.string.tut_perf_title, R.string.tut_perf_summary, R.string.tut_perf_desc)
                .icon(R.drawable.ic_px_ram).category(Category.SETTINGS).after(SETTINGS)
                .step(step("memory", R.string.tut_perf_mem_t, R.string.tut_perf_mem_b).on(SETTINGS_JAVA).targetPref("allocation"))
                .step(step("warning", R.string.tut_perf_warn_t, R.string.tut_perf_warn_b).on(SETTINGS_MISC).targetPref("showMemoryWarning").optional())
                .step(step("renderer", R.string.tut_perf_renderer_t, R.string.tut_perf_renderer_b).on(SETTINGS_VIDEO).targetPref("renderer"))
                .step(step("resolution", R.string.tut_perf_res_t, R.string.tut_perf_res_b).on(SETTINGS_VIDEO).targetPref("resolutionRatio"))
                .step(step("sustained", R.string.tut_perf_sus_t, R.string.tut_perf_sus_b).on(SETTINGS_VIDEO).targetPref("sustainedPerformance").optional())
                .step(step("vsync", R.string.tut_perf_vsync_t, R.string.tut_perf_vsync_b).on(SETTINGS_VIDEO).targetPref("force_vsync").optional())
                .step(step("profile", R.string.tut_perf_profile_t, R.string.tut_perf_profile_b).on(PROFILE_EDITOR).target(R.id.pe_row_jvm).when(HAS_PROFILE))
                .step(step("logs", R.string.tut_perf_logs_t, R.string.tut_perf_logs_b).on(LOGS).target(R.id.logs_text))
                .step(step("retry", R.string.tut_perf_retry_t, R.string.tut_perf_retry_b).on(HOME).target(R.id.play_button))
                .build());

        // ── Controls ─────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(CONTROLS, R.string.tut_ctl_title, R.string.tut_ctl_summary, R.string.tut_ctl_desc)
                .icon(R.drawable.ic_px_gamepad).category(Category.SETTINGS)
                .step(step("touch", R.string.tut_ctl_touch_t, R.string.tut_ctl_touch_b).on(SETTINGS_CONTROL).targetPref("buttonscale"))
                .step(step("gestures", R.string.tut_ctl_gestures_t, R.string.tut_ctl_gestures_b).on(SETTINGS_CONTROL).targetPref("disableGestures"))
                .step(step("mouse", R.string.tut_ctl_mouse_t, R.string.tut_ctl_mouse_b).on(SETTINGS_CONTROL).targetPref("mousespeed"))
                .step(step("gyro", R.string.tut_ctl_gyro_t, R.string.tut_ctl_gyro_b).on(SETTINGS_CONTROL).targetPref("enableGyro").optional())
                .step(step("gamepad", R.string.tut_ctl_gamepad_t, R.string.tut_ctl_gamepad_b).on(SETTINGS_CONTROL).targetPref("gamepad_deadzone_scale").optional())
                .step(step("layout", R.string.tut_ctl_layout_t, R.string.tut_ctl_layout_b).on(PROFILE_EDITOR).target(R.id.pe_row_control).when(HAS_PROFILE).optional())
                .step(step("editor", R.string.tut_ctl_editor_t, R.string.tut_ctl_editor_b).on(HOME).target(R.id.nav_controls))
                .step(step("ingame", R.string.tut_ctl_ingame_t, R.string.tut_ctl_ingame_b).on(HOME).target(R.id.nav_controls))
                .build());

        // ── Logs ─────────────────────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(LOGS, R.string.tut_log_title, R.string.tut_log_summary, R.string.tut_log_desc)
                .icon(R.drawable.ic_px_console).category(Category.TOOLS)
                .step(step("open", R.string.tut_log_open_t, R.string.tut_log_open_b).instruction(R.string.tut_log_open_i)
                        .on(HOME).target(R.id.nav_logs).waitScreen(LogsFragment.class))
                .step(step("text", R.string.tut_log_text_t, R.string.tut_log_text_b).on(LOGS).target(R.id.logs_text))
                .step(step("reload", R.string.tut_log_reload_t, R.string.tut_log_reload_b).on(LOGS).target(R.id.logs_reload_button))
                .step(step("share", R.string.tut_log_share_t, R.string.tut_log_share_b).on(LOGS).target(R.id.logs_share_button))
                .step(step("crash", R.string.tut_log_crash_t, R.string.tut_log_crash_b).on(LOGS))
                .build());

        // ── Virtual LAN multiplayer ──────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(MULTIPLAYER, R.string.tut_mp_title, R.string.tut_mp_summary, R.string.tut_mp_desc)
                .icon(R.drawable.ic_px_zap).category(Category.TOOLS)
                .step(step("open", R.string.tut_mp_open_t, R.string.tut_mp_open_b).instruction(R.string.tut_mp_open_i)
                        .on(HOME).target(R.id.nav_multiplayer).waitScreen(VirtualLanFragment.class))
                .step(step("beta", R.string.tut_mp_beta_t, R.string.tut_mp_beta_b).on(MULTIPLAYER).target(R.id.vl_beta_card).optional())
                .step(step("create", R.string.tut_mp_create_t, R.string.tut_mp_create_b).on(MULTIPLAYER).target(R.id.vl_action_create).optional())
                .step(step("join", R.string.tut_mp_join_t, R.string.tut_mp_join_b).on(MULTIPLAYER).target(R.id.vl_join_box).optional())
                .step(step("recent", R.string.tut_mp_recent_t, R.string.tut_mp_recent_b).on(MULTIPLAYER).target(R.id.vl_recent_card).optional())
                .step(step("ingame", R.string.tut_mp_ingame_t, R.string.tut_mp_ingame_b).on(MULTIPLAYER))
                .step(step("logs", R.string.tut_mp_logs_t, R.string.tut_mp_logs_b).on(MULTIPLAYER).target(R.id.vl_send_logs).optional())
                .build());

        // ── Discord Rich Presence ────────────────────────────────────────────────────
        list.add(new TutorialDefinition.Builder(DISCORD, R.string.tut_dc_title, R.string.tut_dc_summary, R.string.tut_dc_desc)
                .icon(R.drawable.ic_discord_mark).category(Category.TOOLS)
                .step(step("intro", R.string.tut_dc_intro_t, R.string.tut_dc_intro_b).on(SETTINGS_MISC).targetPref("discordRpc"))
                .step(step("preview", R.string.tut_dc_preview_t, R.string.tut_dc_preview_b).on(SETTINGS_MISC).targetPref("discordPreview"))
                .step(step("privacy", R.string.tut_dc_privacy_t, R.string.tut_dc_privacy_b).on(SETTINGS_MISC).targetPref("discordRpc"))
                .build());

        // ── Other tools (help button, updates, shortcuts) ────────────────────────────
        list.add(new TutorialDefinition.Builder(TOOLS, R.string.tut_tools_title, R.string.tut_tools_summary, R.string.tut_tools_desc)
                .icon(R.drawable.ic_px_details).category(Category.TOOLS)
                .step(step("helpbtn", R.string.tut_tools_helpbtn_t, R.string.tut_tools_helpbtn_b).on(HOME).target(R.id.help_button))
                .step(step("updates", R.string.tut_tools_updates_t, R.string.tut_tools_updates_b).on(SETTINGS_MISC).targetPref("checkUpdates"))
                .step(step("source", R.string.tut_tools_source_t, R.string.tut_tools_source_b).on(SETTINGS_MISC).targetPref("downloadSource").optional())
                .step(step("skins", R.string.tut_tools_skins_t, R.string.tut_tools_skins_b).on(SETTINGS_MISC).targetPref("localSkins").optional())
                .step(step("shortcut", R.string.tut_tools_shortcut_t, R.string.tut_tools_shortcut_b).on(PROFILE_EDITOR).target(R.id.pe_row_shortcut).when(HAS_PROFILE).optional())
                .step(step("help", R.string.tut_tools_help_t, R.string.tut_tools_help_b).on(SETTINGS_MISC).targetPref("tutorialCenter"))
                .build());

        return list;
    }
}
