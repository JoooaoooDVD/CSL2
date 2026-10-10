package com.craftstudio.cslauncher.modding.modpacks.imagecache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.DownloadUtils;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader for project screenshots (gallery strip and full screen viewer).
 * Bitmaps are decoded at the size they are shown at, kept in a memory cache sized from the
 * heap, and the downloaded files are cached on disk (trimmed to about 60 MB). A view only
 * receives the bitmap it last asked for, so fast scrolling never shows a stale image.
 */
public final class GalleryImageLoader {
    private static final String TAG = "GalleryImageLoader";
    private static final long DISK_LIMIT = 60L * 1024 * 1024;
    private static final long DISK_TRIM_TO = 40L * 1024 * 1024;

    private static GalleryImageLoader sInstance;

    public interface Callback {
        /** @param fromMemory true when the bitmap was already cached and shown without delay */
        void onLoaded(@NonNull ImageView view, boolean fromMemory);
    }

    private final LruCache<String, Bitmap> mMemory;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final File mDiskDir;
    private boolean mTrimmed;

    public static synchronized GalleryImageLoader get(@NonNull Context context) {
        if (sInstance == null) sInstance = new GalleryImageLoader(context.getApplicationContext());
        return sInstance;
    }

    private GalleryImageLoader(Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mMemory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        mDiskDir = new File(context.getCacheDir(), "lib_gallery");
    }

    /**
     * Loads {@code url} into {@code view}, decoded for a width of about {@code targetWidth}
     * pixels. The view keeps its current drawable (placeholder) until the bitmap is ready.
     */
    public void load(@NonNull ImageView view, @NonNull String url, int targetWidth, @Nullable Callback callback) {
        final int width = Math.max(64, targetWidth);
        final String key = url + "@" + bucket(width);
        view.setTag(R.id.lib_image_url_tag, key);
        Bitmap cached = mMemory.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            if (callback != null) callback.onLoaded(view, true);
            return;
        }
        mExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = diskFile(url);
                if (!file.isFile() || file.length() == 0) download(url, file);
                bitmap = decode(file, bucket(width));
                if (bitmap == null) {
                    // Corrupted cache entry, retry once from the network
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    download(url, file);
                    bitmap = decode(file, bucket(width));
                }
            } catch (Throwable e) {
                Log.w(TAG, "Unable to load " + url + ": " + e);
            }
            if (bitmap == null) return;
            mMemory.put(key, bitmap);
            final Bitmap result = bitmap;
            mMain.post(() -> {
                if (!key.equals(view.getTag(R.id.lib_image_url_tag))) return;
                view.setImageBitmap(result);
                if (callback != null) callback.onLoaded(view, false);
            });
        });
    }

    /** Forgets the pending request of a recycled view */
    public void cancel(@NonNull ImageView view) {
        view.setTag(R.id.lib_image_url_tag, null);
    }

    /** Rounds widths so the strip and the viewer share cache entries across close sizes */
    private static int bucket(int width) {
        if (width <= 480) return 480;
        if (width <= 960) return 960;
        return 1600;
    }

    private File diskFile(String url) {
        return new File(mDiskDir, sha1(url));
    }

    private void download(String url, File file) throws Exception {
        if (!mDiskDir.isDirectory() && !mDiskDir.mkdirs()) throw new IllegalStateException("No cache dir");
        File temp = new File(mDiskDir, file.getName() + "." + Thread.currentThread().getId() + ".part");
        DownloadUtils.downloadFile(url, temp);
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (!file.isFile()) throw new IllegalStateException("Unable to store " + url);
        }
        trimDiskOnce();
    }

    @Nullable
    private static Bitmap decode(File file, int targetWidth) {
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private synchronized void trimDiskOnce() {
        if (mTrimmed) return;
        mTrimmed = true;
        File[] files = mDiskDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_TRIM_TO) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(text.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
