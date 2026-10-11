package com.craftstudio.cslauncher.fragments;

import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.CenterCropVideoView;
import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import com.craftstudio.cslauncher.CustomControlsActivity;
import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.extra.ExtraListener;
import com.craftstudio.cslauncher.game.renderer.def.Renderers;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.OutlineArcProgressView;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.RamInputDialog;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceJavaFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceVideoFragment;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.LauncherBackground;
import com.craftstudio.cslauncher.utils.motion.HomeMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.File;
import java.util.Locale;

import static com.craftstudio.cslauncher.Architecture.is32BitsDevice;
import static com.craftstudio.cslauncher.Tools.getTotalDeviceMemory;

/**
 * The home content of the right pane: banner, selected profile, EDIT, PLAY and the two
 * feature cards. Every control is wired to the existing launcher systems, this
 * fragment owns no state of its own.
 */
public class RightPaneHomeFragment extends Fragment implements ExtraListener<Void> {
    public static final String TAG = "RightPaneHomeFragment";

    private ImageView mWallpaper;
    private View mPlayIcon;
    private TextView mPlayTitle;
    private TextView mPlaySubtitle;
    private OutlineArcProgressView mPlayArc;
    private boolean mDownloading;
    private int mAggregateProgress = -1;
    /** One listener covers every progress record + the initial state on registration */
    private final ProgressKeeper.GlobalProgressListener mGlobalProgressListener =
            (taskCount, aggregatePercent) -> Tools.runOnUiThread(
                    () -> onDownloadStateChanged(taskCount, aggregatePercent));
    private CenterCropVideoView mVideo;
    private mcVersionSpinner mVersionSpinner;
    /** Selected profile card: mirrors the (hidden) spinner + the "version • loader" line */
    private View mProfileTile;
    private ImageView mProfileIcon;
    private TextView mProfileName, mProfileDetail;
    /* Incremented to drop the result of a decode that is not needed anymore */
    private int mLoadId;
    /** Key of the artwork currently shown on the banner (path + mtime), null before the first load */
    private String mBackgroundKey;
    /** The full entrance plays once per fragment instance, returns get a light settle */
    private boolean mEntrancePlayed;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mWallpaper = view.findViewById(R.id.right_pane_wallpaper);
        mVideo = view.findViewById(R.id.right_pane_wallpaper_video);
        mVideo.setErrorListener(this::clearBackground);
        loadBackground();

        // android:clipToOutline in XML only works from API 31: round the banner art and the
        // PLAY texture in code so every Android version gets the rounded corners
        view.findViewById(R.id.home_banner_frame).setClipToOutline(true);
        view.findViewById(R.id.home_play_face).setClipToOutline(true);
        view.findViewById(R.id.home_card_browser).setClipToOutline(true);
        view.findViewById(R.id.home_card_manager).setClipToOutline(true);

        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        bindProfileCard(view);
        // The Change button opens the editor of the currently selected profile
        view.findViewById(R.id.edit_profile_button).setOnClickListener(v -> {
            mVersionSpinner.openProfileEditor(requireActivity());
        });

        // PLAY: hands the request to the existing launch pipeline of LauncherActivity,
        // which already refuses to double-launch while a task is running
        view.findViewById(R.id.play_button).setOnClickListener(v -> {
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });
        mPlayIcon = view.findViewById(R.id.home_play_icon);
        mPlayTitle = view.findViewById(R.id.home_play_title);
        mPlaySubtitle = view.findViewById(R.id.home_play_subtitle);
        mPlayArc = view.findViewById(R.id.home_play_arc);

        view.findViewById(R.id.home_card_browser).setOnClickListener(v -> {
            HomeMotion.cardTapped(v, view.findViewById(R.id.home_card_browser_chevron), () -> openContentPicker(false));
        });
        view.findViewById(R.id.home_card_manager).setOnClickListener(v -> {
            HomeMotion.cardTapped(v, view.findViewById(R.id.home_card_manager_chevron), () -> openContentPicker(true));
        });
        // Version chip on the banner + chevron of PLAY: open the profile list
        View.OnClickListener openProfiles = v -> {
            if (mVersionSpinner != null) mVersionSpinner.showProfilePicker();
        };
        view.findViewById(R.id.home_banner_version_chip).setOnClickListener(openProfiles);
        view.findViewById(R.id.home_play_more).setOnClickListener(openProfiles);

        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.home_card_browser));
        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.home_card_manager));
        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.edit_profile_button));
        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.home_banner_version_chip));
        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.play_button));

        refreshVersionInfo();

        if (!mEntrancePlayed) {
            mEntrancePlayed = true;
            HomeMotion.playEntrance(
                    view.findViewById(R.id.home_banner_frame), mWallpaper,
                    view.findViewById(R.id.home_banner_wordmark),
                    view.findViewById(R.id.home_banner_version_chip),
                    view.findViewById(R.id.home_banner_features),
                    view.findViewById(R.id.home_profile_card),
                    view.findViewById(R.id.home_edit_card),
                    view.findViewById(R.id.home_play_container),
                    view.findViewById(R.id.home_play_glint),
                    new View[]{view.findViewById(R.id.home_card_browser), view.findViewById(R.id.home_card_manager)},
                    new View[]{view.findViewById(R.id.home_card_browser_tile), view.findViewById(R.id.home_card_manager_tile)},
                    new View[]{view.findViewById(R.id.home_card_browser_art), view.findViewById(R.id.home_card_manager_art)});
            HomeMotion.profileDetails(mProfileTile, view.findViewById(R.id.home_profile_chevron),
                    view.findViewById(R.id.home_profile_dot));
        } else {
            // Coming back (closing a screen of the pane): a quick settle instead of the full show
            HomeMotion.playReturn(view.findViewById(R.id.home_root), view.findViewById(R.id.home_play_glint));
        }
    }

    /**
     * Selected profile card: the whole card opens the profile picker. The spinner keeps owning
     * the profile list (selection, picker, editor); its icon + name are mirrored here.
     */
    private void bindProfileCard(@NonNull View view) {
        View card = view.findViewById(R.id.home_profile_card);
        View chevron = view.findViewById(R.id.home_profile_chevron);
        mProfileTile = view.findViewById(R.id.home_profile_tile);
        mProfileIcon = view.findViewById(R.id.home_profile_icon);
        mProfileName = view.findViewById(R.id.home_profile_name);
        mProfileDetail = view.findViewById(R.id.home_profile_detail);
        card.setClipToOutline(true);
        mProfileTile.setClipToOutline(true);
        card.setOnClickListener(v -> HomeMotion.profileTapped(chevron, () -> {
            if (mVersionSpinner != null && isAdded()) mVersionSpinner.showProfilePicker();
        }));
        CSAnimationEngine.attachPressFeedback(card);
        if (mVersionSpinner == null) return;
        // Every selection change (also a reload after the app comes back) re-mirrors the card
        mVersionSpinner.setSelectionListener(() -> mirrorSpinner(true));
        mirrorSpinner(false);
    }

    private void mirrorSpinner(boolean animate) {
        if (mVersionSpinner == null || mProfileName == null || mProfileIcon == null) return;
        CharSequence name = mVersionSpinner.getText();
        boolean changed = !TextUtils.equals(mProfileName.getText(), name);
        if (animate && changed && mProfileName.length() > 0) {
            Motion.swapText(mProfileName, name);
            HomeMotion.profileChanged(mProfileTile);
        } else {
            mProfileName.setText(name);
        }
        // Icon straight from the selected entry (cached by InstanceIconProvider). The spinner's
        // compound drawable is only a fallback: the hidden spinner may not have applied it yet,
        // which left the card without an icon after the app was reopened.
        Drawable icon = null;
        Object item = mVersionSpinner.getSelectedItem();
        try {
            if (item instanceof com.craftstudio.cslauncher.instances.DisplayInstance) {
                icon = com.craftstudio.cslauncher.instances.InstanceIconProvider.fetchIcon(getResources(),
                        (com.craftstudio.cslauncher.instances.DisplayInstance) item);
            } else if (item instanceof com.craftstudio.cslauncher.instances.InstanceAdapterExtra) {
                icon = ((com.craftstudio.cslauncher.instances.InstanceAdapterExtra) item).icon;
            }
        } catch (RuntimeException ignored) {
            // fall back to the spinner drawable below
        }
        if (icon == null) icon = mVersionSpinner.getCompoundDrawablesRelative()[0];
        if (icon == null) icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_cs_full);
        Drawable copy = copyIcon(icon);
        boolean hadIcon = mProfileIcon.getDrawable() != null;
        mProfileIcon.setImageDrawable(copy);
        if (!hadIcon && copy != null && animate) Motion.popIn(mProfileIcon, 0, 0.7f);
    }

    @Override
    public void onStart() {
        super.onStart();
        // Coming back to the launcher: the card is mirrored again right away
        mirrorSpinner(false);
    }

    /** Own copy of the (cached, shared) profile icon; small pixel-art bitmaps stay crisp */
    @Nullable
    private Drawable copyIcon(@Nullable Drawable icon) {
        if (icon == null) return null;
        Drawable.ConstantState state = icon.getConstantState();
        Drawable copy = state != null ? state.newDrawable(getResources()).mutate() : icon;
        if (copy instanceof BitmapDrawable) {
            BitmapDrawable bitmap = (BitmapDrawable) copy;
            if (bitmap.getBitmap() != null && bitmap.getBitmap().getWidth() <= 64) bitmap.setFilterBitmap(false);
        }
        return copy;
    }

    /** Delegates to the parent menu so the picker opens in the panes on tablet-width screens */
    private void openContentPicker(boolean manage) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).showContentPicker(manage);
        }
    }

    /** Opens a screen in the right pane through the parent, falling back to full screen */
    private void openPane(Class<? extends Fragment> fragmentClass, String tag, @Nullable Bundle args) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openPane(fragmentClass, tag, args);
        } else {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    // ─── Selected version ────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.addExtraListener(ExtraConstants.REFRESH_VERSION_SPINNER, this);
        // The PLAY button carries the download state here, so the floating panel steps aside
        ProgressKeeper.addGlobalListener(mGlobalProgressListener);
        ProgressLayout progressLayout = requireActivity().findViewById(R.id.progress_layout);
        if (progressLayout != null) progressLayout.setHiddenByHost(true);
        if (mVideo != null) mVideo.resumePlayback();
        setImageAnimation(true);
        refreshVersionInfo();
    }

    @Override
    public void onPause() {
        super.onPause();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_VERSION_SPINNER, this);
        ProgressKeeper.removeGlobalListener(mGlobalProgressListener);
        ProgressLayout progressLayout = requireActivity().findViewById(R.id.progress_layout);
        if (progressLayout != null) progressLayout.setHiddenByHost(false);
        if (mVideo != null) mVideo.pausePlayback();
        setImageAnimation(false);
    }

    // ─── Download state on the PLAY button ──────────────────────────────────

    /** Callback from ProgressKeeper (any thread) - hops to the UI thread above */
    private void onDownloadStateChanged(int taskCount, int aggregatePercent) {
        View root = getView();
        if (root == null) return;
        mAggregateProgress = aggregatePercent;
        boolean downloading = taskCount > 0;
        if (downloading != mDownloading) {
            mDownloading = downloading;
            applyPlayDownloadState();
        } else if (downloading) {
            updateDownloadSubtitle();
        }
    }

    /** Toggles the PLAY button between its normal and downloading presentation */
    private void applyPlayDownloadState() {
        View root = getView();
        if (root == null || mPlayTitle == null || mPlayArc == null) return;
        if (mDownloading) {
            if (mPlayIcon != null) mPlayIcon.setVisibility(View.GONE);
            setPlayDividerVisible(root, false);
            CSAnimationEngine.animateValueRefresh(mPlayTitle, getString(R.string.home_downloading));
            updateDownloadSubtitle();
            mPlayArc.setProgress(mAggregateProgress);
            mPlayArc.setDownloading(true);
        } else {
            mPlayArc.setDownloading(false);
            mPlayArc.setProgress(-1);
            if (mPlayIcon != null) mPlayIcon.setVisibility(View.VISIBLE);
            setPlayDividerVisible(root, true);
            CSAnimationEngine.animateValueRefresh(mPlayTitle, getString(R.string.home_play));
            // Restore the version/loader subtitle
            refreshVersionInfo();
        }
    }

    private static void setPlayDividerVisible(@NonNull View root, boolean visible) {
        View divider = root.findViewById(R.id.home_play_divider);
        if (divider != null) divider.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateDownloadSubtitle() {
        if (mPlaySubtitle == null || !mDownloading) return;
        String text = mAggregateProgress >= 0
                ? mAggregateProgress + "%"
                : getString(R.string.home_downloading_wait);
        CSAnimationEngine.animateValueRefresh(mPlaySubtitle, text);
        if (mPlayArc != null) mPlayArc.setProgress(mAggregateProgress);
    }

    /** The spinner broadcasts a refresh through ExtraCore whenever the selection changed */
    @Override
    public boolean onValueSet(String key, @NonNull Void value) {
        View root = getView();
        if (root != null) {
            // Consume so late-attached screens don't replay it, then update from the real state
            ExtraCore.consumeValue(key);
            root.post(this::refreshVersionInfo);
        }
        return false;
    }

    /** Reloads the spinner list AND updates the subtitle lines below it */
    public void refreshVersionInfo() {
        if (mVersionSpinner != null) mVersionSpinner.reloadProfiles();
        View root = getView();
        if (root == null) return;

        String mcVersion = null;
        String loader = null;
        Instance instance = Instances.loadSelectedInstance();
        if (instance != null) {
            InstanceVersionResolver.Info info =
                    InstanceVersionResolver.resolve(Instances.getSelectedInstanceKey());
            mcVersion = info.mcVersion != null ? info.mcVersion : instance.versionId;
            loader = info.loader;
        }

        String loaderLabel = loader == null || loader.isEmpty() ? "Vanilla" : capitalize(loader);
        String detail;
        if (mcVersion != null && !mcVersion.isEmpty()) {
            detail = getString(R.string.home_version_subtitle_fmt, mcVersion, loaderLabel);
        } else {
            detail = loaderLabel;
        }

        if (mDownloading) {
            // While downloading the subtitle carries the percent instead of the version line
            updateDownloadSubtitle();
        } else if (mPlaySubtitle != null) {
            mPlaySubtitle.setText(detail);
        } else {
            ((TextView) root.findViewById(R.id.home_play_subtitle)).setText(detail);
        }

        // Selected profile card: "26.3 • Fabric" under the profile name
        if (mProfileDetail != null) {
            CharSequence line = instance != null ? detail : "";
            if (mProfileDetail.length() > 0) Motion.swapText(mProfileDetail, line);
            else mProfileDetail.setText(line);
        }

        // The banner pill carries the same info so it is readable at a glance from the home screen
        TextView bannerVersion = root.findViewById(R.id.home_banner_version);
        View versionChip = root.findViewById(R.id.home_banner_version_chip);
        if (bannerVersion != null && versionChip != null) {
            boolean hasInstance = instance != null;
            versionChip.setVisibility(hasInstance ? View.VISIBLE : View.GONE);
            if (hasInstance) bannerVersion.setText(detail);
        }

        // The banner artwork follows the selected profile (and profile-editor saves) immediately;
        // loadBackground() is a no-op while the same artwork is already shown
        loadBackground();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Human-readable names for the stored renderer key, mirrors the video settings list */
    static String rendererLabel(String key) {
        if (key == null || key.isEmpty()) return "OpenGL ES 2";
        switch (key) {
            case Renderers.LTW_RENDERER: return "LTW GL Wrapper";
            case Renderers.GL4ES_RENDERER: return "GL4ES";
            case Renderers.ZINK_RENDERER: return "Zink (Vulkan)";
            case Renderers.LEGACYZINK_RENDERER: return "Legacy Zink";
            case Renderers.FREEDRENO_RENDERER: return "Freedreno KGSL";
            case Renderers.MESA_RENDERER: return "Mesa (Vulkan)";
            case Renderers.MESA_RENDERER_EXT: return "Mesa (external)";
            case Renderers.MOBILEGLUES_RENDERER: return "MobileGlues";
            case Renderers.SFPEW_RENDERER: return "SimpleFPE Wrapper";
            default: return key.toUpperCase(Locale.ROOT);
        }
    }

    // ─── Launcher background (overrides the banner art when the user set one) ───

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mLoadId++;
        if (mVideo != null) mVideo.release();
        setImageAnimation(false);
        mWallpaper = null;
        mVideo = null;
        if (mVersionSpinner != null) mVersionSpinner.setSelectionListener(null);
        mVersionSpinner = null;
        mBackgroundKey = null;
        mPlayIcon = null;
        mPlayTitle = null;
        mPlaySubtitle = null;
        mPlayArc = null;
    }

    /**
     * Loads the banner artwork: the selected profile's own background first (when set),
     * otherwise the global launcher background, otherwise the default banner.
     * Decoding is skipped entirely while the same artwork (profile + mtime) is already shown,
     * so profile refreshes never repeatedly decode large images.
     */
    private void loadBackground() {
        if (mWallpaper == null || mVideo == null) return;
        String key = backgroundKey();
        // Same artwork already shown or being decoded: nothing to do. The load id is only
        // bumped AFTER this check, otherwise a refresh would drop the decode in flight and the
        // banner would stay on the default art (profile background not showing on home)
        if (key != null && key.equals(mBackgroundKey)) return;
        int loadId = ++mLoadId;
        mBackgroundKey = key;

        Instance selected = Instances.loadSelectedInstance();
        if (selected != null && selected.hasCustomBackground()) {
            // Profile-specific artwork: stored next to the profile, shown only while that
            // profile is selected. The global launcher background file stays untouched.
            final File profileBackground = selected.getBackgroundFile();
            mVideo.release();
            mVideo.setVisibility(View.GONE);
            CSLauncherApplication.sExecutorService.execute(() -> {
                Drawable drawable = LauncherBackground.decodeImage(profileBackground);
                View root = getView();
                if (root == null) return;
                root.post(() -> {
                    if (loadId != mLoadId || mWallpaper == null) return;
                    if (drawable == null) {
                        // Missing/unreadable file: fall back and allow a later retry
                        mBackgroundKey = null;
                        clearBackground();
                        return;
                    }
                    mWallpaper.setImageDrawable(drawable);
                    mWallpaper.setVisibility(View.VISIBLE);
                    setImageAnimation(isResumed());
                    HomeMotion.artSwap(mWallpaper);
                });
            });
            return;
        }

        String kind = LauncherBackground.getKind(requireContext());
        if (kind == null) {
            clearBackground();
            return;
        }
        File file = LauncherBackground.getFile();
        if (LauncherBackground.KIND_VIDEO.equals(kind)) {
            mWallpaper.setVisibility(View.GONE);
            mVideo.setVisibility(View.VISIBLE);
            mVideo.setVideoPath(file.getAbsolutePath());
            return;
        }
        mVideo.release();
        mVideo.setVisibility(View.GONE);
        // Decoding a big image or GIF takes a while, keep it off the UI thread
        CSLauncherApplication.sExecutorService.execute(() -> {
            Drawable drawable = LauncherBackground.decodeImage(file);
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (loadId != mLoadId || mWallpaper == null) return;
                if (drawable == null) {
                    mBackgroundKey = null;
                    clearBackground();
                    return;
                }
                mWallpaper.setImageDrawable(drawable);
                mWallpaper.setVisibility(View.VISIBLE);
                setImageAnimation(isResumed());
                HomeMotion.artSwap(mWallpaper);
            });
        });
    }

    /** Cheap identity of the artwork the banner should show right now (no decoding) */
    private String backgroundKey() {
        try {
            Instance selected = Instances.loadSelectedInstance();
            if (selected != null && selected.hasCustomBackground()) {
                File profileBackground = selected.getBackgroundFile();
                return "profile:" + profileBackground.getAbsolutePath() + ":" + profileBackground.lastModified();
            }
            String kind = LauncherBackground.getKind(requireContext());
            if (kind == null) return "default";
            File file = LauncherBackground.getFile();
            return "global:" + kind + ":" + file.getAbsolutePath() + ":" + (file.exists() ? file.lastModified() : 0L);
        } catch (Exception e) {
            return null;
        }
    }

    /** No custom background: the banner art shows */
    private void clearBackground() {
        if (mWallpaper == null || mVideo == null) return;
        setImageAnimation(false);
        mWallpaper.setImageDrawable(null);
        mWallpaper.setImageResource(R.drawable.banner_home);
        mWallpaper.setVisibility(View.VISIBLE);
        mVideo.release();
        mVideo.setVisibility(View.GONE);
    }

    /** Animated images (GIF, animated WebP) are only played while the screen is shown */
    private void setImageAnimation(boolean play) {
        if (mWallpaper == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Drawable drawable = mWallpaper.getDrawable();
        if (!(drawable instanceof AnimatedImageDrawable)) return;
        AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
        if (play) {
            animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            animated.start();
        } else {
            animated.stop();
        }
    }
}
