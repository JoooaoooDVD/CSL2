package com.craftstudio.cslauncher.prefs.screens;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.Manifest;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.discord.DiscordAccount;
import com.craftstudio.cslauncher.discord.DiscordLoginActivity;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.tasks.DataMigrator;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceFragment {
    @Override
    protected int[] pageHeader() {
        return new int[]{R.drawable.ic_pe_cube, R.string.preference_misc_title, R.string.preference_misc_description};
    }


    private final ActivityResultLauncher<Uri> mMigrateLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), (uri) -> {
                if(uri != null) {
                    new com.craftstudio.cslauncher.prefs.SettingsPopup(getLauncherActivity())
                            .title(R.string.migration_progress_warning_title)
                            .icon(androidx.core.content.ContextCompat.getDrawable(getLauncherActivity(), R.drawable.ic_px_data_import))
                            .message(R.string.migration_progress_warning_summary)
                            .positive(android.R.string.ok, () -> new DataMigrator(getLauncherActivity(), uri).migrateData())
                            .negative(android.R.string.cancel, null)
                            .show();
                }
            }
    );

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_misc);
        Preference importPreference = requirePreference("runDataMigration");
        importPreference.setOnPreferenceClickListener(preference -> {
            if(ProgressKeeper.getTaskCount() > 0) {
                Toast.makeText(getContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
                return true;
            }
            mMigrateLauncher.launch(null);
            return true;
        });
        setupCacheClearPreference();
        setupMicrophoneRequestPreference();
        setupDiscordPreferences();
        setupUpdatePreference();
        updateVisibility();
    }

    private void setupUpdatePreference() {
        Preference check = requirePreference("checkUpdates");
        check.setSummary(getString(R.string.preference_check_updates_summary_fmt,
                com.craftstudio.cslauncher.update.UpdateManager.currentLabel()));
        check.setOnPreferenceClickListener(preference -> {
            android.app.Activity activity = getActivity();
            if (activity != null) com.craftstudio.cslauncher.update.UpdateManager.manualCheck(activity);
            return true;
        });
    }

    private final ActivityResultLauncher<android.content.Intent> mDiscordLoginLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> updateDiscordAccountSummary());

    private void setupDiscordPreferences() {
        Preference account = requirePreference("discordAccount");
        updateDiscordAccountSummary();
        account.setOnPreferenceClickListener(preference -> {
            com.craftstudio.cslauncher.discord.CSDiscordRpcSheet.show(requireActivity());
            return true;
        });

        Preference appId = requirePreference(DiscordAccount.PREF_APPLICATION_ID);
        appId.setSummaryProvider(preference -> {
            String value = ((androidx.preference.EditTextPreference) preference).getText();
            return value == null || value.trim().isEmpty()
                    ? getString(R.string.preference_discord_app_id_description) : value.trim();
        });
        appId.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = newValue == null ? "" : newValue.toString().trim();
            if (value.isEmpty() || value.matches("\\d{15,22}")) return true;
            Toast.makeText(requireContext(), R.string.preference_discord_app_id_invalid, Toast.LENGTH_SHORT).show();
            return false;
        });
    }

    private void updateDiscordAccountSummary() {
        if (getContext() == null) return;
        Preference account = findPreference("discordAccount");
        if (account == null) return;
        com.craftstudio.cslauncher.discord.DiscordAccountStorage.DiscordProfile profile =
                com.craftstudio.cslauncher.discord.DiscordAccountStorage.loadAccount(requireContext());
        if (profile.isLinked) {
            account.setSummary(getString(R.string.preference_discord_account_logged_in, "@" + profile.getEffectiveName()));
        } else {
            String name = DiscordAccount.getDisplayName(requireContext());
            account.setSummary(DiscordAccount.isLoggedIn(requireContext())
                    ? getString(R.string.preference_discord_account_logged_in, name == null ? "Discord" : name)
                    : getString(R.string.preference_discord_account_logged_out));
        }
    }

    private void updateVisibility(){
        requirePreference("microphoneAccessRequest").setVisible(!getLauncherActivity().checkForPermissionRationale(33, Manifest.permission.RECORD_AUDIO));
        requirePreference("clearMetadataCache").setVisible(new File(Tools.DIR_CACHE, "string_cache").exists());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private void setupMicrophoneRequestPreference() {
        Preference mRequestMicrophonePermissionPreference = requirePreference("microphoneAccessRequest");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            mRequestMicrophonePermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(23, Manifest.permission.RECORD_AUDIO);
                return true;
            });
        } else {
            mRequestMicrophonePermissionPreference.setVisible(false);
        }
    }
    private void setupCacheClearPreference() {
        Preference clearPreference = requirePreference("clearMetadataCache");
        clearPreference.setOnPreferenceClickListener(preference -> {
            if(ProgressKeeper.getTaskCount() > 0) {
                Toast.makeText(getContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
                return true;
            }
            CSLauncherApplication.sExecutorService.submit(() -> {
                try {
                    FileUtils.deleteDirectory(new File(Tools.DIR_CACHE, "string_cache"));
                } catch (IOException e) {
                    Tools.showErrorRemote(getLauncherActivity(), R.string.preference_metadata_clear_fail, e);
                    return;
                }
                Tools.runOnUiThread(() -> {
                    Toast.makeText(getLauncherActivity(), R.string.preference_metadata_clear_complete, Toast.LENGTH_LONG).show();
                    updateVisibility();
                });
            });
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDiscordAccountSummary();
    }
}
