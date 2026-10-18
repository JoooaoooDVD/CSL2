package com.craftstudio.cslauncher.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Downloads one APK to the app's private update folder, then verifies it: SHA-256 from the
 * release metadata, a parseable package with our package name and the expected versionCode,
 * and the same signing certificate as the running build (so the system installer will accept
 * it as an update instead of failing late with "App not installed").
 */
public final class UpdateDownloader {
    public interface Listener {
        void onProgress(long done, long total);
        void onVerifying();
        void onReady(@NonNull File apk);
        void onError(@NonNull String message, boolean cancelled);
    }

    private final Context mContext;
    private final UpdateInfo mInfo;
    private final UpdateInfo.Apk mApk;
    private final Listener mListener;
    private volatile boolean mCancelled;
    private Thread mThread;

    public UpdateDownloader(@NonNull Context context, @NonNull UpdateInfo info, @NonNull UpdateInfo.Apk apk, @NonNull Listener listener) {
        mContext = context.getApplicationContext();
        mInfo = info;
        mApk = apk;
        mListener = listener;
    }

    @NonNull
    public static File updateDir(@NonNull Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Removes every previously downloaded update (called after an install attempt / on start). */
    public static void cleanUp(@NonNull Context context) {
        File[] files = updateDir(context).listFiles();
        if (files == null) return;
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    public void start() {
        mThread = new Thread(this::run, "cs-update-download");
        mThread.setDaemon(true);
        mThread.start();
    }

    public void cancel() {
        mCancelled = true;
        if (mThread != null) mThread.interrupt();
    }

    private void post(Runnable r) {
        Tools.MAIN_HANDLER.post(r);
    }

    private void run() {
        File target = new File(updateDir(mContext), safeName(mApk.file));
        File part = new File(target.getPath() + ".part");
        try {
            // Already there from an earlier attempt and still intact: skip the download
            if (target.isFile() && target.length() > 0 && sha256(target).equals(mApk.sha256)) {
                post(mListener::onVerifying);
                verify(target);
                post(() -> mListener.onReady(target));
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            download(part);
            if (mCancelled) throw new InterruptedException();
            post(mListener::onVerifying);
            String digest = sha256(part);
            if (!digest.equals(mApk.sha256)) {
                throw new IOException("Checksum mismatch - the download is corrupted or was tampered with");
            }
            if (!part.renameTo(target)) throw new IOException("Could not store the update file");
            verify(target);
            post(() -> mListener.onReady(target));
        } catch (InterruptedException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            post(() -> mListener.onError("Download cancelled", true));
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            final String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            post(() -> mListener.onError(mCancelled ? "Download cancelled" : msg, mCancelled));
        }
    }

    private void download(File part) throws IOException, InterruptedException {
        HttpURLConnection c = (HttpURLConnection) new URL(mApk.downloadUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "CSLauncherPlus-Updater");
        c.setRequestProperty("Accept", "application/octet-stream");
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Download failed: HTTP " + status);
            long total = c.getContentLength();
            if (total <= 0) total = mApk.size;
            final long fTotal = total;
            long done = 0, lastReport = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (mCancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 120 || done == fTotal) {
                        lastReport = now;
                        final long fDone = done;
                        post(() -> mListener.onProgress(fDone, fTotal));
                    }
                }
                out.getFD().sync();
            }
            if (fTotal > 0 && done != fTotal) throw new IOException("Download interrupted (" + done + " of " + fTotal + " bytes)");
        } finally {
            c.disconnect();
        }
    }

    /** Package name, versionCode and signing certificate must match the running build. */
    private void verify(File apk) throws IOException {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo remote;
        PackageInfo local;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                remote = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                local = pm.getPackageInfo(mContext.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                remote = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
                local = pm.getPackageInfo(mContext.getPackageName(), PackageManager.GET_SIGNATURES);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Could not read the installed package");
        }
        if (remote == null) throw new IOException("The downloaded file is not a valid APK");
        if (!mContext.getPackageName().equals(remote.packageName)) {
            throw new IOException("Wrong package: " + remote.packageName + " (expected " + mContext.getPackageName() + ")");
        }
        long remoteCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? remote.getLongVersionCode() : remote.versionCode;
        if (remoteCode != mInfo.versionCode) {
            throw new IOException("Version mismatch: file is build " + remoteCode + ", release says " + mInfo.versionCode);
        }
        Signature[] a = signatures(remote), b = signatures(local);
        if (a == null || a.length == 0) {
            // Some OEM package parsers do not expose archive signatures; the installer still checks them
            return;
        }
        if (b == null || b.length == 0) return;
        if (!Arrays.equals(a[0].toByteArray(), b[0].toByteArray())) {
            throw new IOException("This build is signed with a different key than the installed launcher, "
                    + "so Android would refuse to update it. Install it manually from GitHub after uninstalling.");
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    @NonNull
    static String sha256(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
