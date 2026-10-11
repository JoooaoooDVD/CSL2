package com.craftstudio.cslauncher.instances;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.JSONUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class Instance extends DisplayInstance {
    public static final int ARGS_MODE_REPLACE = 0;
    public static final int ARGS_MODE_MERGE_DEFAULT_FIRST = 1;
    public static final int ARGS_MODE_MERGE_INSTANCE_FIRST = 2;
    public static final int ARGS_MODE_LAST = ARGS_MODE_MERGE_INSTANCE_FIRST;

    public static final String VERSION_LATEST_RELEASE = "latest_release";
    public static final String VERSION_LATEST_SNAPSHOT = "latest_snapshot";

    public InstanceInstaller installer;
    public String renderer;
    public String jvmArgs;
    public int argsMode;
    public String selectedRuntime;
    public String controlLayout;
    public boolean sharedData;
    /** Per-instance memory in MB, 0 = use the global allocation */
    public int ramAllocation;

    protected Instance() {
    }

    @Override
    protected void sanitize() {
        super.sanitize();
        sanitizeArgs();
    }

    private void sanitizeArgs() {
        if(argsMode > ARGS_MODE_LAST) {
            argsMode = 0;
            jvmArgs = null;
        }
    }

    /**
     * Write the current contents of the instance to persistent storage.
     * @throws IOException in case of write errors
     */
    public void write() throws IOException {
        JSONUtils.writeToFile(Instances.metadataLocation(mInstanceRoot), this);
    }

    /**
     * Try to write the contents of the instance, ignore any exceptions
     */
    public void maybeWrite() {
        try {
            write();
        }catch (IOException e) {
            Log.e("Instance", "Failed to write",e);
        }
    }

    /**
     * Encode the Bitmap as the new profile icon with required encoding settings.
     * @param bitmap the target bitmap
     * @throws IOException in case of errors while storing the icon
     */
    public void encodeNewIcon(Bitmap bitmap) throws IOException {
        try(FileOutputStream fileOutputStream = new FileOutputStream(getInstanceIconLocation())) {
            bitmap.compress(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                            // On Android < 30, there was no distinction between "lossy" and "lossless",
                            // and the type is picked by the quality parameter. We set the quality to 60.
                            // so it should be lossy,
                            Bitmap.CompressFormat.WEBP:
                            // On Android >= 30, we can explicitly specify that we want lossy compression
                            // with the visual quality of 60.
                            Bitmap.CompressFormat.WEBP_LOSSY,
                    60,
                    fileOutputStream
            );
        }
    }

    /**
     * @return the profile's custom banner artwork file (may not exist)
     */
    public File getBackgroundFile() {
        return getInstanceBackgroundLocation();
    }

    /**
     * @return true when a readable custom background artwork exists for this profile
     */
    public boolean hasCustomBackground() {
        File background = getBackgroundFile();
        return background != null && background.isFile() && background.canRead();
    }

    /**
     * Store the given bitmap as this profile's custom home-banner artwork.
     * The global launcher banner (LauncherBackground) is a separate file and is untouched.
     * @param bitmap the cropped banner bitmap
     * @throws IOException in case of write errors
     */
    public void encodeNewBackground(Bitmap bitmap) throws IOException {
        try(FileOutputStream fileOutputStream = new FileOutputStream(getBackgroundFile())) {
            bitmap.compress(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                            Bitmap.CompressFormat.WEBP :
                            Bitmap.CompressFormat.WEBP_LOSSY,
                    85,
                    fileOutputStream
            );
        }
    }

    /**
     * Remove this profile's custom background so the global launcher banner shows again.
     * Ignores a missing file; logs but does not throw if deletion fails.
     */
    public void clearCustomBackground() {
        File background = getBackgroundFile();
        if(background != null && background.exists() && !background.delete()) {
            Log.w("Instance", "Failed to delete profile background " + background);
        }
    }

    /**
     * @return true when a readable custom icon file exists for this profile
     */
    public boolean hasCustomIconFile() {
        File iconFile = getInstanceIconLocation();
        return iconFile != null && iconFile.isFile() && iconFile.canRead();
    }

    /**
     * Remove the custom icon file so the profile falls back to its built-in icon.
     */
    public void deleteCustomIcon() {
        File iconFile = getInstanceIconLocation();
        if(iconFile != null && iconFile.exists() && !iconFile.delete()) {
            Log.w("Instance", "Failed to delete profile icon " + iconFile);
        }
    }

    public String getLaunchRenderer() {
        if(Tools.isValidString(renderer)) return renderer;
        return LauncherPreferences.PREF_RENDERER;
    }

    public String getLaunchArgs() {
        if(!Tools.isValidString(jvmArgs)) return LauncherPreferences.PREF_CUSTOM_JAVA_ARGS;
        switch (argsMode) {
            case ARGS_MODE_REPLACE:
                return jvmArgs;
            case ARGS_MODE_MERGE_DEFAULT_FIRST:
                return LauncherPreferences.PREF_CUSTOM_JAVA_ARGS + " " + jvmArgs;
            case ARGS_MODE_MERGE_INSTANCE_FIRST:
                return jvmArgs + " " + LauncherPreferences.PREF_CUSTOM_JAVA_ARGS;
            default:
                throw new RuntimeException("Unknown value for argsMode: "+argsMode);
        }
    }

    public String getLaunchControls() {
        if(!Tools.isValidString(controlLayout)) return LauncherPreferences.PREF_DEFAULTCTRL_PATH;
        return Tools.CTRLMAP_PATH + "/" + controlLayout;
    }

    public File getGameDirectory() {
        if(sharedData) return Instances.SHARED_DATA_DIRECTORY;
        return mInstanceRoot;
    }
}
