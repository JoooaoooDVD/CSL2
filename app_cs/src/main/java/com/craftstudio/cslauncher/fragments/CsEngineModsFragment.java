package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.engine.horizon.HorizonProfile;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CS Engine mod browser: the Horizon mod registry (index.json) shown for a CS Engine profile
 * in place of the Modrinth/CurseForge search, which only knows Java mods.
 *
 * Three kinds of entry:
 *  - native-mod  (.hnmod): compiled into every CS Engine build. "Install" just turns the mod on
 *                for the profile by writing `mod.<id>:true` to horizon-mods.txt (the same file
 *                Options › Mods uses in game); ids the engine could not build are "Not available".
 *  - mod         (.hmod):  downloaded to horizon-mods/ (sha256 checked) and turned on the same way.
 *  - resourcepack (.zip):  downloaded to resourcepacks/ and selected in options.txt.
 */
public class CsEngineModsFragment extends Fragment {
    public static final String TAG = "CsEngineModsFragment";
    public static final String ARG_TAB = "tab"; // "mods" | "packs"

    private static final String INDEX_URL = "https://horizon-rust.github.io/horizon-mods/index.json";
    private static final String MODS_FILE = "horizon-mods.txt";

    private static final int KIND_NATIVE = 0, KIND_HMOD = 1, KIND_PACK = 2;

    private static final class Entry {
        String id, name, summary, author, version, icon, fileName, fileUrl, fileSha;
        int kind;
        boolean available = true; // native: compiled into this engine
        String unavailableWhy;
        boolean busy;
    }

    private final ExecutorService mIo = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> mIcons = new HashMap<>();
    private final Set<String> mIconLoading = new HashSet<>();

    private final List<Entry> mAll = new ArrayList<>();
    private final List<Entry> mShown = new ArrayList<>();
    private Adapter mAdapter;
    private boolean mPacksTab;
    private String mQuery = "";

    private File mGameHome;
    private Set<String> mBuilt = new HashSet<>();
    private Map<String, String> mSkipped = new HashMap<>();

    private View mState, mRetry;
    private View mProgress;
    private TextView mStateText, mTabMods, mTabPacks;
    private RecyclerView mList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cs_engine_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        mState = v.findViewById(R.id.cem_state);
        mRetry = v.findViewById(R.id.cem_retry);
        mProgress = v.findViewById(R.id.cem_progress);
        mStateText = v.findViewById(R.id.cem_state_text);
        mTabMods = v.findViewById(R.id.cem_tab_mods);
        mTabPacks = v.findViewById(R.id.cem_tab_packs);
        mList = v.findViewById(R.id.cem_list);
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);

        v.findViewById(R.id.cem_back).setOnClickListener(x -> getParentFragmentManager().popBackStack());
        mRetry.setOnClickListener(x -> load());
        mTabMods.setOnClickListener(x -> selectTab(false));
        mTabPacks.setOnClickListener(x -> selectTab(true));
        ((EditText) v.findViewById(R.id.cem_search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                refilter();
            }
        });
        Bundle args = getArguments();
        mPacksTab = args != null && "packs".equals(args.getString(ARG_TAB));
        applyTabLook();

        try {
            Instance instance = Instances.loadInstance(Instances.getSelectedInstanceKey());
            if (instance != null) mGameHome = HorizonProfile.gameHome(instance);
        } catch (Exception ignored) {}
        load();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mIo.shutdownNow();
    }

    private void selectTab(boolean packs) {
        if (mPacksTab == packs) return;
        mPacksTab = packs;
        applyTabLook();
        refilter();
    }

    private void applyTabLook() {
        mTabMods.setBackgroundResource(mPacksTab ? 0 : R.drawable.bg_mm_tab_indicator);
        mTabPacks.setBackgroundResource(mPacksTab ? R.drawable.bg_mm_tab_indicator : 0);
        mTabMods.setTextColor(getResources().getColor(mPacksTab ? R.color.hm_text_dim : R.color.hm_text));
        mTabPacks.setTextColor(getResources().getColor(mPacksTab ? R.color.hm_text : R.color.hm_text_dim));
    }

    // ---- loading -------------------------------------------------------------------------------

    private void load() {
        mState.setVisibility(View.VISIBLE);
        mProgress.setVisibility(View.VISIBLE);
        mRetry.setVisibility(View.GONE);
        mStateText.setText(R.string.hz_browser_loading);
        final Context app = requireContext().getApplicationContext();
        final File cache = new File(app.getCacheDir(), "horizon-index.json");
        mIo.execute(() -> {
            readBuiltList(app);
            String json = null;
            try {
                json = fetchText(INDEX_URL);
                writeText(cache, json);
            } catch (Exception e) {
                if (cache.isFile()) {
                    try { json = readText(cache); } catch (Exception ignored) {}
                }
            }
            final List<Entry> parsed = new ArrayList<>();
            boolean ok = false;
            if (json != null) {
                try { parse(json, parsed); ok = true; } catch (Exception ignored) {}
            }
            final boolean fine = ok;
            mMain.post(() -> {
                if (getView() == null) return;
                mAll.clear();
                mAll.addAll(parsed);
                if (!fine) {
                    mProgress.setVisibility(View.GONE);
                    mStateText.setText(R.string.hz_browser_offline);
                    mRetry.setVisibility(View.VISIBLE);
                    return;
                }
                refilter();
            });
        });
    }

    /** assets/horizon/cs-native-mods.json — written by the engine build: which ids are compiled in. */
    private void readBuiltList(Context app) {
        Set<String> built = new HashSet<>();
        Map<String, String> skipped = new HashMap<>();
        try {
            InputStream in = app.getAssets().open("horizon/cs-native-mods.json");
            String text = readStream(in);
            Object root = new org.json.JSONTokener(text).nextValue();
            JSONArray builtArr;
            if (root instanceof JSONObject) {
                builtArr = ((JSONObject) root).optJSONArray("built");
                JSONArray sk = ((JSONObject) root).optJSONArray("skipped");
                if (sk != null) for (int i = 0; i < sk.length(); i++) {
                    JSONObject o = sk.optJSONObject(i);
                    if (o != null) skipped.put(o.optString("id"), o.optString("reason"));
                }
            } else {
                builtArr = (JSONArray) root; // older engines: a bare list
            }
            if (builtArr != null) for (int i = 0; i < builtArr.length(); i++) {
                JSONObject o = builtArr.optJSONObject(i);
                if (o != null) built.add(o.optString("id"));
            }
        } catch (Exception ignored) {}
        mBuilt = built;
        mSkipped = skipped;
    }

    private void parse(String json, List<Entry> out) throws Exception {
        JSONObject root = new JSONObject(json);
        Set<String> revoked = new HashSet<>();
        JSONArray rv = root.optJSONArray("revoked");
        if (rv != null) for (int i = 0; i < rv.length(); i++) {
            JSONObject o = rv.optJSONObject(i);
            if (o != null) revoked.add(o.optString("id"));
        }
        JSONArray projects = root.getJSONArray("projects");
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            String id = p.optString("id");
            if (id.isEmpty() || revoked.contains(id)) continue;
            JSONArray versions = p.optJSONArray("versions");
            if (versions == null || versions.length() == 0) continue;
            JSONObject v = versions.getJSONObject(0);
            JSONObject file = v.optJSONObject("file");
            Entry e = new Entry();
            e.id = id;
            e.name = p.optString("name", id);
            e.summary = p.optString("summary", "");
            e.version = v.optString("version", "");
            e.icon = p.isNull("icon") ? null : p.optString("icon", null);
            JSONArray authors = p.optJSONArray("authors");
            if (authors != null && authors.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int a = 0; a < authors.length(); a++) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(authors.optString(a));
                }
                e.author = sb.toString();
            }
            if (file != null) {
                e.fileName = file.optString("name");
                e.fileUrl = file.optString("url");
                e.fileSha = file.optString("sha256");
            }
            String kind = p.optString("kind");
            if ("native-mod".equals(kind)) {
                e.kind = KIND_NATIVE;
                e.available = mBuilt.contains(id);
                if (!e.available) e.unavailableWhy = mSkipped.get(id);
            } else if ("resourcepack".equals(kind)) {
                e.kind = KIND_PACK;
                e.available = e.fileUrl != null && !e.fileUrl.isEmpty();
            } else {
                e.kind = KIND_HMOD;
                e.available = e.fileUrl != null && !e.fileUrl.isEmpty();
            }
            byId.put(id, e);
        }
        out.addAll(byId.values());
    }

    private void refilter() {
        mShown.clear();
        for (Entry e : mAll) {
            boolean pack = e.kind == KIND_PACK;
            if (pack != mPacksTab) continue;
            if (!mQuery.isEmpty()) {
                String hay = (e.name + " " + e.id + " " + e.summary + " " + (e.author == null ? "" : e.author))
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(mQuery)) continue;
            }
            mShown.add(e);
        }
        mAdapter.notifyDataSetChanged();
        mProgress.setVisibility(View.GONE);
        mRetry.setVisibility(View.GONE);
        if (mShown.isEmpty()) {
            mState.setVisibility(View.VISIBLE);
            mStateText.setText(R.string.hz_browser_empty);
        } else {
            mState.setVisibility(View.GONE);
        }
    }

    // ---- profile state -------------------------------------------------------------------------

    /** Parsed horizon-mods.txt: `mod.<id>:true|false`, order kept. */
    private LinkedHashMap<String, Boolean> readModsFile() {
        LinkedHashMap<String, Boolean> map = new LinkedHashMap<>();
        if (mGameHome == null) return map;
        File f = new File(mGameHome, MODS_FILE);
        if (!f.isFile()) return map;
        try {
            for (String line : readText(f).split("\n")) {
                line = line.trim();
                if (!line.startsWith("mod.")) continue;
                int c = line.lastIndexOf(':');
                if (c <= 4) continue;
                map.put(line.substring(4, c), "true".equals(line.substring(c + 1).trim()));
            }
        } catch (Exception ignored) {}
        return map;
    }

    private void writeModsFile(LinkedHashMap<String, Boolean> map) {
        if (mGameHome == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            sb.append("mod.").append(e.getKey()).append(':').append(e.getValue() ? "true" : "false").append('\n');
        }
        try {
            if (!mGameHome.isDirectory()) mGameHome.mkdirs();
            writeText(new File(mGameHome, MODS_FILE), sb.toString());
        } catch (Exception ignored) {}
    }

    private boolean isInstalled(Entry e) {
        if (mGameHome == null) return false;
        if (e.kind == KIND_PACK) {
            return e.fileName != null && new File(new File(mGameHome, "resourcepacks"), e.fileName).isFile();
        }
        Boolean on = readModsFile().get(e.id);
        if (e.kind == KIND_HMOD) {
            return e.fileName != null && new File(new File(mGameHome, "horizon-mods"), e.fileName).isFile()
                    && Boolean.TRUE.equals(on);
        }
        return Boolean.TRUE.equals(on);
    }

    private void setModOn(String id, boolean on) {
        LinkedHashMap<String, Boolean> map = readModsFile();
        map.put(id, on);
        writeModsFile(map);
    }

    private void onAction(Entry e, int position) {
        if (mGameHome == null || e.busy) return;
        if (!e.available) {
            String why = e.unavailableWhy;
            Toast.makeText(requireContext(), getString(R.string.hz_browser_unavailable_why, e.name)
                    + (why == null || why.isEmpty() ? "" : "\n\n" + why), Toast.LENGTH_LONG).show();
            return;
        }
        boolean installed = isInstalled(e);
        if (e.kind == KIND_NATIVE) {
            setModOn(e.id, !installed);
            Toast.makeText(requireContext(), getString(installed ? R.string.hz_browser_disabled_toast
                    : R.string.hz_browser_enabled_toast, e.name), Toast.LENGTH_SHORT).show();
            mAdapter.notifyItemChanged(position);
            return;
        }
        if (installed) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.hz_browser_remove_q, e.name))
                    .setPositiveButton(R.string.hz_browser_remove, (d, w) -> {
                        File dir = new File(mGameHome, e.kind == KIND_PACK ? "resourcepacks" : "horizon-mods");
                        new File(dir, e.fileName).delete();
                        if (e.kind == KIND_PACK) selectPack(e.fileName, false);
                        else {
                            LinkedHashMap<String, Boolean> map = readModsFile();
                            map.remove(e.id);
                            writeModsFile(map);
                        }
                        mAdapter.notifyItemChanged(position);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        e.busy = true;
        mAdapter.notifyItemChanged(position);
        final File dir = new File(mGameHome, e.kind == KIND_PACK ? "resourcepacks" : "horizon-mods");
        mIo.execute(() -> {
            String error = null;
            try {
                dir.mkdirs();
                File tmp = new File(dir, e.fileName + ".part");
                download(e.fileUrl, tmp);
                if (e.fileSha != null && !e.fileSha.isEmpty() && !sha256(tmp).equalsIgnoreCase(e.fileSha)) {
                    tmp.delete();
                    throw new Exception("checksum mismatch");
                }
                File dst = new File(dir, e.fileName);
                dst.delete();
                if (!tmp.renameTo(dst)) throw new Exception("could not save " + dst.getName());
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final String err = error;
            mMain.post(() -> {
                e.busy = false;
                if (getView() == null) return;
                if (err != null) {
                    Toast.makeText(requireContext(), getString(R.string.hz_browser_download_failed, err),
                            Toast.LENGTH_LONG).show();
                } else if (e.kind == KIND_PACK) {
                    selectPack(e.fileName, true);
                    Toast.makeText(requireContext(), getString(R.string.hz_browser_pack_toast, e.name),
                            Toast.LENGTH_LONG).show();
                } else {
                    setModOn(e.id, true);
                    Toast.makeText(requireContext(), getString(R.string.hz_browser_enabled_toast, e.name),
                            Toast.LENGTH_LONG).show();
                }
                mAdapter.notifyItemChanged(position);
            });
        });
    }

    /** options.txt `resourcePacks:["vanilla","file/<name>"]` — add or drop the pack. */
    private void selectPack(String fileName, boolean on) {
        if (mGameHome == null) return;
        File opts = new File(mGameHome, "options.txt");
        String key = "file/" + fileName;
        try {
            String text = opts.isFile() ? readText(opts) : "";
            StringBuilder out = new StringBuilder();
            boolean found = false;
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("resourcePacks:")) {
                    found = true;
                    line = "resourcePacks:" + editPackList(line.substring("resourcePacks:".length()), key, on);
                }
                out.append(line).append('\n');
            }
            String result = out.toString();
            if (text.isEmpty()) result = "";
            else if (!text.endsWith("\n")) result = result.substring(0, result.length() - 1);
            if (!found && on) result = result + (result.isEmpty() || result.endsWith("\n") ? "" : "\n")
                    + "resourcePacks:" + editPackList("[\"vanilla\"]", key, true) + "\n";
            if (!mGameHome.isDirectory()) mGameHome.mkdirs();
            writeText(opts, result);
        } catch (Exception ignored) {}
    }

    private static String editPackList(String jsonList, String key, boolean on) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jsonList.trim());
            for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
        } catch (Exception e) {
            ids.add("vanilla");
        }
        ids.remove(key);
        if (on) ids.add(key); // last = highest priority, like the in-game selector
        JSONArray outArr = new JSONArray();
        for (String id : ids) outArr.put(id);
        return outArr.toString();
    }

    // ---- adapter -------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cs_engine_mod, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(mShown.get(position));
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, kind, summary, meta, action;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.cem_icon);
            name = v.findViewById(R.id.cem_name);
            kind = v.findViewById(R.id.cem_kind);
            summary = v.findViewById(R.id.cem_summary);
            meta = v.findViewById(R.id.cem_meta);
            action = v.findViewById(R.id.cem_action);
            View tile = v.findViewById(R.id.cem_icon_tile);
            tile.setClipToOutline(true);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    float r = 12f * view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
        }

        void bind(final Entry e) {
            name.setText(e.name);
            summary.setText(e.summary);
            summary.setVisibility(e.summary.isEmpty() ? View.GONE : View.VISIBLE);
            meta.setText(e.author == null ? getString(R.string.hz_browser_version, e.version)
                    : getString(R.string.hz_browser_by, e.author, e.version));
            int kindRes = e.kind == KIND_NATIVE ? R.string.hz_browser_kind_builtin
                    : e.kind == KIND_PACK ? R.string.hz_browser_kind_pack : R.string.hz_browser_kind_mod;
            kind.setText(kindRes);

            boolean installed = e.available && isInstalled(e);
            if (e.busy) {
                action.setText(R.string.hz_browser_installing);
                action.setAlpha(0.6f);
            } else if (!e.available) {
                action.setText(R.string.hz_browser_unavailable);
                action.setAlpha(0.5f);
            } else {
                action.setText(installed ? R.string.hz_browser_installed : R.string.hz_browser_install);
                action.setAlpha(1f);
            }
            action.setBackgroundResource(installed && !e.busy ? R.drawable.bg_mm_state_on : R.drawable.bg_cs_pill_dark);
            action.setTextColor(getResources().getColor(installed && !e.busy ? R.color.hm_profile_active : R.color.hm_text));
            action.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) onAction(e, pos);
            });

            icon.setImageBitmap(null);
            icon.setTag(e.id);
            if (e.icon == null) {
                icon.setImageResource(R.drawable.ic_dock_download);
                icon.setAlpha(0.35f);
                return;
            }
            icon.setAlpha(1f);
            Bitmap cached = mIcons.get(e.icon);
            if (cached != null) {
                icon.setImageBitmap(cached);
            } else {
                loadIcon(e.icon, icon, e.id);
            }
        }
    }

    private void loadIcon(final String url, final ImageView target, final String tag) {
        if (!mIconLoading.add(url)) return;
        final File cacheDir = new File(requireContext().getCacheDir(), "horizon-icons");
        mIo.execute(() -> {
            Bitmap bmp = null;
            try {
                cacheDir.mkdirs();
                File f = new File(cacheDir, sha256Hex(url.getBytes(StandardCharsets.UTF_8)) + ".png");
                if (!f.isFile()) download(url, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (bmp != null && bmp.getWidth() > 256) {
                    bmp = Bitmap.createScaledBitmap(bmp, 256, 256 * bmp.getHeight() / bmp.getWidth(), true);
                }
            } catch (Exception ignored) {}
            final Bitmap result = bmp;
            mMain.post(() -> {
                mIconLoading.remove(url);
                if (result == null) return;
                mIcons.put(url, result);
                if (tag.equals(target.getTag())) target.setImageBitmap(result);
            });
        });
    }

    // ---- io helpers ----------------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "CS-Launcher-Plus (CS Engine mod browser)");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
            return readStream(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dst) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String readText(File f) throws Exception {
        return readStream(new FileInputStream(f));
    }

    private static void writeText(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new Exception("could not write " + f.getName());
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
