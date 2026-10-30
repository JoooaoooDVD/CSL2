package com.craftstudio.cslauncher.game.resources;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.modding.modpacks.api.CommonApi;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchFilters;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The in-game "Browse Resources" popup. It is a plain view overlay added to the activity's
 * content frame (no Dialog window, so the immersive game surface is never disturbed and no touch
 * can reach the game), animated in/out, and attached to {@link ResourceInstallManager} only
 * while visible. Downloads keep running after {@link #dismiss()}.
 */
public final class ResourceBrowserPopup implements ResourceInstallManager.Listener, ResourceCardAdapter.Listener {
    private static final long DEBOUNCE_MS = 420;
    private static final int PREFETCH_THRESHOLD = 6;

    private final Activity mActivity;
    private final ViewGroup mContainer;
    private final Instance mInstance;
    @Nullable private final String mVersionId;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mSearchExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "resource-search"));
    /** Modrinth + CurseForge (the launcher's key); CurseForge is skipped when the key is missing/disabled */
    private final CommonApi mApi;
    private final boolean mCurseforgeAvailable;
    private int mEngine = Constants.ENGINE_MODRINTH;
    private final ModIconCache mIconCache = new ModIconCache();
    private final ResourceInstallManager mManager = ResourceInstallManager.get();

    private View mRoot, mSheet;
    private EditText mSearch;
    private View mSearchSpinner, mSearchClear;
    private TextView mTabPacks, mTabShaders;
    private TextView mSrcModrinth, mSrcCurseforge;
    private RecyclerView mList;
    private View mStateRoot, mStateSpinner, mStateAction;
    private ImageView mStateIcon;
    private TextView mStateTitle, mStateMessage;
    private TextView mProfileName, mProfileVersion, mProfileLoader, mProfileDir;
    private View mConfirmRoot;
    private TextView mConfirmMessage, mConfirmPositive, mConfirmNegative;
    private ResourceCardAdapter mAdapter;

    private ContentType mType = ContentType.RESOURCE_PACK;
    @Nullable private ResourceProfileContext mProfile;
    private boolean mProfileResolved;
    private boolean mShowing, mDismissing;
    private int mSearchGeneration;
    private boolean mLoadingPage;
    private boolean mEndReached;
    @Nullable private SearchResult mLastPage;
    @Nullable private String mPendingConfirmKey;
    private final Runnable mDebounced = this::startNewSearch;

    private ResourceBrowserPopup(@NonNull Activity activity, @NonNull Instance instance, @Nullable String versionId) {
        mActivity = activity;
        mContainer = activity.findViewById(android.R.id.content);
        mInstance = instance;
        mVersionId = versionId;
        String key = null;
        try {
            key = com.craftstudio.cslauncher.prefs.LauncherPreferences.PREF_DISABLE_CURSEFORGE_API
                    ? null : com.craftstudio.cslauncher.prefs.LauncherPreferences.resolveCurseforgeApiKey(activity);
        } catch (Throwable ignored) {}
        if (key != null && key.trim().isEmpty()) key = null;
        mCurseforgeAvailable = key != null;
        mManager.attach(activity);
        mApi = new CommonApi(key != null ? key : "", key == null);
    }

    /** Builds, attaches and animates the popup in. */
    @MainThread
    @NonNull
    public static ResourceBrowserPopup show(@NonNull Activity activity, @NonNull Instance instance, @Nullable String versionId) {
        ResourceBrowserPopup popup = new ResourceBrowserPopup(activity, instance, versionId);
        popup.inflate();
        popup.animateIn();
        popup.resolveProfile();
        return popup;
    }

    public boolean isShowing() {
        return mShowing && !mDismissing;
    }

    // ------------------------------------------------------------------ build

    private void inflate() {
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        mRoot = inflater.inflate(R.layout.popup_resource_browser, mContainer, false);
        mSheet = mRoot.findViewById(R.id.rb_sheet);
        mSearch = mRoot.findViewById(R.id.rb_search);
        mSearchSpinner = mRoot.findViewById(R.id.rb_search_spinner);
        mSearchClear = mRoot.findViewById(R.id.rb_search_clear);
        mTabPacks = mRoot.findViewById(R.id.rb_tab_resourcepacks);
        mTabShaders = mRoot.findViewById(R.id.rb_tab_shaders);
        mSrcModrinth = mRoot.findViewById(R.id.rb_src_modrinth);
        mSrcCurseforge = mRoot.findViewById(R.id.rb_src_curseforge);
        mList = mRoot.findViewById(R.id.rb_list);
        mStateRoot = mRoot.findViewById(R.id.rb_state_root);
        mStateSpinner = mRoot.findViewById(R.id.rb_state_spinner);
        mStateIcon = mRoot.findViewById(R.id.rb_state_icon);
        mStateTitle = mRoot.findViewById(R.id.rb_state_title);
        mStateMessage = mRoot.findViewById(R.id.rb_state_message);
        mStateAction = mRoot.findViewById(R.id.rb_state_action);
        mProfileName = mRoot.findViewById(R.id.rb_profile_name);
        mProfileVersion = mRoot.findViewById(R.id.rb_profile_version);
        mProfileLoader = mRoot.findViewById(R.id.rb_profile_loader);
        mProfileDir = mRoot.findViewById(R.id.rb_profile_dir);
        mConfirmRoot = mRoot.findViewById(R.id.rb_confirm_root);
        mConfirmMessage = mRoot.findViewById(R.id.rb_confirm_message);
        mConfirmPositive = mRoot.findViewById(R.id.rb_confirm_positive);
        mConfirmNegative = mRoot.findViewById(R.id.rb_confirm_negative);

        // Sheet width: full width on phones, capped on tablets
        int maxWidth = mActivity.getResources().getDimensionPixelSize(R.dimen.rb_sheet_max_width);
        int margin = mActivity.getResources().getDimensionPixelSize(R.dimen.rb_sheet_margin);
        int screenWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        if (screenWidth - 2 * margin > maxWidth) {
            ViewGroup.LayoutParams lp = mSheet.getLayoutParams();
            lp.width = maxWidth;
            mSheet.setLayoutParams(lp);
        }

        mRoot.findViewById(R.id.rb_close).setOnClickListener(v -> dismiss());
        // Tapping the scrim closes, taps on the sheet are consumed by the sheet itself
        mRoot.setOnClickListener(v -> dismiss());
        mSheet.setOnClickListener(v -> { /* swallow */ });

        mAdapter = new ResourceCardAdapter(mIconCache, this);
        LinearLayoutManager lm = new LinearLayoutManager(mActivity);
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null); // progress rebinding must not flash
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                int last = lm.findLastVisibleItemPosition();
                if (last >= mAdapter.itemCountWithoutFooter() - PREFETCH_THRESHOLD) loadNextPage();
            }
        });

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                mSearchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                mMain.removeCallbacks(mDebounced);
                mMain.postDelayed(mDebounced, DEBOUNCE_MS);
            }
        });
        mSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                mMain.removeCallbacks(mDebounced);
                startNewSearch();
                hideKeyboard();
                return true;
            }
            return false;
        });
        mSearchClear.setOnClickListener(v -> {
            mSearch.setText("");
            mSearch.requestFocus();
        });

        mTabPacks.setOnClickListener(v -> selectType(ContentType.RESOURCE_PACK));
        mTabShaders.setOnClickListener(v -> selectType(ContentType.SHADER_PACK));
        mTabPacks.setSelected(true);
        mSrcModrinth.setSelected(true);
        mSrcModrinth.setOnClickListener(v -> selectEngine(Constants.ENGINE_MODRINTH));
        mSrcCurseforge.setOnClickListener(v -> selectEngine(Constants.ENGINE_CURSEFORGE));
        if (!mCurseforgeAvailable) {
            View sources = mRoot.findViewById(R.id.rb_sources);
            if (sources != null) sources.setVisibility(View.GONE);
        }

        mStateAction.setOnClickListener(v -> startNewSearch());
        mConfirmRoot.setOnClickListener(v -> { /* swallow */ });

        mContainer.addView(mRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mShowing = true;
        mManager.addListener(this);
        showState(State.LOADING);
    }

    private void animateIn() {
        mRoot.setAlpha(0f);
        mSheet.setScaleX(0.94f);
        mSheet.setScaleY(0.94f);
        mSheet.setAlpha(0f);
        mRoot.animate().alpha(1f).setDuration(180).setInterpolator(com.craftstudio.cslauncher.utils.motion.Motion.STANDARD).start();
        mSheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240)
                .setInterpolator(com.craftstudio.cslauncher.utils.motion.Motion.ENTER).start();
        mRoot.requestFocus();
    }

    /** Back button / close: animates out and detaches. Safe to call twice. */
    @MainThread
    public void dismiss() {
        if (!mShowing || mDismissing) return;
        if (mConfirmRoot.getVisibility() == View.VISIBLE) {
            // Back while the replace question is open just closes the question (keeps the file)
            resolveConfirm(false);
            return;
        }
        mDismissing = true;
        hideKeyboard();
        mMain.removeCallbacks(mDebounced);
        mManager.removeListener(this);
        mSearchGeneration++; // drop in-flight search results
        mRoot.animate().alpha(0f).setDuration(160).start();
        mSheet.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(160)
                .withEndAction(this::detach).start();
    }

    private void detach() {
        mShowing = false;
        mRoot.animate().cancel();
        mSheet.animate().cancel();
        mList.setAdapter(null);
        mContainer.removeView(mRoot);
        mSearchExecutor.shutdownNow();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && mSearch != null) imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
        if (mSearch != null) mSearch.clearFocus();
        if (mRoot != null) mRoot.requestFocus();
    }

    // ------------------------------------------------------------------ profile

    private void resolveProfile() {
        final Instance instance = mInstance;
        final String versionId = mVersionId;
        mSearchExecutor.execute(() -> {
            ResourceProfileContext ctx = ResourceProfileContext.resolve(instance, versionId);
            mMain.post(() -> onProfileResolved(ctx));
        });
    }

    private void onProfileResolved(@NonNull ResourceProfileContext ctx) {
        if (!mShowing) return;
        mProfile = ctx;
        mProfileResolved = true;
        bindProfile();
        if (!ctx.isUsable()) {
            Toast.makeText(mActivity, R.string.rb_state_profile_title, Toast.LENGTH_LONG).show();
        }
        mAdapter.setContext(mType, ctx, ctx.isUsable());
        refreshInstalled();
        startNewSearch();
        // A download parked on "replace?" while the popup was closed asks again now
        for (ResourceInstallManager.Task t : mManager.activeTasks()) {
            if (t.status == ResourceInstallManager.Status.AWAITING_REPLACE) { askReplace(t); break; }
        }
    }

    private void bindProfile() {
        ResourceProfileContext p = mProfile;
        if (p == null) return;
        Context c = mActivity;
        mProfileName.setText(p.profileName);
        mProfileVersion.setText(p.mcVersion != null ? p.mcVersion
                : (p.versionId != null && !p.versionId.isEmpty() ? p.versionId : c.getString(R.string.rb_value_unknown)));
        mProfileLoader.setText(loaderLabel(p));
        File folder = p.folderFor(mType);
        if (folder == null) {
            mProfileDir.setText(R.string.rb_dir_unavailable);
            mProfileDir.setTextColor(ContextCompat.getColor(c, R.color.rb_bad));
        } else if (folder.isDirectory()) {
            mProfileDir.setText(R.string.rb_dir_ready);
            mProfileDir.setTextColor(ContextCompat.getColor(c, R.color.rb_ok));
        } else {
            mProfileDir.setText(R.string.rb_dir_will_create);
            mProfileDir.setTextColor(ContextCompat.getColor(c, R.color.rb_text));
        }
    }

    private String loaderLabel(@NonNull ResourceProfileContext p) {
        String base;
        switch (p.loader) {
            case "fabric": base = "Fabric"; break;
            case "forge": base = "Forge"; break;
            case "quilt": base = "Quilt"; break;
            case "neoforge": base = "NeoForge"; break;
            default: base = mActivity.getString(R.string.rb_loader_vanilla); break;
        }
        if (p.hasIris) base += " + Iris";
        else if (p.hasOptiFine) base += " + OptiFine";
        return base;
    }

    private void refreshInstalled() {
        if (mProfile == null) return;
        mManager.queryInstalled(mProfile, installed -> {
            if (mShowing) mAdapter.setInstalled(installed);
        });
    }

    // ------------------------------------------------------------------ tabs & search

    private void selectType(@NonNull ContentType type) {
        if (mType == type) return;
        mType = type;
        mTabPacks.setSelected(type == ContentType.RESOURCE_PACK);
        mTabShaders.setSelected(type == ContentType.SHADER_PACK);
        mAdapter.setContext(type, mProfile, mProfile != null && mProfile.isUsable());
        bindProfile();
        mMain.removeCallbacks(mDebounced);
        startNewSearch();
    }

    private void selectEngine(int engine) {
        if (mEngine == engine) return;
        mEngine = engine;
        mSrcModrinth.setSelected(engine == Constants.ENGINE_MODRINTH);
        mSrcCurseforge.setSelected(engine == Constants.ENGINE_CURSEFORGE);
        mMain.removeCallbacks(mDebounced);
        startNewSearch();
    }

    private void startNewSearch() {
        if (!mShowing || !mProfileResolved) return;
        final int generation = ++mSearchGeneration;
        final String query = mSearch.getText().toString().trim();
        final ContentType type = mType;
        final String mcVersion = mProfile == null ? null : mProfile.mcVersion;
        mLastPage = null;
        mEndReached = false;
        mLoadingPage = true;
        mAdapter.clear();
        showState(State.LOADING);
        mSearchSpinner.setVisibility(View.VISIBLE);

        mSearchExecutor.execute(() -> {
            PageResult page = fetchPage(type, query, mcVersion, null, true);
            mMain.post(() -> {
                if (generation != mSearchGeneration || !mShowing) return;
                mLoadingPage = false;
                mSearchSpinner.setVisibility(View.GONE);
                if (page.error) {
                    showState(State.ERROR);
                    return;
                }
                mLastPage = page.result;
                List<ModItem> items = page.items();
                if (items.isEmpty()) {
                    mEndReached = true;
                    showState(State.EMPTY);
                    return;
                }
                mAdapter.setItems(items);
                mEndReached = page.result == null || items.size() >= page.result.totalResultCount || items.size() < 50;
                mAdapter.setFooter(mEndReached ? ResourceCardAdapter.Footer.NONE : ResourceCardAdapter.Footer.LOADING);
                showState(State.LIST);
                mList.scrollToPosition(0);
            });
        });
    }

    private void loadNextPage() {
        if (!mShowing || mLoadingPage || mEndReached || mLastPage == null) return;
        final int generation = mSearchGeneration;
        final String query = mSearch.getText().toString().trim();
        final ContentType type = mType;
        final String mcVersion = mProfile == null ? null : mProfile.mcVersion;
        final SearchResult previous = mLastPage;
        mLoadingPage = true;
        mAdapter.setFooter(ResourceCardAdapter.Footer.LOADING);

        mSearchExecutor.execute(() -> {
            PageResult page = fetchPage(type, query, mcVersion, previous, false);
            mMain.post(() -> {
                if (generation != mSearchGeneration || !mShowing) return;
                mLoadingPage = false;
                if (page.error) {
                    // Keep what we have, offer a manual retry
                    mAdapter.setFooter(ResourceCardAdapter.Footer.LOAD_MORE);
                    return;
                }
                mLastPage = page.result;
                List<ModItem> items = page.items();
                mAdapter.appendItems(items);
                int total = page.result == null ? 0 : page.result.totalResultCount;
                mEndReached = items.isEmpty() || mAdapter.itemCountWithoutFooter() >= total;
                mAdapter.setFooter(mEndReached ? ResourceCardAdapter.Footer.NONE : ResourceCardAdapter.Footer.LOADING);
            });
        });
    }

    @Override
    public void onLoadMore() {
        loadNextPage();
    }

    private static final class PageResult {
        @Nullable SearchResult result;
        boolean error;

        @NonNull
        List<ModItem> items() {
            if (result == null || result.results == null) return new ArrayList<>();
            return new ArrayList<>(Arrays.asList(result.results));
        }
    }

    /**
     * Blocking Modrinth query. The first page is filtered to the profile's Minecraft version when
     * known; if that yields nothing (e.g. a pack that only targets other versions) it falls back to
     * an unfiltered search so the user still sees it, flagged as a version mismatch.
     */
    @NonNull
    private PageResult fetchPage(@NonNull ContentType type, @NonNull String query, @Nullable String mcVersion,
                                 @Nullable SearchResult previous, boolean firstPage) {
        PageResult out = new PageResult();
        try {
            boolean filtered = mcVersion != null && !mcVersion.isEmpty() && (previous == null || previous instanceof Filtered);
            SearchResult r = runSearch(type, query, filtered ? mcVersion : null, previous);
            if (r == null) {
                out.error = true;
                return out;
            }
            if (firstPage && filtered && (r.results == null || r.results.length == 0)) {
                SearchResult fallback = runSearch(type, query, null, null);
                if (fallback == null) { out.error = true; return out; }
                out.result = fallback;
                return out;
            }
            out.result = filtered ? Filtered.wrap(r) : r;
        } catch (Exception e) {
            android.util.Log.w("ResourceBrowser", "search failed", e);
            out.error = true;
        }
        return out;
    }

    @Nullable
    private SearchResult runSearch(@NonNull ContentType type, @NonNull String query, @Nullable String mcVersion,
                                   @Nullable SearchResult previous) {
        SearchFilters f = new SearchFilters();
        f.isModpack = false;
        f.contentType = type;
        f.name = query;
        f.mcVersion = mcVersion;
        f.sort = query.isEmpty() ? SearchFilters.SORT_DOWNLOADS : SearchFilters.SORT_RELEVANCE;
        f.engine = mCurseforgeAvailable ? mEngine : Constants.ENGINE_MODRINTH;
        SearchResult prev = previous instanceof Filtered ? ((Filtered) previous).inner : previous;
        return mApi.searchMod(f, prev);
    }

    /** Marker wrapper so later pages know the first page was version-filtered. */
    private static final class Filtered extends SearchResult {
        final SearchResult inner;
        Filtered(SearchResult inner) {
            this.inner = inner;
            this.results = inner.results;
            this.totalResultCount = inner.totalResultCount;
        }
        static Filtered wrap(SearchResult r) { return r instanceof Filtered ? (Filtered) r : new Filtered(r); }
    }

    // ------------------------------------------------------------------ state panel

    private enum State { LOADING, LIST, EMPTY, ERROR }

    private void showState(@NonNull State state) {
        boolean list = state == State.LIST;
        mList.setVisibility(list ? View.VISIBLE : View.INVISIBLE);
        mStateRoot.setVisibility(list ? View.GONE : View.VISIBLE);
        if (list) return;
        mStateSpinner.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
        mStateIcon.setVisibility(state == State.LOADING ? View.GONE : View.VISIBLE);
        mStateAction.setVisibility(state == State.ERROR ? View.VISIBLE : View.GONE);
        switch (state) {
            case LOADING:
                mStateTitle.setText(R.string.rb_state_loading);
                mStateMessage.setText("");
                mStateMessage.setVisibility(View.GONE);
                break;
            case EMPTY:
                mStateIcon.setImageResource(R.drawable.ic_rb_search_off);
                mStateTitle.setText(R.string.rb_state_empty_title);
                mStateMessage.setText(R.string.rb_state_empty_message);
                mStateMessage.setVisibility(View.VISIBLE);
                break;
            default:
                mStateIcon.setImageResource(R.drawable.ic_rb_alert);
                mStateTitle.setText(R.string.rb_state_offline_title);
                mStateMessage.setText(isOnline() ? R.string.rb_state_empty_message : R.string.rb_state_offline_message);
                mStateMessage.setVisibility(View.VISIBLE);
                break;
        }
        mStateRoot.setAlpha(0f);
        mStateRoot.animate().alpha(1f).setDuration(140).start();
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) mActivity.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ installs

    @Override
    public void onInstall(@NonNull ModItem item) {
        if (mProfile == null || !mProfile.isUsable()) {
            Toast.makeText(mActivity, R.string.rb_err_profile, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isOnline()) {
            Toast.makeText(mActivity, R.string.rb_state_offline_message, Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.isRestricted) {
            Toast.makeText(mActivity, R.string.rb_err_restricted, Toast.LENGTH_LONG).show();
            return;
        }
        ResourceInstallManager.Task task = mManager.install(item, mType, mProfile);
        if (task != null) mAdapter.onTaskChanged(task);
    }

    @Override
    public void onCancel(@NonNull ModItem item) {
        mManager.cancel(ResourceInstallManager.keyFor(mType, item));
    }

    @Override
    public void onTaskChanged(@NonNull ResourceInstallManager.Task task) {
        if (!mShowing) return;
        mAdapter.onTaskChanged(task);
        if (task.status == ResourceInstallManager.Status.AWAITING_REPLACE && task.conflictName != null) {
            askReplace(task);
        } else if (task.status == ResourceInstallManager.Status.INSTALLED && task.type == mType) {
            bindProfile(); // folder may just have been created
            Toast.makeText(mActivity, mActivity.getString(R.string.rb_toast_installed, task.item.title), Toast.LENGTH_SHORT).show();
        }
    }

    private void askReplace(@NonNull ResourceInstallManager.Task task) {
        if (mPendingConfirmKey != null) return; // one question at a time; the task stays parked
        mPendingConfirmKey = task.key;
        mConfirmMessage.setText(mActivity.getString(R.string.rb_replace_message, task.conflictName));
        mConfirmPositive.setOnClickListener(v -> resolveConfirm(true));
        mConfirmNegative.setOnClickListener(v -> resolveConfirm(false));
        mConfirmRoot.setAlpha(0f);
        mConfirmRoot.setVisibility(View.VISIBLE);
        mConfirmRoot.animate().alpha(1f).setDuration(140).start();
        View card = mConfirmRoot.findViewById(R.id.rb_confirm_card);
        card.setScaleX(0.95f); card.setScaleY(0.95f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(com.craftstudio.cslauncher.utils.motion.Motion.ENTER).start();
    }

    private void resolveConfirm(boolean replace) {
        String key = mPendingConfirmKey;
        mPendingConfirmKey = null;
        mConfirmRoot.animate().alpha(0f).setDuration(120).withEndAction(() -> mConfirmRoot.setVisibility(View.GONE)).start();
        if (key == null) return;
        if (replace) mManager.confirmReplace(key);
        else mManager.keepExisting(key);
        // Another task may be waiting for its own answer
        for (ResourceInstallManager.Task t : mManager.activeTasks()) {
            if (t.status == ResourceInstallManager.Status.AWAITING_REPLACE && !t.key.equals(key)) {
                mMain.postDelayed(() -> { if (mShowing && t.status == ResourceInstallManager.Status.AWAITING_REPLACE) askReplace(t); }, 200);
                break;
            }
        }
    }
}
