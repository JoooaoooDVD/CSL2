package com.craftstudio.cslauncher.game.crash.autofix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One repair's undo record. Every change a {@link RepairAction} makes goes through here so it can
 * be reverted in reverse order, both right away (a later step failed) and later from the UI.
 *
 * <p>Layout: {@code <backupRoot>/<id>/manifest.json} + {@code files/NNN-<name>} copies. Nothing is
 * ever deleted from the player's folders: "move" quarantines the file into the backup folder,
 * "copy" snapshots a file we are about to edit, "rename" is used for the .disabled convention,
 * "create" marks a file the repair itself produced (so rollback can remove it), and "value"
 * records a setting the repair changed.</p>
 */
public final class BackupSession {
    public static final String OP_MOVE = "move";
    public static final String OP_COPY = "copy";
    public static final String OP_RENAME = "rename";
    public static final String OP_CREATE = "create";
    public static final String OP_VALUE = "value";

    /** Restores non-file values recorded with {@link #recordValue}. */
    public interface ValueRestorer {
        void restore(String scope, String key, String oldValue) throws IOException;
    }

    public static final class Entry {
        public String op;
        public String path;       // original absolute path (move/copy/rename-from/create)
        public String backup;     // absolute path of the copy inside the session (move/copy)
        public String to;         // rename target
        public String scope, key, old; // value entries

        Entry() {}
    }

    public static final class Manifest {
        public String id;
        public long createdAt;
        public String instanceKey;
        public String signature;
        public String category;
        public List<Entry> entries = new ArrayList<>();
        public boolean rolledBack;
        public boolean completed;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File mDir;
    private final File mFilesDir;
    private final Manifest mManifest;
    private int mCounter;

    private BackupSession(File dir, Manifest manifest) {
        mDir = dir;
        mFilesDir = new File(dir, "files");
        mManifest = manifest;
        mCounter = manifest.entries.size();
    }

    /** Starts a fresh session folder under {@code root}. */
    public static BackupSession create(File root, String instanceKey, String signature, String category) throws IOException {
        if (root == null) throw new IOException("No backup root");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create backup root " + root);
        long now = System.currentTimeMillis();
        String id = String.valueOf(now);
        File dir = new File(root, id);
        int n = 1;
        while (dir.exists()) dir = new File(root, id + "-" + (n++));
        if (!dir.mkdirs()) throw new IOException("Cannot create backup folder " + dir);
        Manifest m = new Manifest();
        m.id = dir.getName();
        m.createdAt = now;
        m.instanceKey = instanceKey;
        m.signature = signature;
        m.category = category;
        BackupSession s = new BackupSession(dir, m);
        s.save();
        return s;
    }

    /** Opens an existing session (for a later rollback). Null when the manifest is missing/broken. */
    public static BackupSession open(File dir) {
        File mf = new File(dir, "manifest.json");
        if (!mf.isFile()) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(mf), StandardCharsets.UTF_8)) {
            Manifest m = GSON.fromJson(r, Manifest.class);
            if (m == null || m.entries == null) return null;
            return new BackupSession(dir, m);
        } catch (Exception e) {
            return null;
        }
    }

    public File dir() { return mDir; }
    public Manifest manifest() { return mManifest; }
    public boolean isEmpty() { return mManifest.entries.isEmpty(); }
    public List<Entry> entries() { return Collections.unmodifiableList(mManifest.entries); }

    // ---- recording operations ---------------------------------------------------------------

    /** Copies {@code src} into the session and removes the original (quarantine). */
    public void moveToBackup(File src) throws IOException {
        if (!src.isFile()) throw new IOException("Not a file: " + src);
        File dst = nextBackupFile(src.getName());
        copy(src, dst);
        if (!src.delete()) {
            // keep the copy but report honestly
            if (!dst.delete()) { /* leave it, harmless */ }
            throw new IOException("Cannot remove " + src.getName());
        }
        Entry e = new Entry();
        e.op = OP_MOVE; e.path = src.getAbsolutePath(); e.backup = dst.getAbsolutePath();
        add(e);
    }

    /** Snapshots {@code src} before it is edited in place. */
    public void snapshot(File src) throws IOException {
        if (!src.isFile()) throw new IOException("Not a file: " + src);
        File dst = nextBackupFile(src.getName());
        copy(src, dst);
        Entry e = new Entry();
        e.op = OP_COPY; e.path = src.getAbsolutePath(); e.backup = dst.getAbsolutePath();
        add(e);
    }

    /** Renames {@code from} to {@code to} (same folder), e.g. foo.jar → foo.jar.disabled. */
    public void rename(File from, File to) throws IOException {
        if (!from.exists()) throw new IOException("Missing " + from.getName());
        if (to.exists()) throw new IOException("Target exists " + to.getName());
        if (!from.renameTo(to)) throw new IOException("Cannot rename " + from.getName());
        Entry e = new Entry();
        e.op = OP_RENAME; e.path = from.getAbsolutePath(); e.to = to.getAbsolutePath();
        add(e);
    }

    /** Marks a file produced by the repair (a downloaded dependency). Rollback deletes it. */
    public void created(File f) throws IOException {
        Entry e = new Entry();
        e.op = OP_CREATE; e.path = f.getAbsolutePath();
        add(e);
    }

    /** Records a setting change so the restorer can undo it. */
    public void recordValue(String scope, String key, String oldValue) throws IOException {
        Entry e = new Entry();
        e.op = OP_VALUE; e.scope = scope; e.key = key; e.old = oldValue;
        add(e);
    }

    public void markCompleted() throws IOException {
        mManifest.completed = true;
        save();
    }

    // ---- rollback -----------------------------------------------------------------------------

    /**
     * Reverts every recorded entry in reverse order. Continues past individual failures and
     * reports them; the manifest is marked rolled back only when everything succeeded.
     * @return list of problems (empty on full success)
     */
    public List<String> rollback(ValueRestorer restorer) {
        List<String> problems = new ArrayList<>();
        List<Entry> list = mManifest.entries;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            try {
                switch (e.op) {
                    case OP_MOVE: {
                        File orig = new File(e.path), bak = new File(e.backup);
                        if (!bak.isFile()) throw new IOException("backup copy missing for " + orig.getName());
                        ensureParent(orig);
                        copyAtomic(bak, orig);
                        break;
                    }
                    case OP_COPY: {
                        File orig = new File(e.path), bak = new File(e.backup);
                        if (!bak.isFile()) throw new IOException("backup copy missing for " + orig.getName());
                        ensureParent(orig);
                        copyAtomic(bak, orig);
                        break;
                    }
                    case OP_RENAME: {
                        File from = new File(e.path), to = new File(e.to);
                        if (from.exists()) break; // already back
                        if (!to.exists()) throw new IOException("renamed file missing " + to.getName());
                        if (!to.renameTo(from)) throw new IOException("cannot rename back " + to.getName());
                        break;
                    }
                    case OP_CREATE: {
                        File f = new File(e.path);
                        if (f.exists() && !f.delete()) throw new IOException("cannot remove " + f.getName());
                        break;
                    }
                    case OP_VALUE: {
                        if (restorer == null) throw new IOException("no restorer for " + e.scope + "/" + e.key);
                        restorer.restore(e.scope, e.key, e.old);
                        break;
                    }
                    default:
                        throw new IOException("unknown op " + e.op);
                }
            } catch (Exception ex) {
                problems.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        }
        if (problems.isEmpty()) {
            mManifest.rolledBack = true;
            try { save(); } catch (IOException ignored) {}
        }
        return problems;
    }

    // ---- internals ----------------------------------------------------------------------------

    private void add(Entry e) throws IOException {
        mManifest.entries.add(e);
        save();
    }

    private File nextBackupFile(String name) throws IOException {
        if (!mFilesDir.isDirectory() && !mFilesDir.mkdirs()) throw new IOException("Cannot create " + mFilesDir);
        String safe = name.replaceAll("[^A-Za-z0-9._\\-]", "_");
        return new File(mFilesDir, String.format(java.util.Locale.ROOT, "%03d-%s", ++mCounter, safe));
    }

    private void save() throws IOException {
        File mf = new File(mDir, "manifest.json");
        File tmp = new File(mDir, "manifest.json.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(mManifest, w);
        }
        if (mf.exists() && !mf.delete()) throw new IOException("Cannot replace manifest");
        if (!tmp.renameTo(mf)) throw new IOException("Cannot write manifest");
    }

    static void ensureParent(File f) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("Cannot create " + p);
    }

    static void copy(File src, File dst) throws IOException {
        ensureParent(dst);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Writes to a temp file next to the target and renames, so a crash mid-copy never leaves a half file. */
    public static void copyAtomic(File src, File dst) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".autofix-tmp");
        copy(src, tmp);
        if (dst.exists() && !dst.delete()) { tmp.delete(); throw new IOException("Cannot replace " + dst.getName()); }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("Cannot move into place " + dst.getName()); }
    }

    /** Replaces {@code dst} with the given UTF-8 text atomically. */
    public static void writeAtomic(File dst, String text) throws IOException {
        ensureParent(dst);
        File tmp = new File(dst.getParentFile(), dst.getName() + ".autofix-tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(text);
        }
        if (dst.exists() && !dst.delete()) { tmp.delete(); throw new IOException("Cannot replace " + dst.getName()); }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("Cannot move into place " + dst.getName()); }
    }
}
