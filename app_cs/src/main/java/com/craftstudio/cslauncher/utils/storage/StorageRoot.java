package com.craftstudio.cslauncher.utils.storage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Where the launcher keeps its game files. By default that is the app's private external folder
 * ({@code Android/data/<pkg>/files}); the first-launch setup lets the user pick any folder on the
 * device instead, which needs "All files access" on Android 11+ (the game itself must be able to
 * read and write there with plain file paths).
 */
public final class StorageRoot {
    private static final String PREF_CUSTOM_ROOT = "customStorageRoot";
    private static final String PREF_SETUP_DONE = "storageSetupDoneV1";
    private static final String PREF_SETUP_PENDING = "storageSetupPendingV1";

    private StorageRoot() {}

    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ───────────────────────── setup state ─────────────────────────

    public static boolean isSetupDone(@NonNull Context context) {
        return prefs(context).getBoolean(PREF_SETUP_DONE, false);
    }

    public static boolean isSetupPending(@NonNull Context context) {
        return prefs(context).getBoolean(PREF_SETUP_PENDING, false);
    }

    /** Called right after the policy is accepted on a fresh install: the setup must follow. */
    public static void markSetupPending(@NonNull Context context) {
        if (!isSetupDone(context)) prefs(context).edit().putBoolean(PREF_SETUP_PENDING, true).apply();
    }

    /** Existing installs never saw the setup; keep their current (default) folder silently. */
    public static void markSetupDone(@NonNull Context context) {
        prefs(context).edit().putBoolean(PREF_SETUP_DONE, true).remove(PREF_SETUP_PENDING).apply();
    }

    // ───────────────────────── roots ─────────────────────────

    /** Custom folder chosen by the user, or null when the default is used. */
    @Nullable
    public static String getCustomRoot(@NonNull Context context) {
        String path = prefs(context).getString(PREF_CUSTOM_ROOT, null);
        return path == null || path.trim().isEmpty() ? null : path;
    }

    public static boolean isUsingCustomRoot(@NonNull Context context) {
        return getCustomRoot(context) != null;
    }

    /** The custom root when it is set, reachable and writable; null → use the default. */
    private static String sProbedPath;
    private static boolean sProbedOk;

    @Nullable
    public static File resolveCustomRoot(@NonNull Context context) {
        String path = getCustomRoot(context);
        if (path == null) return null;
        if (!hasAllFilesAccess(context)) return null;
        File dir = new File(path);
        // The write probe is cheap but this runs on every storage check; only probe once per path
        if (!path.equals(sProbedPath)) {
            sProbedOk = isWritableDirectory(dir);
            sProbedPath = path;
        }
        return sProbedOk ? dir : null;
    }

    /** The default, permission-free location. */
    @NonNull
    public static File defaultRoot(@NonNull Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = new File(context.getFilesDir(), "external");
        return dir;
    }

    /** Human readable form of the default root ("Android/data/<pkg>/files"). */
    @NonNull
    public static String defaultRootLabel(@NonNull Context context) {
        String path = defaultRoot(context).getAbsolutePath();
        int idx = path.indexOf("/Android/");
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    public static void setCustomRoot(@NonNull Context context, @NonNull File dir) {
        prefs(context).edit()
                .putString(PREF_CUSTOM_ROOT, dir.getAbsolutePath())
                .putBoolean(PREF_SETUP_DONE, true)
                .remove(PREF_SETUP_PENDING)
                .apply();
    }

    public static void useDefaultRoot(@NonNull Context context) {
        prefs(context).edit()
                .remove(PREF_CUSTOM_ROOT)
                .putBoolean(PREF_SETUP_DONE, true)
                .remove(PREF_SETUP_PENDING)
                .apply();
    }

    // ───────────────────────── permissions ─────────────────────────

    /** True when plain file I/O works anywhere on shared storage. */
    public static boolean hasAllFilesAccess(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Intent that opens the "All files access" screen for this app (Android 11+). */
    @Nullable
    public static Intent allFilesAccessIntent(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        }
        return intent;
    }

    // ───────────────────────── folder resolution ─────────────────────────

    /** Turns a document-tree URI from the system picker into a real path, or null when it isn't on local storage. */
    @Nullable
    public static File treeUriToFile(@NonNull Uri treeUri) {
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (docId == null || !"com.android.externalstorage.documents".equals(treeUri.getAuthority())) return null;
        int colon = docId.indexOf(':');
        String volume = colon >= 0 ? docId.substring(0, colon) : docId;
        String rel = colon >= 0 ? docId.substring(colon + 1) : "";
        File base;
        if ("primary".equalsIgnoreCase(volume)) {
            base = Environment.getExternalStorageDirectory();
        } else {
            base = new File("/storage/" + volume);
            if (!base.isDirectory()) return null;
        }
        return rel.isEmpty() ? base : new File(base, rel);
    }

    /** True when files can really be created inside {@code dir} (creating it if needed). */
    public static boolean isWritableDirectory(@NonNull File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File probe = new File(dir, ".cs_write_test");
        try (FileOutputStream out = new FileOutputStream(probe)) {
            out.write(1);
        } catch (IOException e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
        return true;
    }

    /** A folder is "fresh" when it is empty or doesn't exist yet: nothing of the user's gets mixed in. */
    public static boolean isEmptyOrMissing(@NonNull File dir) {
        if (!dir.exists()) return true;
        String[] list = dir.list();
        return list == null || list.length == 0;
    }

    /** Restarts the whole process so every path constant is rebuilt from the new root. */
    public static void restartLauncher(@NonNull Context context) {
        Intent intent = new Intent(context, com.craftstudio.cslauncher.TestStorageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                com.craftstudio.cslauncher.Tools::fullyExit, 250L);
    }
}
