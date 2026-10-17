package com.craftstudio.cslauncher.multiplayer.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * The few platform specific things the tunnel core needs. On Android they come from the app
 * context (nativeLibraryDir, files dir, android.os.Process); the desktop implementation used by
 * the end-to-end test harness points at the plain Linux binaries.
 */
public interface TunnelEnvironment {
    /** The easytier-core executable, or null when this device has no build (x86 32-bit) */
    @Nullable File coreBinary();
    /** The easytier-cli executable, or null */
    @Nullable File cliBinary();
    /** Private, writable directory for config / pid files (0700 on Android) */
    @NonNull File workDir();
    /** A POSIX shell: /system/bin/sh on Android */
    @NonNull String shell();
    /** Our own process id (the watchdog kills the tunnel if this process disappears) */
    int myPid();
    /** Kills a process we own */
    void killProcess(int pid);
    /** Diagnostics log line; secrets are already scrubbed by the caller */
    void log(@NonNull String line);
}
