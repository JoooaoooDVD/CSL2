package com.craftstudio.cslauncher.fragments;

import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.CenterCropVideoView;
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
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.RamInputDialog;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceJavaFragment;
import com.craftstudio.cslauncher.prefs.screens.LauncherPreferenceVideoFragment;
import com.craftstudio.cslauncher.utils.LauncherBackground;

import java.io.File;
import java.util.Locale;

import static com.craftstudio.cslauncher.Architecture.is32BitsDevice;
import static com.craftstudio.cslauncher.Tools.getTotalDeviceMemory;

/**
 * The home content of the right pane: banner, selected version, PLAY, the two feature cards
 * and the quick settings. Every control is wired to the existing launcher systems, this
 * fragment owns no state of its own.
 */
public class RightPaneHomeFragment extends Fragment implements ExtraListener<Void> {
    public static final String TAG = "RightPaneHomeFragment";

    private ImageView mWallpaper;
    private CenterCropVideoView mVideo;
    private mcVersionSpinner mVersionSpinner;
    /* Incremented to drop the result of a decode that is not needed anymore */
    private int mLoadId;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mWallpaper = view.findViewById(R.id.right_pane_wallpaper);
        mVideo = view.findViewById(R.id.right_pane_wallpaper_video);
        mVideo.setErrorListener(this::clearBackground);
        loadBackground();

        // Banner tap: nothing to open, it is pure branding

        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        // The Change button opens the editor of the currently selected profile
        view.findViewById(R.id.edit_profile_button).setOnClickListener(v -> {
            mVersionSpinner.openProfileEditor(requireActivity());
        });

        // PLAY: hands the request to the existing launch pipeline of LauncherActivity,
        // which already refuses to double-launch while a task is running
        view.findViewById(R.id.play_button).setOnClickListener(v -> {
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        view.findViewById(R.id.home_card_browser).setOnClickListener(v -> {
            openContentPicker(false);
        });
        view.findViewById(R.id.home_card_manager).setOnClickListener(v -> {
            openContentPicker(true);
        });

        // Quick settings
        view.findViewById(R.id.home_qs_ram).setOnClickListener(v -> {
            showRamDialog();
        });
        view.findViewById(R.id.home_qs_java).setOnClickListener(v -> {
            openPane(LauncherPreferenceJavaFragment.class, "HOME_SETTINGS_JAVA", null);
        });
        view.findViewById(R.id.home_qs_renderer).setOnClickListener(v -> {
            openPane(LauncherPreferenceVideoFragment.class, "HOME_SETTINGS_RENDERER", null);
        });
        view.findViewById(R.id.home_qs_controls).setOnClickListener(v -> {
            startActivity(new android.content.Intent(requireContext(), CustomControlsActivity.class));
        });
        view.findViewById(R.id.home_qs_more).setOnClickListener(v -> {
            // Same target as the settings button of the header
            Tools.swapFragmentFullScreen(requireActivity(),
                    SettingsHostFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null);
        });

        refreshQuickSettings();
        refreshVersionInfo();
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
        if (mVideo != null) mVideo.resumePlayback();
        setImageAnimation(true);
        refreshQuickSettings();
        refreshVersionInfo();
    }

    @Override
    public void onPause() {
        super.onPause();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_VERSION_SPINNER, this);
        if (mVideo != null) mVideo.pausePlayback();
        setImageAnimation(false);
    }

    /** The spinner broadcasts a refresh through ExtraCore whenever the selection changed */
    @Override
    public boolean onValueSet(String key, @NonNull Void value) {
        View root = getView();
        if (root != null) {
            // Consume so late-attached screens don't replay it, then update from the real state
            ExtraCore.consumeValue(key);
            root.post(() -> {
                refreshVersionInfo();
                refreshQuickSettings();
            });
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

        ((TextView) root.findViewById(R.id.home_version_subtitle)).setText(detail);
        ((TextView) root.findViewById(R.id.home_play_subtitle)).setText(detail);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── Quick settings ──────────────────────────────────────────────────────

    /** Pulls the live values out of the preference system, no copies of the state here */
    private void refreshQuickSettings() {
        View root = getView();
        if (root == null) return;

        ((TextView) root.findViewById(R.id.home_qs_ram_value))
                .setText(LauncherPreferences.PREF_RAM_ALLOCATION + " MB");
        ((TextView) root.findViewById(R.id.home_qs_java_value))
                .setText(javaRuntimeLabel());
        ((TextView) root.findViewById(R.id.home_qs_renderer_value))
                .setText(rendererLabel(LauncherPreferences.PREF_RENDERER));

        TextView controls = root.findViewById(R.id.home_qs_controls_value);
        String ctrlPath = LauncherPreferences.PREF_DEFAULTCTRL_PATH;
        if (ctrlPath == null || ctrlPath.equals(Tools.CTRLDEF_FILE)) {
            controls.setText(R.string.home_qs_default);
        } else {
            String name = new File(ctrlPath).getName();
            int dot = name.lastIndexOf('.');
            controls.setText(dot > 0 ? name.substring(0, dot) : name);
        }
    }

    private String javaRuntimeLabel() {
        String configured = LauncherPreferences.PREF_DEFAULT_RUNTIME;
        if (configured == null || configured.isEmpty()) {
            return getString(R.string.home_qs_default);
        }
        // Resolve the readable "Java N" label from the runtime folder, fall back to its name
        try {
            for (Runtime runtime : MultiRTUtils.getRuntimes()) {
                if (configured.equals(runtime.name)) {
                    return "Java " + runtime.javaVersion;
                }
            }
        } catch (Exception ignored) {
            // Runtime directory not ready, the raw name is still a meaningful label
        }
        return configured;
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

    /** Same dialog the memory slider in the settings uses, validated against the same bounds */
    private void showRamDialog() {
        int deviceRam = getTotalDeviceMemory(requireContext());
        int maxRAM;
        if (is32BitsDevice() || deviceRam < 2048) maxRAM = Math.min(1024, deviceRam);
        else maxRAM = deviceRam - (deviceRam < 3064 ? 800 : 1024); //Keep some room for the system
        int minRAM = Math.min(256, maxRAM);

        RamInputDialog.show(requireContext(), LauncherPreferences.PREF_RAM_ALLOCATION,
                minRAM, maxRAM, megabytes -> {
                    LauncherPreferences.PREF_RAM_ALLOCATION = megabytes;
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putInt("allocation", megabytes).apply();
                    refreshQuickSettings();
                });
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
        mVersionSpinner = null;
    }

    private void loadBackground() {
        int loadId = ++mLoadId;
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
                    clearBackground();
                    return;
                }
                mWallpaper.setImageDrawable(drawable);
                mWallpaper.setVisibility(View.VISIBLE);
                setImageAnimation(isResumed());
            });
        });
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
