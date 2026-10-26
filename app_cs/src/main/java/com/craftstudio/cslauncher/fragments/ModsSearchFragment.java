package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.ContentInstaller;
import com.craftstudio.cslauncher.modding.modpacks.InstalledContentIndex;
import com.craftstudio.cslauncher.modding.modpacks.LibraryCardAdapter;
import com.craftstudio.cslauncher.modding.modpacks.LibraryCatalog;
import com.craftstudio.cslauncher.modding.modpacks.api.CommonApi;
import com.craftstudio.cslauncher.modding.modpacks.api.LocalModpackImporter;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchFilters;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchResult;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.progresskeeper.TaskCountListener;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.ThemeColors;
import com.craftstudio.cslauncher.utils.motion.LibraryMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.craftstudio.cslauncher.utils.profiles.VersionSelectorDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Content library: one screen to browse mods, resource packs and shaders for the selected
 * profile. The content type is picked with the tabs in the header, categories with the chips
 * next to the search box. Results show up as cards in a grid; tapping a card (or its Install /
 * Update button) opens {@link ModInstallFragment}, while installed projects get a red
 * Uninstall button right on the card.
 * <p>
 * Opened with {@link #ARG_CONTENT_TYPE} = MODPACK the same screen becomes the modpack browser:
 * the type tabs make way for an Import button, nothing is tied to the selected profile and the
 * install page creates a new profile from the chosen pack.
 */
public class ModsSearchFragment extends Fragment implements LibraryCardAdapter.Listener {

    public static final String TAG = "ModsSearchFragment";

    /** Bundle key: pre-seed the MC version filter (e.g. "1.20.1"). */
    public static final String ARG_PRESET_MC_VERSION = "preset_mc_version";
    /** Bundle key: pre-seed the mod loader filter ("fabric","forge","quilt","neoforge"). */
    public static final String ARG_PRESET_LOADER = "preset_loader";
    /** Bundle key: which content type to open on, a ContentType name(). Defaults to MOD. */
    public static final String ARG_CONTENT_TYPE = "content_type";

    private static final ExecutorService sLibraryExecutor = Executors.newFixedThreadPool(3);
    private static final float NARROW_TOOLBAR_DP = 640f;
    private static final float NARROW_HEADER_DP = 560f;
    private static final float MIN_CARD_WIDTH_DP = 236f;

    private final SearchFilters mFilters = new SearchFilters();
    private final Map<ContentType, InstalledContentIndex> mIndexCache = new EnumMap<>(ContentType.class);
    private final List<ModItem> mItems = new ArrayList<>();

    private CommonApi mApi;
    private String mCurseforgeKey;
    private ModIconCache mIconCache;
    private boolean mInitialized;

    /** Selected profile, used for compatibility badges and the installed-state lookup */
    @Nullable private String mProfileVersion;
    private String mProfileLoader = "";
    /** Loader filter for mod searches; resource packs and shaders never filter by loader */
    private String mPreferredLoader = "";

    private SearchFilters mActiveQuery;
    private SearchResult mLastResult;
    private boolean mLastPage;
    private boolean mLoadingMore;
    private boolean mSearchFailed;
    private int mSearchGeneration;
    private int mIndexGeneration;
    private Future<?> mSearchTask;
    private int mLastTaskCount;

    // Views, null while the view is destroyed
    private TextView mTitle, mSubtitle, mContextPill;
    private TextView mTabMods, mTabResourcePacks, mTabShaders, mTabWorlds;
    private EditText mSearchInput;
    private LinearLayout mChips;
    private View mProgress;
    private RecyclerView mGrid;
    private GridLayoutManager mGridLayout;
    private LibraryCardAdapter mAdapter;
    private View mStateContainer, mStateLoading, mStateRetry;
    private ImageView mStateIcon;
    private TextView mStateText;
    private View mTabIndicator, mTypeTabsFrame, mToolbar, mSearchBox, mSortChevron, mImportButton;
    // Modrinth / CurseForge source switcher
    private View mSourceFrame, mSourceIndicator, mSourceModrinth, mSourceCurseforge;
    /** BASE OPTIMISE MOD card above the grid (Mods tab only, needs CurseForge) */
    @Nullable private com.craftstudio.cslauncher.modding.modpacks.BaseOptimiseCard mFeatured;
    private static final String PREF_LIBRARY_SOURCE = "libraryContentSource";

    private final ActivityResultLauncher<String> mImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::importLocalModpack);

    private final TaskCountListener mTaskCountListener = taskCount -> {
        Tools.runOnUiThread(() -> {
            boolean finished = mLastTaskCount > 0 && taskCount == 0;
            mLastTaskCount = taskCount;
            if (finished && getView() != null) refreshInstalled();
        });
        return false;
    };

    public ModsSearchFragment() {
        super(R.layout.fragment_mod_library);
        mFilters.isModpack = false;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (mApi == null) {
            mCurseforgeKey = LauncherPreferences.PREF_DISABLE_CURSEFORGE_API
                    ? null : LauncherPreferences.resolveCurseforgeApiKey(context);
            mApi = new CommonApi(mCurseforgeKey != null ? mCurseforgeKey : "", mCurseforgeKey == null);
        }
        if (mIconCache == null) mIconCache = new ModIconCache();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mTitle = view.findViewById(R.id.library_title);
        mSubtitle = view.findViewById(R.id.library_subtitle);
        mContextPill = view.findViewById(R.id.library_context_pill);
        mTabMods = view.findViewById(R.id.library_tab_mods);
        mTabResourcePacks = view.findViewById(R.id.library_tab_resourcepacks);
        mTabShaders = view.findViewById(R.id.library_tab_shaders);
        mTabWorlds = view.findViewById(R.id.library_tab_worlds);
        mSearchInput = view.findViewById(R.id.library_search_input);
        mChips = view.findViewById(R.id.library_chips);
        mProgress = view.findViewById(R.id.library_progress);
        mGrid = view.findViewById(R.id.library_grid);
        mStateContainer = view.findViewById(R.id.library_state);
        mStateLoading = view.findViewById(R.id.library_state_loading);
        mStateIcon = view.findViewById(R.id.library_state_icon);
        mStateText = view.findViewById(R.id.library_state_text);
        mStateRetry = view.findViewById(R.id.library_state_retry);
        mTabIndicator = view.findViewById(R.id.library_tab_indicator);
        mTypeTabsFrame = view.findViewById(R.id.library_type_tabs_frame);
        mToolbar = view.findViewById(R.id.library_toolbar);
        setupHeaderCollapse(view);
        mSearchBox = view.findViewById(R.id.library_search_box);
        mSortChevron = view.findViewById(R.id.library_sort_chevron);
        mImportButton = view.findViewById(R.id.library_import_button);
        mImportButton.setOnClickListener(v -> {
            if (ProgressKeeper.getTaskCount() > 0) {
                Toast.makeText(requireContext(), R.string.lib_tasks_running, Toast.LENGTH_SHORT).show();
                return;
            }
            mImportLauncher.launch("*/*");
        });
        CSAnimationEngine.attachPressFeedback(mImportButton);

        if (!mInitialized) resolveProfile();

        mAdapter = new LibraryCardAdapter(mIconCache, this);
        View featuredRoot = view.findViewById(R.id.library_featured);
        if (featuredRoot != null && mCurseforgeKey != null) {
            mFeatured = new com.craftstudio.cslauncher.modding.modpacks.BaseOptimiseCard(featuredRoot, mIconCache, mCurseforgeKey,
                    new com.craftstudio.cslauncher.modding.modpacks.BaseOptimiseCard.Host() {
                        @Override public void onOpenDetails(@NonNull ModItem item) { openInstallScreen(item); }
                        @Override public void onInstalledChanged() { refreshInstalled(); }
                    });
            mFeatured.setProfile(mProfileVersion, mProfileLoader);
            mFeatured.setEnabled(false);
        }
        mGridLayout = new GridLayoutManager(requireContext(), 3);
        mGrid.setLayoutManager(mGridLayout);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mFeatured == null) return;
                // Hide the featured card while browsing, bring it back once the grid is at the top
                boolean atTop = !rv.canScrollVertically(-1);
                if (atTop) mFeatured.setCollapsed(false);
                else if (dy > 0) mFeatured.setCollapsed(true);
            }
        });
        mGrid.setAdapter(mAdapter);
        mGrid.setItemAnimator(null);
        mGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) updateSpanCount(r - l);
        });

        setupTabs(view);
        setupSource(view);
        setupSearch(view);
        setupSort(view);
        mContextPill.setOnClickListener(v -> displayFilterDialog());
        mStateRetry.setOnClickListener(v -> startSearch());
        view.post(() -> adaptToWidth(view));

        if (!mInitialized) {
            mInitialized = true;
            applyPresetArgs();
            if (mFilters.contentType == ContentType.MOD && !modsSupported()) {
                // Vanilla / OptiFine profile: land on resource packs instead of a tab that can't be used
                mFilters.contentType = ContentType.RESOURCE_PACK;
            }
            mFilters.engine = restoreSource();
            applyContentType(mFilters.contentType, false);
            applySource(mFilters.engine, false);
            startSearch();
        } else {
            applyContentType(mFilters.contentType, false);
            applySource(mFilters.engine, false);
            mSearchInput.setText(mFilters.name);
            if (mItems.isEmpty()) {
                startSearch();
            } else {
                mAdapter.setItems(mItems, false);
                showContent();
            }
        }
        InstalledContentIndex cached = mIndexCache.get(mFilters.contentType);
        if (cached != null) mAdapter.setInstalledIndex(cached);
        if (mFeatured != null) {
            InstalledContentIndex mods = mIndexCache.get(ContentType.MOD);
            if (mods != null) mFeatured.setInstalledIndex(mods);
        }
        refreshInstalled();

        mLastTaskCount = ProgressKeeper.getTaskCount();
        ProgressKeeper.addTaskCountListener(mTaskCountListener, false);

        LibraryMotion.playEntrance(mTitle, mSubtitle, isModpackMode() ? mImportButton : mTypeTabsFrame, mToolbar, mChips);
        Motion.riseIn(mSourceFrame, 90L, 10f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mTaskCountListener);
        if (mFeatured != null) {
            mFeatured.destroy();
            mFeatured = null;
        }
        mTitle = mSubtitle = mContextPill = mTabMods = mTabResourcePacks = mTabShaders = mTabWorlds = mStateText = null;
        mSourceFrame = mSourceIndicator = mSourceModrinth = mSourceCurseforge = null;
        mSearchInput = null;
        mChips = null;
        mProgress = null;
        mGrid = null;
        mGridLayout = null;
        mAdapter = null;
        mStateContainer = mStateLoading = mStateRetry = null;
        mStateIcon = null;
        mTabIndicator = mTypeTabsFrame = mToolbar = mSearchBox = mSortChevron = mImportButton = null;
    }

    /** Modpack browser: no profile context, installs create a profile */
    private boolean isModpackMode() {
        return mFilters.contentType == ContentType.MODPACK;
    }

    /** A .mrpack / CurseForge .zip picked from the device becomes a profile */
    private void importLocalModpack(@Nullable Uri uri) {
        if (uri == null || getContext() == null) return;
        LocalModpackImporter.importAsync(requireContext(), uri, mApi);
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    private void resolveProfile() {
        String key = Instances.getSelectedInstanceKey();
        if (key == null || key.isEmpty()) return;
        InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(key);
        mProfileVersion = info.mcVersion;
        mProfileLoader = info.loader == null ? "" : info.loader;
    }

    private void applyPresetArgs() {
        Bundle args = getArguments();
        String version = mProfileVersion;
        String loader = mProfileLoader;
        if (args != null) {
            String type = args.getString(ARG_CONTENT_TYPE, null);
            if (type != null) {
                try {
                    mFilters.contentType = ContentType.valueOf(type);
                } catch (IllegalArgumentException ignored) {}
            }
            String presetVersion = args.getString(ARG_PRESET_MC_VERSION, null);
            String presetLoader = args.getString(ARG_PRESET_LOADER, null);
            if (presetVersion != null && !presetVersion.isEmpty()) version = presetVersion;
            if (presetLoader != null && !presetLoader.isEmpty()) loader = presetLoader;
        }
        if (isModpackMode()) {
            // A modpack brings its own version and loader, so nothing is pre-filtered unless asked
            boolean presetVersion = args != null && !isEmpty(args.getString(ARG_PRESET_MC_VERSION, null));
            boolean presetLoader = args != null && !isEmpty(args.getString(ARG_PRESET_LOADER, null));
            mFilters.mcVersion = presetVersion ? version : "";
            mPreferredLoader = presetLoader ? loader : "";
            return;
        }
        mFilters.mcVersion = version == null ? "" : version;
        mPreferredLoader = loader == null ? "" : loader;
    }

    /**
     * Mods only make sense on a profile with a mod loader. Vanilla and OptiFine profiles have no
     * mods folder the game would read, so the Mods tab is shown greyed out and inert there while
     * resource packs, shaders and worlds stay available.
     */
    private boolean modsSupported() {
        return LibraryCatalog.isModLoader(mProfileLoader);
    }

    private void applyModsAvailability() {
        if (mTabMods == null) return;
        boolean ok = modsSupported();
        mTabMods.setEnabled(ok);
        mTabMods.setAlpha(ok ? 1f : 0.38f);
        mTabMods.setContentDescription(ok ? getString(R.string.lib_tab_mods) : getString(R.string.lib_mods_need_loader));
    }

    private void setupTabs(View view) {
        View.OnClickListener listener = v -> {
            ContentType type = v == mTabResourcePacks ? ContentType.RESOURCE_PACK
                    : v == mTabShaders ? ContentType.SHADER_PACK
                    : v == mTabWorlds ? ContentType.WORLD : ContentType.MOD;
            if (type == ContentType.MOD && !modsSupported()) {
                Toast.makeText(requireContext(), R.string.lib_mods_need_loader, Toast.LENGTH_SHORT).show();
                return;
            }
            if (type == mFilters.contentType || isModpackMode()) return;
            if (type == ContentType.WORLD && mCurseforgeKey == null) {
                Toast.makeText(requireContext(), R.string.lib_worlds_curseforge_only, Toast.LENGTH_SHORT).show();
                return;
            }
            mItems.clear();
            applyContentType(type, true);
            startSearch();
            refreshInstalled();
        };
        for (TextView tab : new TextView[]{mTabMods, mTabResourcePacks, mTabShaders, mTabWorlds}) {
            tab.setOnClickListener(listener);
            CSAnimationEngine.attachPressFeedback(tab);
        }
        applyModsAvailability();
    }

    // ─── Content source (Modrinth / CurseForge) ──────────────────────────────

    private void setupSource(View view) {
        mSourceFrame = view.findViewById(R.id.library_source_frame);
        mSourceIndicator = view.findViewById(R.id.library_source_indicator);
        mSourceModrinth = view.findViewById(R.id.library_source_modrinth);
        mSourceCurseforge = view.findViewById(R.id.library_source_curseforge);
        if (mSourceFrame == null) return;

        boolean curseforgeAvailable = mCurseforgeKey != null;
        mSourceCurseforge.setVisibility(curseforgeAvailable ? View.VISIBLE : View.GONE);

        View.OnClickListener listener = v -> {
            int engine = v == mSourceCurseforge ? Constants.ENGINE_CURSEFORGE : Constants.ENGINE_MODRINTH;
            if (mFilters.contentType == ContentType.WORLD && engine != Constants.ENGINE_CURSEFORGE) {
                Motion.shake(v);
                Toast.makeText(requireContext(), R.string.lib_worlds_curseforge_only, Toast.LENGTH_SHORT).show();
                return;
            }
            if (engine == mFilters.engine) {
                Motion.bounce(v.findViewById(engine == Constants.ENGINE_CURSEFORGE
                        ? R.id.library_source_curseforge_logo : R.id.library_source_modrinth_logo));
                return;
            }
            mFilters.engine = engine;
            LauncherPreferences.DEFAULT_PREF.edit().putInt(PREF_LIBRARY_SOURCE, engine).apply();
            mItems.clear();
            applySource(engine, true);
            startSearch();
            Toast.makeText(requireContext(), engine == Constants.ENGINE_CURSEFORGE
                    ? R.string.lib_source_switched_curseforge : R.string.lib_source_switched_modrinth,
                    Toast.LENGTH_SHORT).show();
        };
        mSourceModrinth.setOnClickListener(listener);
        mSourceCurseforge.setOnClickListener(listener);
        CSAnimationEngine.attachPressFeedback(mSourceModrinth);
        CSAnimationEngine.attachPressFeedback(mSourceCurseforge);
    }

    /** While browsing worlds the Modrinth option is dimmed and ignored */
    private void setSourceLocked(boolean locked) {
        if (mSourceModrinth == null) return;
        mSourceModrinth.setEnabled(!locked);
        mSourceModrinth.animate().cancel();
        mSourceModrinth.animate().alpha(locked ? 0.35f : 1f).setDuration(Motion.MEDIUM).start();
    }

    /** Last source the user picked, falling back to Modrinth when CurseForge is unavailable */
    private int restoreSource() {
        if (mCurseforgeKey == null) return Constants.ENGINE_MODRINTH;
        int saved = LauncherPreferences.DEFAULT_PREF.getInt(PREF_LIBRARY_SOURCE, Constants.ENGINE_MODRINTH);
        return saved == Constants.ENGINE_CURSEFORGE ? Constants.ENGINE_CURSEFORGE : Constants.ENGINE_MODRINTH;
    }

    /** Glides the brand pill under the chosen source and recolours both options */
    private void applySource(int engine, boolean userAction) {
        if (mSourceFrame == null) return;
        if (engine == Constants.ENGINE_BOTH) engine = Constants.ENGINE_MODRINTH;
        if (mCurseforgeKey == null) engine = Constants.ENGINE_MODRINTH;
        mFilters.engine = engine;
        boolean curseforge = engine == Constants.ENGINE_CURSEFORGE;
        View selected = curseforge ? mSourceCurseforge : mSourceModrinth;

        mSourceIndicator.setBackgroundResource(curseforge
                ? R.drawable.bg_lib_source_indicator_curseforge : R.drawable.bg_lib_source_indicator_modrinth);
        LibraryMotion.moveIndicator(mSourceIndicator, selected, userAction);

        styleSourceOption(mSourceModrinth, R.id.library_source_modrinth_title, R.id.library_source_modrinth_desc,
                R.id.library_source_modrinth_check, R.id.library_source_modrinth_logo,
                R.color.lib_src_modrinth, !curseforge, userAction);
        styleSourceOption(mSourceCurseforge, R.id.library_source_curseforge_title, R.id.library_source_curseforge_desc,
                R.id.library_source_curseforge_check, R.id.library_source_curseforge_logo,
                R.color.lib_src_curseforge, curseforge, userAction);
        if (userAction && mGrid != null) { mGrid.scrollToPosition(0); if (mFeatured != null) mFeatured.setCollapsed(false); }
    }

    private void styleSourceOption(View option, int titleId, int descId, int checkId, int logoId,
                                   int brandColorRes, boolean selected, boolean animate) {
        if (option == null) return;
        android.content.Context context = option.getContext();
        TextView title = option.findViewById(titleId);
        TextView desc = option.findViewById(descId);
        View check = option.findViewById(checkId);
        View logo = option.findViewById(logoId);
        int brand = androidx.core.content.ContextCompat.getColor(context, brandColorRes);
        int onSurface = ThemeColors.get(context, com.google.android.material.R.attr.colorOnSurface);
        int dim = ThemeColors.get(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
        LibraryMotion.tintTab(title, selected ? onSurface : dim, animate);
        LibraryMotion.tintTab(desc, selected ? brand : dim, animate);

        float logoScale = selected ? 1f : 0.86f;
        float logoAlpha = selected ? 1f : 0.55f;
        float checkAlpha = selected ? 1f : 0f;
        float checkScale = selected ? 1f : 0.4f;
        if (animate && Motion.buttons(option)) {
            logo.animate().cancel();
            logo.animate().scaleX(logoScale).scaleY(logoScale).alpha(logoAlpha)
                    .setDuration(Motion.MEDIUM).setInterpolator(selected ? Motion.SPRING : Motion.STANDARD).start();
            check.animate().cancel();
            check.animate().alpha(checkAlpha).scaleX(checkScale).scaleY(checkScale)
                    .setDuration(Motion.MEDIUM).setInterpolator(selected ? Motion.SPRING_STRONG : Motion.EXIT).start();
            if (selected) Motion.pulse(logo);
        } else {
            logo.setScaleX(logoScale); logo.setScaleY(logoScale); logo.setAlpha(logoAlpha);
            check.setAlpha(checkAlpha); check.setScaleX(checkScale); check.setScaleY(checkScale);
        }
    }

    private void setupSearch(View view) {
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_NULL) {
                submitSearchText();
                return true;
            }
            return false;
        });
        View clear = view.findViewById(R.id.library_search_clear);
        clear.setOnClickListener(v -> {
            boolean hadText = mSearchInput.getText().length() > 0 || !isEmpty(mFilters.name);
            mSearchInput.setText("");
            hideKeyboard();
            if (hadText) {
                mFilters.name = "";
                startSearch();
            }
        });
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                LibraryMotion.clearButton(clear, s.length() > 0);
            }
        });
        boolean hasText = mSearchInput.getText().length() > 0;
        clear.setAlpha(hasText ? 1f : 0.35f);
        clear.setScaleX(hasText ? 1f : 0.75f);
        clear.setScaleY(hasText ? 1f : 0.75f);
        mSearchInput.setOnFocusChangeListener((v, focused) -> LibraryMotion.searchFocus(mSearchBox, focused));
    }

    private void submitSearchText() {
        mFilters.name = mSearchInput.getText().toString().trim();
        hideKeyboard();
        startSearch();
    }

    private void hideKeyboard() {
        if (mSearchInput == null) return;
        mSearchInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
    }

    private static final int MENU_GROUP_SORT = 1;
    private static final int MENU_GROUP_FILTERS = 2;
    private static final String[] SORT_VALUES = {
            SearchFilters.SORT_RELEVANCE, SearchFilters.SORT_DOWNLOADS, SearchFilters.SORT_FOLLOWS,
            SearchFilters.SORT_NEWEST, SearchFilters.SORT_UPDATED};
    private static final int[] SORT_LABELS = {
            R.string.lib_sort_relevance, R.string.lib_sort_downloads, R.string.lib_sort_follows,
            R.string.lib_sort_newest, R.string.lib_sort_updated};

    private void setupSort(View view) {
        View sortButton = view.findViewById(R.id.library_sort_button);
        CSAnimationEngine.attachPressFeedback(sortButton);
        sortButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(requireContext(), v);
            Menu menu = popup.getMenu();
            for (int i = 0; i < SORT_VALUES.length; i++) {
                MenuItem item = menu.add(MENU_GROUP_SORT, i, i, SORT_LABELS[i]);
                item.setCheckable(true);
                item.setChecked(SORT_VALUES[i].equals(mFilters.sort));
            }
            menu.setGroupCheckable(MENU_GROUP_SORT, true, true);
            menu.add(MENU_GROUP_FILTERS, 100, 100, R.string.lib_sort_filters);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getGroupId() == MENU_GROUP_FILTERS) {
                    displayFilterDialog();
                    return true;
                }
                int index = item.getItemId();
                if (index < 0 || index >= SORT_VALUES.length) return false;
                if (!SORT_VALUES[index].equals(mFilters.sort)) {
                    mFilters.sort = SORT_VALUES[index];
                    startSearch();
                }
                return true;
            });
            popup.setOnDismissListener(menu1 -> LibraryMotion.chevron(mSortChevron, false));
            LibraryMotion.chevron(mSortChevron, true);
            popup.show();
        });
    }

    /** Rearranges the header and toolbar for narrow (portrait) panes */
    private void adaptToWidth(View root) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp <= 0) return;

        if (widthDp < NARROW_HEADER_DP) {
            LinearLayout header = root.findViewById(R.id.library_header);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(android.view.Gravity.START);
            View titles = header.getChildAt(0);
            titles.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View tabs = root.findViewById(R.id.library_type_tabs_frame);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tabParams.topMargin = Math.round(12 * density);
            tabs.setLayoutParams(tabParams);
            mTitle.setTextSize(26);
        }

        if (widthDp < NARROW_TOOLBAR_DP) {
            HorizontalScrollView chipsScroll = root.findViewById(R.id.library_chips_scroll);
            FrameLayout chipsRow = root.findViewById(R.id.library_chips_row);
            if (chipsScroll.getParent() != chipsRow) {
                ((ViewGroup) chipsScroll.getParent()).removeView(chipsScroll);
                chipsRow.addView(chipsScroll, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46 * density)));
                chipsRow.setVisibility(View.VISIBLE);
            }
        }
        updateSpanCount(mGrid.getWidth());
    }

    private void updateSpanCount(int widthPx) {
        if (mGridLayout == null || widthPx <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float usable = (widthPx - mGrid.getPaddingLeft() - mGrid.getPaddingRight()) / density;
        int span = Math.max(1, (int) (usable / MIN_CARD_WIDTH_DP));
        if (span != mGridLayout.getSpanCount()) mGridLayout.setSpanCount(span);
        if (mAdapter != null) mAdapter.setSpanCount(span);
    }

    // ─── Content type, chips and header ──────────────────────────────────────

    private void applyContentType(ContentType type, boolean userAction) {
        mFilters.contentType = type;
        mFilters.modLoader = usesLoader(type) ? mPreferredLoader : "";
        if (userAction) mFilters.category = "";
        if (mFeatured != null) mFeatured.setEnabled(type == ContentType.MOD);

        if (type == ContentType.DATAPACK) {
            // World data pack browser: no type tabs, the world name is the context
            mTypeTabsFrame.setVisibility(View.GONE);
            if (mImportButton != null) mImportButton.setVisibility(View.GONE);
            mTitle.setText(R.string.lib_title_datapacks);
            File world = InstalledContentIndex.getDatapackWorld();
            mSubtitle.setText(getString(R.string.lib_subtitle_datapacks, world == null ? "" : world.getName()));
            mSearchInput.setHint(R.string.lib_search_datapacks);
            buildChips();
            updateContextPill();
            updateAdapterContext();
            InstalledContentIndex cachedDp = mIndexCache.get(type);
            mAdapter.setInstalledIndex(cachedDp != null ? cachedDp : InstalledContentIndex.empty(type));
            return;
        }
        if (type == ContentType.MODPACK) {
            // Modpack browser: no type tabs, an Import button instead
            mTypeTabsFrame.setVisibility(View.GONE);
            if (mImportButton != null) mImportButton.setVisibility(View.VISIBLE);
            mTitle.setText(R.string.lib_title_modpacks);
            mSubtitle.setText(R.string.lib_subtitle_modpacks);
            mSearchInput.setHint(R.string.lib_search_modpacks);
            buildChips();
            updateContextPill();
            updateAdapterContext();
            mAdapter.setInstalledIndex(InstalledContentIndex.empty(type));
            return;
        }
        mTypeTabsFrame.setVisibility(View.VISIBLE);
        if (mImportButton != null) mImportButton.setVisibility(View.GONE);

        mTabMods.setSelected(type == ContentType.MOD);
        mTabResourcePacks.setSelected(type == ContentType.RESOURCE_PACK);
        mTabShaders.setSelected(type == ContentType.SHADER_PACK);
        mTabWorlds.setSelected(type == ContentType.WORLD);
        tintTab(mTabMods, userAction);
        tintTab(mTabResourcePacks, userAction);
        tintTab(mTabShaders, userAction);
        tintTab(mTabWorlds, userAction);
        TextView selectedTab = type == ContentType.RESOURCE_PACK ? mTabResourcePacks
                : type == ContentType.SHADER_PACK ? mTabShaders
                : type == ContentType.WORLD ? mTabWorlds : mTabMods;
        LibraryMotion.moveIndicator(mTabIndicator, selectedTab, userAction);
        if (userAction) {
            LibraryMotion.selectChip(selectedTab);
            Motion.swapText(mTitle, getString(type == ContentType.RESOURCE_PACK ? R.string.lib_title_resourcepacks
                    : type == ContentType.SHADER_PACK ? R.string.lib_title_shaders
                    : type == ContentType.WORLD ? R.string.lib_title_worlds : R.string.lib_title_mods));
        }
        // Worlds exist on CurseForge only: lock the source switcher there while the tab is active
        if (type == ContentType.WORLD) {
            mFilters.engine = Constants.ENGINE_CURSEFORGE;
            applySource(Constants.ENGINE_CURSEFORGE, userAction);
            setSourceLocked(true);
        } else {
            setSourceLocked(false);
            int restored = restoreSource();
            if (mFilters.engine != restored) {
                mFilters.engine = restored;
                applySource(restored, userAction);
            }
        }

        switch (type) {
            case WORLD:
                if (!userAction) mTitle.setText(R.string.lib_title_worlds);
                mSubtitle.setText(R.string.lib_subtitle_worlds);
                mSearchInput.setHint(R.string.lib_search_worlds);
                break;
            case RESOURCE_PACK:
                if (!userAction) mTitle.setText(R.string.lib_title_resourcepacks);
                mSubtitle.setText(R.string.lib_subtitle_resourcepacks);
                mSearchInput.setHint(R.string.lib_search_resourcepacks);
                break;
            case SHADER_PACK:
                if (!userAction) mTitle.setText(R.string.lib_title_shaders);
                mSubtitle.setText(R.string.lib_subtitle_shaders);
                mSearchInput.setHint(R.string.lib_search_shaders);
                break;
            default:
                if (!userAction) mTitle.setText(R.string.lib_title_mods);
                mSubtitle.setText(R.string.lib_subtitle_mods);
                mSearchInput.setHint(R.string.lib_search_mods);
                break;
        }
        buildChips();
        updateContextPill();
        updateAdapterContext();
        InstalledContentIndex cached = mIndexCache.get(type);
        mAdapter.setInstalledIndex(cached != null ? cached : InstalledContentIndex.empty(type));
        if (userAction && mGrid != null) { mGrid.scrollToPosition(0); if (mFeatured != null) mFeatured.setCollapsed(false); }
    }

    /** Mods and modpacks are picked per loader; packs and shaders are not */
    private static boolean usesLoader(ContentType type) {
        return type == ContentType.MOD || type == ContentType.MODPACK;
    }

    private void tintTab(TextView tab, boolean animate) {
        int color = tab.isSelected()
                ? androidx.core.content.ContextCompat.getColor(tab.getContext(), R.color.lib_chip_selected_text)
                : ThemeColors.get(tab.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        LibraryMotion.tintTab(tab, color, animate);
        int size = Math.round(16 * tab.getResources().getDisplayMetrics().density);
        android.graphics.drawable.Drawable[] drawables = tab.getCompoundDrawablesRelative();
        if (drawables[0] != null) {
            drawables[0].setBounds(0, 0, size, size);
            tab.setCompoundDrawablesRelative(drawables[0], null, null, null);
        }
    }

    private void buildChips() {
        mChips.removeAllViews();
        LibraryCatalog.Category[] categories = LibraryCatalog.categoriesFor(mFilters.contentType);
        String active = mFilters.category == null ? "" : mFilters.category;
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(requireContext());
        for (LibraryCatalog.Category category : categories) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_library_chip, mChips, false);
            chip.setText(category.label);
            chip.setTag(category.slug);
            boolean selected = category.slug.equals(active);
            chip.setSelected(selected);
            styleChip(chip, selected);
            chip.setOnClickListener(v -> {
                String slug = (String) v.getTag();
                if (slug.equals(mFilters.category == null ? "" : mFilters.category)) return;
                mFilters.category = slug;
                for (int i = 0; i < mChips.getChildCount(); i++) {
                    View child = mChips.getChildAt(i);
                    boolean isSelected = slug.equals(child.getTag());
                    child.setSelected(isSelected);
                    styleChip((TextView) child, isSelected);
                }
                LibraryMotion.selectChip(v);
                updateAdapterContext();
                startSearch();
            });
            mChips.addView(chip);
        }
        if (getView() != null && mInitialized) LibraryMotion.cascadeChips(mChips, 60);
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setTextColor(selected
                ? androidx.core.content.ContextCompat.getColor(chip.getContext(), R.color.lib_chip_selected_text)
                : ThemeColors.get(chip.getContext(), com.google.android.material.R.attr.colorOnSurface));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void updateContextPill() {
        if (mContextPill == null) return;
        String version = mFilters.mcVersion;
        String text;
        if (isEmpty(version)) {
            text = isEmpty(mFilters.modLoader) || !isModpackMode()
                    ? getString(R.string.lib_all_versions) : LibraryCatalog.loaderLabel(mFilters.modLoader);
        } else if (usesLoader(mFilters.contentType) && !isEmpty(mFilters.modLoader)) {
            text = LibraryCatalog.loaderLabel(mFilters.modLoader) + " " + version;
        } else {
            text = version;
        }
        mContextPill.setText(text);
        mContextPill.setVisibility(View.VISIBLE);
    }

    private void updateAdapterContext() {
        if (mAdapter == null) return;
        if (isModpackMode()) {
            // Compatibility badges make no sense for a pack that creates its own profile
            mAdapter.setContext(mFilters.contentType, null, "", mFilters.category);
            return;
        }
        mAdapter.setContext(mFilters.contentType, mFilters.contentType == ContentType.WORLD ? null : mProfileVersion,
                mFilters.contentType == ContentType.MOD ? mProfileLoader : "", mFilters.category);
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    private static SearchFilters copyOf(SearchFilters source) {
        SearchFilters copy = new SearchFilters();
        copy.isModpack = source.contentType == ContentType.MODPACK;
        copy.name = source.name == null ? "" : source.name;
        copy.mcVersion = source.mcVersion;
        copy.modLoader = source.modLoader;
        copy.engine = source.engine;
        copy.contentType = source.contentType;
        copy.category = source.category;
        copy.sort = source.sort;
        return copy;
    }

    /**
     * The header block hides as the grid scrolls and only comes back at the very top. While it
     * moves, its content fades and shrinks a little so the hand-off to the full-screen grid feels
     * deliberate instead of a hard cut.
     */
    private void setupHeaderCollapse(@NonNull View root) {
        com.google.android.material.appbar.AppBarLayout appBar = root.findViewById(R.id.library_appbar);
        View block = root.findViewById(R.id.library_collapsing);
        if (appBar == null || block == null) return;
        block.setPivotY(0f);
        appBar.addOnOffsetChangedListener((bar, offset) -> {
            int range = bar.getTotalScrollRange();
            if (range <= 0) return;
            float fraction = Math.min(1f, -offset / (float) range);
            float eased = 1f - (1f - fraction) * (1f - fraction);
            block.setAlpha(1f - Math.min(1f, eased * 1.35f));
            float scale = 1f - 0.04f * eased;
            block.setScaleX(scale);
            block.setScaleY(scale);
            block.setPivotX(block.getWidth() / 2f);
        });
    }

    private void startSearch() {
        // A new result set starts at the top: bring the header back so the user sees the context
        View root = getView();
        if (root != null) {
            com.google.android.material.appbar.AppBarLayout appBar = root.findViewById(R.id.library_appbar);
            if (appBar != null) appBar.setExpanded(true, true);
        }
        if (mSearchTask != null) mSearchTask.cancel(true);
        final int generation = ++mSearchGeneration;
        final SearchFilters query = copyOf(mFilters);
        mActiveQuery = query;
        mLastResult = null;
        mLastPage = false;
        mLoadingMore = false;
        mSearchFailed = false;

        if (mItems.isEmpty() || mAdapter == null) {
            showLoading();
        } else {
            if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
            LibraryMotion.dimGrid(mGrid, true);
        }

        mSearchTask = sLibraryExecutor.submit(() -> {
            SearchResult result = mApi.searchMod(query, null);
            Tools.runOnUiThread(() -> {
                if (generation != mSearchGeneration) return;
                mSearchTask = null;
                onFirstPage(result);
            });
        });
    }

    private void onFirstPage(@Nullable SearchResult result) {
        mItems.clear();
        if (result != null && result.results != null) mItems.addAll(Arrays.asList(result.results));
        mLastResult = result;
        mLastPage = result == null || result.results == null || result.results.length == 0;
        mSearchFailed = result == null;
        if (mAdapter == null) return;
        if (mProgress != null) mProgress.setVisibility(View.INVISIBLE);
        LibraryMotion.dimGrid(mGrid, false);
        updateAdapterContext();
        mAdapter.setItems(mItems, true);
        if (mGrid != null) { mGrid.scrollToPosition(0); if (mFeatured != null) mFeatured.setCollapsed(false); }
        if (mSearchFailed) showMessage(R.drawable.ic_lib_warning, R.string.lib_error, true);
        else if (mItems.isEmpty()) showMessage(R.drawable.ic_lib_search, R.string.lib_no_results, false);
        else showContent();
    }

    @Override
    public void onNearEnd() {
        if (mLoadingMore || mLastPage || mLastResult == null || mActiveQuery == null || mSearchTask != null) return;
        mLoadingMore = true;
        final int generation = mSearchGeneration;
        final SearchFilters query = mActiveQuery;
        final SearchResult previous = mLastResult;
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        sLibraryExecutor.execute(() -> {
            SearchResult result = mApi.searchMod(query, previous);
            Tools.runOnUiThread(() -> {
                if (generation != mSearchGeneration) return;
                mLoadingMore = false;
                if (mProgress != null) mProgress.setVisibility(View.INVISIBLE);
                if (result == null || result.results == null || result.results.length == 0) {
                    mLastPage = true;
                    return;
                }
                mLastResult = result;
                List<ModItem> page = new ArrayList<>(Arrays.asList(result.results));
                mItems.addAll(page);
                if (mAdapter != null) mAdapter.appendItems(page);
            });
        });
    }

    /** Skeleton cards breathe in the grid while the first page loads */
    private void showLoading() {
        if (mStateContainer == null || mAdapter == null) return;
        mStateContainer.setVisibility(View.GONE);
        mGrid.animate().cancel();
        mGrid.setAlpha(1f);
        mGrid.setVisibility(View.VISIBLE);
        mAdapter.showSkeleton(Math.max(6, mGridLayout.getSpanCount() * 3));
        mGrid.scrollToPosition(0);
        if (mFeatured != null) mFeatured.setCollapsed(false);
        mProgress.setVisibility(View.INVISIBLE);
    }

    private void showMessage(int icon, int text, boolean retry) {
        if (mStateContainer == null) return;
        mGrid.setVisibility(View.INVISIBLE);
        mStateContainer.setVisibility(View.VISIBLE);
        mStateLoading.setVisibility(View.GONE);
        mStateIcon.setVisibility(View.VISIBLE);
        mStateIcon.setImageResource(icon);
        mStateText.setVisibility(View.VISIBLE);
        mStateText.setText(text);
        mStateRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
        mStateContainer.setVisibility(View.GONE);
        LibraryMotion.showState(mStateContainer);
    }

    private void showContent() {
        if (mStateContainer == null) return;
        mStateContainer.setVisibility(View.GONE);
        mGrid.setVisibility(View.VISIBLE);
        LibraryMotion.dimGrid(mGrid, false);
    }

    // ─── Installed state ─────────────────────────────────────────────────────

    private void refreshInstalled() {
        final ContentType type = mFilters.contentType;
        if (type == ContentType.MODPACK) return;
        final int generation = ++mIndexGeneration;
        final String version = mProfileVersion;
        final String loader = mProfileLoader;
        final String key = mCurseforgeKey;
        sLibraryExecutor.execute(() -> {
            InstalledContentIndex index = InstalledContentIndex.build(type, version, loader, key);
            Tools.runOnUiThread(() -> {
                mIndexCache.put(type, index);
                if (type == ContentType.MOD && mFeatured != null) mFeatured.setInstalledIndex(index);
                if (generation != mIndexGeneration || mAdapter == null || type != mFilters.contentType) return;
                mAdapter.setInstalledIndex(index);
            });
        });
    }

    // ─── Card callbacks ──────────────────────────────────────────────────────

    @Override
    public void onCardClicked(@NonNull ModItem item) {
        openInstallScreen(item);
    }

    @Override
    public void onActionClicked(@NonNull ModItem item, @NonNull LibraryCardAdapter.CardState state) {
        if (state == LibraryCardAdapter.CardState.UNINSTALL) confirmUninstall(item);
        else openInstallScreen(item);
    }

    private void openInstallScreen(ModItem item) {
        hideKeyboard();
        Bundle args = new Bundle();
        args.putString(ModInstallFragment.ARG_ITEM, Tools.GLOBAL_GSON.toJson(item));
        args.putString(ModInstallFragment.ARG_CONTENT_TYPE, mFilters.contentType.name());
        args.putString(ModInstallFragment.ARG_LIBRARY_TITLE, getString(libraryTabLabel()));
        Tools.swapFragment(requireActivity(), ModInstallFragment.class, ModInstallFragment.TAG, args);
    }

    private int libraryTabLabel() {
        switch (mFilters.contentType) {
            case RESOURCE_PACK: return R.string.lib_tab_resourcepacks;
            case SHADER_PACK: return R.string.lib_tab_shaders;
            case MODPACK: return R.string.lib_tab_modpacks;
            case DATAPACK: return R.string.lib_tab_datapacks;
            case WORLD: return R.string.lib_tab_worlds;
            default: return R.string.lib_tab_mods;
        }
    }

    private void confirmUninstall(ModItem item) {
        if (mAdapter == null) return;
        InstalledContentIndex index = mAdapter.getInstalledIndex();
        InstalledContentIndex.Entry entry = index.find(item);
        if (entry == null) return;
        final File file = entry.file;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.lib_uninstall_title, item.title))
                .setMessage(getString(R.string.lib_uninstall_message, file.getName(), getString(libraryTabLabel())))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lib_uninstall, (dialog, which) -> {
                    if (ContentInstaller.uninstall(file)) {
                        index.remove(file);
                        if (mAdapter != null) mAdapter.setInstalledIndex(index);
                        Toast.makeText(requireContext(), getString(R.string.lib_uninstall_done, item.title),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.lib_uninstall_failed, file.getName()),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // ─── Source / version / loader filter dialog ─────────────────────────────

    /** Public so ContentPickerFragment's filter button can open the same dialog */
    public void displayFilterDialog() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView selectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button selectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button applyButton = dialog.findViewById(R.id.search_mod_apply_filters);
            android.widget.Spinner loaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            android.widget.Spinner engineSpinner = dialog.findViewById(R.id.search_mod_engine_spinner);
            TextView loaderLabel = dialog.findViewById(R.id.search_mod_loader_textview);
            if (selectedVersion == null || selectVersionButton == null || applyButton == null) return;

            boolean curseforgeDisabled = mCurseforgeKey == null;
            final int[] engineValues = curseforgeDisabled
                    ? new int[]{Constants.ENGINE_MODRINTH}
                    : new int[]{Constants.ENGINE_MODRINTH, Constants.ENGINE_CURSEFORGE};
            if (engineSpinner != null) {
                String[] engineLabels = curseforgeDisabled
                        ? new String[]{getString(R.string.search_mod_engine_modrinth)}
                        : new String[]{getString(R.string.search_mod_engine_modrinth),
                        getString(R.string.search_mod_engine_curseforge)};
                android.widget.ArrayAdapter<String> engineAdapter = new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.spinner_item_m3, engineLabels);
                engineAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_m3);
                engineSpinner.setAdapter(engineAdapter);
                if (curseforgeDisabled) {
                    mFilters.engine = Constants.ENGINE_MODRINTH;
                    engineSpinner.setEnabled(false);
                } else {
                    for (int i = 0; i < engineValues.length; i++) {
                        if (engineValues[i] == mFilters.engine) engineSpinner.setSelection(i);
                    }
                }
            }

            final String[] loaderValues = {"", "fabric", "forge", "quilt", "neoforge"};
            boolean showLoader = usesLoader(mFilters.contentType);
            if (loaderLabel != null) loaderLabel.setVisibility(showLoader ? View.VISIBLE : View.GONE);
            if (loaderSpinner != null) {
                loaderSpinner.setVisibility(showLoader ? View.VISIBLE : View.GONE);
                String[] loaderLabels = {getString(R.string.search_mod_any_loader), "Fabric", "Forge", "Quilt", "NeoForge"};
                android.widget.ArrayAdapter<String> loaderAdapter = new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.spinner_item_m3, loaderLabels);
                loaderAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_m3);
                loaderSpinner.setAdapter(loaderAdapter);
                for (int i = 0; i < loaderValues.length; i++) {
                    if (loaderValues[i].equals(mPreferredLoader)) loaderSpinner.setSelection(i);
                }
            }

            selectVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true,
                    (id, snapshot) -> selectedVersion.setText(id)));
            selectedVersion.setText(mFilters.mcVersion);

            applyButton.setOnClickListener(v -> {
                if (engineSpinner != null) {
                    int position = engineSpinner.getSelectedItemPosition();
                    if (position >= 0 && position < engineValues.length) {
                        int picked = engineValues[position];
                        if (picked != mFilters.engine) {
                            LauncherPreferences.DEFAULT_PREF.edit().putInt(PREF_LIBRARY_SOURCE, picked).apply();
                        }
                        applySource(picked, true);
                    }
                }
                if (showLoader && loaderSpinner != null) {
                    int position = loaderSpinner.getSelectedItemPosition();
                    if (position >= 0 && position < loaderValues.length) mPreferredLoader = loaderValues[position];
                }
                mFilters.modLoader = usesLoader(mFilters.contentType) ? mPreferredLoader : "";
                mFilters.mcVersion = selectedVersion.getText().toString();
                updateContextPill();
                startSearch();
                dialogInterface.dismiss();
            });
        });
        dialog.show();
    }

    private static boolean isEmpty(@Nullable String text) {
        return text == null || text.isEmpty();
    }
}
