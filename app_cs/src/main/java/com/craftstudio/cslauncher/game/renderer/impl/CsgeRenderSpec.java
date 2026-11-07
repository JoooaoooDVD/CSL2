package com.craftstudio.cslauncher.game.renderer.impl;

import android.content.Context;
import android.util.Log;

import com.craftstudio.cslauncher.BuildConfig;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.game.renderer.def.Renderers;
import com.craftstudio.cslauncher.game.renderer.extra.GLESProvider;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.jreexec.JreExec;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Craft Studio Graphics Engine (CSGE): the Craft Studio Development Group build of
 * MobileGlues 2.0.0, shipped as {@code libcsge.so} (SONAME {@code libmobileglues.so}) so it
 * can live next to the stock MobileGlues AAR library without replacing it. Loaded by absolute
 * path; the engine finds itself again through its SONAME.
 *
 * The engine initialises in its static constructor, so every setting it reads
 * (MG_DIR_PATH, CS_LAUNCHER_PLUS_VERSION_CODE, config.json) must be in place before
 * {@link #setupRenderer()} - that's what {@link #setupEnvironment} does.
 */
public class CsgeRenderSpec extends MobileGluesRenderSpec {
    private static final String TAG = "CsgeRenderSpec";
    /** Where CSGE keeps config.json and latest.log. */
    public static File dir() {
        return new File(Tools.DIR_DATA, "CSGE");
    }
    public String library() {
        return "libcsge.so";
    }
    public boolean compatibleDevice(Context context) {
        return new File(Tools.NATIVE_LIB_DIR, this.library()).exists();
    }
    public String name() {
        return "CSGE";
    }
    public int displayName() {
        return R.string.mcl_setting_renderer_csge;
    }
    public String tag() {
        return Renderers.CSGE_RENDERER;
    }
    /**
     * The engine's GLSL cache stores translated shaders keyed by their source. When the
     * shipped engine library changes, a translation fix would otherwise be shadowed by
     * the cache written by the previous build, so the cache is dropped once per library.
     */
    private static void dropStaleGlslCache(File dir) {
        File lib = new File(Tools.NATIVE_LIB_DIR, "libcsge.so");
        String stamp = lib.length() + ":" + lib.lastModified() + ":" + BuildConfig.VERSION_CODE;
        File stampFile = new File(dir, ".lib_stamp");
        String previous = null;
        if (stampFile.isFile()) {
            try {
                previous = Tools.read(stampFile.getAbsolutePath()).trim();
            } catch (IOException ignored) {
            }
        }
        if (stamp.equals(previous)) return;
        File cache = new File(dir, "glsl_cache.tmp");
        if (cache.exists() && cache.delete()) Log.i(TAG, "CSGE library changed, dropped GLSL cache");
        new File(dir, "glsl_cache.tmp.new").delete();
        try {
            dir.mkdirs();
            Tools.write(stampFile.getAbsolutePath(), stamp);
        } catch (IOException e) {
            Log.w(TAG, "Could not record CSGE library stamp", e);
        }
    }

    public void setupEnvironment(Context context, Map<String, String> envMap) {
        File dir = dir();
        dropStaleGlslCache(dir);
        try {
            LauncherPreferences.writeMGRendererSettings(new File(dir, "config.json"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write CSGE renderer settings", e);
        }
        GLESProvider provider = GLESProvider.getGlesProvider(context, true, LauncherPreferences.PREF_USE_SYSTEM_ANGLE);
        Log.i(TAG, "Using GLESProvider: " + provider.type());
        provider.setEnvironment(envMap);
        // Both are required by the engine before it loads, else config.json is ignored.
        envMap.put("MG_DIR_PATH", dir.getAbsolutePath());
        envMap.put("CS_LAUNCHER_PLUS_VERSION_CODE", String.valueOf(BuildConfig.VERSION_CODE));
    }
    public boolean setupRenderer() {
        File lib = new File(Tools.NATIVE_LIB_DIR, library());
        return JreExec.prepareEgl(lib.getAbsolutePath(), true, false, 0);
    }
}
