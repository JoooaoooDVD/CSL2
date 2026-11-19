package com.craftstudio.cslauncher.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.BuildConfig;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

import java.io.IOException;

/**
 * Entry points of the update system: the silent check at launcher start (throttled, never
 * blocking, failures are ignored) and the manual "Check for updates" from the settings
 * (always reports: new build / up to date / error). One check runs at a time.
 */
public final class UpdateManager {
    public static final String PREF_CHANNEL = "update_channel";      // "latest" | "stable"
    public static final String PREF_AUTO_CHECK = "update_auto_check";
    private static final String PREF_LAST_CHECK = "update_last_check";
    private static final String PREF_SNOOZE_UNTIL = "update_snooze_until";
    private static final String PREF_SKIPPED_CODE = "update_skipped_code";

    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long SNOOZE_MS = 24L * 60 * 60 * 1000;

    private static boolean sChecking;
    @Nullable private static UpdatePopup sPopup;

    private UpdateManager() {}

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static int currentCode() { return BuildConfig.VERSION_CODE; }

    @NonNull
    public static String currentLabel() {
        return BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")";
    }

    /** Name of the release asset that matches this installation (flavor + build type). */
    @NonNull
    public static String expectedApkName() {
        if ("debug".equals(BuildConfig.BUILD_TYPE)) return "app-debug-" + BuildConfig.FLAVOR + ".apk";
        return "CS Launcher Plus-release.apk";
    }

    public static boolean stableOnly(Context c) {
        return "stable".equals(prefs(c).getString(PREF_CHANNEL, "latest"));
    }

    // ------------------------------------------------------------ scheduling

    /** Silent check at start: at most every 6 h, skipped while snoozed; shows the popup only for a new build. */
    public static void autoCheck(@NonNull Activity activity) {
        if (sChecking || sPopup != null) return;
        SharedPreferences p = prefs(activity);
        if (!p.getBoolean(PREF_AUTO_CHECK, true)) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong(PREF_LAST_CHECK, 0L) < AUTO_INTERVAL_MS) return;
        if (now < p.getLong(PREF_SNOOZE_UNTIL, 0L)) return;
        p.edit().putLong(PREF_LAST_CHECK, now).apply();
        UpdateDownloader.cleanUp(activity);
        check(activity, false);
    }

    /** Settings button: always gives feedback. */
    public static void manualCheck(@NonNull Activity activity) {
        if (sChecking) return;
        if (sPopup != null && sPopup.isShowing()) return;
        prefs(activity).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
        check(activity, true);
    }

    private static void check(@NonNull final Activity activity, final boolean manual) {
        sChecking = true;
        final boolean stableOnly = stableOnly(activity);
        final SettingsPopup[] checking = new SettingsPopup[1];
        if (manual) {
            checking[0] = new SettingsPopup(activity)
                    .title(R.string.upd_checking_title)
                    .subtitle(activity.getString(R.string.upd_current_fmt, currentLabel()))
                    .icon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_cs_update))
                    .message(R.string.upd_checking_msg);
            checking[0].show();
        }
        Thread t = new Thread(() -> {
            UpdateChecker.Result result = null;
            String error = null;
            try {
                result = UpdateChecker.fetch(stableOnly);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (Throwable e) {
                error = e.toString();
            }
            final UpdateChecker.Result fResult = result;
            final String fError = error;
            Tools.MAIN_HANDLER.post(() -> {
                sChecking = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Runnable show = () -> deliver(activity, manual, fResult, fError);
                if (checking[0] != null && checking[0].isShowing()) checking[0].closeThen(show);
                else show.run();
            });
        }, "cs-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void deliver(@NonNull Activity activity, boolean manual, @Nullable UpdateChecker.Result result, @Nullable String error) {
        if (error != null || result == null) {
            if (manual) {
                new SettingsPopup(activity)
                        .title(R.string.upd_error_title)
                        .icon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_cs_update))
                        .message(activity.getString(R.string.upd_error_check_fmt, error == null ? "?" : error))
                        .positive(R.string.upd_retry, () -> manualCheck(activity))
                        .negative(R.string.pe_cancel, null)
                        .show();
            }
            return;
        }
        UpdateInfo newest = result.newest;
        SharedPreferences p = prefs(activity);
        if (newest == null || newest.versionCode <= currentCode()) {
            if (manual) {
                String msg = !result.anyPublished
                        ? activity.getString(R.string.upd_none_published)
                        : activity.getString(R.string.upd_up_to_date_msg, currentLabel());
                new SettingsPopup(activity)
                        .title(R.string.upd_up_to_date_title)
                        .icon(androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_cs_update))
                        .message(msg)
                        .positive(R.string.upd_ok, null)
                        .show();
            }
            return;
        }
        if (!manual && p.getInt(PREF_SKIPPED_CODE, -1) == newest.versionCode) return;
        showPopup(activity, newest);
    }

    private static void showPopup(@NonNull Activity activity, @NonNull UpdateInfo info) {
        UpdatePopup popup = new UpdatePopup(activity, info);
        popup.setOnDismissListener(d -> { if (sPopup == popup) sPopup = null; });
        sPopup = popup;
        popup.show();
    }

    // ------------------------------------------------------------ user choices

    public static void snooze(Context c) {
        prefs(c).edit().putLong(PREF_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply();
    }

    public static void skip(Context c, int versionCode) {
        prefs(c).edit().putInt(PREF_SKIPPED_CODE, versionCode).apply();
    }

    // ------------------------------------------------------------ installer results

    /** Called by the launcher activity; returns true when the result belonged to the updater. */
    public static boolean onActivityResult(@NonNull Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == UpdateInstaller.REQUEST_UNKNOWN_SOURCES) {
            if (sPopup != null && sPopup.isShowing()) sPopup.onPermissionReturned();
            return true;
        }
        if (requestCode == UpdateInstaller.REQUEST_INSTALL) {
            if (resultCode == Activity.RESULT_OK) {
                // Normally never reached - the process is replaced by the new build
                UpdateDownloader.cleanUp(activity);
                if (sPopup != null && sPopup.isShowing()) sPopup.closeThen(null);
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(activity, R.string.upd_install_cancelled, Toast.LENGTH_SHORT).show();
                if (sPopup != null && sPopup.isShowing()) sPopup.onInstallCancelled();
            } else {
                String why = data != null && data.hasExtra("android.intent.extra.INSTALL_RESULT")
                        ? "code " + data.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) : "";
                if (sPopup != null && sPopup.isShowing()) sPopup.onInstallFailed(why);
                else Toast.makeText(activity, activity.getString(R.string.upd_install_failed_fmt, why), Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }
}
