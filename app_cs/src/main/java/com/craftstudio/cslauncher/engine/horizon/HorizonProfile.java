package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A CS profile that runs on the Horizon engine. Profiles are ordinary {@link Instance}s
 * ({@code engine = "horizon"}, {@code versionId = "horizon-26.3"}) so they appear in the normal profile
 * selector/editor; what differs is the launch path and the shared engine install.
 *
 * <pre>
 * profile folder (instance root)   = Horizon game_home: saves/, options.txt, resourcepacks/, shaderpacks/,
 *                                    mods/ (.hmod), horizon-mods.json, logs/ — one per profile
 * {@link HorizonEngine#home()}      = shared engine home + prepared Minecraft 26.3 resources (one download
 *                                    for every Horizon profile)
 * </pre>
 *
 * Setup state machine (persisted in {@link Instance#horizonState}; the effective state is always derived
 * from the shared install so a killed app / retry can never leave a profile "stuck"):
 * NOT_INSTALLED → CREATING_PROFILE → DOWNLOADING → VERIFYING → PREPARING → READY, or FAILED.
 */
public final class HorizonProfile {
    public static final String VERSION_ID = "horizon-" + HorizonEngine.MINECRAFT_VERSION;
    public static final String VERSION_PREFIX = "horizon-";

    public static final String STATE_NOT_INSTALLED = "NOT_INSTALLED";
    public static final String STATE_CREATING_PROFILE = "CREATING_PROFILE";
    public static final String STATE_DOWNLOADING = "DOWNLOADING";
    public static final String STATE_VERIFYING = "VERIFYING";
    public static final String STATE_PREPARING = "PREPARING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";

    public static final int[] SCALE_STEPS = {25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100};

    private HorizonProfile() {}

    public static boolean isHorizon(@Nullable Instance instance) {
        return instance != null && instance.isHorizon();
    }

    public static boolean isHorizonVersionId(@Nullable String versionId) {
        return versionId != null && versionId.startsWith(VERSION_PREFIX);
    }

    /**
     * Creates and saves the profile (state CREATING_PROFILE → NOT_INSTALLED/READY). The engine install is
     * started separately by the caller; nothing here touches the network.
     */
    @NonNull
    public static Instance create(@NonNull Context context, @NonNull String name, @Nullable String renderer,
                                  int renderScale, @Nullable String controlLayout) throws IOException {
        final String finalRenderer = renderer == null || renderer.isEmpty() ? null : renderer;
        final String finalControls = controlLayout == null || controlLayout.isEmpty() ? null : controlLayout;
        final boolean installed = HorizonEngine.isInstalled(context);
        Instance instance = Instances.createInstance(i -> {
            i.name = name;
            i.engine = HorizonEngine.ENGINE_ID;
            i.versionId = VERSION_ID;
            i.icon = "horizon";
            i.renderer = finalRenderer;
            i.horizonRenderScale = renderScale;
            i.controlLayout = finalControls;
            i.sharedData = false;
            i.horizonState = installed ? STATE_READY : STATE_NOT_INSTALLED;
        }, "CS-Engine");
        prepareGameHome(instance);
        return instance;
    }

    /** Per-profile folders the engine and the CS content managers expect. */
    public static void prepareGameHome(@NonNull Instance instance) {
        File home = gameHome(instance);
        for (String dir : new String[]{"saves", "resourcepacks", "shaderpacks", "mods", "logs", "screenshots"}) {
            File f = new File(home, dir);
            if (!f.isDirectory()) //noinspection ResultOfMethodCallIgnored
                f.mkdirs();
        }
    }

    /** The profile's own game folder (saves, options, packs, shaders, .hmod mods). */
    @NonNull
    public static File gameHome(@NonNull Instance instance) {
        return instance.getInstanceRoot();
    }

    /**
     * Effective setup state, derived from the shared engine install and the live install progress.
     * Also persists it on the instance when it changed.
     */
    @NonNull
    public static String effectiveState(@NonNull Context context, @NonNull Instance instance) {
        String state;
        if (HorizonEngine.isInstalled(context)) {
            state = STATE_READY;
        } else {
            HorizonInstallState install = HorizonInstallState.get(context);
            if (install.isRunning()) {
                int stage = install.stageIndex();
                if (stage <= HorizonInstallState.STAGE_DOWNLOAD) state = STATE_DOWNLOADING;
                else if (stage == HorizonInstallState.STAGE_EXTRACT || stage == HorizonInstallState.STAGE_VERIFY) state = STATE_VERIFYING;
                else state = STATE_PREPARING;
            } else if (install.status == HorizonInstallState.STATUS_FAILED || install.isInterrupted()) {
                state = STATE_FAILED;
            } else {
                state = STATE_NOT_INSTALLED;
            }
        }
        if (!state.equals(instance.horizonState)) {
            instance.horizonState = state;
            instance.maybeWrite();
        }
        return state;
    }

    public static boolean isReady(@NonNull Context context, @NonNull Instance instance) {
        return STATE_READY.equals(effectiveState(context, instance));
    }

    @NonNull
    public static String stateLabel(@NonNull Context context, @NonNull String state) {
        switch (state) {
            case STATE_CREATING_PROFILE: return context.getString(R.string.hz_state_creating);
            case STATE_DOWNLOADING: return context.getString(R.string.hz_state_downloading);
            case STATE_VERIFYING: return context.getString(R.string.hz_state_verifying);
            case STATE_PREPARING: return context.getString(R.string.hz_state_preparing);
            case STATE_READY: return context.getString(R.string.hz_state_ready);
            case STATE_FAILED: return context.getString(R.string.hz_state_failed);
            default: return context.getString(R.string.hz_state_not_installed);
        }
    }

    /** Starts (or resumes) the shared engine install unless it is already running. */
    public static void ensureInstalling(@NonNull Context context) {
        if (HorizonEngine.isInstalled(context)) return;
        HorizonInstallState state = HorizonInstallState.get(context);
        if (state.isRunning()) return;
        HorizonInstallService.start(context);
    }

    /** Renderer ids the engine accepts, in picker order (empty = engine default). */
    @NonNull
    public static List<String> rendererIds() {
        List<String> ids = new ArrayList<>();
        ids.add(HorizonLaunch.RENDERER_VULKAN_COMPAT);
        ids.add(HorizonLaunch.RENDERER_VULKAN);
        ids.add(HorizonLaunch.RENDERER_OPENGL);
        return ids;
    }

    @NonNull
    public static List<String> rendererNames(@NonNull Context context) {
        List<String> names = new ArrayList<>();
        names.add(context.getString(R.string.hz_renderer_vulkan_compat));
        names.add(context.getString(R.string.hz_renderer_vulkan));
        names.add(context.getString(R.string.hz_renderer_opengl));
        return names;
    }

    @NonNull
    public static String rendererLabel(@NonNull Context context, @Nullable String id) {
        List<String> ids = rendererIds();
        int index = id == null ? -1 : ids.indexOf(id);
        return index < 0 ? context.getString(R.string.hz_renderer_default) : rendererNames(context).get(index);
    }

    /** Control layouts (*.json) in the CS controlmap folder, by file name. */
    @NonNull
    public static List<String> controlLayouts() {
        List<String> names = new ArrayList<>();
        File[] files = new File(Tools.CTRLMAP_PATH).listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().endsWith(".json")) names.add(f.getName());
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static int scaleOf(@NonNull Instance instance) {
        int scale = instance.horizonRenderScale;
        return scale < 25 || scale > 100 ? 100 : scale;
    }

    /** The engine intent for PLAY: profile folder, renderer, scale and CS controls of this profile. */
    @NonNull
    public static Intent launchIntent(@NonNull Context context, @Nullable Account account, @NonNull Instance instance)
            throws IOException, org.json.JSONException {
        return launchIntent(context, account, instance, null);
    }

    /** The same, started straight into {@code server} (CS Friends "Join"). */
    @NonNull
    public static Intent launchIntent(@NonNull Context context, @Nullable Account account, @NonNull Instance instance,
                                      @Nullable String server) throws IOException, org.json.JSONException {
        prepareGameHome(instance);
        writeServerBranding(context, gameHome(instance));
        HorizonLaunch launch = new HorizonLaunch(instance.getKey(), gameHome(instance));
        launch.server = server;
        launch.renderer = instance.renderer == null || !rendererIds().contains(instance.renderer) ? "" : instance.renderer;
        launch.renderScale = instance.horizonRenderScale;
        launch.controlsPath = instance.getLaunchControls();
        Intent intent = launch.intent(context, account);
        intent.putExtra(HorizonGameActivity.EXTRA_PROFILE_NAME, instance.name);
        return intent;
    }

    /**
     * What friends see in their server list when this profile hosts: the launcher icon as the
     * server icon ({@code cs-server-icon.png}, 64x64) and the MOTD from the Friends page
     * ({@code cs-server.json}); the engine reads both when the world opens to LAN.
     */
    private static void writeServerBranding(@NonNull Context context, @NonNull File gameHome) {
        try {
            File icon = new File(gameHome, "cs-server-icon.png");
            if (!icon.isFile() || icon.length() == 0) {
                android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
                if (d != null) {
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                    d.setBounds(0, 0, 64, 64);
                    d.draw(canvas);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(icon);
                    try {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        out.close();
                    }
                }
            }
            String motd = com.craftstudio.cslauncher.multiplayer.friends.FriendsService.get(context).motd();
            File json = new File(gameHome, "cs-server.json");
            if (motd == null || motd.trim().isEmpty()) {
                if (json.isFile()) //noinspection ResultOfMethodCallIgnored
                    json.delete();
            } else {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("motd", motd.trim());
                o.put("icon", "cs-server-icon.png");
                Tools.write(json.getAbsolutePath(), o.toString());
            }
        } catch (Throwable t) {
            android.util.Log.w("Horizon", "server branding not written", t);
        }
    }
}
