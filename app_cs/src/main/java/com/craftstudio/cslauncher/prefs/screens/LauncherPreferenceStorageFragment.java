package com.craftstudio.cslauncher.prefs.screens;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;

import com.craftstudio.cslauncher.ImportControlActivity;
import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.FileManagerFragment;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.tasks.DataMigrator;
import com.craftstudio.cslauncher.utils.storage.StorageRoot;

import java.io.File;

/**
 * Settings → Storage: where the game files live (scoped storage = Android/data, default storage =
 * a folder of the user's choice), a tap on the other mode switches; the current folder opens in
 * the file manager; control layouts and launcher data can be imported from here.
 */
public class LauncherPreferenceStorageFragment extends LauncherPreferenceFragment {
    @Override
    protected int[] pageHeader() {
        return new int[]{R.drawable.ic_px_folder, R.string.preference_storage_title, R.string.preference_storage_description};
    }

    private final ActivityResultLauncher<Uri> mMigrateLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                new SettingsPopup(getLauncherActivity())
                        .title(R.string.migration_progress_warning_title)
                        .icon(ContextCompat.getDrawable(getLauncherActivity(), R.drawable.ic_px_data_import))
                        .message(R.string.migration_progress_warning_summary)
                        .positive(android.R.string.ok, () -> new DataMigrator(getLauncherActivity(), uri).migrateData())
                        .negative(android.R.string.cancel, null)
                        .show();
            });

    private final ActivityResultLauncher<String[]> mControlsLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                Activity activity = getActivity();
                if (activity == null) return;
                Intent intent = new Intent(activity, ImportControlActivity.class);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(intent);
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_storage);
        requirePreference("storage_mode_scoped").setOnPreferenceClickListener(p -> { chooseScoped(); return true; });
        requirePreference("storage_mode_default").setOnPreferenceClickListener(p -> { chooseDefault(); return true; });
        requirePreference("storage_current_folder").setOnPreferenceClickListener(p -> { openFolder(); return true; });
        requirePreference("storage_import_controls").setOnPreferenceClickListener(p -> {
            mControlsLauncher.launch(new String[]{"application/json", "application/octet-stream", "text/plain", "*/*"});
            return true;
        });
        requirePreference("storage_import_data").setOnPreferenceClickListener(p -> {
            if (ProgressKeeper.getTaskCount() > 0) {
                Toast.makeText(getContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
                return true;
            }
            mMigrateLauncher.launch(null);
            return true;
        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** Marks the active mode and shows the folder in use. */
    private void refresh() {
        Activity activity = getActivity();
        if (activity == null) return;
        boolean custom = StorageRoot.isUsingCustomRoot(activity);
        Preference scoped = requirePreference("storage_mode_scoped");
        Preference def = requirePreference("storage_mode_default");
        String tick = getString(R.string.preference_storage_mode_active) + "\n";
        scoped.setSummary((custom ? "" : tick) + getString(R.string.ss_default_desc));
        def.setSummary((custom ? tick : "") + getString(R.string.ss_custom_desc));
        scoped.setIcon(custom ? R.drawable.ic_px_folder : R.drawable.ic_px_check_circle);
        def.setIcon(custom ? R.drawable.ic_px_check_circle : R.drawable.ic_px_data_import);
        String where = custom ? StorageRoot.getCustomRoot(activity) : StorageRoot.defaultRootLabel(activity);
        requirePreference("storage_current_folder").setSummary(where);
    }

    private void chooseScoped() {
        Activity activity = getActivity();
        if (activity == null) return;
        if (!StorageRoot.isUsingCustomRoot(activity)) {
            Toast.makeText(activity, R.string.preference_storage_mode_already, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(activity, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        new SettingsPopup(getLauncherActivity())
                .title(R.string.ss_default_title)
                .icon(ContextCompat.getDrawable(activity, R.drawable.ic_px_folder))
                .message(getString(R.string.preference_storage_switch_confirm, StorageRoot.defaultRootLabel(activity)))
                .positive(R.string.preference_storage_switch_restart, () -> {
                    StorageRoot.useDefaultRoot(activity);
                    StorageRoot.restartLauncher(activity);
                })
                .negative(android.R.string.cancel, null)
                .show();
    }

    /** Default storage needs a folder (and maybe the permission): the setup chooser handles both. */
    private void chooseDefault() {
        Activity activity = getActivity();
        if (!(activity instanceof LauncherActivity)) return;
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(activity, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        ((LauncherActivity) activity).reopenStorageSetup(true);
    }

    private void openFolder() {
        Activity activity = getActivity();
        if (activity == null) return;
        File root = new File(Tools.DIR_GAME_HOME);
        if (!root.isDirectory()) root = StorageRoot.isUsingCustomRoot(activity) ? new File(StorageRoot.getCustomRoot(activity)) : StorageRoot.defaultRoot(activity);
        Tools.swapFragment(getLauncherActivity(), FileManagerFragment.class, FileManagerFragment.TAG,
                FileManagerFragment.args(root, getString(R.string.preference_storage_folder_title), null));
    }
}
