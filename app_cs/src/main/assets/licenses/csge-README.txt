Craft Studio Graphics Engine (CSGE) 1.0.0
Developer: Craft Studio Development Group
Built on MobileGlues 2.0.0 (LGPL-2.1, Copyright (c) 2025-2026 MobileGL-Dev)

WHAT THIS IS
  libmobileglues.so -- a native GL-to-GLES translation library.
  This is NOT an app and will not install. It is a .so that a launcher
  (e.g. CS Launcher Plus) loads with System.loadLibrary("mobileglues").

INSTALL (into a launcher project)
  Copy jniLibs/<abi>/libmobileglues.so into your app module's
  src/main/jniLibs/<abi>/ directory. Nothing else is needed -- the
  library has no hard dependency on libGLESv2/libEGL; it dlopens them.

  Before System.loadLibrary, set one of these or your config.json is ignored:
    Os.setenv("CS_LAUNCHER_PLUS_VERSION_CODE", "<versionCode>", true)
  or
    Os.setenv("MG_DIR_PATH", "<writable dir>", true)

  Log after launch: <MG_DIR>/latest.log   (logcat tag: CSGE)

STATUS
  Built and link-verified for all 4 ABIs. NOT verified running on a device
  or with Minecraft. No FPS claim is made. See docs/TESTING.md.

LICENSE
  GNU LGPL-2.1. Full source: the craft-studio-graphics-engine repository.
