package com.craftstudio.cslauncher.fragments;

import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.instances.Instance;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.contracts.OpenDocumentWithExtension;
import com.craftstudio.cslauncher.modding.optifine.InstalledModAdapter;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.LibraryMotion;
import com.craftstudio.cslauncher.utils.motion.MainMenuMotion;
import com.craftstudio.cslauncher.utils.motion.ManagerMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Manages installed content (mods, resource packs, or shader packs — see
 * {@link #ARG_CONTENT_TYPE}) for the current instance: list, enable/disable,
 * delete, and check for updates. The version/loader filter used for update
 * checking now lives in the "Manage Content" picker (see MainMenuFragment /
 * ContentFilterDialog) rather than a button on this screen, since it's a
 * single per-instance filter shared across all three content types.
 *
 * The loader half of that filter only ever applies to mods — resource packs
 * and shader packs aren't loader-specific, so update checks/searches for
 * those two content types always ignore the saved loader value even if one
 * is set (see {@link #effectiveLoader(String)}).
 */
public class ManageModsFragment extends Fragment {

    public static final String TAG = "ManageModsFragment";

    /** Bundle key: which kind of content this screen manages. Value is a
     *  ContentType enum name(); defaults to MOD when absent. */
    public static final String ARG_CONTENT_TYPE = "content_type";

    private static final String PREF_FILE      = "mod_filters";
    private static final String KEY_MC_VERSION = "mc_version_";
    private static final String KEY_LOADER     = "loader_";

    private static final int[] FILTER_IDS = {
            R.id.manage_filter_all, R.id.manage_filter_enabled,
            R.id.manage_filter_disabled, R.id.manage_filter_updates};
    private static final int[] FILTER_LABELS = {
            R.string.mm_filter_all, R.string.mm_filter_enabled,
            R.string.mm_filter_disabled, R.string.mm_filter_updates};
    private static final int[] FILTER_MODES = {
            InstalledModAdapter.FILTER_ALL, InstalledModAdapter.FILTER_ENABLED,
            InstalledModAdapter.FILTER_DISABLED, InstalledModAdapter.FILTER_UPDATES};

    private ImageButton mRefreshButton;
    private ProgressBar mUpdateProgress;
    private InstalledModAdapter mAdapter;
    private ContentType mContentType = ContentType.MOD;

    private RecyclerView mRecycler;
    private View mContent;
    private View mEmptyState;
    private View mNoResults;
    private View mTypeTile;
    private ImageView mTypeIcon;
    private TextView mTitle;
    private EditText mSearchInput;
    private View mSearchClear;
    private View mTabIndicator;
    private final TextView[] mTabs = new TextView[3];
    private final TextView[] mFilterChips = new TextView[4];
    private int mStateFilter = InstalledModAdapter.FILTER_ALL;
    private int mSpanCount = 1;
    /** Bumped on every type switch, so a finished check of an old adapter is ignored */
    private int mCheckToken;
    private boolean mStatsAnimate;
    private final java.util.EnumSet<ContentType> mAutoChecked = java.util.EnumSet.noneOf(ContentType.class);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mApplySearch = this::applyQuery;

    // Registered unconditionally as a field initializer (must happen before the
    // fragment reaches STARTED) — mContentType isn't resolved from arguments yet
    // at construction time, so this accepts either supported extension and
    // onImportFilePicked() checks the picked file against mContentType itself.
    private final ActivityResultLauncher<Object> mImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension(new String[]{"jar", "zip"}),
                    this::onImportFilePicked);

    public ManageModsFragment() {
        super(R.layout.fragment_manage_mods);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String typeName = args != null ? args.getString(ARG_CONTENT_TYPE, null) : null;
        mContentType = typeName != null ? ContentType.valueOf(typeName) : ContentType.MOD;
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(ARG_CONTENT_TYPE, null);
            if (saved != null) mContentType = ContentType.valueOf(saved);
        }

        ImageButton backButton = view.findViewById(R.id.manage_mods_back);
        mRefreshButton  = view.findViewById(R.id.manage_mods_refresh);
        mUpdateProgress = view.findViewById(R.id.manage_mods_update_progress);
        View addButton  = view.findViewById(R.id.manage_mods_add);
        ImageButton importButton = view.findViewById(R.id.manage_mods_import);
        mTitle       = view.findViewById(R.id.manage_mods_title);
        mRecycler    = view.findViewById(R.id.manage_mods_recycler);
        mContent     = view.findViewById(R.id.manage_content);
        mEmptyState  = view.findViewById(R.id.manage_mods_empty);
        mNoResults   = view.findViewById(R.id.manage_no_results);
        mTypeTile    = view.findViewById(R.id.manage_type_tile);
        mTypeIcon    = view.findViewById(R.id.manage_mods_header_icon);
        mSearchInput = view.findViewById(R.id.manage_search_input);
        mSearchClear = view.findViewById(R.id.manage_search_clear);
        mTabIndicator = view.findViewById(R.id.manage_tab_indicator);
        mTabs[0] = view.findViewById(R.id.manage_tab_mods);
        mTabs[1] = view.findViewById(R.id.manage_tab_resourcepacks);
        mTabs[2] = view.findViewById(R.id.manage_tab_shaders);
        for (int i = 0; i < FILTER_IDS.length; i++) mFilterChips[i] = view.findViewById(FILTER_IDS[i]);

        // Full screen page: the back button is always there (the top bar is hidden)
        backButton.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        mRefreshButton.setOnClickListener(v -> runUpdateCheck(false));
        addButton.setOnClickListener(v -> openModSearch());
        addButton.setClipToOutline(true);
        importButton.setOnClickListener(v -> mImportLauncher.launch(null));
        view.findViewById(R.id.manage_empty_browse).setOnClickListener(v -> openModSearch());
        view.findViewById(R.id.manage_empty_import).setOnClickListener(v -> mImportLauncher.launch(null));
        view.findViewById(R.id.manage_show_all).setOnClickListener(v -> {
            mSearchInput.setText("");
            selectFilter(0, true);
        });
        for (View pressable : new View[]{backButton, mRefreshButton, importButton, addButton,
                view.findViewById(R.id.manage_empty_browse), view.findViewById(R.id.manage_empty_import),
                view.findViewById(R.id.manage_show_all), mSearchClear}) {
            CSAnimationEngine.attachPressFeedback(pressable);
        }

        String profileName = getCurrentProfileName();
        String versionLabel = getCurrentVersionLabel();
        TextView subtitle = view.findViewById(R.id.manage_mods_subtitle);
        if (profileName.isEmpty()) subtitle.setText(versionLabel);
        else if (versionLabel.isEmpty()) subtitle.setText(profileName);
        else subtitle.setText(getString(R.string.mm_subtitle, profileName, versionLabel));

        // Type tabs: switch in place
        ContentType[] tabTypes = {ContentType.MOD, ContentType.RESOURCE_PACK, ContentType.SHADER_PACK};
        for (int i = 0; i < mTabs.length; i++) {
            final ContentType type = tabTypes[i];
            mTabs[i].setOnClickListener(v -> switchType(type));
        }

        // State filter chips (counts are appended by the stats listener)
        for (int i = 0; i < mFilterChips.length; i++) {
            final int index = i;
            mFilterChips[i].setOnClickListener(v -> selectFilter(index, true));
            CSAnimationEngine.attachPressFeedback(mFilterChips[i]);
        }
        selectFilter(0, false);

        // Search
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                boolean hasText = s.length() > 0;
                if (hasText && mSearchClear.getVisibility() != View.VISIBLE) {
                    mSearchClear.setVisibility(View.VISIBLE);
                    Motion.popIn(mSearchClear, 0, 0.5f);
                } else if (!hasText && mSearchClear.getVisibility() == View.VISIBLE) {
                    Motion.hide(mSearchClear, true);
                }
                mHandler.removeCallbacks(mApplySearch);
                mHandler.postDelayed(mApplySearch, 160);
            }
        });
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });
        mSearchClear.setOnClickListener(v -> mSearchInput.setText(""));

        // List: two columns on wide screens
        int widthDp = getResources().getConfiguration().screenWidthDp;
        mSpanCount = widthDp >= 760 ? 2 : 1;
        mRecycler.setLayoutManager(mSpanCount > 1
                ? new GridLayoutManager(requireContext(), mSpanCount)
                : new LinearLayoutManager(requireContext()));
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setAddDuration(Motion.MEDIUM);
        animator.setRemoveDuration(Motion.MEDIUM);
        animator.setMoveDuration(Motion.MEDIUM);
        mRecycler.setItemAnimator(animator);

        applyType(false);

        boolean animate = savedInstanceState == null;
        if (animate) {
            ManagerMotion.playEntrance(backButton, mTypeTile, view.findViewById(R.id.manage_title_block),
                    view.findViewById(R.id.manage_tabs), view.findViewById(R.id.manage_toolbar));
            ManagerMotion.rowsIn(mRecycler, mSpanCount, 220);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Full screen: the top bar (navigation dock) is hidden while the manager is open
        MainMenuMotion.topBar(getActivity(), false);
    }

    @Override
    public void onStop() {
        MainMenuMotion.topBar(getActivity(), true);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ARG_CONTENT_TYPE, mContentType.name());
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mApplySearch);
        mCheckToken++;
        super.onDestroyView();
    }

    // ── Content type ─────────────────────────────────────────────────────────

    private static int tabIndex(ContentType type) {
        switch (type) {
            case RESOURCE_PACK: return 1;
            case SHADER_PACK:   return 2;
            default:            return 0;
        }
    }

    /** Tab tapped: slides the list toward the new tab and rebuilds it for that content type */
    private void switchType(ContentType type) {
        if (type == mContentType || getView() == null) {
            if (getView() != null) Motion.bounce(mTabs[tabIndex(type)]);
            return;
        }
        boolean toRight = tabIndex(type) > tabIndex(mContentType);
        mContentType = type;
        hideKeyboard();
        selectTabs(true);
        ManagerMotion.tileFlip(mTypeTile, this::applyTypeHeader);
        Motion.swapText(mTitle, getString(contentTypeLabelRes()));
        ManagerMotion.typeSwitch(mContent, toRight, () -> {
            if (getView() == null) return;
            applyType(true);
            ManagerMotion.rowsIn(mRecycler, mSpanCount, 40);
        });
    }

    /** Header tile, glyph, empty state art and search hint of the current type */
    private void applyTypeHeader() {
        int tileRes, glowRes, glyphColor, iconRes, searchHint, emptyTitle, emptyDesc;
        switch (mContentType) {
            case RESOURCE_PACK:
                tileRes = R.drawable.bg_mm_type_rp; glowRes = R.drawable.bg_mm_glow_rp;
                glyphColor = R.color.mm_rp_glyph; iconRes = R.drawable.ic_lib_image;
                searchHint = R.string.mm_search_rp;
                emptyTitle = R.string.mm_empty_title_rp; emptyDesc = R.string.mm_empty_desc_rp;
                break;
            case SHADER_PACK:
                tileRes = R.drawable.bg_mm_type_sh; glowRes = R.drawable.bg_mm_glow_sh;
                glyphColor = R.color.mm_sh_glyph; iconRes = R.drawable.ic_lib_sun;
                searchHint = R.string.mm_search_sh;
                emptyTitle = R.string.mm_empty_title_sh; emptyDesc = R.string.mm_empty_desc_sh;
                break;
            default:
                tileRes = R.drawable.bg_mm_type_mods; glowRes = R.drawable.bg_mm_glow_mods;
                glyphColor = R.color.mm_mods_glyph; iconRes = R.drawable.ic_lib_cube;
                searchHint = R.string.mm_search_mods;
                emptyTitle = R.string.mm_empty_title_mods; emptyDesc = R.string.mm_empty_desc_mods;
                break;
        }
        View root = getView();
        if (root == null) return;
        int color = ContextCompat.getColor(requireContext(), glyphColor);
        mTypeTile.setBackgroundResource(tileRes);
        mTypeIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(mTypeIcon, ColorStateList.valueOf(color));
        mSearchInput.setHint(searchHint);
        root.findViewById(R.id.manage_empty_glow).setBackgroundResource(glowRes);
        root.findViewById(R.id.manage_empty_tile).setBackgroundResource(tileRes);
        ImageView emptyIcon = root.findViewById(R.id.manage_empty_icon);
        emptyIcon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(emptyIcon, ColorStateList.valueOf(color));
        ((TextView) root.findViewById(R.id.manage_empty_title)).setText(emptyTitle);
        ((TextView) root.findViewById(R.id.manage_empty_desc)).setText(emptyDesc);
    }

    /** Builds the adapter of the current type (header, tabs, list, counts) */
    private void applyType(boolean animated) {
        applyTypeHeader();
        if (!animated) {
            mTitle.setText(contentTypeLabelRes());
            selectTabs(false);
        }

        String[] filter = resolveFilter(getCurrentProfileKey());
        mStatsAnimate = false;
        mAdapter = new InstalledModAdapter(requireContext(), getContentDir(), mContentType,
                isEmpty -> updateListState());
        mAdapter.setFilter(filter[0], filter[1]);
        mAdapter.setQuery(mSearchInput.getText().toString(), FILTER_MODES[indexOfFilter(mStateFilter)]);
        mAdapter.setStatsListener(this::onStatsChanged);
        mRecycler.setAdapter(mAdapter);
        mStatsAnimate = true;
        updateListState();

        // A check of the previous type may still be running: give the button back now
        mCheckToken++;
        mRefreshButton.setEnabled(true);
        mRefreshButton.setVisibility(View.VISIBLE);
        if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.GONE);

        // Auto-check for updates the first time each type is shown. Silent (no
        // "checking…" toast, and no "set a filter first" toast) since this fires
        // automatically — if there's genuinely no version to go on, it quietly does nothing.
        if (mAutoChecked.add(mContentType)) runUpdateCheck(true);
    }

    private void selectTabs(boolean animate) {
        int selected = tabIndex(mContentType);
        int selectedColor = ContextCompat.getColor(requireContext(), R.color.lib_chip_selected_text);
        int idleColor = ContextCompat.getColor(requireContext(), R.color.hm_text_dim);
        int size = Math.round(16 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < mTabs.length; i++) {
            TextView tab = mTabs[i];
            tab.setSelected(i == selected);
            Drawable[] drawables = tab.getCompoundDrawablesRelative();
            if (drawables[0] != null) {
                drawables[0].setBounds(0, 0, size, size);
                tab.setCompoundDrawablesRelative(drawables[0], null, null, null);
            }
            LibraryMotion.tintTab(tab, i == selected ? selectedColor : idleColor, animate);
        }
        LibraryMotion.moveIndicator(mTabIndicator, mTabs[selected], animate);
        if (animate) Motion.bounce(mTabs[selected]);
    }

    // ── Search / filters / states ────────────────────────────────────────────

    private int indexOfFilter(int mode) {
        for (int i = 0; i < FILTER_MODES.length; i++) if (FILTER_MODES[i] == mode) return i;
        return 0;
    }

    private void selectFilter(int index, boolean animate) {
        mStateFilter = FILTER_MODES[index];
        for (int i = 0; i < mFilterChips.length; i++) mFilterChips[i].setSelected(i == index);
        if (animate) Motion.bounce(mFilterChips[index]);
        applyQuery();
    }

    private void applyQuery() {
        if (mAdapter == null || mSearchInput == null) return;
        mAdapter.setQuery(mSearchInput.getText().toString(), mStateFilter);
        updateListState();
    }

    private void onStatsChanged(int total, int enabled, int disabled, int updates) {
        if (getView() == null) return;
        int[] counts = {total, enabled, disabled, updates};
        for (int i = 0; i < mFilterChips.length; i++) {
            ManagerMotion.chipCount(mFilterChips[i],
                    getString(R.string.mm_chip_count, getString(FILTER_LABELS[i]), counts[i]),
                    mStatsAnimate && i != 0);
        }
    }

    /** List, empty folder or "nothing matches", with a soft transition between them */
    private void updateListState() {
        if (mAdapter == null || getView() == null) return;
        boolean folderEmpty = mAdapter.getTotalCount() == 0;
        boolean listEmpty = mAdapter.getItemCount() == 0;
        if (folderEmpty) {
            mRecycler.setVisibility(View.GONE);
            mNoResults.setVisibility(View.GONE);
            if (mEmptyState.getVisibility() != View.VISIBLE) {
                mEmptyState.setVisibility(View.VISIBLE);
                View root = getView();
                ManagerMotion.emptyIn(root.findViewById(R.id.manage_empty_glow),
                        root.findViewById(R.id.manage_empty_tile), (ViewGroup) mEmptyState);
            }
        } else if (listEmpty) {
            mRecycler.setVisibility(View.GONE);
            mEmptyState.setVisibility(View.GONE);
            if (mNoResults.getVisibility() != View.VISIBLE) {
                mNoResults.setVisibility(View.VISIBLE);
                ManagerMotion.noResultsIn(mNoResults);
            }
        } else {
            mEmptyState.setVisibility(View.GONE);
            mNoResults.setVisibility(View.GONE);
            mRecycler.setVisibility(View.VISIBLE);
        }
    }

    private void hideKeyboard() {
        if (mSearchInput == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
        mSearchInput.clearFocus();
    }

    /**
     * Runs the Modrinth update check across all installed content for this instance.
     * Triggered automatically when the screen opens (silent = true) and manually
     * via the refresh button (silent = false).
     *
     * @param silent when true, skips the "checking…"/"set a filter first" toasts —
     *               used for the automatic on-open check so it doesn't nag the
     *               user if a version genuinely couldn't be determined.
     */
    private void runUpdateCheck(boolean silent) {
        if (mAdapter == null) return;

        String[] filter = resolveFilter(getCurrentProfileKey());
        String version = filter[0];
        String loader  = filter[1];

        if (version.isEmpty() && loader.isEmpty()) {
            if (!silent) {
                Toast.makeText(requireContext(),
                        R.string.mod_update_no_filter, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        mAdapter.setFilter(version, loader);

        // While checking: hide the refresh button and show just a spinner in
        // its place. No per-mod update button appears until its own check
        // resolves, so this spinner is the only thing indicating progress.
        mRefreshButton.setEnabled(false);
        mRefreshButton.setVisibility(View.GONE);
        if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.VISIBLE);

        if (!silent) {
            Toast.makeText(requireContext(), R.string.mod_update_checking, Toast.LENGTH_SHORT).show();
        }

        final int token = ++mCheckToken;
        mAdapter.checkForUpdates(() -> {
            if (token != mCheckToken || getView() == null) return;
            mRefreshButton.setEnabled(true);
            mRefreshButton.setVisibility(View.VISIBLE);
            Motion.popIn(mRefreshButton, 0, 0.6f);
            if (mUpdateProgress != null) mUpdateProgress.setVisibility(View.GONE);
        });
    }

    private void openModSearch() {
        String[] filter = resolveFilter(getCurrentProfileKey());
        String version = filter[0];
        String loader  = filter[1];

        Bundle args = new Bundle();
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (!loader.isEmpty())  args.putString(ModsSearchFragment.ARG_PRESET_LOADER,     loader);
        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, mContentType.name());

        ModsSearchFragment fragment = new ModsSearchFragment();
        fragment.setArguments(args);
        navigateToFragment(fragment, ModsSearchFragment.TAG + ":" + mContentType.name());
    }

    /**
     * Handles a file picked via {@link #mImportLauncher} (e.g. from Downloads):
     * validates its extension against the content type this screen manages,
     * copies it into that content type's folder for the current instance, and
     * inserts it into the list. Runs the copy off the main thread since the
     * source may be a slow content provider (cloud storage, etc.).
     */
    private void onImportFilePicked(Uri uri) {
        if (uri == null || mAdapter == null) return;

        String requiredExtension = mContentType.fileExtension;
        String pickedName = queryDisplayName(uri);
        if (pickedName == null || !pickedName.toLowerCase().endsWith(requiredExtension)) {
            Toast.makeText(requireContext(),
                    getString(R.string.content_import_wrong_type, requiredExtension),
                    Toast.LENGTH_LONG).show();
            return;
        }

        File contentDir = getContentDir();
        File destination = uniqueDestination(contentDir, pickedName);

        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean success = copyToFile(uri, contentDir, destination);
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (success) {
                    mAdapter.addContentFile(destination);
                    Toast.makeText(requireContext(),
                            getString(R.string.content_import_success, destination.getName()),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(),
                            R.string.content_import_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {}
        String path = uri.getLastPathSegment();
        return path != null ? path.substring(path.lastIndexOf('/') + 1) : null;
    }

    /** Appends " (1)", " (2)", etc. before the extension if a file of that name already exists. */
    private File uniqueDestination(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int count = 1;
        do {
            candidate = new File(dir, base + " (" + count + ")" + ext);
            count++;
        } while (candidate.exists());
        return candidate;
    }

    private boolean copyToFile(Uri source, File contentDir, File destination) {
        if (!contentDir.isDirectory() && !contentDir.mkdirs()) return false;
        try (InputStream input = requireContext().getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) return false;
            byte[] buffer = new byte[262144];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return true;
        } catch (IOException e) {
            destination.delete();
            return false;
        }
    }

    /**
     * The version/loader filter to actually use: whatever's saved for this profile,
     * or — if nothing's been saved yet — whatever the instance itself is detected as
     * running. This has to match what the filter dialog *shows* as its default (see
     * ContentFilterDialog), otherwise the dialog can look like a filter is already
     * set while everything that actually reads from SharedPreferences (like this
     * screen's update check) still sees nothing and does nothing — which is exactly
     * what used to happen, since the dialog's auto-fill was only ever visual until
     * Apply was pressed.
     */
    private String[] resolveFilter(String profileKey) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
        String version = prefs.getString(KEY_MC_VERSION + profileKey, "");
        String loader  = effectiveLoader(prefs.getString(KEY_LOADER + profileKey, ""));

        if (version.isEmpty() && loader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(profileKey);
            if (info.mcVersion != null) version = info.mcVersion;
            loader = effectiveLoader(info.loader);
        }
        return new String[]{version, loader};
    }

    /** Loader filters only make sense for mods — a saved loader preference is
     *  simply ignored when managing/searching resource packs or shader packs. */
    private String effectiveLoader(String savedLoader) {
        return mContentType == ContentType.MOD ? savedLoader : "";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int contentTypeLabelRes() {
        switch (mContentType) {
            case RESOURCE_PACK: return R.string.mcl_content_resourcepacks;
            case SHADER_PACK:   return R.string.mcl_content_shaderpacks;
            default:            return R.string.mcl_content_mods;
        }
    }

    /** "1.21.4 Fabric" style label of the selected instance, empty when unknown */
    private String getCurrentVersionLabel() {
        try {
            Instance instance = Instances.loadInstance(getCurrentProfileKey());
            if (instance == null || instance.versionId == null) return "";
            return InstanceVersionResolver.shortVersion(instance.versionId);
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private String getCurrentProfileKey() {
        return Instances.getSelectedInstanceKey();
    }

    private String getCurrentProfileName() {
        try {
            String key = getCurrentProfileKey();
            Instance instance = Instances.loadInstance(key);
            if (instance == null) return "";
            return instance.name != null ? instance.name : key;
        } catch (Exception e) {
            return "";
        }
    }

    private File getContentDir() {
        try {
            Instance instance = Instances.loadInstance(getCurrentProfileKey());
            if (instance != null) {
                return new File(instance.getGameDirectory(), mContentType.folderName);
            }
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW, mContentType.folderName);
    }

    private void navigateToFragment(Fragment fragment, String tag) {
        Fragment parent = getParentFragment();
        if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, fragment, tag)
                    .addToBackStack(tag)
                    .commit();
        } else {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.container_fragment, fragment, tag)
                    .addToBackStack(tag)
                    .commit();
        }
    }
}