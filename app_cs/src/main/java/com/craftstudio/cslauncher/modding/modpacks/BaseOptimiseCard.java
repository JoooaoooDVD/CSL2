package com.craftstudio.cslauncher.modding.modpacks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.AnimatedIcons;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.AnimatedImageReceiver;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.LibraryMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.Collections;
import java.util.Locale;

/**
 * "BASE OPTIMISE MOD" card pinned above the Mods grid: Thorium Optimization from CurseForge.
 *
 * <p>Owns the featured view inflated in {@code fragment_mod_library.xml}, loads the project
 * and its file list once (cached across screen re-creations), and installs the newest file
 * that matches the selected profile's Minecraft version and loader through the same
 * {@link ContentInstaller} path the install screen uses - so the download lands in the
 * profile's mods folder, is validated, replaces an older copy instead of duplicating it and
 * shows up as "Installed" everywhere in the library.
 */
public final class BaseOptimiseCard {
    /** CurseForge project id of https://www.curseforge.com/minecraft/mc-mods/thorium-optimization */
    public static final String THORIUM_ID = "1753836";
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    public interface Host {
        /** Open the full install screen for the project */
        void onOpenDetails(@NonNull ModItem item);
        /** A file was installed / replaced: refresh the installed index */
        void onInstalledChanged();
    }

    private enum State { LOADING, FAILED, READY }

    // Shared across instances so switching tabs / rotating does not refetch
    private static ModItem sItem;
    private static ModDetail sDetail;
    private static long sFetchedAt;
    private static boolean sLoading;

    private final View mRoot;
    private final ImageView mIcon;
    private final TextView mTitle, mDescription, mTagLoader, mTagVersion, mTagState, mStatus;
    private final MaterialButton mAction;
    private final ModIconCache mIconCache;
    private final String mCurseforgeKey;
    private final Host mHost;

    @Nullable private String mProfileVersion;
    @NonNull private String mProfileLoader = "";
    @Nullable private InstalledContentIndex mIndex;
    @Nullable private InstalledContentIndex.Entry mInstalled;
    private State mState = State.LOADING;
    private boolean mInstalling;
    private boolean mEnabled = true;
    /** Collapsed while the grid is scrolled away from the top */
    private boolean mCollapsed = false;
    private int mExpandedHeight = 0;
    @Nullable private android.animation.ValueAnimator mCollapseAnimator;
    private AnimatedImageReceiver mReceiver;

    public BaseOptimiseCard(@NonNull View root, @NonNull ModIconCache iconCache, @NonNull String curseforgeKey, @NonNull Host host) {
        mRoot = root;
        mIconCache = iconCache;
        mCurseforgeKey = curseforgeKey;
        mHost = host;
        mIcon = root.findViewById(R.id.featured_icon);
        mTitle = root.findViewById(R.id.featured_title);
        mDescription = root.findViewById(R.id.featured_description);
        mTagLoader = root.findViewById(R.id.featured_tag_loader);
        mTagVersion = root.findViewById(R.id.featured_tag_version);
        mTagState = root.findViewById(R.id.featured_tag_state);
        mStatus = root.findViewById(R.id.featured_status);
        mAction = root.findViewById(R.id.featured_action);
        CSAnimationEngine.attachPressFeedback(mAction);
        mRoot.setOnClickListener(v -> {
            if (sItem != null) mHost.onOpenDetails(sItem);
            else if (mState == State.FAILED) load(true);
        });
        mAction.setOnClickListener(v -> onAction());
        render();
        load(false);
    }

    // ───────────────────────────── inputs ─────────────────────────────

    public void setProfile(@Nullable String mcVersion, @Nullable String loader) {
        mProfileVersion = mcVersion;
        mProfileLoader = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        resolveInstalled();
        render();
    }

    public void setInstalledIndex(@Nullable InstalledContentIndex index) {
        mIndex = index;
        resolveInstalled();
        render();
    }

    /** Show the card only on the Mods tab */
    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (enabled) {
            mCollapsed = false;
            resetHeight();
            if (mRoot.getVisibility() != View.VISIBLE) {
                mRoot.setVisibility(View.VISIBLE);
                Motion.riseIn(mRoot, 0L, 10f);
            }
        } else {
            cancelCollapse();
            mRoot.setVisibility(View.GONE);
        }
    }

    /**
     * Auto-hide while the user scrolls down the grid, come back when the grid is at the top.
     * Height is animated so the grid slides up smoothly instead of jumping.
     */
    public void setCollapsed(boolean collapsed) {
        if (!mEnabled || mCollapsed == collapsed || mRoot.getVisibility() != View.VISIBLE) return;
        mCollapsed = collapsed;
        cancelCollapse();
        int current = mRoot.getHeight();
        if (collapsed && current > 0) mExpandedHeight = current;
        if (mExpandedHeight <= 0) {
            mRoot.measure(View.MeasureSpec.makeMeasureSpec(((View) mRoot.getParent()).getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            mExpandedHeight = mRoot.getMeasuredHeight();
        }
        int target = collapsed ? 0 : mExpandedHeight;
        if (!Motion.content(mRoot) || mExpandedHeight <= 0) {
            applyHeight(target);
            mRoot.setAlpha(collapsed ? 0f : 1f);
            if (!collapsed) resetHeight();
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(current, target);
        animator.setDuration(collapsed ? Motion.SHORT : Motion.MEDIUM);
        animator.setInterpolator(collapsed ? Motion.EXIT : Motion.ENTER);
        animator.addUpdateListener(a -> {
            int h = (Integer) a.getAnimatedValue();
            applyHeight(h);
            mRoot.setAlpha(mExpandedHeight == 0 ? 1f : Math.min(1f, h / (float) mExpandedHeight));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                mCollapseAnimator = null;
                if (!mCollapsed) resetHeight();
            }
        });
        mCollapseAnimator = animator;
        animator.start();
    }

    private void applyHeight(int height) {
        android.view.ViewGroup.LayoutParams lp = mRoot.getLayoutParams();
        if (lp == null) return;
        lp.height = height;
        mRoot.setLayoutParams(lp);
    }

    /** Back to wrap_content so content changes (status line, tags) still resize the card */
    private void resetHeight() {
        android.view.ViewGroup.LayoutParams lp = mRoot.getLayoutParams();
        if (lp != null && lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            mRoot.setLayoutParams(lp);
        }
        mRoot.setAlpha(1f);
    }

    private void cancelCollapse() {
        if (mCollapseAnimator != null) {
            mCollapseAnimator.cancel();
            mCollapseAnimator = null;
        }
    }

    public void destroy() {
        cancelCollapse();
        if (mReceiver != null) {
            mIconCache.cancelImage(mReceiver);
            mReceiver = null;
        }
    }

    private void resolveInstalled() {
        mInstalled = mIndex != null && sItem != null ? mIndex.find(sItem) : null;
    }

    // ───────────────────────────── loading ─────────────────────────────

    private void load(boolean force) {
        if (!force && sItem != null && sDetail != null && SystemClock.elapsedRealtime() - sFetchedAt < CACHE_TTL_MS) {
            mState = State.READY;
            resolveInstalled();
            bindProject();
            render();
            return;
        }
        if (sLoading) {
            // Another card instance is already fetching; poll for the result
            mState = State.LOADING;
            render();
            mRoot.postDelayed(() -> { if (mRoot.isAttachedToWindow()) load(false); }, 600);
            return;
        }
        sLoading = true;
        mState = State.LOADING;
        render();
        final String key = mCurseforgeKey;
        CSLauncherApplication.sExecutorService.execute(() -> {
            ModItem item = null;
            ModDetail detail = null;
            try {
                CurseforgeApi api = new CurseforgeApi(key);
                item = api.getMod(THORIUM_ID);
                if (item != null && !item.isRestricted) detail = api.getModDetails(item);
            } catch (Exception ignored) {
            }
            final ModItem loadedItem = item;
            final ModDetail loadedDetail = detail;
            Tools.runOnUiThread(() -> {
                sLoading = false;
                if (loadedItem != null && loadedDetail != null && loadedDetail.versionUrls != null && loadedDetail.versionUrls.length > 0) {
                    sItem = loadedItem;
                    sDetail = loadedDetail;
                    sFetchedAt = SystemClock.elapsedRealtime();
                    mState = State.READY;
                } else {
                    mState = sItem != null && sDetail != null ? State.READY : State.FAILED;
                }
                if (!mRoot.isAttachedToWindow()) return;
                resolveInstalled();
                bindProject();
                render();
            });
        });
    }

    private void bindProject() {
        ModItem item = sItem;
        if (item == null) return;
        if (item.title != null && !item.title.isEmpty()) mTitle.setText(item.title);
        if (item.description != null && !item.description.isEmpty()) mDescription.setText(item.description);
        if (mReceiver != null || item.imageUrl == null || item.imageUrl.isEmpty()) return;
        final float radius = mIcon.getResources().getDisplayMetrics().density * 10f;
        final long boundAt = SystemClock.uptimeMillis();
        mReceiver = new AnimatedImageReceiver() {
            @Override
            public void onImageAvailable(Bitmap image) {
                if (mReceiver != this || image == null) return;
                RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(mIcon.getResources(), image);
                d.setCornerRadius(radius);
                d.setFilterBitmap(image.getWidth() >= 64);
                mIcon.setPadding(0, 0, 0, 0);
                mIcon.setImageDrawable(d);
                if (SystemClock.uptimeMillis() - boundAt > 120) LibraryMotion.iconLoaded(mIcon);
            }

            @Override
            public void onAnimatedImageAvailable(@NonNull Drawable drawable) {
                if (mReceiver != this) return;
                mIcon.setPadding(0, 0, 0, 0);
                AnimatedIcons.show(mIcon, drawable, radius);
                if (SystemClock.uptimeMillis() - boundAt > 120) LibraryMotion.iconLoaded(mIcon);
            }
        };
        mIconCache.getImage(mReceiver, item.getIconCacheTag(), item.imageUrl);
    }

    // ───────────────────────────── state ─────────────────────────────

    /** Index of the file to install for the current profile, or -1 */
    private int pickIndex() {
        if (sDetail == null || mProfileVersion == null) return -1;
        return CompatibleVersionPicker.pick(sDetail, mProfileVersion, mProfileLoader, ContentType.MOD);
    }

    private boolean profileHasFabric() {
        return "fabric".equals(mProfileLoader) || "quilt".equals(mProfileLoader);
    }

    private boolean installedIsCurrent(int index) {
        if (mInstalled == null) return false;
        if (index < 0 || sDetail == null || sDetail.versionIds == null || index >= sDetail.versionIds.length) return true;
        String id = sDetail.versionIds[index];
        if (mInstalled.versionId == null || id == null) {
            // Unknown file id (manually copied jar): compare file names instead
            String name = sDetail.versionFileNames != null && index < sDetail.versionFileNames.length ? sDetail.versionFileNames[index] : null;
            return name == null || name.equals(mInstalled.file.getName());
        }
        return id.equals(mInstalled.versionId);
    }

    private void render() {
        Context context = mRoot.getContext();
        mTagState.setVisibility(mInstalled != null ? View.VISIBLE : View.GONE);
        mStatus.setVisibility(View.GONE);
        mAction.setIconResource(R.drawable.ic_lib_download);
        if (mProfileVersion != null && !mProfileVersion.isEmpty()) {
            mTagVersion.setVisibility(View.VISIBLE);
            mTagVersion.setText(mProfileVersion);
        } else {
            mTagVersion.setVisibility(View.GONE);
        }

        if (mInstalling) {
            mAction.setEnabled(false);
            mAction.setText(R.string.lib_base_opt_installing);
            return;
        }
        switch (mState) {
            case LOADING:
                mAction.setEnabled(false);
                mAction.setText(R.string.lib_base_opt_loading);
                return;
            case FAILED:
                mAction.setEnabled(true);
                mAction.setIconResource(R.drawable.ic_lib_update);
                mAction.setText(R.string.lib_retry);
                mStatus.setVisibility(View.VISIBLE);
                mStatus.setText(R.string.lib_base_opt_offline);
                return;
            default:
                break;
        }
        int index = pickIndex();
        if (mProfileVersion == null || mProfileVersion.isEmpty() || !profileHasFabric()) {
            mAction.setEnabled(false);
            mAction.setText(R.string.lib_base_opt_install);
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(R.string.lib_base_opt_no_loader);
            return;
        }
        if (index < 0) {
            mAction.setEnabled(false);
            mAction.setText(R.string.lib_base_opt_install);
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(context.getString(R.string.lib_base_opt_incompatible, prettyLoader(), mProfileVersion));
            return;
        }
        if (mInstalled != null && installedIsCurrent(index)) {
            mAction.setEnabled(false);
            mAction.setIconResource(R.drawable.ic_lib_check);
            mAction.setText(R.string.lib_base_opt_installed);
            return;
        }
        mAction.setEnabled(true);
        if (mInstalled != null) {
            mAction.setIconResource(R.drawable.ic_lib_update);
            mAction.setText(R.string.lib_base_opt_update);
        } else {
            mAction.setText(R.string.lib_base_opt_install);
        }
        String fileName = sDetail != null && sDetail.versionFileNames != null && index < sDetail.versionFileNames.length
                ? sDetail.versionFileNames[index] : null;
        if (fileName != null) {
            mStatus.setVisibility(View.VISIBLE);
            mStatus.setText(context.getString(R.string.lib_base_opt_for, prettyLoader(), fileName));
        }
    }

    private String prettyLoader() {
        if (mProfileLoader.isEmpty()) return "";
        return Character.toUpperCase(mProfileLoader.charAt(0)) + mProfileLoader.substring(1);
    }

    // ───────────────────────────── install ─────────────────────────────

    private void onAction() {
        if (mState == State.FAILED) {
            load(true);
            return;
        }
        if (mInstalling || mState != State.READY || sDetail == null || sItem == null) return;
        final int index = pickIndex();
        if (index < 0) return;
        Context context = mRoot.getContext();
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(context, R.string.lib_base_opt_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        final ModDetail detail = sDetail;
        final String title = sItem.title;
        final boolean wasInstalled = mInstalled != null;
        final String oldPath = mInstalled != null ? mInstalled.file.getAbsolutePath() : null;
        final Context appContext = context.getApplicationContext();
        mInstalling = true;
        render();
        LibraryMotion.actionMorph(mAction);
        ContentInstaller.install(detail, index, ContentType.MOD, Collections.<ContentInstaller.Dependency>emptyList(),
                oldPath, mProfileVersion, mProfileLoader, mCurseforgeKey, (file, error) -> {
                    mInstalling = false;
                    if (error == null && file != null) {
                        mInstalled = new InstalledContentIndex.Entry(file,
                                detail.versionIds != null && index < detail.versionIds.length ? detail.versionIds[index] : null);
                        Toast.makeText(appContext, appContext.getString(wasInstalled ? R.string.lib_update_done : R.string.lib_install_done, title),
                                Toast.LENGTH_SHORT).show();
                        mHost.onInstalledChanged();
                    } else {
                        String message = error == null ? "" : String.valueOf(error.getMessage());
                        Toast.makeText(appContext, appContext.getString(R.string.lib_install_failed, message), Toast.LENGTH_LONG).show();
                    }
                    if (mRoot.isAttachedToWindow()) render();
                });
    }

    /** True when the given file is the Thorium jar this card manages (used by callers that list mods) */
    public static boolean isThoriumJar(@Nullable File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).startsWith("thorium-");
    }
}
