package com.craftstudio.cslauncher.multiplayer;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.multiplayer.core.TunnelEnvironment;

import java.io.File;

/** Binds the tunnel core to this app: binaries from nativeLibraryDir, private work dir */
public final class AndroidTunnelEnvironment implements TunnelEnvironment {
    public static final String TAG = "VirtualLAN";
    private final File mNativeDir;
    private final File mWorkDir;

    public AndroidTunnelEnvironment(@NonNull Context context, @NonNull String role) {
        mNativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        mWorkDir = new File(context.getFilesDir(), "virtual_lan/" + role);
        //noinspection ResultOfMethodCallIgnored
        mWorkDir.mkdirs();
    }

    /** Whether this ABI ships the engine (32-bit x86 does not) */
    public static boolean isSupported(@NonNull Context context) {
        File dir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(dir, "libeasytier_core.so").canExecute() && new File(dir, "libeasytier_cli.so").canExecute();
    }

    @Nullable @Override public File coreBinary() {
        File f = new File(mNativeDir, "libeasytier_core.so");
        return f.exists() ? f : null;
    }

    @Nullable @Override public File cliBinary() {
        File f = new File(mNativeDir, "libeasytier_cli.so");
        return f.exists() ? f : null;
    }

    @NonNull @Override public File workDir() { return mWorkDir; }

    @NonNull @Override public String shell() {
        return new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/sh";
    }

    @Override public int myPid() { return android.os.Process.myPid(); }

    @Override public void killProcess(int pid) {
        try { android.os.Process.killProcess(pid); } catch (Throwable ignored) {}
    }

    @Override public void log(@NonNull String line) { Log.i(TAG, line); }
}
