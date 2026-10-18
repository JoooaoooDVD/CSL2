# In-app updates

CS Launcher Plus checks GitHub Releases of `JoooaoooDVD/CSL2` for newer builds.

## How a release becomes "known" to the launcher

The CI (`.github/workflows/android.yml`) writes the release body from `out/release-body.md`:
human notes (last 15 commit subjects) plus a machine-readable block

```
<!-- cs-update-begin -->
cs-update: versionCode=<GITHUB_RUN_NUMBER> versionName=<versionName from build.gradle>
cs-apk: file=app-debug-full.apk sha256=<hex> size=<bytes>
cs-apk: file=app-debug-noruntime.apk sha256=<hex> size=<bytes>
cs-apk: file=CS Launcher Plus-release.apk sha256=<hex> size=<bytes>
<!-- cs-update-end -->
```

`versionCode` is the run number (same value the APK is built with), so it increases with every
build and an older release can never look newer. Releases without the block are ignored.

## Client (`com.craftstudio.cslauncher.update`)

* `UpdateChecker` - one unauthenticated GET of `/releases?per_page=12`, picks the highest
  `versionCode` (channel "stable" ignores pre-releases, i.e. the rolling `latest` build).
* `UpdateManager` - auto check 3.2 s after start, at most every 6 h, skipped while snoozed
  (Later = 24 h) or when the build was skipped; manual check from Settings > Misc > Updates
  always answers (new / up to date / error). Failures never block the launcher.
* `UpdatePopup` - installed vs latest, release notes (markdown subset), Update now with
  progress, Later, Skip this build.
* `UpdateDownloader` - downloads the asset matching this installation
  (`app-debug-<flavor>.apk` for debug builds, `CS Launcher Plus-release.apk` for release) to
  `<external files>/updates`, then verifies SHA-256, package name, versionCode and the signing
  certificate against the running app.
* `UpdateInstaller` - `ACTION_INSTALL_PACKAGE` through the `${applicationId}.updates`
  FileProvider; asks for "install unknown apps" on Android 8+; cancel / failure results come
  back through `LauncherActivity.onActivityResult`.
