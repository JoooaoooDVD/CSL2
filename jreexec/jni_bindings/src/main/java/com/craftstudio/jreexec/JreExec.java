package com.craftstudio.jreexec;

public class JreExec {
    public static native boolean prepareEgl(String eglPath, boolean useBypass, boolean useGles, int glesVersion);
    public static native void setDisplayParams(int width, int height, float hz);
    public static native void setUseTurnip(boolean enable);
    public static native void preloadVulkan();
    public static native void setNativeLibraryDir(String dir);
}
