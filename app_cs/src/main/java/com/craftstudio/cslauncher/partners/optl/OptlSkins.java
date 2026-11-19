package com.craftstudio.cslauncher.partners.optl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.authenticator.accounts.SkinHeadRenderer;
import com.craftstudio.cslauncher.friends.RemoteSkins;
import com.craftstudio.cslauncher.skins.SkinTextures;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skins of the players on the OPTL tier list, by Minecraft username. Looked up in this order:
 * <ol>
 *   <li>the CS skin backend — the skin a CS Launcher Plus player published (most Pojav players
 *       are offline-mode, this is the only place their real skin exists);</li>
 *   <li>Visage ({@code vzge.me}, the renderer OPTL's own website uses) for premium names;</li>
 *   <li>the launcher's default skin.</li>
 * </ol>
 * Results are kept in memory and on disk (cache dir, one day) so the list does not refetch.
 */
public final class OptlSkins {
    private static final String TAG = "OptlSkins";
    private static final String USER_AGENT = "CS-Launcher-Plus/1.6 (+https://official.craftstudios.site; contact@craftstudios.site)";
    private static final long DISK_TTL = 24 * 60 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "optl-skins");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A resolved skin: 64x64-normalised texture, model, and the 3D head icon. */
    public static final class Skin {
        public final Bitmap texture; public final boolean slim; public final Bitmap head; public final boolean isDefault;
        Skin(Bitmap texture, boolean slim, Bitmap head, boolean isDefault) { this.texture = texture; this.slim = slim; this.head = head; this.isDefault = isDefault; }
    }
    public interface Callback { void done(@NonNull String name, @NonNull Skin skin); }

    private static final Map<String, Skin> sCache = new HashMap<>();
    private static final Set<String> sPending = new HashSet<>();
    private static final Map<String, List<Callback>> sWaiting = new HashMap<>();
    @Nullable private static Skin sDefault;

    private OptlSkins() {}

    /** The cached skin, or null (a fetch starts and {@code cb} fires on the main thread when it lands). */
    @Nullable
    public static Skin get(@NonNull Context context, @Nullable String name, @Nullable Callback cb) {
        if (name == null || name.trim().isEmpty()) return defaultSkin(context);
        final String key = name.trim().toLowerCase(Locale.ROOT);
        final Context app = context.getApplicationContext();
        synchronized (OptlSkins.class) {
            Skin s = sCache.get(key);
            if (s != null) return s;
            if (cb != null) {
                List<Callback> l = sWaiting.get(key);
                if (l == null) { l = new ArrayList<>(); sWaiting.put(key, l); }
                l.add(cb);
            }
            if (sPending.contains(key)) return null;
            sPending.add(key);
        }
        IO.execute(() -> {
            Skin s = null;
            try { s = resolve(app, name.trim(), key); } catch (Throwable t) { Log.w(TAG, "skin " + key + ": " + t); }
            if (s == null) s = defaultSkin(app);
            final Skin fs = s;
            List<Callback> l;
            synchronized (OptlSkins.class) {
                sCache.put(key, fs);
                sPending.remove(key);
                l = sWaiting.remove(key);
            }
            if (l != null && fs != null) MAIN.post(() -> { for (Callback c : l) c.done(name.trim(), fs); });
        });
        return null;
    }

    @Nullable
    private static Skin resolve(Context app, String name, String key) {
        File dir = new File(app.getCacheDir(), "optl_skins");
        File file = new File(dir, key + ".png");
        File slimMark = new File(dir, key + ".slim");
        File none = new File(dir, key + ".none");
        long now = System.currentTimeMillis();
        if (file.isFile() && now - file.lastModified() < DISK_TTL) {
            Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (b != null) return make(b, slimMark.isFile(), false);
        }
        if (none.isFile() && now - none.lastModified() < DISK_TTL) return null;

        byte[] png = null; Boolean slim = null;
        // 1. CS skin backend (published launcher skins)
        try {
            RemoteSkins.Skin cs = RemoteSkins.lookupByName(name);
            if (cs != null && cs.skinPng != null) { png = cs.skinPng; slim = cs.slim; }
        } catch (Throwable t) { Log.w(TAG, "cs lookup " + key + ": " + t); }
        // 2. Visage (premium names; 404 for offline names)
        if (png == null) {
            try { png = fetch("https://vzge.me/skin/" + java.net.URLEncoder.encode(name, "UTF-8") + ".png"); }
            catch (IOException e) { Log.i(TAG, "visage " + key + ": " + e.getMessage()); }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (png == null) {
            try { new FileOutputStream(none).close(); } catch (IOException ignored) {}
            return null;
        }
        Bitmap raw = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (raw == null || !SkinTextures.isValidSkinSize(raw.getWidth(), raw.getHeight())) return null;
        Bitmap tex = SkinTextures.normalizeSkin(raw);
        boolean isSlim = slim != null ? slim : SkinTextures.looksSlim(tex);
        try (FileOutputStream out = new FileOutputStream(file)) {
            tex.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (isSlim) new FileOutputStream(slimMark).close(); else //noinspection ResultOfMethodCallIgnored
                slimMark.delete();
            //noinspection ResultOfMethodCallIgnored
            none.delete();
        } catch (IOException ignored) {}
        return make(tex, isSlim, false);
    }

    private static Skin make(Bitmap tex, boolean slim, boolean isDefault) {
        Bitmap head = null;
        try { head = new SkinHeadRenderer().render(96, tex); } catch (RuntimeException ignored) {}
        return new Skin(tex, slim, head, isDefault);
    }

    @NonNull
    public static synchronized Skin defaultSkin(@NonNull Context context) {
        if (sDefault == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            Bitmap b = BitmapFactory.decodeResource(context.getResources(), R.drawable.skin_default_cs, o);
            Bitmap tex = b == null ? Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) : SkinTextures.normalizeSkin(b);
            sDefault = make(tex, false, true);
        }
        return sDefault;
    }

    @Nullable
    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "image/png");
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 2_000_000) return null; }
                return bo.toByteArray();
            }
        } finally { c.disconnect(); }
    }
}
