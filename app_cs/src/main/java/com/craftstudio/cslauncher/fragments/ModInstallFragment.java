package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.ContentInstaller;
import com.craftstudio.cslauncher.modding.modpacks.InstalledContentIndex;
import com.craftstudio.cslauncher.modding.modpacks.LibraryCardAdapter;
import com.craftstudio.cslauncher.modding.modpacks.LibraryCatalog;
import com.craftstudio.cslauncher.modding.modpacks.api.CommonApi;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ImageReceiver;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.ThemeColors;
import com.craftstudio.cslauncher.utils.motion.InstallMotion;
import com.craftstudio.cslauncher.utils.motion.LibraryMotion;
import com.craftstudio.cslauncher.utils.motion.MainMenuMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.GalleryImageLoader;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Install page of the content library. Detects the Minecraft version and loader of the
 * selected profile, recommends the best matching version of the project, lets the user pick
 * another one with "Change version", and installs, updates or uninstalls it.
 */
public class ModInstallFragment extends Fragment {

    public static final String TAG = "ModInstallFragment";
    /** ModItem serialized with Tools.GLOBAL_GSON */
    public static final String ARG_ITEM = "library_item";
    /** ContentType name() */
    public static final String ARG_CONTENT_TYPE = "content_type";
    /** Name of the library section, shown in the breadcrumb */
    public static final String ARG_LIBRARY_TITLE = "library_title";

    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3);

    private ModItem mItem;
    private ContentType mType = ContentType.MOD;
    private String mLibraryTitle;
    private String mCurseforgeKey;
    private CommonApi mApi;
    private ModIconCache mIconCache;

    @Nullable private Instance mInstance;
    @Nullable private String mProfileVersion;
    private String mProfileLoader = "";

    // Loaded state, kept across view re-creation
    private boolean mLoading;
    private boolean mLoadFailed;
    @Nullable private ModDetail mDetail;
    @Nullable private InstalledContentIndex mIndex;
    @Nullable private InstalledContentIndex.Entry mInstalled;
    private int mInstalledIndex = -1;
    private int mRecommendedIndex = -1;
    private int mSelectedIndex = -1;
    private boolean mUserPicked;
    private boolean mInstalling;
    private int mLoadGeneration;
    private final Map<String, String[]> mDependencyInfo = new HashMap<>();
    private int mDependencyGeneration;
    @Nullable private List<ContentInstaller.GalleryImage> mGallery;
    private boolean mGalleryLoading;
    private boolean mEntrancePlayed;

    // Views
    private TextView mVersionLabel, mVersionBadge, mVersionName, mVersionMeta, mVersionExtra, mVersionWarning, mStatus;
    private View mVersionLoading;
    private MaterialButton mChangeVersion, mUseRecommended, mPrimary, mUninstall;
    private View mProgress;
    private LinearLayout mDepsList;
    private TextView mDepsEmpty;
    private View mVersionBox, mGallerySection;
    private RecyclerView mGalleryList;
    private TextView mGalleryCount;
    private final List<DependencyRow> mDependencyRows = new ArrayList<>();

    /** One dependency of the selected version */
    private static final class DependencyRow {
        final String projectId;
        final boolean required;
        boolean installed;
        MaterialCheckBox check;
        ImageView icon;
        TextView name;
        TextView status;
        ImageReceiver receiver;

        DependencyRow(String projectId, boolean required) {
            this.projectId = projectId;
            this.required = required;
        }
    }

    public ModInstallFragment() {
        super(R.layout.fragment_mod_install);
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
        if (mItem == null && !readArguments()) {
            Tools.removeCurrentFragment(requireActivity());
            return;
        }

        mVersionLabel = view.findViewById(R.id.install_version_label);
        mVersionBadge = view.findViewById(R.id.install_version_badge);
        mVersionLoading = view.findViewById(R.id.install_version_loading);
        mVersionName = view.findViewById(R.id.install_version_name);
        mVersionMeta = view.findViewById(R.id.install_version_meta);
        mVersionExtra = view.findViewById(R.id.install_version_extra);
        mVersionWarning = view.findViewById(R.id.install_version_warning);
        mChangeVersion = view.findViewById(R.id.install_change_version);
        mUseRecommended = view.findViewById(R.id.install_use_recommended);
        mPrimary = view.findViewById(R.id.install_primary);
        mProgress = view.findViewById(R.id.install_progress);
        mStatus = view.findViewById(R.id.install_status);
        mUninstall = view.findViewById(R.id.install_uninstall);
        mDepsList = view.findViewById(R.id.install_deps_list);
        mDepsEmpty = view.findViewById(R.id.install_deps_empty);
        mVersionBox = view.findViewById(R.id.install_version_box);
        mGallerySection = view.findViewById(R.id.install_gallery_section);
        mGalleryList = view.findViewById(R.id.install_gallery);
        mGalleryCount = view.findViewById(R.id.install_gallery_count);

        View back = view.findViewById(R.id.install_back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(back);
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(mPrimary);
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(mChangeVersion);
        bindProjectInfo(view);
        bindProfile(view);
        setupGallery();

        if (!mEntrancePlayed) {
            mEntrancePlayed = true;
            boolean wide = view.findViewById(R.id.install_columns) != null;
            InstallMotion.playEntrance((View) back.getParent(), view.findViewById(R.id.install_info_panel),
                    view.findViewById(R.id.install_action_panel), wide, view.findViewById(R.id.install_tags));
            countStats(view);
        }

        mChangeVersion.setOnClickListener(v -> showVersionPicker());
        mUseRecommended.setOnClickListener(v -> {
            if (mRecommendedIndex < 0) return;
            mSelectedIndex = mRecommendedIndex;
            mUserPicked = false;
            renderVersion();
            InstallMotion.versionChanged(mVersionBox);
            renderDependencies();
            renderActions();
        });
        mPrimary.setOnClickListener(v -> onPrimaryClicked());
        mUninstall.setOnClickListener(v -> confirmUninstall());

        if (mDetail != null) {
            renderVersion();
            renderDependencies();
            renderActions();
        } else if (mLoadFailed) {
            renderLoadFailed();
        } else {
            renderLoading();
            if (!mLoading) load();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Full screen install page: the launcher top bar steps aside while it is visible
        MainMenuMotion.topBar(getActivity(), false);
    }

    @Override
    public void onStop() {
        MainMenuMotion.topBar(getActivity(), true);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        for (DependencyRow row : mDependencyRows) {
            if (row.receiver != null) mIconCache.cancelImage(row.receiver);
        }
        mDependencyRows.clear();
        mVersionLabel = mVersionBadge = mVersionName = mVersionMeta = mVersionExtra = mVersionWarning = mStatus = null;
        mVersionLoading = mProgress = null;
        mChangeVersion = mUseRecommended = mPrimary = mUninstall = null;
        mDepsList = null;
        mDepsEmpty = null;
        if (mGalleryList != null) mGalleryList.setAdapter(null);
        mVersionBox = mGallerySection = null;
        mGalleryList = null;
        mGalleryCount = null;
    }

    private boolean readArguments() {
        Bundle args = getArguments();
        if (args == null) return false;
        String json = args.getString(ARG_ITEM, null);
        if (json == null) return false;
        try {
            mItem = Tools.GLOBAL_GSON.fromJson(json, ModItem.class);
        } catch (Exception e) {
            mItem = null;
        }
        if (mItem == null || mItem.id == null) return false;
        String type = args.getString(ARG_CONTENT_TYPE, null);
        if (type != null) {
            try {
                mType = ContentType.valueOf(type);
            } catch (IllegalArgumentException ignored) {}
        }
        mLibraryTitle = args.getString(ARG_LIBRARY_TITLE, null);
        if (mLibraryTitle == null) mLibraryTitle = getString(R.string.lib_tab_mods);

        mInstance = Instances.loadSelectedInstance();
        String key = Instances.getSelectedInstanceKey();
        // A modpack creates its own profile, so the selected one plays no part in compatibility
        if (!isModpack() && mInstance != null && key != null && !key.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(key);
            mProfileVersion = info.mcVersion;
            mProfileLoader = info.loader == null ? "" : info.loader;
        }
        return true;
    }

    /** Modpack browser: install creates a new profile instead of touching the selected one */
    private boolean isModpack() {
        return mType == ContentType.MODPACK;
    }

    /** Loader used for matching versions: only mods depend on the profile loader */
    private String typeLoader() {
        return mType == ContentType.MOD ? mProfileLoader : "";
    }

    // ─── Static parts ────────────────────────────────────────────────────────

    private void bindProjectInfo(View view) {
        Context context = view.getContext();
        TextView breadcrumb = view.findViewById(R.id.install_breadcrumb);
        breadcrumb.setText(getString(R.string.lib_breadcrumb, mLibraryTitle, mItem.title));
        TextView source = view.findViewById(R.id.install_source);
        boolean fromCurseforge = mItem.apiSource == Constants.SOURCE_CURSEFORGE;
        source.setText(fromCurseforge ? R.string.lib_source_curseforge : R.string.lib_source_modrinth);
        styleBadge(source, BADGE_NEUTRAL);
        // Brand-coloured pill with the platform logo
        int brand = androidx.core.content.ContextCompat.getColor(context,
                fromCurseforge ? R.color.lib_src_curseforge : R.color.lib_src_modrinth);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        pill.setColor((brand & 0x00FFFFFF) | 0x2E000000);
        pill.setStroke(Math.round(getResources().getDisplayMetrics().density), (brand & 0x00FFFFFF) | 0x99000000);
        source.setBackground(pill);
        source.setTextColor(brand);
        source.setTypeface(source.getTypeface(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.Drawable logo = androidx.core.content.ContextCompat.getDrawable(context,
                fromCurseforge ? R.drawable.ic_curseforge : R.drawable.ic_modrinth);
        if (logo != null) {
            logo = androidx.core.graphics.drawable.DrawableCompat.wrap(logo.mutate());
            androidx.core.graphics.drawable.DrawableCompat.setTint(logo, brand);
            int size = Math.round(14 * getResources().getDisplayMetrics().density);
            logo.setBounds(0, 0, size, size);
            source.setCompoundDrawablesRelative(logo, null, null, null);
        }

        ((TextView) view.findViewById(R.id.install_title)).setText(mItem.title);
        TextView author = view.findViewById(R.id.install_author);
        if (TextUtils.isEmpty(mItem.author)) {
            author.setVisibility(View.GONE);
        } else {
            author.setText(getString(R.string.lib_by_author, mItem.author));
        }
        ((TextView) view.findViewById(R.id.install_description)).setText(mItem.description);

        LinearLayout tags = view.findViewById(R.id.install_tags);
        tags.removeAllViews();
        String category = LibraryCatalog.primaryCategory(mType, mItem, null);
        if (category != null) {
            addTag(tags, LibraryCatalog.labelFor(mType, category), LibraryCatalog.tintFor(mType, category));
        }
        String loader = LibraryCatalog.displayLoader(mType, mItem, typeLoader());
        if (loader != null) addTag(tags, LibraryCatalog.loaderLabel(loader), LibraryCatalog.TINT_NEUTRAL);
        String version = LibraryCatalog.versionPill(mItem.gameVersions, mProfileVersion);
        if (version != null) addTag(tags, version, LibraryCatalog.TINT_NEUTRAL);
        tags.setVisibility(tags.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        TextView downloads = view.findViewById(R.id.install_stat_downloads);
        TextView follows = view.findViewById(R.id.install_stat_follows);
        TextView updated = view.findViewById(R.id.install_stat_updated);
        bindStat(downloads, mItem.downloads > 0
                ? getString(R.string.lib_stat_downloads, LibraryCatalog.compactNumber(mItem.downloads)) : null);
        bindStat(follows, mItem.follows > 0
                ? getString(R.string.lib_stat_follows, LibraryCatalog.compactNumber(mItem.follows)) : null);
        String relative = LibraryCatalog.relativeDate(mItem.dateModified);
        bindStat(updated, TextUtils.isEmpty(relative) ? null : getString(R.string.lib_stat_updated, relative));
        if (downloads.getVisibility() == View.GONE && follows.getVisibility() == View.GONE
                && updated.getVisibility() == View.GONE) {
            view.findViewById(R.id.install_stats).setVisibility(View.GONE);
        }

        ImageView icon = view.findViewById(R.id.install_icon);
        icon.setImageResource(iconPlaceholder());
        float radius = 14 * getResources().getDisplayMetrics().density;
        mIconCache.getImage(new ImageReceiver() {
            @Override
            public void onImageAvailable(Bitmap image) {
                if (getView() == null || image == null) return;
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(getResources(), image);
                drawable.setCornerRadius(radius);
                icon.setImageDrawable(drawable);
            }
        }, mItem.getIconCacheTag(), mItem.imageUrl);
    }

    private int iconPlaceholder() {
        switch (mType) {
            case RESOURCE_PACK: return R.drawable.ic_lib_image;
            case SHADER_PACK: return R.drawable.ic_lib_sun;
            case DATAPACK: return R.drawable.ic_lib_world;
            case WORLD: return R.drawable.ic_lib_world;
            case MODPACK: return R.drawable.ic_lib_world;
            default: return R.drawable.ic_lib_cube;
        }
    }

    private void addTag(LinearLayout parent, String text, int tint) {
        if (TextUtils.isEmpty(text)) return;
        Context context = parent.getContext();
        float density = getResources().getDisplayMetrics().density;
        TextView tag = new TextView(context, null, 0, R.style.Library_Tag);
        tag.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(26 * density));
        params.setMarginEnd(Math.round(6 * density));
        tag.setLayoutParams(params);
        LibraryCardAdapter.applyTint(context, tag, tint);
        parent.addView(tag);
    }

    private void bindStat(TextView stat, @Nullable String text) {
        if (text == null) {
            stat.setVisibility(View.GONE);
            return;
        }
        stat.setText(text);
        int color = ThemeColors.get(stat.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        Drawable[] drawables = stat.getCompoundDrawablesRelative();
        if (drawables[0] != null) {
            int size = Math.round(16 * getResources().getDisplayMetrics().density);
            Drawable start = drawables[0].mutate();
            start.setBounds(0, 0, size, size);
            stat.setCompoundDrawablesRelative(start, null, null, null);
        }
        TextViewCompat.setCompoundDrawableTintList(stat, ColorStateList.valueOf(color));
    }

    private void bindProfile(View view) {
        ImageView icon = view.findViewById(R.id.install_profile_icon);
        TextView name = view.findViewById(R.id.install_profile_name);
        TextView version = view.findViewById(R.id.install_profile_version);
        View autoBadge = view.findViewById(R.id.install_auto_badge);
        if (isModpack()) {
            name.setText(R.string.lib_modpack_new_profile);
            version.setText(R.string.lib_modpack_profile_hint);
            autoBadge.setVisibility(View.GONE);
            icon.setImageResource(R.drawable.ic_pe_plus);
            return;
        }
        if (mInstance == null) {
            name.setText(R.string.lib_no_profile);
            version.setText(R.string.lib_profile_any_version);
            autoBadge.setVisibility(View.GONE);
            icon.setImageResource(R.drawable.ic_lib_cube);
            return;
        }
        name.setText(TextUtils.isEmpty(mInstance.name) ? getString(R.string.lib_no_profile) : mInstance.name);
        version.setText(profileLabel());
        autoBadge.setVisibility(mProfileVersion != null ? View.VISIBLE : View.GONE);
        if (autoBadge instanceof TextView) styleBadge((TextView) autoBadge, BADGE_GREEN);
        try {
            icon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), mInstance));
        } catch (Exception e) {
            icon.setImageResource(R.drawable.ic_lib_cube);
        }
    }

    /** "Fabric 1.21.1", "1.20.4" or "Version not detected" */
    private String profileLabel() {
        if (isModpack()) return getString(R.string.lib_modpack_any_version);
        if (mProfileVersion == null) return getString(R.string.lib_profile_any_version);
        String loader = mType == ContentType.MOD ? LibraryCatalog.loaderLabel(mProfileLoader) : "";
        return loader.isEmpty() ? mProfileVersion : loader + " " + mProfileVersion;
    }

    /** Download and follower numbers count up from zero on the first appearance */
    private void countStats(View view) {
        TextView downloads = view.findViewById(R.id.install_stat_downloads);
        TextView follows = view.findViewById(R.id.install_stat_follows);
        if (mItem.downloads > 0 && downloads.getVisibility() == View.VISIBLE) {
            Motion.countUp(downloads, mItem.downloads, 380, value -> isAdded()
                    ? getString(R.string.lib_stat_downloads, LibraryCatalog.compactNumber(value)) : "");
        }
        if (mItem.follows > 0 && follows.getVisibility() == View.VISIBLE) {
            Motion.countUp(follows, mItem.follows, 460, value -> isAdded()
                    ? getString(R.string.lib_stat_follows, LibraryCatalog.compactNumber(value)) : "");
        }
    }

    // ─── Gallery ─────────────────────────────────────────────────────────────

    private void setupGallery() {
        if (mGalleryList == null) return;
        mGalleryList.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        mGalleryList.setNestedScrollingEnabled(false);
        mGalleryList.setHasFixedSize(true);
        mGalleryList.setItemAnimator(null);
        if (mGalleryList.getOnFlingListener() == null) new LinearSnapHelper().attachToRecyclerView(mGalleryList);
        if (mGallery != null) {
            renderGallery(false);
        } else if (!mGalleryLoading) {
            loadGallery();
        }
    }

    private void loadGallery() {
        mGalleryLoading = true;
        final ModItem item = mItem;
        final String key = mCurseforgeKey;
        sExecutor.execute(() -> {
            List<ContentInstaller.GalleryImage> images = ContentInstaller.fetchGallery(item, key);
            Tools.runOnUiThread(() -> {
                mGalleryLoading = false;
                mGallery = images;
                if (getView() != null) renderGallery(true);
            });
        });
    }

    private void renderGallery(boolean animate) {
        if (mGallerySection == null || mGalleryList == null) return;
        if (mGallery == null || mGallery.isEmpty()) {
            mGallerySection.setVisibility(View.GONE);
            return;
        }
        mGalleryCount.setText(getString(R.string.lib_gallery_count, mGallery.size()));
        mGalleryList.setAdapter(new GalleryAdapter(mGallery, animate));
        if (animate) {
            mGallerySection.setVisibility(View.GONE);
            Motion.reveal(mGallerySection);
        } else {
            mGallerySection.setVisibility(View.VISIBLE);
        }
    }

    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.Holder> {
        private final List<ContentInstaller.GalleryImage> mImages;
        private int mAnimatedUpTo;

        GalleryAdapter(List<ContentInstaller.GalleryImage> images, boolean animate) {
            mImages = images;
            mAnimatedUpTo = animate ? -1 : Integer.MAX_VALUE;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;

            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.gallery_thumb_image);
                view.setClipToOutline(true);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lib_gallery_thumb, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ContentInstaller.GalleryImage image = mImages.get(position);
            holder.image.setImageDrawable(null);
            holder.image.setAlpha(1f);
            int width = Math.round(216 * holder.itemView.getResources().getDisplayMetrics().density);
            GalleryImageLoader.get(holder.itemView.getContext()).load(holder.image, image.thumbnailUrl, width,
                    (view, fromMemory) -> {
                        if (!fromMemory) InstallMotion.imageLoaded(view);
                    });
            holder.itemView.setContentDescription(image.title);
            holder.itemView.setOnClickListener(v -> openGalleryViewer(holder.getAdapterPosition()));
            if (position > mAnimatedUpTo) {
                mAnimatedUpTo = position;
                InstallMotion.galleryItemIn(holder.itemView, position);
            }
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            GalleryImageLoader.get(holder.itemView.getContext()).cancel(holder.image);
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationX(0f);
        }

        @Override
        public int getItemCount() {
            return mImages.size();
        }
    }

    /** Full screen, swipeable screenshot viewer */
    private void openGalleryViewer(int start) {
        if (mGallery == null || mGallery.isEmpty() || start < 0 || !isAdded()) return;
        final List<ContentInstaller.GalleryImage> images = mGallery;
        android.app.Dialog dialog = new android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lib_gallery, null, false);
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
        }
        ViewPager2 pager = content.findViewById(R.id.gallery_pager);
        TextView counter = content.findViewById(R.id.gallery_counter);
        TextView title = content.findViewById(R.id.gallery_title);
        TextView description = content.findViewById(R.id.gallery_description);
        View caption = content.findViewById(R.id.gallery_caption);
        View close = content.findViewById(R.id.gallery_close);
        close.setOnClickListener(v -> dialog.dismiss());
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(close);

        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View page = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lib_gallery_page, parent, false);
                return new RecyclerView.ViewHolder(page) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ImageView image = holder.itemView.findViewById(R.id.gallery_page_image);
                View loading = holder.itemView.findViewById(R.id.gallery_page_loading);
                image.setImageDrawable(null);
                loading.setVisibility(View.VISIBLE);
                GalleryImageLoader.get(image.getContext()).load(image, images.get(position).url, screenWidth,
                        (view, fromMemory) -> {
                            loading.setVisibility(View.GONE);
                            if (!fromMemory) InstallMotion.imageLoaded(view);
                        });
            }

            @Override
            public int getItemCount() {
                return images.size();
            }
        });
        // Pages slide with a light depth effect
        pager.setPageTransformer((page, position) -> {
            float abs = Math.abs(position);
            page.setAlpha(1f - Math.min(abs, 1f) * 0.5f);
            float scale = 1f - Math.min(abs, 1f) * 0.08f;
            page.setScaleX(scale);
            page.setScaleY(scale);
        });
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                ContentInstaller.GalleryImage image = images.get(position);
                counter.setText(getString(R.string.lib_gallery_counter, position + 1, images.size()));
                boolean hasTitle = !TextUtils.isEmpty(image.title);
                boolean hasDescription = !TextUtils.isEmpty(image.description);
                title.setText(image.title);
                title.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
                description.setText(image.description);
                description.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
                caption.setVisibility(hasTitle || hasDescription ? View.VISIBLE : View.GONE);
            }
        });
        pager.setCurrentItem(start, false);
        dialog.show();
        InstallMotion.viewerOpen(pager);
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private void load() {
        mLoading = true;
        mLoadFailed = false;
        final int generation = ++mLoadGeneration;
        final ModItem item = mItem;
        final ContentType type = mType;
        final String version = mProfileVersion;
        final String loader = mProfileLoader;
        final String key = mCurseforgeKey;
        final boolean hasProfile = mInstance != null && !isModpack();
        sExecutor.execute(() -> {
            ModDetail detail = null;
            InstalledContentIndex index = null;
            try {
                detail = mApi.getModDetails(item);
            } catch (Exception ignored) {}
            if (hasProfile) {
                try {
                    index = InstalledContentIndex.build(type, version, loader, key);
                } catch (Exception ignored) {}
            }
            final ModDetail loadedDetail = detail;
            final InstalledContentIndex loadedIndex = index != null ? index : InstalledContentIndex.empty(type);
            Tools.runOnUiThread(() -> {
                if (generation != mLoadGeneration) return;
                mLoading = false;
                onLoaded(loadedDetail, loadedIndex);
            });
        });
    }

    private void onLoaded(@Nullable ModDetail detail, @NonNull InstalledContentIndex index) {
        mIndex = index;
        mInstalled = index.find(mItem);
        if (detail == null || detail.versionNames == null || detail.versionNames.length == 0) {
            mDetail = null;
            mLoadFailed = true;
            if (getView() != null) renderLoadFailed();
            return;
        }
        mDetail = detail;
        mInstalledIndex = -1;
        if (mInstalled != null && mInstalled.versionId != null && detail.versionIds != null) {
            for (int i = 0; i < detail.versionIds.length; i++) {
                if (mInstalled.versionId.equals(detail.versionIds[i])) {
                    mInstalledIndex = i;
                    break;
                }
            }
        }
        mRecommendedIndex = findRecommended();
        mSelectedIndex = mRecommendedIndex >= 0 ? mRecommendedIndex : newestIndex(false);
        if (mInstalledIndex >= 0 && (mRecommendedIndex < 0 || !isNewer(mRecommendedIndex, mInstalledIndex))) {
            // The installed version is already the best one (or newer), keep it selected
            mSelectedIndex = mInstalledIndex;
        }
        mUserPicked = false;
        if (getView() == null) return;
        renderVersion();
        renderDependencies();
        renderActions();
        InstallMotion.versionLoaded(mVersionBox);
        InstallMotion.dependenciesIn(mDepsList);
    }

    // ─── Version logic ───────────────────────────────────────────────────────

    private int versionCount() {
        return mDetail == null || mDetail.versionNames == null ? 0 : mDetail.versionNames.length;
    }

    private boolean isRestrictedProject() {
        if (mDetail == null) return mItem.isRestricted;
        if (mItem.isRestricted) return true;
        if (mDetail.versionUrls == null) return true;
        for (String url : mDetail.versionUrls) {
            if (url != null && !url.isEmpty()) return false;
        }
        return true;
    }

    private String[] gameVersionsOf(int index) {
        if (mDetail.versionGameVersions != null && index < mDetail.versionGameVersions.length
                && mDetail.versionGameVersions[index] != null) {
            return mDetail.versionGameVersions[index];
        }
        if (mDetail.mcVersionNames != null && index < mDetail.mcVersionNames.length && mDetail.mcVersionNames[index] != null) {
            return new String[]{mDetail.mcVersionNames[index]};
        }
        return new String[0];
    }

    @Nullable
    private String[] loadersOf(int index) {
        if (mDetail.versionLoaders != null && index < mDetail.versionLoaders.length) return mDetail.versionLoaders[index];
        return null;
    }

    private boolean isReleaseVersion(int index) {
        if (mDetail.versionTypes == null || index >= mDetail.versionTypes.length) return true;
        String type = mDetail.versionTypes[index];
        return type == null || type.isEmpty() || "release".equalsIgnoreCase(type);
    }

    private long dateOf(int index) {
        if (mDetail.versionDates == null || index >= mDetail.versionDates.length) return 0;
        return LibraryCatalog.parseIsoDate(mDetail.versionDates[index]);
    }

    /** Whether version a was published after version b; the APIs list newest first otherwise */
    private boolean isNewer(int a, int b) {
        long dateA = dateOf(a), dateB = dateOf(b);
        if (dateA > 0 && dateB > 0 && dateA != dateB) return dateA > dateB;
        return a < b;
    }

    private boolean isDownloadable(int index) {
        return mDetail.versionUrls != null && index < mDetail.versionUrls.length
                && mDetail.versionUrls[index] != null && !mDetail.versionUrls[index].isEmpty();
    }

    /** Whether a version runs on the selected profile */
    private boolean isCompatible(int index) {
        if (mProfileVersion == null || mType == ContentType.WORLD) return true;
        if (!LibraryCatalog.containsVersion(gameVersionsOf(index), mProfileVersion)) return false;
        return mType != ContentType.MOD || LibraryCatalog.loaderMatches(loadersOf(index), mProfileLoader);
    }

    /** Version indices, newest first */
    private List<Integer> orderedIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < versionCount(); i++) indices.add(i);
        Collections.sort(indices, (a, b) -> {
            if (a.equals(b)) return 0;
            return isNewer(a, b) ? -1 : 1;
        });
        return indices;
    }

    /** First compatible release, else any compatible version, else -1 */
    private int findRecommended() {
        if (mProfileVersion == null) return newestIndex(true);
        int fallback = -1;
        for (int index : orderedIndices()) {
            if (!isDownloadable(index) || !isCompatible(index)) continue;
            if (isReleaseVersion(index)) return index;
            if (fallback < 0) fallback = index;
        }
        return fallback;
    }

    /** Newest downloadable version, preferring releases when asked */
    private int newestIndex(boolean releaseOnly) {
        int fallback = -1;
        for (int index : orderedIndices()) {
            if (!isDownloadable(index)) continue;
            if (!releaseOnly || isReleaseVersion(index)) return index;
            if (fallback < 0) fallback = index;
        }
        if (fallback < 0 && versionCount() > 0) fallback = 0;
        return fallback;
    }

    private boolean hasAnyCompatible() {
        for (int i = 0; i < versionCount(); i++) {
            if (isDownloadable(i) && isCompatible(i)) return true;
        }
        return false;
    }

    private String versionTitle(int index) {
        String name = mDetail.versionNames[index];
        String number = mDetail.versionNumbers != null && index < mDetail.versionNumbers.length
                ? mDetail.versionNumbers[index] : null;
        if (TextUtils.isEmpty(name)) return number == null ? "" : number;
        return name;
    }

    /** "Fabric, Quilt · 1.21 – 1.21.1 · Beta" */
    private String versionMeta(int index) {
        List<String> parts = new ArrayList<>();
        String[] loaders = loadersOf(index);
        if (loaders != null && mType != ContentType.RESOURCE_PACK && mType != ContentType.DATAPACK && mType != ContentType.WORLD) {
            List<String> labels = new ArrayList<>();
            for (String loader : loaders) {
                if (loader == null || "minecraft".equalsIgnoreCase(loader)) continue;
                labels.add(LibraryCatalog.loaderLabel(loader));
            }
            if (!labels.isEmpty()) parts.add(TextUtils.join(", ", labels));
        }
        String range = LibraryCatalog.versionRange(gameVersionsOf(index));
        if (range != null) parts.add(range);
        if (!isReleaseVersion(index)) parts.add(LibraryCatalog.capitalize(mDetail.versionTypes[index]));
        return TextUtils.join("  \u00b7  ", parts);
    }

    /** "sodium-fabric-0.6.0.jar · 1.2 MB · 3 days ago" */
    private String versionExtra(int index) {
        List<String> parts = new ArrayList<>();
        if (mDetail.versionFileNames != null && index < mDetail.versionFileNames.length
                && !TextUtils.isEmpty(mDetail.versionFileNames[index])) {
            parts.add(mDetail.versionFileNames[index]);
        }
        if (mDetail.versionFileSizes != null && index < mDetail.versionFileSizes.length) {
            String size = LibraryCatalog.fileSize(mDetail.versionFileSizes[index]);
            if (!TextUtils.isEmpty(size)) parts.add(size);
        }
        if (mDetail.versionDates != null && index < mDetail.versionDates.length) {
            String date = LibraryCatalog.relativeDate(mDetail.versionDates[index]);
            if (!TextUtils.isEmpty(date)) parts.add(date);
        }
        return TextUtils.join("  \u00b7  ", parts);
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    private static final int BADGE_GREEN = 0, BADGE_WARNING = 1, BADGE_NEUTRAL = 2;

    private void styleBadge(TextView badge, int style) {
        Context context = badge.getContext();
        switch (style) {
            case BADGE_WARNING:
                badge.setBackgroundResource(R.drawable.bg_lib_badge_warning);
                badge.setTextColor(ContextCompat.getColor(context, R.color.lib_warning));
                break;
            case BADGE_NEUTRAL:
                badge.setBackgroundResource(R.drawable.bg_lib_badge_neutral);
                badge.setTextColor(ThemeColors.get(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
                break;
            default:
                badge.setBackgroundResource(R.drawable.bg_lib_badge_green);
                badge.setTextColor(ContextCompat.getColor(context, R.color.lib_badge_green_text));
                break;
        }
    }

    private void renderLoading() {
        mVersionLoading.setVisibility(View.VISIBLE);
        mVersionName.setVisibility(View.GONE);
        mVersionMeta.setVisibility(View.GONE);
        mVersionExtra.setVisibility(View.GONE);
        mVersionWarning.setVisibility(View.GONE);
        mVersionBadge.setVisibility(View.GONE);
        mChangeVersion.setEnabled(false);
        mUseRecommended.setVisibility(View.GONE);
        mDepsEmpty.setVisibility(View.VISIBLE);
        mDepsEmpty.setText(R.string.lib_dep_resolving);
        styleInstallButton(R.string.lib_checking, R.drawable.ic_lib_download);
        mPrimary.setEnabled(false);
        mUninstall.setVisibility(View.GONE);
    }

    private void renderLoadFailed() {
        mVersionLoading.setVisibility(View.GONE);
        mVersionName.setVisibility(View.GONE);
        mVersionMeta.setVisibility(View.GONE);
        mVersionExtra.setVisibility(View.GONE);
        mVersionBadge.setVisibility(View.GONE);
        mVersionWarning.setVisibility(View.VISIBLE);
        mVersionWarning.setText(R.string.lib_versions_failed);
        mChangeVersion.setEnabled(false);
        mUseRecommended.setVisibility(View.GONE);
        mDepsEmpty.setVisibility(View.GONE);
        mDepsList.removeAllViews();
        styleInstallButton(R.string.lib_retry, R.drawable.ic_lib_update);
        mPrimary.setEnabled(true);
        mUninstall.setVisibility(mInstalled != null ? View.VISIBLE : View.GONE);
    }

    private void renderVersion() {
        mVersionLoading.setVisibility(View.GONE);
        boolean restricted = isRestrictedProject();
        if (restricted || mSelectedIndex < 0) {
            mVersionLabel.setText(R.string.lib_selected_version);
            mVersionBadge.setVisibility(View.GONE);
            mVersionName.setVisibility(View.VISIBLE);
            mVersionName.setText(restricted ? getString(R.string.lib_open_curseforge) : mItem.title);
            mVersionMeta.setVisibility(View.GONE);
            mVersionExtra.setVisibility(View.GONE);
            mVersionWarning.setVisibility(View.VISIBLE);
            mVersionWarning.setText(restricted ? getString(R.string.lib_restricted) : getString(R.string.lib_versions_failed));
            mChangeVersion.setEnabled(false);
            mUseRecommended.setVisibility(View.GONE);
            return;
        }

        int index = mSelectedIndex;
        boolean compatible = isCompatible(index);
        mVersionLabel.setText(index == mRecommendedIndex ? R.string.lib_recommended_version : R.string.lib_selected_version);

        if (index == mInstalledIndex) {
            mVersionBadge.setText(R.string.lib_badge_installed);
            styleBadge(mVersionBadge, BADGE_GREEN);
            mVersionBadge.setVisibility(View.VISIBLE);
        } else if (!compatible) {
            mVersionBadge.setText(R.string.lib_badge_incompatible);
            styleBadge(mVersionBadge, BADGE_WARNING);
            mVersionBadge.setVisibility(View.VISIBLE);
        } else if (index == mRecommendedIndex) {
            mVersionBadge.setText(R.string.lib_badge_recommended);
            styleBadge(mVersionBadge, BADGE_GREEN);
            mVersionBadge.setVisibility(View.VISIBLE);
        } else {
            mVersionBadge.setText(R.string.lib_badge_selected);
            styleBadge(mVersionBadge, BADGE_NEUTRAL);
            mVersionBadge.setVisibility(View.VISIBLE);
        }

        mVersionName.setVisibility(View.VISIBLE);
        mVersionName.setText(versionTitle(index));
        String meta = versionMeta(index);
        mVersionMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        mVersionMeta.setText(meta);
        String extra = versionExtra(index);
        mVersionExtra.setVisibility(extra.isEmpty() ? View.GONE : View.VISIBLE);
        mVersionExtra.setText(extra);

        if (!compatible) {
            mVersionWarning.setVisibility(View.VISIBLE);
            mVersionWarning.setText(hasAnyCompatible()
                    ? getString(R.string.lib_version_mismatch, profileLabel())
                    : getString(R.string.lib_no_compatible_version, profileLabel()));
        } else if (!isDownloadable(index)) {
            mVersionWarning.setVisibility(View.VISIBLE);
            mVersionWarning.setText(R.string.lib_restricted);
        } else {
            mVersionWarning.setVisibility(View.GONE);
        }

        mChangeVersion.setEnabled(!mInstalling && versionCount() > 1);
        mUseRecommended.setVisibility(mRecommendedIndex >= 0 && index != mRecommendedIndex && !mInstalling
                ? View.VISIBLE : View.GONE);
    }

    /** Whether the selected version is the one already installed */
    private boolean selectionIsInstalled() {
        if (mInstalled == null) return false;
        if (mInstalledIndex >= 0) return mSelectedIndex == mInstalledIndex;
        // Installed, but its exact version isn't in the list: treat it as current unless
        // an update is known or the user picked something on purpose
        return !mUserPicked && !mInstalled.updateAvailable;
    }

    private void renderActions() {
        mProgress.setVisibility(mInstalling ? View.VISIBLE : View.GONE);
        mStatus.setTextColor(ThemeColors.get(mStatus.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
        if (mInstalling) {
            styleInstallButton(R.string.lib_installing, R.drawable.ic_lib_download);
            mPrimary.setEnabled(false);
            mUninstall.setVisibility(View.GONE);
            return;
        }
        boolean hasProfile = mInstance != null || isModpack();
        if (isRestrictedProject() || (mSelectedIndex >= 0 && !isDownloadable(mSelectedIndex))) {
            styleInstallButton(R.string.lib_open_curseforge, R.drawable.ic_lib_open);
            mPrimary.setEnabled(true);
            mUninstall.setVisibility(mInstalled != null && hasProfile ? View.VISIBLE : View.GONE);
            return;
        }
        if (mSelectedIndex < 0) {
            styleInstallButton(R.string.lib_install, R.drawable.ic_lib_download);
            mPrimary.setEnabled(false);
            mUninstall.setVisibility(View.GONE);
            return;
        }
        if (mInstalled == null) {
            styleInstallButton(R.string.lib_install, R.drawable.ic_lib_download);
            mUninstall.setVisibility(View.GONE);
        } else if (selectionIsInstalled()) {
            styleUninstallButton();
            mUninstall.setVisibility(View.GONE);
        } else {
            int label;
            if (mInstalledIndex >= 0) {
                label = isNewer(mSelectedIndex, mInstalledIndex) ? R.string.lib_update : R.string.lib_downgrade;
            } else {
                label = mInstalled.updateAvailable && !mUserPicked ? R.string.lib_update : R.string.lib_reinstall;
            }
            styleInstallButton(label, label == R.string.lib_update ? R.drawable.ic_lib_update : R.drawable.ic_lib_download);
            mUninstall.setVisibility(View.VISIBLE);
        }
        mPrimary.setEnabled(hasProfile);
        if (mInstalled != null && selectionIsInstalled()) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(R.string.lib_installed_hint);
        } else if (mInstalled != null) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(mInstalledIndex >= 0 && isNewer(mSelectedIndex, mInstalledIndex) || mInstalled.updateAvailable
                    ? R.string.lib_update_hint : R.string.lib_installed_hint);
        } else if (!hasProfile) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(R.string.lib_no_profile);
        } else {
            mStatus.setVisibility(View.GONE);
        }
    }

    private void styleInstallButton(int label, int icon) {
        Context context = mPrimary.getContext();
        int text = ContextCompat.getColor(context, R.color.lib_update_text);
        Object previous = mPrimary.getTag();
        if (previous != null && !previous.equals(label)) InstallMotion.buttonStateChanged(mPrimary);
        mPrimary.setText(label);
        mPrimary.setIconResource(icon);
        mPrimary.setTextColor(text);
        mPrimary.setIconTint(ColorStateList.valueOf(text));
        mPrimary.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.lib_update_bg));
        mPrimary.setTag(label);
    }

    private void styleUninstallButton() {
        Context context = mPrimary.getContext();
        int white = 0xFFFFFFFF;
        Object previous = mPrimary.getTag();
        if (previous != null && !previous.equals(R.string.lib_uninstall)) InstallMotion.buttonStateChanged(mPrimary);
        mPrimary.setText(R.string.lib_uninstall);
        mPrimary.setIconResource(R.drawable.ic_lib_trash);
        mPrimary.setTextColor(white);
        mPrimary.setIconTint(ColorStateList.valueOf(white));
        mPrimary.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.lib_danger));
        mPrimary.setTag(R.string.lib_uninstall);
    }

    // ─── Dependencies ────────────────────────────────────────────────────────

    private void renderDependencies() {
        for (DependencyRow row : mDependencyRows) {
            if (row.receiver != null) mIconCache.cancelImage(row.receiver);
        }
        mDependencyRows.clear();
        mDepsList.removeAllViews();
        final int generation = ++mDependencyGeneration;

        List<DependencyRow> rows = new ArrayList<>();
        if (mDetail != null && mSelectedIndex >= 0 && mDetail.dependencies != null
                && mSelectedIndex < mDetail.dependencies.length && mDetail.dependencies[mSelectedIndex] != null) {
            List<String> seen = new ArrayList<>();
            for (ModDetail.Dependencies dependency : mDetail.dependencies[mSelectedIndex]) {
                if (dependency == null || TextUtils.isEmpty(dependency.project_id)) continue;
                if (seen.contains(dependency.project_id) || dependency.project_id.equals(mItem.id)) continue;
                String kind = dependency.dependency_type == null ? "" : dependency.dependency_type.toLowerCase(Locale.ROOT);
                boolean required = kind.equals("required") || kind.equals("3");
                boolean optional = kind.equals("optional") || kind.equals("2");
                if (!required && !optional) continue;
                seen.add(dependency.project_id);
                rows.add(new DependencyRow(dependency.project_id, required));
            }
        }
        // Required ones first
        Collections.sort(rows, (a, b) -> Boolean.compare(b.required, a.required));

        if (rows.isEmpty()) {
            mDepsEmpty.setVisibility(View.VISIBLE);
            mDepsEmpty.setText(R.string.lib_no_dependencies);
            return;
        }
        mDepsEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (DependencyRow row : rows) {
            View view = inflater.inflate(R.layout.item_lib_dependency, mDepsList, false);
            row.icon = view.findViewById(R.id.dep_icon);
            row.name = view.findViewById(R.id.dep_name);
            row.status = view.findViewById(R.id.dep_status);
            row.check = view.findViewById(R.id.dep_check);
            TextView type = view.findViewById(R.id.dep_type);
            type.setText(row.required ? R.string.lib_dep_required : R.string.lib_dep_optional);
            row.icon.setImageResource(R.drawable.ic_lib_cube);
            row.check.setChecked(row.required);
            row.check.setEnabled(!mInstalling);
            view.setOnClickListener(v -> {
                if (row.check.getVisibility() == View.VISIBLE && row.check.isEnabled()) row.check.toggle();
            });
            styleBadge(row.status, BADGE_GREEN);
            int size = Math.round(14 * getResources().getDisplayMetrics().density);
            Drawable[] drawables = row.status.getCompoundDrawablesRelative();
            if (drawables[0] != null) {
                Drawable start = drawables[0].mutate();
                start.setBounds(0, 0, size, size);
                row.status.setCompoundDrawablesRelative(start, null, null, null);
            }
            TextViewCompat.setCompoundDrawableTintList(row.status, ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.lib_badge_green_text)));
            updateDependencyInstalled(row, null);
            String[] info = mDependencyInfo.get(cacheKey(row.projectId));
            if (info != null) applyDependencyInfo(row, info);
            mDepsList.addView(view);
            mDependencyRows.add(row);
        }

        // Fetch names and icons of the ones not resolved yet
        final int source = mItem.apiSource;
        final String key = mCurseforgeKey;
        for (DependencyRow row : rows) {
            if (mDependencyInfo.containsKey(cacheKey(row.projectId))) continue;
            sExecutor.execute(() -> {
                String[] info = null;
                try {
                    info = source == Constants.SOURCE_CURSEFORGE
                            ? ContentInstaller.fetchCurseforgeProject(row.projectId, key)
                            : ContentInstaller.fetchModrinthProject(row.projectId);
                } catch (Exception ignored) {}
                final String[] result = info;
                Tools.runOnUiThread(() -> {
                    if (result != null) mDependencyInfo.put(cacheKey(row.projectId), result);
                    if (generation != mDependencyGeneration || getView() == null) return;
                    if (result != null) applyDependencyInfo(row, result);
                    else row.name.setText(row.projectId);
                });
            });
        }
    }

    private String cacheKey(String projectId) {
        return mItem.apiSource + "_" + projectId;
    }

    /** info = {title, icon url, slug, project type} */
    private void applyDependencyInfo(DependencyRow row, String[] info) {
        row.name.setText(TextUtils.isEmpty(info[0]) ? row.projectId : info[0]);
        updateDependencyInstalled(row, info.length > 2 ? info[2] : null);
        String iconUrl = info.length > 1 ? info[1] : null;
        if (TextUtils.isEmpty(iconUrl)) return;
        float radius = 8 * getResources().getDisplayMetrics().density;
        ImageView icon = row.icon;
        row.receiver = new ImageReceiver() {
            @Override
            public void onImageAvailable(Bitmap image) {
                if (getView() == null || image == null || row.receiver != this) return;
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(getResources(), image);
                drawable.setCornerRadius(radius);
                icon.setImageDrawable(drawable);
            }
        };
        mIconCache.getImage(row.receiver, mItem.apiSource + "_" + row.projectId, iconUrl);
    }

    private void updateDependencyInstalled(DependencyRow row, @Nullable String slug) {
        if (mIndex == null) return;
        ModItem probe = new ModItem(mItem.apiSource, false, row.projectId, "", "", null);
        probe.slug = slug;
        boolean installed = mIndex.find(probe) != null;
        // Mods are matched against the mods folder only
        row.installed = installed;
        row.status.setVisibility(installed ? View.VISIBLE : View.GONE);
        row.check.setVisibility(installed ? View.GONE : View.VISIBLE);
    }

    private List<ContentInstaller.Dependency> selectedDependencies() {
        List<ContentInstaller.Dependency> list = new ArrayList<>();
        for (DependencyRow row : mDependencyRows) {
            if (row.installed || !row.check.isChecked()) continue;
            list.add(new ContentInstaller.Dependency(row.projectId, mItem.apiSource));
        }
        return list;
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private void onPrimaryClicked() {
        if (mInstalling) return;
        if (mLoadFailed || mDetail == null) {
            renderLoading();
            load();
            return;
        }
        Object tag = mPrimary.getTag();
        if (tag instanceof Integer && (Integer) tag == R.string.lib_uninstall) {
            confirmUninstall();
            return;
        }
        if (tag instanceof Integer && (Integer) tag == R.string.lib_open_curseforge) {
            openCurseforgePage();
            return;
        }
        if (mInstance == null && !isModpack()) {
            Toast.makeText(requireContext(), R.string.lib_no_profile, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(requireContext(), R.string.lib_tasks_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (mSelectedIndex < 0) return;
        if (isModpack()) startModpackInstall();
        else startInstall();
    }

    /**
     * Modpacks go through the profile installer: the pack is downloaded, a new profile is
     * created from it and the home screen follows the progress. This page steps aside.
     */
    private void startModpackInstall() {
        final ModDetail detail = mDetail;
        if (detail == null) return;
        Context appContext = requireContext().getApplicationContext();
        mApi.handleInstallation(appContext, detail, mSelectedIndex);
        Toast.makeText(appContext, appContext.getString(R.string.lib_modpack_install_started, mItem.title),
                Toast.LENGTH_SHORT).show();
        Tools.backToMainMenu(requireActivity());
    }

    private void openCurseforgePage() {
        String url = mItem.websiteUrl;
        if (TextUtils.isEmpty(url)) {
            String section;
            switch (mType) {
                case RESOURCE_PACK: section = "texture-packs"; break;
                case SHADER_PACK: section = "shaders"; break;
                case MODPACK: section = "modpacks"; break;
                case DATAPACK: section = "data-packs"; break;
                case WORLD: section = "worlds"; break;
                default: section = "mc-mods"; break;
            }
            url = "https://www.curseforge.com/minecraft/" + section + "/"
                    + (TextUtils.isEmpty(mItem.slug) ? mItem.id : mItem.slug);
        }
        Tools.openURL(requireActivity(), url);
    }

    private void startInstall() {
        final ModDetail detail = mDetail;
        final int index = mSelectedIndex;
        final boolean wasInstalled = mInstalled != null;
        final String oldPath = mInstalled != null ? mInstalled.file.getAbsolutePath() : null;
        final String title = mItem.title;
        final Context appContext = requireContext().getApplicationContext();
        List<ContentInstaller.Dependency> dependencies = selectedDependencies();

        mInstalling = true;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setTextColor(ThemeColors.get(mStatus.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
        mStatus.setText(R.string.lib_installing);
        setDependencyChecksEnabled(false);
        renderVersion();
        renderActions();

        ContentInstaller.install(detail, index, mType, dependencies, oldPath, mProfileVersion, typeLoader(),
                mCurseforgeKey, (file, error) -> {
                    mInstalling = false;
                    if (error == null && file != null) {
                        mInstalled = new InstalledContentIndex.Entry(file,
                                detail.versionIds != null && index < detail.versionIds.length ? detail.versionIds[index] : null);
                        mInstalledIndex = index;
                        mUserPicked = false;
                        Toast.makeText(appContext, mType == ContentType.WORLD
                                ? appContext.getString(R.string.lib_world_installed, file.getName())
                                : appContext.getString(wasInstalled ? R.string.lib_update_done : R.string.lib_install_done, title),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        String message = error == null ? "" : String.valueOf(error.getMessage());
                        Toast.makeText(appContext, appContext.getString(R.string.lib_install_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                    if (getView() == null) return;
                    setDependencyChecksEnabled(true);
                    renderVersion();
                    renderActions();
                    if (error != null) {
                        mStatus.setVisibility(View.VISIBLE);
                        mStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lib_danger));
                        mStatus.setText(getString(R.string.lib_install_failed, String.valueOf(error.getMessage())));
                    } else {
                        refreshDependencyState();
                    }
                });
    }

    private void setDependencyChecksEnabled(boolean enabled) {
        for (DependencyRow row : mDependencyRows) {
            if (row.check != null) row.check.setEnabled(enabled);
        }
    }

    /** Rescans the content folder so freshly installed dependencies show as installed */
    private void refreshDependencyState() {
        final ContentType type = mType;
        final String version = mProfileVersion;
        final String loader = mProfileLoader;
        final String key = mCurseforgeKey;
        sExecutor.execute(() -> {
            InstalledContentIndex index;
            try {
                index = InstalledContentIndex.build(type, version, loader, key);
            } catch (Exception e) {
                return;
            }
            Tools.runOnUiThread(() -> {
                mIndex = index;
                if (getView() == null) return;
                for (DependencyRow row : mDependencyRows) {
                    String[] info = mDependencyInfo.get(cacheKey(row.projectId));
                    updateDependencyInstalled(row, info != null && info.length > 2 ? info[2] : null);
                }
            });
        });
    }

    private void confirmUninstall() {
        if (mInstalled == null) return;
        final File file = mInstalled.file;
        String section = mLibraryTitle;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.lib_uninstall_title, mItem.title))
                .setMessage(getString(R.string.lib_uninstall_message, file.getName(), section))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lib_uninstall, (dialog, which) -> {
                    if (ContentInstaller.uninstall(file)) {
                        if (mIndex != null) mIndex.remove(file);
                        mInstalled = null;
                        mInstalledIndex = -1;
                        mUserPicked = false;
                        if (mDetail != null && mRecommendedIndex >= 0) mSelectedIndex = mRecommendedIndex;
                        Toast.makeText(requireContext(), getString(R.string.lib_uninstall_done, mItem.title),
                                Toast.LENGTH_SHORT).show();
                        mStatus.setTextColor(ThemeColors.get(mStatus.getContext(),
                                com.google.android.material.R.attr.colorOnSurfaceVariant));
                        if (mDetail != null) {
                            renderVersion();
                            renderDependencies();
                            renderActions();
                        } else {
                            renderLoadFailed();
                        }
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.lib_uninstall_failed, file.getName()),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // ─── Version picker ──────────────────────────────────────────────────────

    private void showVersionPicker() {
        if (mDetail == null || versionCount() == 0 || getView() == null) return;
        com.kdt.mcgui.VersionPickerDialog.Source source = new com.kdt.mcgui.VersionPickerDialog.Source() {
            @NonNull @Override public List<Integer> orderedIndices() { return ModInstallFragment.this.orderedIndices(); }
            @NonNull @Override public String title(int index) { return versionTitle(index); }
            @NonNull @Override public String meta(int index) { return versionMeta(index); }
            @NonNull @Override public String extra(int index) { return versionExtra(index); }
            @NonNull @Override public String[] gameVersions(int index) { return gameVersionsOf(index); }
            @Nullable @Override public String channel(int index) {
                return mDetail != null && mDetail.versionTypes != null && index < mDetail.versionTypes.length ? mDetail.versionTypes[index] : null;
            }
            @Override public boolean compatible(int index) { return isCompatible(index); }
            @Override public boolean downloadable(int index) { return isDownloadable(index); }
            @Override public int mark(int index) {
                if (index == mInstalledIndex) return com.kdt.mcgui.VersionPickerDialog.MARK_INSTALLED;
                if (index == mRecommendedIndex) return com.kdt.mcgui.VersionPickerDialog.MARK_RECOMMENDED;
                return com.kdt.mcgui.VersionPickerDialog.MARK_NONE;
            }
        };
        ImageView icon = getView().findViewById(R.id.install_icon);
        android.graphics.drawable.Drawable iconDrawable = icon != null ? icon.getDrawable() : null;
        boolean hasProfile = mProfileVersion != null && mType != ContentType.WORLD && !isModpack();
        new com.kdt.mcgui.VersionPickerDialog(requireContext(), source, mItem.title == null ? "" : mItem.title,
                profileLabel(), iconDrawable, mSelectedIndex, hasProfile, hasAnyCompatible(), index -> {
            if (getView() == null || index == mSelectedIndex) return;
            mSelectedIndex = index;
            mUserPicked = index != mRecommendedIndex;
            renderVersion();
            renderDependencies();
            renderActions();
            InstallMotion.versionChanged(mVersionBox);
            InstallMotion.dependenciesIn(mDepsList);
        }).show();
    }
}
