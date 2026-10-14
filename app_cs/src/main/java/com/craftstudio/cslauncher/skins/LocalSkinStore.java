package com.craftstudio.cslauncher.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.authenticator.accounts.SkinHeadRenderer;
import com.craftstudio.cslauncher.utils.FileUtils;
import com.craftstudio.cslauncher.utils.JSONUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Skins and capes of local (offline) accounts.
 * <p>
 * Skins come from the shared {@link SkinLibrary} (the selection is stored under {@link #keyOf}).
 * Capes are a separate local library because, unlike Microsoft accounts, a local account owns
 * no capes: the user adds any cape PNG. The game gets both through the local Yggdrasil server
 * ({@code LocalYggdrasilServer}) + authlib-injector.
 */
public final class LocalSkinStore {
    private static final String TAG = "LocalSkinStore";

    private LocalSkinStore() {}

    /** Selection key of a local account (local accounts share the nil UUID, so the name is used) */
    @NonNull
    public static String keyOf(@NonNull Account account) {
        return keyOfName(account.username);
    }

    /** Same key built from just the player name (used before the account exists) */
    @NonNull
    public static String keyOfName(@Nullable String username) {
        String name = username == null ? "player" : username;
        return "local:" + name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ skins

    @Nullable
    public static SkinLibrary.Entry selectedSkin(@NonNull Account account) {
        return SkinLibrary.find(SkinLibrary.getSelected(keyOf(account)));
    }

    // ------------------------------------------------------------------ capes

    @Keep
    public static final class Cape {
        public String id;
        public String name;
        public long added;
    }

    @Keep
    private static final class CapeIndex {
        List<Cape> capes = new ArrayList<>();
        /** account key -> id of the cape it wears */
        Map<String, String> selected = new HashMap<>();
    }

    private static File capeDir() {
        return new File(Tools.DIR_DATA, "local_capes");
    }

    private static File capeIndexFile() {
        return new File(capeDir(), "index.json");
    }

    @NonNull
    private static CapeIndex readCapeIndex() {
        File file = capeIndexFile();
        if (!file.exists()) return new CapeIndex();
        try {
            CapeIndex index = JSONUtils.readFromFile(file, CapeIndex.class);
            if (index == null) return new CapeIndex();
            if (index.capes == null) index.capes = new ArrayList<>();
            if (index.selected == null) index.selected = new HashMap<>();
            return index;
        } catch (Exception e) {
            return new CapeIndex();
        }
    }

    private static void writeCapeIndex(@NonNull CapeIndex index) throws IOException {
        FileUtils.ensureDirectory(capeDir());
        JSONUtils.writeToFile(capeIndexFile(), index);
    }

    @NonNull
    public static File capeFile(@NonNull Cape cape) {
        return new File(capeDir(), cape.id + ".png");
    }

    @NonNull
    public static synchronized List<Cape> listCapes() {
        List<Cape> result = new ArrayList<>();
        for (Cape cape : readCapeIndex().capes) {
            if (cape != null && cape.id != null && capeFile(cape).exists()) result.add(cape);
        }
        return result;
    }

    @Nullable
    public static synchronized Cape findCape(@Nullable String id) {
        if (id == null) return null;
        for (Cape cape : listCapes()) if (id.equals(cape.id)) return cape;
        return null;
    }

    @NonNull
    public static synchronized Cape addCape(@NonNull byte[] png, @NonNull String name) throws IOException {
        FileUtils.ensureDirectory(capeDir());
        Cape cape = new Cape();
        cape.id = UUID.randomUUID().toString();
        cape.name = name;
        cape.added = System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(capeFile(cape))) {
            out.write(png);
        }
        CapeIndex index = readCapeIndex();
        index.capes.add(cape);
        writeCapeIndex(index);
        return cape;
    }

    public static synchronized void deleteCape(@NonNull String id) throws IOException {
        CapeIndex index = readCapeIndex();
        Cape target = null;
        for (Cape cape : index.capes) if (id.equals(cape.id)) target = cape;
        if (target != null) {
            index.capes.remove(target);
            //noinspection ResultOfMethodCallIgnored
            capeFile(target).delete();
        }
        Iterator<String> selected = index.selected.values().iterator();
        while (selected.hasNext()) {
            if (id.equals(selected.next())) selected.remove();
        }
        writeCapeIndex(index);
    }

    @Nullable
    public static synchronized String getSelectedCape(@NonNull Account account) {
        return readCapeIndex().selected.get(keyOf(account));
    }

    public static synchronized void setSelectedCape(@NonNull Account account, @Nullable String id) throws IOException {
        setSelectedCapeByKey(keyOf(account), id);
    }

    public static synchronized void setSelectedCapeByKey(@NonNull String key, @Nullable String id) throws IOException {
        CapeIndex index = readCapeIndex();
        if (id == null) index.selected.remove(key);
        else index.selected.put(key, id);
        writeCapeIndex(index);
    }

    @Nullable
    public static Cape selectedCape(@NonNull Account account) {
        return findCape(getSelectedCape(account));
    }

    /** Capes are 64x32 (or any multiple, HD) or the old 22x17 OptiFine layout */
    public static boolean isValidCapeSize(int width, int height) {
        if (width == 22 && height == 17) return true;
        return width >= 64 && width % 64 == 0 && width == height * 2;
    }

    @NonNull
    public static byte[] readBytes(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return IOUtils.toByteArray(in);
        }
    }

    // ------------------------------------------------------------------ face (home / account list)

    @NonNull
    public static File faceFile(@NonNull Account account) {
        return faceFileForKey(keyOf(account));
    }

    @NonNull
    public static File faceFileForKey(@NonNull String key) {
        String safe = key.replaceAll("[^a-z0-9_.-]", "_");
        return new File(Tools.DIR_CACHE, "skin-face-" + safe + ".webp");
    }

    /**
     * Renders the head of the selected skin for the account list / home top bar.
     * Without a selected skin the cached face is removed (the default head is shown).
     * Performs I/O: call it off the UI thread.
     */
    public static void updateFace(@NonNull Account account) {
        updateFaceForKey(keyOf(account));
    }

    public static void updateFaceForKey(@NonNull String key) {
        File face = faceFileForKey(key);
        SkinLibrary.Entry entry = SkinLibrary.find(SkinLibrary.getSelected(key));
        if (entry == null) {
            //noinspection ResultOfMethodCallIgnored
            face.delete();
            return;
        }
        Bitmap raw = BitmapFactory.decodeFile(SkinLibrary.fileOf(entry).getAbsolutePath());
        if (raw == null) return;
        try {
            Bitmap normalized = SkinTextures.normalizeSkin(raw);
            Bitmap head = new SkinHeadRenderer().render(100, normalized);
            normalized.recycle();
            if (head == null) return;
            FileUtils.ensureParentDirectory(face);
            try (FileOutputStream out = new FileOutputStream(face)) {
                head.compress(Bitmap.CompressFormat.WEBP, 90, out);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not render the local skin face", e);
        } finally {
            raw.recycle();
        }
    }
}
