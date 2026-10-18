package com.craftstudio.cslauncher.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

/** Hands a verified APK to the system package installer (standard confirmation flow). */
public final class UpdateInstaller {
    public static final int REQUEST_INSTALL = 0x4350;
    public static final int REQUEST_UNKNOWN_SOURCES = 0x4351;

    private UpdateInstaller() {}

    /** On Android 8+ the user has to allow this app to install packages first. */
    public static boolean canInstall(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    public static void requestInstallPermission(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivityForResult(intent, REQUEST_UNKNOWN_SOURCES);
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    @SuppressWarnings("deprecation")
    public static void install(@NonNull Activity activity, @NonNull File apk) throws Exception {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.getPackageName());
        activity.startActivityForResult(intent, REQUEST_INSTALL);
    }
}
