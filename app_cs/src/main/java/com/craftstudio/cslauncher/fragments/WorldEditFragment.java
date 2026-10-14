package com.craftstudio.cslauncher.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.InstalledContentIndex;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.craftstudio.cslauncher.worlds.WorldDetails;
import com.craftstudio.cslauncher.worlds.WorldSaves;

import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Editor for one world: icon, display name, facts read from level.dat, data packs
 * (library + zip import + enable/disable/remove) and maintenance (files, backup, duplicate, delete).
 */
public class WorldEditFragment extends Fragment {

    public static final String TAG = "WorldEditFragment";
    public static final String ARG_WORLD_DIR = "world_dir";

    private File mWorldDir;
    private WorldDetails mDetails;
    private long mSizeBytes;

    private ImageView mIcon;
    private EditText mName;
    private TextView mSaveName, mResetIcon, mVersionBadge, mDpCount, mSubtitle;
    private View mUnreadable, mDpEmpty;
    private GridLayout mFacts;
    private LinearLayout mDpList;

    private final ActivityResultLauncher<String> mIconPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onIconPicked);
    private final ActivityResultLauncher<String[]> mDatapackPicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onDatapacksPicked);

    public WorldEditFragment() {
        super(R.layout.fragment_world_edit);
    }

    public static Bundle args(@NonNull File worldDir) {
        Bundle bundle = new Bundle();
        bundle.putString(ARG_WORLD_DIR, worldDir.getAbsolutePath());
        return bundle;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        String path = args == null ? null : args.getString(ARG_WORLD_DIR);
        if (path == null || !new File(path).isDirectory()) {
            getParentFragmentManager().popBackStack();
            return;
        }
        mWorldDir = new File(path);

        mIcon = view.findViewById(R.id.we_icon);
        mName = view.findViewById(R.id.we_name);
        mSaveName = view.findViewById(R.id.we_save_name);
        mResetIcon = view.findViewById(R.id.we_reset_icon);
        mVersionBadge = view.findViewById(R.id.we_version_badge);
        mDpCount = view.findViewById(R.id.we_dp_count);
        mSubtitle = view.findViewById(R.id.we_subtitle);
        mUnreadable = view.findViewById(R.id.we_unreadable);
        mDpEmpty = view.findViewById(R.id.we_dp_empty);
        mFacts = view.findViewById(R.id.we_facts_grid);
        mDpList = view.findViewById(R.id.we_dp_list);

        mSubtitle.setText("saves/" + mWorldDir.getName());

        view.findViewById(R.id.we_back).setOnClickListener(v -> getParentFragmentManager().popBackStack());
        View.OnClickListener openFiles = v -> openFileManager();
        view.findViewById(R.id.we_files).setOnClickListener(openFiles);
        view.findViewById(R.id.we_action_files).setOnClickListener(openFiles);
        view.findViewById(R.id.we_icon_frame).setOnClickListener(v -> mIconPicker.launch("image/*"));
        mResetIcon.setOnClickListener(v -> resetIcon());
        mSaveName.setOnClickListener(v -> saveName());
        view.findViewById(R.id.we_dp_import).setOnClickListener(v -> mDatapackPicker.launch(new String[]{"application/zip", "application/octet-stream"}));
        view.findViewById(R.id.we_dp_browse).setOnClickListener(v -> openDatapackLibrary());
        view.findViewById(R.id.we_action_backup).setOnClickListener(v -> backupWorld());
        view.findViewById(R.id.we_action_duplicate).setOnClickListener(v -> duplicateWorld());
        view.findViewById(R.id.we_action_delete).setOnClickListener(v -> deleteWorld());
        for (int id : new int[]{R.id.we_dp_browse, R.id.we_dp_import, R.id.we_save_name, R.id.we_icon_frame}) {
            CSAnimationEngine.attachPressFeedback(view.findViewById(id));
        }

        mName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean dirty = mDetails != null && !s.toString().trim().equals(mDetails.levelName);
                setSaveEnabled(dirty);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        mName.setOnEditorActionListener((v, actionId, event) -> {
            if (mSaveName.isEnabled()) saveName();
            return false;
        });

        Motion.cascadeChildren(view.findViewById(R.id.we_content), 40, 60, 14);
        reload();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from the library: the data pack list may have changed
        if (mDetails != null) refreshDatapacks();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        InstalledContentIndex.setDatapackWorld(null);
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private void reload() {
        final File dir = mWorldDir;
        CSLauncherApplication.sExecutorService.execute(() -> {
            final WorldDetails details = WorldDetails.load(dir);
            final long size = WorldSaves.sizeOf(dir);
            final Bitmap icon = loadIcon(dir);
            Tools.runOnUiThread(() -> {
                if (getView() == null) return;
                mDetails = details;
                mSizeBytes = size;
                bind(icon);
            });
        });
    }

    @Nullable
    private static Bitmap loadIcon(File dir) {
        File icon = new File(dir, "icon.png");
        if (!icon.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(icon.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private void bind(@Nullable Bitmap icon) {
        applyIcon(icon);
        mName.setText(mDetails.levelName);
        setSaveEnabled(false);
        mUnreadable.setVisibility(mDetails.readable ? View.GONE : View.VISIBLE);
        mVersionBadge.setText(mDetails.versionName != null ? mDetails.versionName
                : mDetails.dataVersion > 0 ? "DV " + mDetails.dataVersion : "?");
        buildFacts();
        refreshDatapacks();
    }

    private void applyIcon(@Nullable Bitmap icon) {
        if (icon != null) {
            mIcon.setImageBitmap(icon);
            mIcon.setPadding(0, 0, 0, 0);
            mIcon.setImageTintList(null);
            mResetIcon.setVisibility(View.VISIBLE);
        } else {
            mIcon.setImageResource(R.drawable.ic_lib_world);
            int pad = (int) Motion.dp(mIcon, 22);
            mIcon.setPadding(pad, pad, pad, pad);
            mIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    com.craftstudio.cslauncher.utils.ThemeColors.get(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant)));
            mResetIcon.setVisibility(View.GONE);
        }
        CSAnimationEngine.animateImageSwap(mIcon);
    }

    private void setSaveEnabled(boolean enabled) {
        if (mSaveName.isEnabled() == enabled) return;
        mSaveName.setEnabled(enabled);
        mSaveName.animate().alpha(enabled ? 1f : 0.45f).setDuration(Motion.SHORT).start();
        if (enabled) Motion.bounce(mSaveName);
    }

    private void buildFacts() {
        mFacts.removeAllViews();
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        if (mDetails.readable) {
            addFact(R.drawable.ic_lib_cube, R.string.we_fact_version,
                    mDetails.versionName != null ? mDetails.versionName : "Unknown", null);
            addFact(R.drawable.ic_pe_gamepad, R.string.we_fact_gamemode, mDetails.gameModeLabel(), null);
            addFact(R.drawable.ic_lib_warning, R.string.we_fact_difficulty, mDetails.difficultyLabel(), null);
            addFact(R.drawable.ic_pe_terminal, R.string.we_fact_cheats,
                    getString(mDetails.allowCommands ? R.string.we_on : R.string.we_off), null);
            if (mDetails.hasSeed) {
                final String seed = String.valueOf(mDetails.seed);
                addFact(R.drawable.ic_lib_star, R.string.we_fact_seed, seed, v -> {
                    ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("seed", seed));
                    Motion.pulse(v);
                    toast(R.string.we_seed_copied);
                });
            }
            addFact(R.drawable.ic_lib_clock, R.string.we_fact_playtime, mDetails.playTimeLabel(), null);
            addFact(R.drawable.ic_lib_sun, R.string.we_fact_day, String.valueOf(mDetails.dayNumber()), null);
            addFact(R.drawable.ic_lib_world, R.string.we_fact_type, mDetails.worldTypeLabel(), null);
            if (mDetails.hasSpawn) {
                addFact(R.drawable.ic_pe_person, R.string.we_fact_spawn,
                        mDetails.spawnX + ", " + mDetails.spawnY + ", " + mDetails.spawnZ, null);
            }
            addFact(R.drawable.ic_pe_layers, R.string.we_fact_modded,
                    getString(mDetails.wasModded ? R.string.we_yes : R.string.we_no), null);
        }
        long lastPlayed = mDetails.lastPlayedMs > 0 ? mDetails.lastPlayedMs : mWorldDir.lastModified();
        addFact(R.drawable.ic_lib_clock, R.string.we_fact_last_played, dateFormat.format(new Date(lastPlayed)), null);
        addFact(R.drawable.ic_lib_download, R.string.we_fact_size, WorldSaves.formatSize(mSizeBytes), null);
        addFact(R.drawable.ic_pe_folder, R.string.we_fact_folder, mWorldDir.getName(), v -> openFileManager());
        Motion.popChildren(mFacts, 60, 30, 0.92f);
    }

    private void addFact(@DrawableRes int icon, int label, String value, @Nullable View.OnClickListener onClick) {
        View tile = LayoutInflater.from(requireContext()).inflate(R.layout.item_world_fact, mFacts, false);
        ((ImageView) tile.findViewById(R.id.fact_icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.fact_label)).setText(label);
        ((TextView) tile.findViewById(R.id.fact_value)).setText(value);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int margin = (int) Motion.dp(tile, 4);
        params.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(params);
        if (onClick != null) {
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setOnClickListener(onClick);
            CSAnimationEngine.attachPressFeedback(tile);
        }
        mFacts.addView(tile);
    }

    // ─── Name & icon ─────────────────────────────────────────────────────────

    private void saveName() {
        final String name = mName.getText().toString().trim();
        if (name.isEmpty() || mDetails == null) return;
        final File dir = mWorldDir;
        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                WorldDetails.rename(dir, name);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            Tools.runOnUiThread(() -> {
                if (getView() == null) return;
                if (success) {
                    mDetails.levelName = name;
                    setSaveEnabled(false);
                    toast(R.string.we_name_saved);
                } else {
                    Motion.shake(mName);
                    toast(R.string.we_name_failed);
                }
            });
        });
    }

    private void onIconPicked(@Nullable Uri uri) {
        if (uri == null || getContext() == null) return;
        final Context context = requireContext().getApplicationContext();
        final File dir = mWorldDir;
        CSLauncherApplication.sExecutorService.execute(() -> {
            Bitmap result = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                Bitmap source = BitmapFactory.decodeStream(in, null, options);
                if (source != null) {
                    WorldDetails.saveIcon(dir, source);
                    result = loadIcon(dir);
                    source.recycle();
                }
            } catch (Exception ignored) {}
            final Bitmap icon = result;
            Tools.runOnUiThread(() -> {
                if (getView() == null) return;
                if (icon != null) {
                    applyIcon(icon);
                    Motion.bounce(mIcon);
                    toast(R.string.we_icon_saved);
                } else {
                    toast(R.string.we_icon_failed);
                }
            });
        });
    }

    private void resetIcon() {
        File icon = new File(mWorldDir, "icon.png");
        //noinspection ResultOfMethodCallIgnored
        icon.delete();
        applyIcon(null);
    }

    // ─── Data packs ──────────────────────────────────────────────────────────

    private void refreshDatapacks() {
        File datapacks = new File(mWorldDir, "datapacks");
        File[] files = datapacks.listFiles(f -> f.isDirectory()
                || f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                || f.getName().toLowerCase(Locale.ROOT).endsWith(".zip.disabled"));
        List<File> packs = new ArrayList<>();
        if (files != null) for (File f : files) packs.add(f);
        packs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        mDpList.removeAllViews();
        mDpCount.setText(getString(R.string.we_dp_count, packs.size()));
        mDpEmpty.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final File pack : packs) {
            View row = inflater.inflate(R.layout.item_world_datapack, mDpList, false);
            TextView name = row.findViewById(R.id.dp_name);
            TextView detail = row.findViewById(R.id.dp_detail);
            MaterialSwitch enabled = row.findViewById(R.id.dp_enabled);
            final boolean isDisabled = pack.getName().endsWith(".disabled");
            String display = isDisabled ? pack.getName().substring(0, pack.getName().length() - ".disabled".length()) : pack.getName();
            name.setText(display);
            long size = pack.isDirectory() ? WorldSaves.sizeOf(pack) : pack.length();
            detail.setText(WorldSaves.formatSize(size) + " • " + getString(isDisabled ? R.string.we_dp_disabled : R.string.we_dp_enabled)
                    + (pack.isDirectory() ? " • " + getString(R.string.we_dp_folder) : ""));
            enabled.setChecked(!isDisabled);
            enabled.setEnabled(!pack.isDirectory());
            enabled.setOnCheckedChangeListener((button, checked) -> {
                File target = new File(pack.getParentFile(), checked ? display : display + ".disabled");
                if (!pack.renameTo(target)) {
                    button.setChecked(!checked);
                    return;
                }
                refreshDatapacks();
            });
            row.findViewById(R.id.dp_delete).setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.we_dp_delete_title)
                    .setMessage(getString(R.string.we_dp_delete_message, display))
                    .setPositiveButton(R.string.global_delete, (d, w) -> {
                        try {
                            if (pack.isDirectory()) FileUtils.deleteDirectory(pack);
                            else //noinspection ResultOfMethodCallIgnored
                                pack.delete();
                        } catch (IOException ignored) {}
                        row.animate().alpha(0f).translationX(Motion.dp(row, 40)).setDuration(Motion.SHORT)
                                .withEndAction(this::refreshDatapacks).start();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            mDpList.addView(row);
        }
        Motion.cascadeChildren(mDpList, 0, 35, 8);
    }

    private void openDatapackLibrary() {
        InstalledContentIndex.setDatapackWorld(mWorldDir);
        Bundle args = new Bundle();
        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, ContentType.DATAPACK.name());
        Tools.swapFragment(requireActivity(), ModsSearchFragment.class, ModsSearchFragment.TAG, args);
    }

    private void onDatapacksPicked(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty() || getContext() == null) return;
        final Context context = requireContext().getApplicationContext();
        final File datapacks = WorldSaves.datapacksDir(mWorldDir);
        CSLauncherApplication.sExecutorService.execute(() -> {
            int imported = 0, rejected = 0;
            for (Uri uri : uris) {
                String name = queryDisplayName(context, uri);
                if (TextUtils.isEmpty(name)) name = "datapack.zip";
                if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name + ".zip";
                File destination = FileManagerFragment.uniqueName(datapacks, name);
                boolean ok = false;
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(destination)) {
                    if (in != null) {
                        byte[] buffer = new byte[262144];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        ok = true;
                    }
                } catch (Exception ignored) {}
                if (ok && !WorldSaves.looksLikeDatapack(destination)) ok = false;
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    destination.delete();
                    rejected++;
                } else imported++;
            }
            final int fImported = imported, fRejected = rejected;
            Tools.runOnUiThread(() -> {
                if (getView() == null) return;
                refreshDatapacks();
                if (fRejected > 0) toast(R.string.mcl_world_datapack_invalid);
                else if (fImported > 0) Toast.makeText(requireContext(), getString(R.string.mcl_world_datapack_installed, fImported + " pack(s)"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ─── Maintenance ─────────────────────────────────────────────────────────

    private void openFileManager() {
        File root = gameDirectory();
        Tools.swapFragment(requireActivity(), FileManagerFragment.class, FileManagerFragment.TAG,
                FileManagerFragment.args(root, profileName(), mWorldDir));
    }

    private void backupWorld() {
        toast(R.string.we_backup_working);
        final File dir = mWorldDir;
        final File out = FileManagerFragment.uniqueName(requireContext().getCacheDir(), dir.getName() + "-backup.zip");
        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean ok;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                zipDir(zip, dir, dir.getName() + "/");
                ok = true;
            } catch (IOException e) {
                ok = false;
            }
            final boolean success = ok;
            Tools.runOnUiThread(() -> {
                if (getContext() == null) return;
                if (success) Tools.openPath(requireContext(), out, true);
                else toast(R.string.content_import_failed);
            });
        });
    }

    private static void zipDir(ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(prefix + child.getName() + "/"));
                zip.closeEntry();
                zipDir(zip, child, prefix + child.getName() + "/");
            } else {
                ZipEntry entry = new ZipEntry(prefix + child.getName());
                entry.setTime(child.lastModified());
                zip.putNextEntry(entry);
                try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buffer = new byte[262144];
                    int read;
                    while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private void duplicateWorld() {
        final File source = mWorldDir;
        final File target = FileManagerFragment.uniqueName(source.getParentFile(), source.getName() + " copy");
        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                FileUtils.copyDirectory(source, target);
                try {
                    WorldDetails.rename(target, (mDetails != null ? mDetails.levelName : source.getName()) + " copy");
                } catch (Exception ignored) {}
                ok = true;
            } catch (IOException e) {
                ok = false;
            }
            final boolean success = ok;
            Tools.runOnUiThread(() -> {
                if (getContext() == null) return;
                toast(success ? R.string.we_duplicated : R.string.content_import_failed);
            });
        });
    }

    private void deleteWorld() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mcl_world_delete_title)
                .setMessage(getString(R.string.mcl_world_delete_message, mDetails != null ? mDetails.levelName : mWorldDir.getName()))
                .setPositiveButton(R.string.global_delete, (dialog, which) -> CSLauncherApplication.sExecutorService.execute(() -> {
                    try {
                        FileUtils.deleteDirectory(mWorldDir);
                    } catch (Exception e) {
                        Tools.showErrorRemote(e);
                        return;
                    }
                    Tools.runOnUiThread(() -> {
                        if (getContext() == null) return;
                        toast(R.string.mcl_world_deleted);
                        getParentFragmentManager().popBackStack();
                    });
                }))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void toast(int res) {
        if (getContext() != null) Toast.makeText(getContext(), res, Toast.LENGTH_SHORT).show();
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

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return null;
    }
}
