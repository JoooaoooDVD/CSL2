package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.motion.Motion;
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
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sandboxed file manager. It only ever shows what lives under {@link #ARG_ROOT} (a profile
 * game directory, app-private storage) so no storage permission is involved. Folders,
 * breadcrumbs, search, sorting, list/grid, multi-select with copy/move/zip/share/delete,
 * rename, new folder/file, import from device, text editing, image preview and zip extraction.
 */
public class FileManagerFragment extends Fragment {

    public static final String TAG = "FileManagerFragment";
    public static final String ARG_ROOT = "fm_root";
    public static final String ARG_TITLE = "fm_title";
    public static final String ARG_START = "fm_start";

    private static final long MAX_EDITABLE_BYTES = 512 * 1024;
    private static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;

    private File mRoot;
    private File mCurrent;
    private String mTitleName = "";

    private RecyclerView mList;
    private FileAdapter mAdapter;
    private GridLayoutManager mGridManager;
    private LinearLayoutManager mListManager;
    private LinearLayout mCrumbs;
    private View mCrumbScroll;
    private TextView mTitle, mSubtitle, mEmptyText, mSelCount, mPasteText;
    private View mEmpty, mProgress, mSearchFrame, mSelectionBar, mPasteBar;
    private EditText mSearch;
    private ImageButton mViewToggle;

    private final List<File> mEntries = new ArrayList<>();
    private final List<File> mVisible = new ArrayList<>();
    private final Set<File> mSelected = new LinkedHashSet<>();
    private final List<File> mClipboard = new ArrayList<>();
    private boolean mClipboardMove;
    private boolean mGrid;
    private int mSort = SORT_NAME;
    private boolean mSortDescending;
    private int mLoadGeneration;
    private final Deque<Integer> mScrollMemory = new ArrayDeque<>();

    private final ActivityResultLauncher<String[]> mImportPicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onImportPicked);

    private final OnBackPressedCallback mBackCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (!mSelected.isEmpty()) {
                clearSelection();
                return;
            }
            if (mSearchFrame.getVisibility() == View.VISIBLE) {
                toggleSearch(false);
                return;
            }
            if (!isRoot(mCurrent)) {
                navigateTo(mCurrent.getParentFile(), true);
                return;
            }
            setEnabled(false);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    };

    public FileManagerFragment() {
        super(R.layout.fragment_file_manager);
    }

    /** Builds the arguments for opening the manager at a root (and optionally a sub folder) */
    public static Bundle args(@NonNull File root, @NonNull String title, @Nullable File start) {
        Bundle bundle = new Bundle();
        bundle.putString(ARG_ROOT, root.getAbsolutePath());
        bundle.putString(ARG_TITLE, title);
        if (start != null) bundle.putString(ARG_START, start.getAbsolutePath());
        return bundle;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        String root = args == null ? null : args.getString(ARG_ROOT);
        mRoot = root == null ? new File(Tools.DIR_GAME_NEW) : new File(root);
        mTitleName = args == null || args.getString(ARG_TITLE) == null ? mRoot.getName() : args.getString(ARG_TITLE);
        String start = args == null ? null : args.getString(ARG_START);
        mCurrent = start != null && isInside(new File(start)) && new File(start).isDirectory() ? new File(start) : mRoot;
        if (!mRoot.isDirectory()) //noinspection ResultOfMethodCallIgnored
            mRoot.mkdirs();

        mList = view.findViewById(R.id.fm_list);
        mCrumbs = view.findViewById(R.id.fm_crumbs);
        mCrumbScroll = view.findViewById(R.id.fm_crumb_scroll);
        mTitle = view.findViewById(R.id.fm_title);
        mSubtitle = view.findViewById(R.id.fm_subtitle);
        mEmpty = view.findViewById(R.id.fm_empty);
        mEmptyText = view.findViewById(R.id.fm_empty_text);
        mProgress = view.findViewById(R.id.fm_progress);
        mSearchFrame = view.findViewById(R.id.fm_search_frame);
        mSearch = view.findViewById(R.id.fm_search);
        mSelectionBar = view.findViewById(R.id.fm_selection_bar);
        mSelCount = view.findViewById(R.id.fm_sel_count);
        mPasteBar = view.findViewById(R.id.fm_paste_bar);
        mPasteText = view.findViewById(R.id.fm_paste_text);
        mViewToggle = view.findViewById(R.id.fm_view_toggle);

        mTitle.setText(getString(R.string.fm_title, mTitleName));

        mAdapter = new FileAdapter();
        mListManager = new LinearLayoutManager(requireContext());
        mGridManager = new GridLayoutManager(requireContext(), 3);
        mList.setLayoutManager(mListManager);
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        mList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            if (width > 0) {
                int span = Math.max(2, (int) (width / (getResources().getDisplayMetrics().density * 118)));
                if (mGridManager.getSpanCount() != span) mGridManager.setSpanCount(span);
            }
        });

        view.findViewById(R.id.fm_back).setOnClickListener(v -> mBackCallback.handleOnBackPressed());
        view.findViewById(R.id.fm_search_toggle).setOnClickListener(v -> toggleSearch(mSearchFrame.getVisibility() != View.VISIBLE));
        view.findViewById(R.id.fm_sort).setOnClickListener(this::showSortMenu);
        mViewToggle.setOnClickListener(v -> setGrid(!mGrid));
        view.findViewById(R.id.fm_add).setOnClickListener(this::showAddMenu);
        view.findViewById(R.id.fm_sel_close).setOnClickListener(v -> clearSelection());
        view.findViewById(R.id.fm_sel_all).setOnClickListener(v -> selectAll());
        view.findViewById(R.id.fm_sel_copy).setOnClickListener(v -> stageClipboard(false));
        view.findViewById(R.id.fm_sel_move).setOnClickListener(v -> stageClipboard(true));
        view.findViewById(R.id.fm_sel_zip).setOnClickListener(v -> zipSelection());
        view.findViewById(R.id.fm_sel_share).setOnClickListener(v -> shareFiles(new ArrayList<>(mSelected)));
        view.findViewById(R.id.fm_sel_delete).setOnClickListener(v -> confirmDelete(new ArrayList<>(mSelected)));
        view.findViewById(R.id.fm_paste_here).setOnClickListener(v -> pasteClipboard());
        view.findViewById(R.id.fm_paste_cancel).setOnClickListener(v -> clearClipboard());

        mSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mBackCallback);

        Motion.riseIn(view.findViewById(R.id.fm_header), 0, 8);
        navigateTo(mCurrent, false);
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private boolean isRoot(@Nullable File file) {
        return file == null || file.getAbsolutePath().equals(mRoot.getAbsolutePath());
    }

    private boolean isInside(@NonNull File file) {
        try {
            String path = file.getCanonicalPath();
            String root = mRoot.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private void navigateTo(@Nullable File dir, boolean back) {
        if (dir == null || !isInside(dir)) dir = mRoot;
        if (!back && mList.getLayoutManager() != null && !dir.equals(mCurrent)) {
            View first = mList.getChildAt(0);
            mScrollMemory.push(first == null ? 0 : mList.getChildAdapterPosition(first));
        }
        mCurrent = dir;
        clearSelection();
        if (mSearch.length() > 0) mSearch.setText("");
        buildCrumbs();
        reload(back);
    }

    private void reload(boolean back) {
        final File dir = mCurrent;
        final int generation = ++mLoadGeneration;
        mProgress.setVisibility(View.VISIBLE);
        CSLauncherApplication.sExecutorService.execute(() -> {
            File[] files = dir.listFiles();
            final List<File> entries = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
            long total = 0;
            int folders = 0, plain = 0;
            for (File f : entries) {
                if (f.isDirectory()) folders++;
                else {
                    plain++;
                    total += f.length();
                }
            }
            final int fFolders = folders, fPlain = plain;
            final long fTotal = total;
            Tools.runOnUiThread(() -> {
                if (generation != mLoadGeneration || getView() == null) return;
                mProgress.setVisibility(View.GONE);
                mEntries.clear();
                mEntries.addAll(entries);
                sortEntries();
                mSubtitle.setText(getString(R.string.fm_summary, fFolders, fPlain, WorldSaves.formatSize(fTotal)));
                applyFilter();
                mList.scheduleLayoutAnimation();
                if (back && !mScrollMemory.isEmpty()) {
                    Integer position = mScrollMemory.pop();
                    mList.post(() -> mList.scrollToPosition(Math.max(0, position)));
                }
                Motion.fadeIn(mList, 0, Motion.SHORT);
            });
        });
    }

    private void buildCrumbs() {
        mCrumbs.removeAllViews();
        List<File> chain = new ArrayList<>();
        File walker = mCurrent;
        while (walker != null && !isRoot(walker)) {
            chain.add(0, walker);
            walker = walker.getParentFile();
        }
        chain.add(0, mRoot);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < chain.size(); i++) {
            final File target = chain.get(i);
            TextView crumb = (TextView) inflater.inflate(R.layout.item_fm_crumb, mCrumbs, false);
            crumb.setText(i == 0 ? mTitleName : target.getName());
            crumb.setSelected(i == chain.size() - 1);
            crumb.setTextColor(i == chain.size() - 1
                    ? androidx.core.content.ContextCompat.getColor(requireContext(), R.color.lib_update_text)
                    : com.craftstudio.cslauncher.utils.ThemeColors.get(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
            crumb.setOnClickListener(v -> {
                if (!target.equals(mCurrent)) navigateTo(target, true);
            });
            mCrumbs.addView(crumb);
            if (i < chain.size() - 1) {
                TextView sep = new TextView(requireContext());
                sep.setText("›");
                sep.setTextSize(14);
                sep.setPadding(6, 0, 6, 0);
                sep.setTextColor(com.craftstudio.cslauncher.utils.ThemeColors.get(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
                mCrumbs.addView(sep);
            }
        }
        Motion.popChildren(mCrumbs, 0, 25, 0.85f);
        mCrumbScroll.post(() -> ((android.widget.HorizontalScrollView) mCrumbScroll).fullScroll(View.FOCUS_RIGHT));
    }

    // ─── Sorting / filtering / view ──────────────────────────────────────────

    private void sortEntries() {
        Collections.sort(mEntries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            int result;
            switch (mSort) {
                case SORT_DATE: result = Long.compare(a.lastModified(), b.lastModified()); break;
                case SORT_SIZE: result = Long.compare(a.length(), b.length()); break;
                case SORT_TYPE: result = extension(a).compareToIgnoreCase(extension(b)); break;
                default: result = 0;
            }
            if (result == 0) result = a.getName().compareToIgnoreCase(b.getName());
            return mSortDescending ? -result : result;
        });
    }

    private void applyFilter() {
        String query = mSearch.getText() == null ? "" : mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        mVisible.clear();
        for (File f : mEntries) {
            if (query.isEmpty() || f.getName().toLowerCase(Locale.ROOT).contains(query)) mVisible.add(f);
        }
        mAdapter.notifyDataSetChanged();
        boolean empty = mVisible.isEmpty();
        mEmptyText.setText(query.isEmpty() ? R.string.fm_empty : R.string.fm_no_results);
        if (empty && mEmpty.getVisibility() != View.VISIBLE) {
            mEmpty.setVisibility(View.VISIBLE);
            Motion.popIn(mEmpty, 0, 0.9f);
        } else if (!empty) {
            mEmpty.setVisibility(View.GONE);
        }
    }

    private void toggleSearch(boolean show) {
        if (show) {
            mSearchFrame.setVisibility(View.VISIBLE);
            Motion.riseIn(mSearchFrame, 0, -6);
            mSearch.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mSearch, 0);
        } else {
            mSearch.setText("");
            Motion.hide(mSearchFrame, true);
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
        }
    }

    private void setGrid(boolean grid) {
        if (mGrid == grid) return;
        mGrid = grid;
        mViewToggle.setImageResource(grid ? R.drawable.ic_fm_list : R.drawable.ic_fm_grid);
        Motion.bounce(mViewToggle);
        mList.setLayoutManager(grid ? mGridManager : mListManager);
        mAdapter.notifyDataSetChanged();
        mList.setAlpha(0f);
        mList.animate().alpha(1f).setDuration(Motion.MEDIUM).start();
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, SORT_NAME, 0, R.string.fm_sort_name);
        menu.getMenu().add(0, SORT_DATE, 1, R.string.fm_sort_date);
        menu.getMenu().add(0, SORT_SIZE, 2, R.string.fm_sort_size);
        menu.getMenu().add(0, SORT_TYPE, 3, R.string.fm_sort_type);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == mSort) mSortDescending = !mSortDescending;
            else {
                mSort = item.getItemId();
                mSortDescending = mSort == SORT_DATE || mSort == SORT_SIZE;
            }
            sortEntries();
            applyFilter();
            mList.scheduleLayoutAnimation();
            return true;
        });
        menu.show();
    }

    // ─── Selection ───────────────────────────────────────────────────────────

    private void toggleSelected(File file) {
        if (!mSelected.remove(file)) mSelected.add(file);
        updateSelectionBar();
        int index = mVisible.indexOf(file);
        if (index >= 0) mAdapter.notifyItemChanged(index);
    }

    private void selectAll() {
        if (mSelected.size() == mVisible.size()) mSelected.clear();
        else mSelected.addAll(mVisible);
        updateSelectionBar();
        mAdapter.notifyDataSetChanged();
    }

    private void clearSelection() {
        if (mSelected.isEmpty()) return;
        mSelected.clear();
        updateSelectionBar();
        mAdapter.notifyDataSetChanged();
    }

    private void updateSelectionBar() {
        boolean show = !mSelected.isEmpty();
        if (show) {
            mSelCount.setText(getString(R.string.fm_selected, mSelected.size()));
            if (mSelectionBar.getVisibility() != View.VISIBLE) {
                mSelectionBar.setVisibility(View.VISIBLE);
                mSelectionBar.setTranslationY(Motion.dp(mSelectionBar, 40));
                mSelectionBar.setAlpha(0f);
                mSelectionBar.animate().translationY(0f).alpha(1f).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.ENTER).start();
            }
        } else if (mSelectionBar.getVisibility() == View.VISIBLE) {
            mSelectionBar.animate().translationY(Motion.dp(mSelectionBar, 40)).alpha(0f)
                    .setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                    .withEndAction(() -> mSelectionBar.setVisibility(View.GONE)).start();
        }
    }

    // ─── Clipboard (copy / move) ─────────────────────────────────────────────

    private void stageClipboard(boolean move) {
        if (mSelected.isEmpty()) return;
        mClipboard.clear();
        mClipboard.addAll(mSelected);
        mClipboardMove = move;
        clearSelection();
        mPasteText.setText(getString(move ? R.string.fm_clip_move : R.string.fm_clip_copy, mClipboard.size()));
        if (mPasteBar.getVisibility() != View.VISIBLE) {
            mPasteBar.setVisibility(View.VISIBLE);
            Motion.riseIn(mPasteBar, 0, -8);
        } else {
            Motion.pulse(mPasteBar);
        }
    }

    private void clearClipboard() {
        mClipboard.clear();
        Motion.hide(mPasteBar, true);
    }

    private void pasteClipboard() {
        if (mClipboard.isEmpty()) return;
        final List<File> sources = new ArrayList<>(mClipboard);
        final boolean move = mClipboardMove;
        final File destination = mCurrent;
        clearClipboard();
        runOperation(() -> {
            for (File source : sources) {
                if (!source.exists()) continue;
                File target = uniqueName(destination, source.getName());
                if (destination.getAbsolutePath().startsWith(source.getAbsolutePath() + File.separator)) {
                    throw new IOException("Cannot paste a folder into itself");
                }
                if (move) {
                    if (!source.renameTo(target)) {
                        if (source.isDirectory()) FileUtils.moveDirectory(source, target);
                        else FileUtils.moveFile(source, target);
                    }
                } else {
                    if (source.isDirectory()) FileUtils.copyDirectory(source, target);
                    else FileUtils.copyFile(source, target);
                }
            }
        });
    }

    // ─── Create / import ─────────────────────────────────────────────────────

    private void showAddMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.fm_new_folder);
        menu.getMenu().add(0, 2, 1, R.string.fm_new_file);
        menu.getMenu().add(0, 3, 2, R.string.fm_import_file);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: promptName(getString(R.string.fm_new_folder), "", name -> {
                    File dir = new File(mCurrent, name);
                    if (dir.exists()) { toast(R.string.fm_exists); return; }
                    if (dir.mkdirs()) reload(false); else toast(getString(R.string.fm_failed, name));
                }); break;
                case 2: promptName(getString(R.string.fm_new_file), ".txt", name -> {
                    File file = new File(mCurrent, name);
                    if (file.exists()) { toast(R.string.fm_exists); return; }
                    try {
                        if (file.createNewFile()) {
                            reload(false);
                            openTextEditor(file);
                        }
                    } catch (IOException e) {
                        toast(getString(R.string.fm_failed, String.valueOf(e.getMessage())));
                    }
                }); break;
                case 3: mImportPicker.launch(new String[]{"*/*"}); break;
            }
            return true;
        });
        menu.show();
    }

    private void onImportPicked(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty() || getContext() == null) return;
        final Context context = requireContext().getApplicationContext();
        final File destination = mCurrent;
        runOperation(() -> {
            for (Uri uri : uris) {
                String name = queryDisplayName(context, uri);
                if (TextUtils.isEmpty(name)) name = "imported_" + System.currentTimeMillis();
                File target = uniqueName(destination, name);
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new IOException("Cannot open " + name);
                    byte[] buffer = new byte[262144];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
            }
        });
    }

    private interface NameCallback { void onName(String name); }

    private void promptName(String title, String preset, NameCallback callback) {
        Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.fm_name_hint);
        input.setText(preset);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (!preset.isEmpty()) input.setSelection(0, preset.startsWith(".") ? 0 : preset.lastIndexOf('.') > 0 ? preset.lastIndexOf('.') : preset.length());
        int pad = (int) Motion.dp(input, 20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(context);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                        toast(R.string.fm_invalid_name);
                        return;
                    }
                    callback.onName(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    // ─── Item actions ────────────────────────────────────────────────────────

    private void openEntry(File file) {
        if (file.isDirectory()) {
            navigateTo(file, false);
            return;
        }
        String ext = extension(file).toLowerCase(Locale.ROOT);
        if (isImage(ext)) {
            previewImage(file);
        } else if (isText(ext) || file.length() == 0) {
            openTextEditor(file);
        } else if (ext.equals("zip") || ext.equals("jar") || ext.equals("mrpack")) {
            showItemMenu(file, null);
        } else {
            try {
                Tools.openPath(requireContext(), file, false);
            } catch (Exception e) {
                showItemMenu(file, null);
            }
        }
    }

    private void showItemMenu(File file, @Nullable View anchor) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String ext = extension(file).toLowerCase(Locale.ROOT);
        if (!file.isDirectory() && (isText(ext) || file.length() == 0)) {
            labels.add(getString(R.string.fm_edit_text));
            actions.add(() -> openTextEditor(file));
        }
        if (!file.isDirectory() && isImage(ext)) {
            labels.add(getString(R.string.fm_preview_image));
            actions.add(() -> previewImage(file));
        }
        if (!file.isDirectory() && (ext.equals("zip") || ext.equals("jar") || ext.equals("mrpack"))) {
            labels.add(getString(R.string.fm_unzip));
            actions.add(() -> extractZip(file));
        }
        labels.add(getString(R.string.fm_rename));
        actions.add(() -> renameEntry(file));
        labels.add(getString(R.string.fm_copy));
        actions.add(() -> { mSelected.clear(); mSelected.add(file); stageClipboard(false); });
        labels.add(getString(R.string.fm_move));
        actions.add(() -> { mSelected.clear(); mSelected.add(file); stageClipboard(true); });
        labels.add(getString(R.string.fm_zip));
        actions.add(() -> zipFiles(Collections.singletonList(file)));
        labels.add(getString(R.string.fm_share));
        actions.add(() -> shareFiles(Collections.singletonList(file)));
        if (!file.isDirectory()) {
            labels.add(getString(R.string.fm_open_with));
            actions.add(() -> {
                try { Tools.openPath(requireContext(), file, false); } catch (Exception e) { toast(getString(R.string.fm_failed, String.valueOf(e.getMessage()))); }
            });
        }
        labels.add(getString(R.string.fm_details));
        actions.add(() -> showDetails(file));
        labels.add(getString(R.string.global_delete));
        actions.add(() -> confirmDelete(Collections.singletonList(file)));

        if (anchor != null) {
            PopupMenu menu = new PopupMenu(requireContext(), anchor);
            for (int i = 0; i < labels.size(); i++) menu.getMenu().add(0, i, i, labels.get(i));
            menu.setOnMenuItemClickListener(item -> { actions.get(item.getItemId()).run(); return true; });
            menu.show();
        } else {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(file.getName())
                    .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                    .show();
        }
    }

    private void renameEntry(File file) {
        promptName(getString(R.string.fm_rename), file.getName(), name -> {
            File target = new File(file.getParentFile(), name);
            if (target.exists()) { toast(R.string.fm_exists); return; }
            if (file.renameTo(target)) reload(false); else toast(getString(R.string.fm_failed, name));
        });
    }

    private void confirmDelete(List<File> files) {
        if (files.isEmpty()) return;
        String title = files.size() == 1 ? getString(R.string.fm_delete_one, files.get(0).getName())
                : getString(R.string.fm_delete_title, files.size());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(R.string.fm_delete_message)
                .setPositiveButton(R.string.global_delete, (d, w) -> {
                    clearSelection();
                    runOperation(() -> {
                        for (File file : files) {
                            if (file.isDirectory()) FileUtils.deleteDirectory(file);
                            else if (!file.delete() && file.exists()) throw new IOException("Could not delete " + file.getName());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDetails(File file) {
        CSLauncherApplication.sExecutorService.execute(() -> {
            long size = file.isDirectory() ? WorldSaves.sizeOf(file) : file.length();
            String contains;
            if (file.isDirectory()) {
                int[] counts = countTree(file);
                contains = counts[0] + " folders, " + counts[1] + " files";
            } else {
                contains = extension(file).isEmpty() ? getString(R.string.fm_type_folder) : extension(file).toUpperCase(Locale.ROOT) + " file";
            }
            String relative = file.getAbsolutePath().substring(Math.min(file.getAbsolutePath().length(), mRoot.getAbsolutePath().length()));
            if (relative.isEmpty()) relative = "/";
            String message = getString(R.string.fm_details_message, relative, WorldSaves.formatSize(size),
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(file.lastModified())), contains);
            Tools.runOnUiThread(() -> {
                if (getContext() == null) return;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(file.getName())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private static int[] countTree(File dir) {
        int[] counts = new int[2];
        File[] files = dir.listFiles();
        if (files == null) return counts;
        for (File f : files) {
            if (f.isDirectory()) {
                counts[0]++;
                int[] sub = countTree(f);
                counts[0] += sub[0];
                counts[1] += sub[1];
            } else counts[1]++;
        }
        return counts;
    }

    private void shareFiles(List<File> files) {
        if (files.isEmpty() || getContext() == null) return;
        boolean anyDir = false;
        for (File f : files) if (f.isDirectory()) anyDir = true;
        if (anyDir || files.size() > 1) {
            // Folders / batches are shared as one zip
            File out = uniqueName(requireContext().getCacheDir(), (files.size() == 1 ? files.get(0).getName() : mCurrent.getName()) + ".zip");
            runOperation(() -> zipTo(files, out), () -> shareSingle(out));
        } else {
            shareSingle(files.get(0));
        }
    }

    private void shareSingle(File file) {
        try {
            Tools.openPath(requireContext(), file, true);
        } catch (Exception e) {
            try {
                Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
                Intent intent = new Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, file.getName()));
            } catch (Exception inner) {
                toast(getString(R.string.fm_failed, String.valueOf(inner.getMessage())));
            }
        }
    }

    // ─── Zip ─────────────────────────────────────────────────────────────────

    private void zipSelection() {
        List<File> files = new ArrayList<>(mSelected);
        clearSelection();
        zipFiles(files);
    }

    private void zipFiles(List<File> files) {
        if (files.isEmpty()) return;
        String base = files.size() == 1 ? stripExtension(files.get(0).getName()) : mCurrent.getName();
        File out = uniqueName(mCurrent, base + ".zip");
        runOperation(() -> zipTo(files, out));
    }

    private static void zipTo(List<File> files, File out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            for (File file : files) {
                if (file.equals(out)) continue;
                addToZip(zip, file, file.isDirectory() ? file.getName() + "/" : file.getName(), out);
            }
        }
    }

    private static void addToZip(ZipOutputStream zip, File file, String entryName, File skip) throws IOException {
        if (file.equals(skip)) return;
        if (file.isDirectory()) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.closeEntry();
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) {
                addToZip(zip, child, entryName + child.getName() + (child.isDirectory() ? "/" : ""), skip);
            }
            return;
        }
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[262144];
            int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private void extractZip(File zipFile) {
        final File destination = uniqueName(mCurrent, stripExtension(zipFile.getName()));
        runOperation(() -> {
            if (!destination.mkdirs()) throw new IOException("Could not create " + destination.getName());
            String canonicalRoot = destination.getCanonicalPath();
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                ZipEntry entry;
                byte[] buffer = new byte[262144];
                while ((entry = in.getNextEntry()) != null) {
                    File target = new File(destination, entry.getName());
                    if (!target.getCanonicalPath().startsWith(canonicalRoot + File.separator)) continue; // zip slip
                    if (entry.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        target.mkdirs();
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs failed");
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                }
            }
        });
    }

    // ─── Viewers ─────────────────────────────────────────────────────────────

    private void openTextEditor(File file) {
        if (file.length() > MAX_EDITABLE_BYTES) {
            toast(getString(R.string.fm_too_large, WorldSaves.formatSize(file.length())));
            return;
        }
        CSLauncherApplication.sExecutorService.execute(() -> {
            String content;
            try {
                content = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                content = null;
            }
            final String text = content;
            Tools.runOnUiThread(() -> {
                if (getContext() == null) return;
                if (text == null) { toast(getString(R.string.fm_failed, file.getName())); return; }
                View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_fm_text_editor, null, false);
                EditText editor = dialogView.findViewById(R.id.fm_editor);
                TextView meta = dialogView.findViewById(R.id.fm_editor_meta);
                editor.setText(text);
                meta.setText(WorldSaves.formatSize(file.length()) + " • " + text.split("\n", -1).length + " lines");
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(file.getName())
                        .setView(dialogView)
                        .setPositiveButton(R.string.fm_save, (d, w) -> {
                            final String newText = editor.getText().toString();
                            runOperation(() -> FileUtils.writeStringToFile(file, newText, StandardCharsets.UTF_8), () -> toast(R.string.fm_saved));
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void previewImage(File file) {
        CSLauncherApplication.sExecutorService.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2;
            options.inSampleSize = sample;
            final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            final String meta = bounds.outWidth + " × " + bounds.outHeight + " • " + WorldSaves.formatSize(file.length());
            Tools.runOnUiThread(() -> {
                if (getContext() == null) return;
                if (bitmap == null) { toast(getString(R.string.fm_failed, file.getName())); return; }
                View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_fm_image_preview, null, false);
                ImageView image = dialogView.findViewById(R.id.fm_preview_image);
                TextView metaView = dialogView.findViewById(R.id.fm_preview_meta);
                image.setImageBitmap(bitmap);
                metaView.setText(meta);
                Motion.popIn(image, 60, 0.9f);
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(file.getName())
                        .setView(dialogView)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.fm_share, (d, w) -> shareSingle(file))
                        .show();
            });
        });
    }

    // ─── Background operations ───────────────────────────────────────────────

    private interface Operation { void run() throws Exception; }

    private void runOperation(Operation operation) {
        runOperation(operation, null);
    }

    private void runOperation(Operation operation, @Nullable Runnable onSuccess) {
        mProgress.setVisibility(View.VISIBLE);
        CSLauncherApplication.sExecutorService.execute(() -> {
            Exception failure = null;
            try {
                operation.run();
            } catch (Exception e) {
                failure = e;
            }
            final Exception error = failure;
            Tools.runOnUiThread(() -> {
                if (getView() == null) return;
                mProgress.setVisibility(View.GONE);
                if (error != null) toast(getString(R.string.fm_failed, String.valueOf(error.getMessage())));
                else if (onSuccess != null) onSuccess.run();
                reload(false);
            });
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void toast(int res) {
        if (getContext() != null) Toast.makeText(getContext(), res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        if (getContext() != null) Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

    @NonNull
    static File uniqueName(@NonNull File dir, @NonNull String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String base = stripExtension(name);
        String ext = name.substring(base.length());
        int i = 1;
        do {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    static String extension(File file) {
        if (file.isDirectory()) return "";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "json", "json5", "toml", "properties", "cfg", "conf", "ini", "yml", "yaml", "log", "md",
            "mcmeta", "lang", "csv", "xml", "sh", "bat", "js", "zs", "snbt", "fsh", "vsh", "glsl", "options", "lock"));
    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "webp", "gif", "bmp"));
    private static final Set<String> CODE_EXT = new HashSet<>(Arrays.asList("json", "json5", "toml", "yml", "yaml", "xml", "js", "zs", "snbt", "fsh", "vsh", "glsl"));

    private static boolean isText(String ext) { return TEXT_EXT.contains(ext); }
    private static boolean isImage(String ext) { return IMAGE_EXT.contains(ext); }

    private static int iconFor(File file, String ext) {
        if (file.isDirectory()) {
            // Well-known game folders get their own icon
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            File parent = file.getParentFile();
            String parentName = parent == null ? "" : parent.getName().toLowerCase(java.util.Locale.ROOT);
            switch (name) {
                case "mods": return R.drawable.ic_nav_mods;
                case "resourcepacks": case "texturepacks": return R.drawable.ic_pe_image;
                case "shaderpacks": return R.drawable.ic_pe_layers;
                case "saves": return R.drawable.ic_lib_cube;
                case "datapacks": return R.drawable.ic_fm_code;
                case "config": return R.drawable.ic_pe_gear;
                case "screenshots": return R.drawable.ic_pe_image_filled;
                case "logs": case "crash-reports": return R.drawable.ic_pe_terminal;
                case "skins": return R.drawable.ic_pe_person;
                case "servers": return R.drawable.ic_nav_servers;
                case "versions": return R.drawable.ic_nav_versions;
            }
            if (parentName.equals("saves") && new File(file, "level.dat").exists()) return R.drawable.ic_lib_cube;
            return R.drawable.ic_pe_folder;
        }
        String parentName = file.getParentFile() == null ? "" : file.getParentFile().getName().toLowerCase(java.util.Locale.ROOT);
        if (isImage(ext)) return parentName.equals("skins") ? R.drawable.ic_pe_person : R.drawable.ic_lib_image;
        if (ext.equals("jar")) return R.drawable.ic_nav_mods;                   // a mod
        if (ext.equals("mrpack")) return R.drawable.ic_nav_mods;                // a modpack
        if (ext.equals("zip")) {
            if (parentName.equals("resourcepacks") || parentName.equals("texturepacks")) return R.drawable.ic_pe_image;
            if (parentName.equals("shaderpacks")) return R.drawable.ic_pe_layers;
            if (parentName.equals("datapacks")) return R.drawable.ic_fm_code;
            return R.drawable.ic_fm_zip;
        }
        if (ext.equals("dat") || ext.equals("dat_old") || ext.equals("mca") || ext.equals("nbt") || ext.equals("mcr")) return R.drawable.ic_lib_cube;
        if (ext.equals("log") || ext.equals("gz")) return R.drawable.ic_pe_terminal;
        if (ext.equals("jks") || ext.equals("options") ) return R.drawable.ic_pe_gear;
        if (CODE_EXT.contains(ext)) return R.drawable.ic_fm_code;
        if (isText(ext)) return R.drawable.ic_fm_text;
        return R.drawable.ic_fm_file;
    }

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    private final class FileAdapter extends RecyclerView.Adapter<FileHolder> {
        private final DateFormat mDateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        @Override
        public int getItemViewType(int position) {
            return mGrid ? 1 : 0;
        }

        @NonNull
        @Override
        public FileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(viewType == 1 ? R.layout.item_file_grid : R.layout.item_file_row, parent, false);
            return new FileHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileHolder holder, int position) {
            final File file = mVisible.get(position);
            String ext = extension(file).toLowerCase(Locale.ROOT);
            holder.name.setText(file.getName());
            if (file.isDirectory()) {
                String[] children = file.list();
                holder.detail.setText(getString(R.string.fm_items, children == null ? 0 : children.length));
            } else {
                holder.detail.setText(WorldSaves.formatSize(file.length()) + " • " + mDateFormat.format(new Date(file.lastModified())));
            }
            holder.icon.setImageResource(iconFor(file, ext));
            holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    com.craftstudio.cslauncher.utils.ThemeColors.get(holder.itemView.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant)));
            int pad = (int) Motion.dp(holder.icon, mGrid ? 12 : 9);
            holder.icon.setPadding(pad, pad, pad, pad);
            holder.icon.setTag(file);
            if (isImage(ext) && file.length() < 4 * 1024 * 1024) {
                loadThumbnail(holder.icon, file);
            }
            boolean selected = mSelected.contains(file);
            holder.itemView.setSelected(selected);
            holder.check.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) {
                holder.check.setScaleX(0.6f);
                holder.check.setScaleY(0.6f);
                holder.check.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SHORT).setInterpolator(Motion.SPRING).start();
            }
            holder.itemView.setOnClickListener(v -> {
                if (!mSelected.isEmpty()) toggleSelected(file);
                else {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction(() ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
                    openEntry(file);
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                toggleSelected(file);
                return true;
            });
            holder.more.setOnClickListener(v -> showItemMenu(file, v));
        }

        private void loadThumbnail(ImageView target, File file) {
            CSLauncherApplication.sExecutorService.execute(() -> {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                int sample = 1;
                while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2;
                options.inSampleSize = sample;
                final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) return;
                Tools.runOnUiThread(() -> {
                    if (file.equals(target.getTag())) {
                        target.setImageTintList(null);
                        target.setPadding(0, 0, 0, 0);
                        target.setImageBitmap(bitmap);
                        target.setAlpha(0f);
                        target.animate().alpha(1f).setDuration(Motion.SHORT).start();
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return mVisible.size();
        }
    }

    private static final class FileHolder extends RecyclerView.ViewHolder {
        final ImageView icon, check;
        final TextView name, detail;
        final View more;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_icon);
            check = itemView.findViewById(R.id.file_check);
            name = itemView.findViewById(R.id.file_name);
            detail = itemView.findViewById(R.id.file_detail);
            more = itemView.findViewById(R.id.file_more);
        }
    }
}
