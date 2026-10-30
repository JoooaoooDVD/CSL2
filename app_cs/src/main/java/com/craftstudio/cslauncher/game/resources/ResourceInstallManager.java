package com.craftstudio.cslauncher.game.resources;

import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.modding.modpacks.api.ModrinthApi;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Process-wide download/install coordinator for the in-game resource browser.
 * <p>
 * Lives independently of the popup so a download keeps running (and finishes installing)
 * when the user closes the popup; the popup simply re-attaches as a listener and reads the
 * current {@link Task} states when it is reopened. No service, no notifications: just a small
 * executor plus main-thread callbacks.
 * <p>
 * Pipeline per task: resolve Modrinth versions → pick the best file for the profile →
 * free-space check → stream to a temp file in the cache dir (progress + cancel) → sha1 check →
 * zip structure validation (pack.mcmeta / shaders folder) → move into
 * {@code <gameDir>/resourcepacks|shaderpacks} (asking before replacing an existing file).
 */
public final class ResourceInstallManager {
    private static final String TAG = "ResourceInstall";
    private static final long PROGRESS_INTERVAL_MS = 120;
    private static final long STORAGE_MARGIN_BYTES = 16L * 1024 * 1024;

    public enum Status {
        IDLE, RESOLVING, DOWNLOADING, VERIFYING, INSTALLING, AWAITING_REPLACE, INSTALLED, FAILED, CANCELLED;

        public boolean isActive() {
            return this == RESOLVING || this == DOWNLOADING || this == VERIFYING || this == INSTALLING || this == AWAITING_REPLACE;
        }
    }

    /** Observable state of one install. All fields are only written by the manager. */
    public static final class Task {
        @NonNull public final String key;
        @NonNull public final ModItem item;
        @NonNull public final ContentType type;
        @NonNull final ResourceProfileContext profile;
        @NonNull public volatile Status status = Status.IDLE;
        public volatile long bytesDone;
        /** -1 while unknown (no Content-Length). */
        public volatile long bytesTotal = -1;
        public volatile int errorRes;
        @Nullable public volatile String errorArg;
        /** Name of the file that already exists, while status == AWAITING_REPLACE. */
        @Nullable public volatile String conflictName;
        @Nullable public volatile File installedFile;

        volatile boolean cancelled;
        volatile boolean replaceConfirmed;
        @Nullable volatile HttpURLConnection connection;
        @Nullable File tempFile;
        @Nullable File target;
        long lastNotify;

        Task(@NonNull String key, @NonNull ModItem item, @NonNull ContentType type, @NonNull ResourceProfileContext profile) {
            this.key = key;
            this.item = item;
            this.type = type;
            this.profile = profile;
        }

        /** 0..1000 or -1 when indeterminate. */
        public int progressPermille() {
            long total = bytesTotal;
            if (total <= 0) return -1;
            return (int) Math.min(1000, bytesDone * 1000 / total);
        }
    }

    public interface Listener {
        @MainThread
        void onTaskChanged(@NonNull Task task);
    }

    private static volatile ResourceInstallManager sInstance;

    public static ResourceInstallManager get() {
        if (sInstance == null) {
            synchronized (ResourceInstallManager.class) {
                if (sInstance == null) sInstance = new ResourceInstallManager();
            }
        }
        return sInstance;
    }

    private final ExecutorService mExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "resource-install");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ExecutorService mQueryExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "resource-index"));
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, Task> mTasks = Collections.synchronizedMap(new HashMap<>());
    private final List<Listener> mListeners = new ArrayList<>();
    private final ModrinthApi mApi = new ModrinthApi();
    @Nullable private volatile com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi mCurseforgeApi;
    @Nullable private volatile android.content.Context mAppContext;

    /** Remembers the application context (needed to resolve the CurseForge key) */
    public void attach(@NonNull android.content.Context context) {
        mAppContext = context.getApplicationContext();
    }

    /** CurseForge needs the launcher's key; resolved lazily from the application context */
    @Nullable
    private com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi curseforge() {
        com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi api = mCurseforgeApi;
        if (api == null) {
            try {
                android.content.Context ctx = mAppContext;
                String key = ctx == null ? null : com.craftstudio.cslauncher.prefs.LauncherPreferences.resolveCurseforgeApiKey(ctx);
                if (key != null && !key.trim().isEmpty()) mCurseforgeApi = api = new com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi(key);
            } catch (Throwable t) {
                Log.w(TAG, "curseforge api unavailable", t);
            }
        }
        return api;
    }

    private ResourceInstallManager() {
        ((ThreadPoolExecutor) mExecutor).allowCoreThreadTimeOut(true);
    }

    @NonNull
    public static String keyFor(@NonNull ContentType type, @NonNull ModItem item) {
        // CurseForge ids are numeric and live in their own namespace
        String source = item.apiSource == com.craftstudio.cslauncher.modding.modpacks.models.Constants.SOURCE_CURSEFORGE ? "cf:" : "";
        return type.modrinthType + ":" + source + item.id;
    }

    // ------------------------------------------------------------------ listeners

    @MainThread
    public void addListener(@NonNull Listener l) {
        if (!mListeners.contains(l)) mListeners.add(l);
    }

    @MainThread
    public void removeListener(@NonNull Listener l) {
        mListeners.remove(l);
    }

    private void notifyChanged(@NonNull Task task) {
        mMain.post(() -> {
            for (Listener l : new ArrayList<>(mListeners)) l.onTaskChanged(task);
        });
    }

    private void notifyProgress(@NonNull Task task) {
        long now = SystemClock.uptimeMillis();
        if (now - task.lastNotify < PROGRESS_INTERVAL_MS) return;
        task.lastNotify = now;
        notifyChanged(task);
    }

    // ------------------------------------------------------------------ queries

    @Nullable
    public Task getTask(@NonNull String key) {
        return mTasks.get(key);
    }

    /** Snapshot of the tasks that are currently in flight (for "Installing…" state on reopen). */
    @NonNull
    public List<Task> activeTasks() {
        List<Task> out = new ArrayList<>();
        synchronized (mTasks) {
            for (Task t : mTasks.values()) if (t.status.isActive()) out.add(t);
        }
        return out;
    }

    public interface InstalledCallback {
        @MainThread
        void onResult(@NonNull Map<String, File> installed);
    }

    /** Checks the real files on disk for the given profile and reports on the main thread. */
    public void queryInstalled(@NonNull ResourceProfileContext profile, @NonNull InstalledCallback callback) {
        mQueryExecutor.execute(() -> {
            Map<String, File> result = profile.gameDirectory == null
                    ? new HashMap<>() : ResourceInstallIndex.presentFiles(profile.gameDirectory);
            mMain.post(() -> callback.onResult(result));
        });
    }

    // ------------------------------------------------------------------ control

    /**
     * Starts (or restarts after a failure) the install for this item. Returns the task, or null
     * when the profile is unusable. A task that is already active is returned untouched so the
     * same resource is never downloaded twice.
     */
    @MainThread
    @Nullable
    public Task install(@NonNull ModItem item, @NonNull ContentType type, @NonNull ResourceProfileContext profile) {
        if (!profile.isUsable()) return null;
        String key = keyFor(type, item);
        Task existing = mTasks.get(key);
        if (existing != null && existing.status.isActive()) return existing;
        Task task = new Task(key, item, type, profile);
        task.status = Status.RESOLVING;
        mTasks.put(key, task);
        notifyChanged(task);
        mExecutor.execute(() -> run(task));
        return task;
    }

    @MainThread
    public void cancel(@NonNull String key) {
        Task task = mTasks.get(key);
        if (task == null || !task.status.isActive()) return;
        task.cancelled = true;
        HttpURLConnection c = task.connection;
        if (c != null) {
            // disconnect() from a background thread: it blocks on some stacks
            mQueryExecutor.execute(c::disconnect);
        }
        if (task.status == Status.AWAITING_REPLACE) {
            // The worker returned already; finish the cancellation here
            File temp = task.tempFile;
            if (temp != null) mQueryExecutor.execute(() -> deleteQuietly(temp));
            fail(task, Status.CANCELLED, R.string.rb_err_cancelled, null);
        }
    }

    /** Resumes a task parked in AWAITING_REPLACE, overwriting the existing file. */
    @MainThread
    public void confirmReplace(@NonNull String key) {
        Task task = mTasks.get(key);
        if (task == null || task.status != Status.AWAITING_REPLACE) return;
        task.replaceConfirmed = true;
        task.status = Status.INSTALLING;
        notifyChanged(task);
        mExecutor.execute(() -> finishInstall(task));
    }

    /** Resumes a task parked in AWAITING_REPLACE, keeping the file that is already there. */
    @MainThread
    public void keepExisting(@NonNull String key) {
        Task task = mTasks.get(key);
        if (task == null || task.status != Status.AWAITING_REPLACE) return;
        File temp = task.tempFile;
        File target = task.target;
        mExecutor.execute(() -> {
            if (temp != null) deleteQuietly(temp);
            if (target != null && target.isFile() && task.profile.gameDirectory != null) {
                ResourceInstallIndex.record(task.profile.gameDirectory, task.key,
                        task.type.folderName + "/" + target.getName());
            }
            task.installedFile = target;
            task.status = Status.INSTALLED;
            notifyChanged(task);
        });
    }

    // ------------------------------------------------------------------ worker

    private void run(@NonNull Task task) {
        File temp = null;
        try {
            // 1. Resolve the project's version list and pick the best file
            ModDetail detail = resolveDetail(task);
            if (task.cancelled) throw new CancelledException();
            if (detail == null || detail.versionUrls == null || detail.versionUrls.length == 0) {
                fail(task, Status.FAILED, R.string.rb_err_no_version, null);
                return;
            }
            int idx = pickVersion(detail, task.profile);
            String url = detail.versionUrls[idx];
            if (url == null || url.isEmpty()) {
                // Pick any other file that is downloadable before giving up
                for (int i = 0; i < detail.versionUrls.length && (url == null || url.isEmpty()); i++) {
                    if (detail.versionUrls[i] != null && !detail.versionUrls[i].isEmpty()) { idx = i; url = detail.versionUrls[i]; }
                }
            }
            if (url == null || url.isEmpty()) {
                fail(task, Status.FAILED, task.item.isRestricted || task.item.apiSource == com.craftstudio.cslauncher.modding.modpacks.models.Constants.SOURCE_CURSEFORGE
                        ? R.string.rb_err_restricted : R.string.rb_err_no_version, null);
                return;
            }
            String fileName = detail.versionFileNames != null && idx < detail.versionFileNames.length
                    ? detail.versionFileNames[idx] : null;
            if (fileName == null || fileName.isEmpty()) fileName = fileNameFromUrl(url);
            fileName = sanitize(fileName);
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) fileName += ".zip";
            String sha1 = detail.versionHashes != null && idx < detail.versionHashes.length ? detail.versionHashes[idx] : null;
            long expectedSize = detail.versionFileSizes != null && idx < detail.versionFileSizes.length
                    ? detail.versionFileSizes[idx] : -1;

            File folder = task.profile.folderFor(task.type);
            if (folder == null) {
                fail(task, Status.FAILED, R.string.rb_err_profile, null);
                return;
            }
            if (!folder.isDirectory() && !folder.mkdirs()) {
                fail(task, Status.FAILED, R.string.rb_err_move, null);
                return;
            }
            task.target = new File(folder, fileName);

            // 2. Free space (known size up front, re-checked once Content-Length arrives)
            if (expectedSize > 0 && !hasFreeSpace(folder, expectedSize)) {
                fail(task, Status.FAILED, R.string.rb_err_storage, formatBytes(expectedSize));
                return;
            }

            // 3. Download to a temp file in the app cache
            File tempDir = new File(Tools.DIR_CACHE, "resource_downloads");
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) throw new IOException("cache dir");
            temp = new File(tempDir, task.key.replace(':', '_').replace('/', '_') + ".part");
            task.tempFile = temp;
            task.bytesTotal = expectedSize > 0 ? expectedSize : -1;
            task.status = Status.DOWNLOADING;
            notifyChanged(task);
            download(task, url, temp, folder);
            if (task.cancelled) throw new CancelledException();

            // 4. Verify
            task.status = Status.VERIFYING;
            notifyChanged(task);
            if (sha1 != null && !sha1.isEmpty()) {
                String actual = sha1Of(temp);
                if (!sha1.equalsIgnoreCase(actual)) {
                    fail(task, Status.FAILED, R.string.rb_err_checksum, null);
                    deleteQuietly(temp);
                    return;
                }
            }
            int invalidRes = validateArchive(temp, task.type);
            if (invalidRes != 0) {
                fail(task, Status.FAILED, invalidRes, null);
                deleteQuietly(temp);
                return;
            }
            if (task.cancelled) throw new CancelledException();

            // 5. Conflict? park and ask. Otherwise move into place.
            if (task.target.exists() && !task.replaceConfirmed) {
                task.conflictName = task.target.getName();
                task.status = Status.AWAITING_REPLACE;
                notifyChanged(task);
                return; // temp is kept; confirmReplace()/keepExisting()/cancel() continue
            }
            finishInstall(task);
        } catch (CancelledException e) {
            if (temp != null) deleteQuietly(temp);
            fail(task, Status.CANCELLED, R.string.rb_err_cancelled, null);
        } catch (HandledException e) {
            // fail() already ran with a specific message
        } catch (Exception e) {
            Log.w(TAG, "Install failed for " + task.key, e);
            if (temp != null) deleteQuietly(temp);
            if (task.cancelled) fail(task, Status.CANCELLED, R.string.rb_err_cancelled, null);
            else fail(task, Status.FAILED, R.string.rb_err_download_failed, null);
        }
    }

    /** Last step: move temp → target, record, mark installed. Runs on a worker. */
    private void finishInstall(@NonNull Task task) {
        File temp = task.tempFile;
        File target = task.target;
        try {
            if (temp == null || target == null || !temp.isFile()) throw new IOException("temp file missing");
            if (task.cancelled) throw new CancelledException();
            task.status = Status.INSTALLING;
            notifyChanged(task);
            if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
            if (!temp.renameTo(target)) {
                copy(temp, target);
                deleteQuietly(temp);
            }
            if (!target.isFile() || target.length() == 0) throw new IOException("target missing after move");
            if (task.profile.gameDirectory != null) {
                ResourceInstallIndex.record(task.profile.gameDirectory, task.key,
                        task.type.folderName + "/" + target.getName());
            }
            task.installedFile = target;
            task.status = Status.INSTALLED;
            notifyChanged(task);
        } catch (CancelledException e) {
            if (temp != null) deleteQuietly(temp);
            fail(task, Status.CANCELLED, R.string.rb_err_cancelled, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not move the download into place", e);
            if (temp != null) deleteQuietly(temp);
            fail(task, Status.FAILED, R.string.rb_err_move, null);
        }
    }

    private void fail(@NonNull Task task, @NonNull Status status, int errorRes, @Nullable String arg) {
        task.errorRes = errorRes;
        task.errorArg = arg;
        task.status = status;
        notifyChanged(task);
    }

    // ------------------------------------------------------------------ steps

    @Nullable
    private ModDetail resolveDetail(@NonNull Task task) {
        String mc = task.profile.mcVersion;
        ModDetail detail = null;
        boolean cf = task.item.apiSource == com.craftstudio.cslauncher.modding.modpacks.models.Constants.SOURCE_CURSEFORGE;
        com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi cfApi = cf ? curseforge() : null;
        if (cf && cfApi == null) return null;
        if (mc != null && !mc.isEmpty()) {
            try {
                detail = cf ? cfApi.getModDetails(task.item, mc, null) : mApi.getModDetails(task.item, mc, null);
            } catch (Exception e) { Log.w(TAG, "version lookup", e); }
        }
        if (detail == null) {
            // Nothing published for this exact version: fall back to the newest file overall
            try {
                detail = cf ? cfApi.getModDetails(task.item) : mApi.getModDetails(task.item);
            } catch (Exception e) { Log.w(TAG, "version lookup", e); }
        }
        return detail;
    }

    /** Prefers a release that lists the profile's exact version, then same 1.x family, then newest. */
    private static int pickVersion(@NonNull ModDetail detail, @NonNull ResourceProfileContext profile) {
        int n = detail.versionUrls.length;
        String mc = profile.mcVersion;
        String family = ResourceProfileContext.majorMinor(mc);
        int exact = -1, familyMatch = -1, release = -1;
        for (int i = 0; i < n; i++) {
            String[] gv = detail.versionGameVersions != null && i < detail.versionGameVersions.length ? detail.versionGameVersions[i] : null;
            boolean isRelease = detail.versionTypes == null || i >= detail.versionTypes.length
                    || detail.versionTypes[i] == null || "release".equals(detail.versionTypes[i]);
            if (gv != null && mc != null) {
                for (String v : gv) {
                    if (mc.equals(v)) { if (exact < 0 || (isRelease && !isReleaseAt(detail, exact))) exact = i; }
                    else if (family != null && family.equals(ResourceProfileContext.majorMinor(v)) && familyMatch < 0) familyMatch = i;
                }
            }
            if (isRelease && release < 0) release = i;
        }
        if (exact >= 0) return exact;
        if (familyMatch >= 0) return familyMatch;
        return release >= 0 ? release : 0;
    }

    private static boolean isReleaseAt(ModDetail d, int i) {
        return d.versionTypes == null || i >= d.versionTypes.length || d.versionTypes[i] == null || "release".equals(d.versionTypes[i]);
    }

    private void download(@NonNull Task task, @NonNull String url, @NonNull File temp, @NonNull File targetFolder) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", Tools.APP_NAME);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        task.connection = conn;
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            long length;
            try { length = Long.parseLong(conn.getHeaderField("Content-Length")); } catch (Exception e) { length = -1; }
            if (length > 0) {
                task.bytesTotal = length;
                if (!hasFreeSpace(targetFolder, length)) {
                    throw new StorageException(length);
                }
            }
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (task.cancelled) throw new CancelledException();
                    out.write(buf, 0, read);
                    done += read;
                    task.bytesDone = done;
                    notifyProgress(task);
                }
                out.flush();
            }
            task.bytesDone = temp.length();
            if (task.bytesTotal > 0 && temp.length() != task.bytesTotal) throw new IOException("short read");
        } catch (StorageException e) {
            fail(task, Status.FAILED, R.string.rb_err_storage, formatBytes(e.needed));
            deleteQuietly(temp);
            throw new HandledException();
        } finally {
            task.connection = null;
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static boolean hasFreeSpace(@NonNull File dir, long needed) {
        try {
            File probe = dir;
            while (probe != null && !probe.exists()) probe = probe.getParentFile();
            if (probe == null) return true;
            StatFs stat = new StatFs(probe.getAbsolutePath());
            return stat.getAvailableBytes() > needed + STORAGE_MARGIN_BYTES;
        } catch (Exception e) {
            return true; // don't block installs on a stat failure
        }
    }

    /** @return 0 when valid, otherwise the string resource explaining why not. */
    static int validateArchive(@NonNull File file, @NonNull ContentType type) {
        try (ZipFile zip = new ZipFile(file)) {
            boolean hasMcmeta = false, hasShaders = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned++ < 20000) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (name.startsWith("/")) name = name.substring(1);
                int depth = 0;
                for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') depth++;
                // Accept the entry at the root or inside one wrapper folder (common on Modrinth)
                if (name.endsWith("pack.mcmeta") && depth <= 1) hasMcmeta = true;
                if ((name.startsWith("shaders/") || (depth >= 1 && name.contains("/shaders/") && depth <= 2))) hasShaders = true;
                if (hasMcmeta && type == ContentType.RESOURCE_PACK) return 0;
                if (hasShaders && type == ContentType.SHADER_PACK) return 0;
            }
            if (type == ContentType.RESOURCE_PACK) return R.string.rb_err_invalid_resourcepack;
            // Some shader packs ship as a resource-pack style zip with shaders inside assets
            if (type == ContentType.SHADER_PACK) return hasMcmeta ? 0 : R.string.rb_err_invalid_shader;
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "Not a zip: " + e.getMessage());
            return R.string.rb_err_invalid_archive;
        }
    }

    @Nullable
    private static String sha1Of(@NonNull File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(@NonNull File from, @NonNull File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
    }

    private static void deleteQuietly(@Nullable File f) {
        if (f != null && f.exists() && !f.delete()) Log.w(TAG, "Could not delete " + f);
    }

    /** "12.4 MB" style, locale independent. */
    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, mb < 10 ? "%.1f MB" : "%.0f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    @NonNull
    private static String fileNameFromUrl(@NonNull String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
        return name.isEmpty() ? "resource.zip" : name;
    }

    @NonNull
    private static String sanitize(@NonNull String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "resource.zip" : s;
    }

    private static final class CancelledException extends RuntimeException {}
    /** Thrown after fail() was already called with a specific message. */
    private static final class HandledException extends IOException {}
    private static final class StorageException extends IOException {
        final long needed;
        StorageException(long needed) { this.needed = needed; }
    }
}
