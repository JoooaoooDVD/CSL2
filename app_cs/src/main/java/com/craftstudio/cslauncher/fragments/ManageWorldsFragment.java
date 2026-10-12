package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.worlds.WorldSaves;
import com.craftstudio.cslauncher.worlds.WorldSaves.WorldInfo;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Worlds of the selected profile: rename, view files, install datapack zips
 * and delete. Opened from the Manage Content popup's Worlds option.
 */
public class ManageWorldsFragment extends Fragment {

    public static final String TAG = "ManageWorldsFragment";

    private ListView mList;
    private TextView mSubtitle;
    private TextView mEmpty;
    private final List<WorldInfo> mWorlds = new ArrayList<>();
    private WorldAdapter mAdapter;
    private WorldInfo mDatapackTarget;

    private final ActivityResultLauncher<String[]> mDatapackPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onDatapackPicked);

    public ManageWorldsFragment() {
        super(R.layout.fragment_manage_worlds);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mList = view.findViewById(R.id.worlds_list);
        mSubtitle = view.findViewById(R.id.worlds_subtitle);
        mEmpty = view.findViewById(R.id.worlds_empty);
        mAdapter = new WorldAdapter(requireContext());
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((parent, row, position, id) -> showActions(mWorlds.get(position)));
        view.findViewById(R.id.worlds_back).setOnClickListener(v -> getParentFragmentManager().popBackStack());
        reload();
    }

    private void reload() {
        CSLauncherApplication.sExecutorService.execute(() -> {
            File gameDir = gameDirectory();
            final List<WorldInfo> worlds = WorldSaves.listWorlds(gameDir);
            final String profile = profileName();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                mWorlds.clear();
                mWorlds.addAll(worlds);
                mAdapter.notifyDataSetChanged();
                mEmpty.setVisibility(worlds.isEmpty() ? View.VISIBLE : View.GONE);
                mList.setVisibility(worlds.isEmpty() ? View.GONE : View.VISIBLE);
                mSubtitle.setText(getString(R.string.mcl_worlds_subtitle, worlds.size(), profile));
            });
        });
    }

    private void showActions(@NonNull WorldInfo world) {
        Context context = getContext();
        if (context == null) return;
        String[] items = {
                getString(R.string.mcl_world_rename),
                getString(R.string.mcl_world_view_files),
                getString(R.string.mcl_world_install_datapack),
                getString(R.string.mcl_world_delete)
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.mcl_world_actions, world.name))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showRename(world);
                    else if (which == 1) Tools.openPath(context, world.dir, false);
                    else if (which == 2) {
                        mDatapackTarget = world;
                        mDatapackPicker.launch(new String[]{"application/zip"});
                    } else if (which == 3) showDelete(world);
                })
                .show();
    }

    private void showRename(@NonNull WorldInfo world) {
        Context context = getContext();
        if (context == null) return;
        final EditText input = new EditText(context);
        input.setText(world.name);
        input.setSingleLine();
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mcl_world_rename_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim().replaceAll("[/\\\\]", "");
                    if (name.isEmpty() || getContext() == null) return;
                    File target = new File(world.dir.getParentFile(), name);
                    if (target.exists() || !world.dir.renameTo(target)) {
                        Toast.makeText(getContext(), R.string.content_import_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(getContext(), R.string.mcl_world_renamed, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDelete(@NonNull WorldInfo world) {
        Context context = getContext();
        if (context == null) return;
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mcl_world_delete_title)
                .setMessage(getString(R.string.mcl_world_delete_message, world.name))
                .setPositiveButton(R.string.global_delete, (dialog, which) -> CSLauncherApplication.sExecutorService.execute(() -> {
                    try {
                        FileUtils.deleteDirectory(world.dir);
                    } catch (Exception e) {
                        Tools.showErrorRemote(e);
                        return;
                    }
                    if (getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        if (getContext() == null) return;
                        Toast.makeText(getContext(), R.string.mcl_world_deleted, Toast.LENGTH_SHORT).show();
                        reload();
                    });
                }))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private void onDatapackPicked(@Nullable Uri uri) {
        if (uri == null || mDatapackTarget == null || getContext() == null) return;
        final WorldInfo world = mDatapackTarget;
        mDatapackTarget = null;
        final Context context = getContext();
        CSLauncherApplication.sExecutorService.execute(() -> {
            String name = queryDisplayName(context, uri);
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = "datapack.zip";
            File datapacks = WorldSaves.datapacksDir(world.dir);
            File destination = uniqueDestination(datapacks, name);
            boolean ok = copyToFile(context, uri, destination) && WorldSaves.looksLikeDatapack(destination);
            if (!ok) destination.delete();
            final boolean imported = ok;
            final String fileName = destination.getName();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) return;
                Toast.makeText(getContext(), imported
                        ? getString(R.string.mcl_world_datapack_installed, fileName)
                        : getString(R.string.mcl_world_datapack_invalid), Toast.LENGTH_LONG).show();
            });
        });
    }

    private boolean copyToFile(@NonNull Context context, @NonNull Uri source, @NonNull File destination) {
        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) return false;
            byte[] buffer = new byte[262144];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            return true;
        } catch (Exception e) {
            destination.delete();
            return false;
        }
    }

    @NonNull
    private static File uniqueDestination(@NonNull File dir, @NonNull String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        int i = 1;
        do {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    @NonNull
    private static File gameDirectory() {
        try {
            Instance instance = Instances.loadInstance(Instances.getSelectedInstanceKey());
            if (instance != null) return instance.getGameDirectory();
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW);
    }

    @NonNull
    private static String profileName() {
        try {
            String key = Instances.getSelectedInstanceKey();
            Instance instance = Instances.loadInstance(key);
            if (instance == null) return "";
            return instance.name != null && !instance.name.isEmpty() ? instance.name : key;
        } catch (Exception e) {
            return "";
        }
    }

    private final class WorldAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;

        WorldAdapter(@NonNull Context context) {
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return mWorlds.size();
        }

        @Override
        public WorldInfo getItem(int position) {
            return mWorlds.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : mInflater.inflate(R.layout.item_world_row, parent, false);
            WorldInfo world = getItem(position);
            ImageView icon = row.findViewById(R.id.world_icon);
            TextView name = row.findViewById(R.id.world_name);
            TextView detail = row.findViewById(R.id.world_detail);
            name.setText(world.name);
            String date = android.text.format.DateFormat.getMediumDateFormat(getContext())
                    .format(new java.util.Date(world.lastPlayed));
            detail.setText(getString(R.string.mcl_world_detail,
                    WorldSaves.formatSize(world.sizeBytes), date));
            if (world.iconFile != null) {
                try {
                    icon.setImageBitmap(BitmapFactory.decodeFile(world.iconFile.getAbsolutePath()));
                } catch (Exception e) {
                    icon.setImageResource(R.drawable.ic_lib_world);
                }
            } else {
                icon.setImageResource(R.drawable.ic_lib_world);
            }
            return row;
        }
    }
}
