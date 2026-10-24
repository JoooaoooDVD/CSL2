# Crash Auto Fix

Package: `com.craftstudio.cslauncher.game.crash.autofix`
Entry UI: `game/crash/GameCrashActivity` (opened by `ExitActivity.showExitMessage` for any
non-zero exit code or signal; a normal exit code 0 never shows it).

## Pipeline

1. **Collect** – `GameCrashActivity` reads the tail of `latestlog.txt`, the newest crash report
   (last 10 min) and `hs_err_pid*.log`. `CrashContext` resolves the profile (game dir, mods/,
   config/, options.txt, loader, Minecraft version, backup root).
2. **Classify** – `CrashClassifier.analyze(Input)` → `CrashAnalysis` (category, confidence,
   evidence, suspect component, repair plan, `safeToAutoFix`, `requiresConfirmation`). Pure JVM,
   ordered rules; see `CrashClassifierTest` + `src/test/resources/crashlogs/`.
3. **Plan filter** – `RepairHistory` (`<game home>/autofix/history.json`) removes steps that failed
   or relapsed before, marks a previous "applied" attempt as relapsed when the same signature
   crashes again, and caps automatic attempts at 2 per crash signature per profile.
4. **Repair** – `RepairEngine.run` → for every `RepairStep` a `RepairAction` (`RepairActions`):
   `prepare()` (validate, no changes) → `BackupSession` → `apply()` → `verify()`.
   Any failure rolls back every entry of the session in reverse order.
5. **UI** – stages (checking / backup / applying / verifying / rollback) and the final report;
   RETRY LAUNCH relaunches the profile; "Undo last Auto Fix" reverts the newest backup.

## Repair actions

| Kind | Change | Guard |
|---|---|---|
| DISABLE_MOD / ENABLE_MOD | rename `x.jar` ↔ `x.jar.disabled` | file must be inside `mods/` |
| INSTALL_DEPENDENCY | Modrinth lookup by id/alias, loader + MC version filtered, CDN URL only, SHA-1 verified, declared mod id must match | re-enables a disabled jar first if present |
| VERIFY_LIBRARIES | SHA-1 / zip check of every library + client jar of the version; corrupt files moved to the backup so the launcher re-downloads them | only `libraries/` and `versions/` |
| PRUNE_JVM_ARGS | removes just the rejected token from profile (or global, if used) args | `JvmArgsSanitizer` |
| ADJUST_MEMORY | profile `ramAllocation` → `SafeMemory.recommend` | never touches the global setting |
| FALLBACK_RENDERER | profile renderer → gl4es | blocked when already gl4es |
| SELECT_RUNTIME | profile runtime → installed Java ≥ required | blocked when none installed |
| DISABLE_SHADERS | `config/iris.properties enableShaders=false`, `optionsshaders.txt shaderPack=OFF` | snapshot first |
| DISABLE_RESOURCE_PACK | edits only the two pack keys in `options.txt` | `OptionsFileEditor` |
| RESET_CONFIG | moves `config/<file>` to the backup | `PathGuard.resolveRelative` |
| CHECK_NETWORK | 3 bounded probes, no file change | – |

Backups: `<profile root>/.autofix/backups/<id>/manifest.json` + `files/`. Nothing is deleted from
player folders; downloaded dependencies are the only files a rollback removes.

## Optional AI provider

`autofix/ai/AiDiagnosticProvider` receives `LogRedactor`-cleaned lines plus an `AiRequest`
whitelist and must return the strict JSON validated by `AiResponseValidator`. Validated answers
become ordinary `RepairStep`s and go through the same backup/verify path. No provider is wired
in by default.

## Tests

`gradle :app_cs:testFullDebugUnitTest` (also run in CI before the APK build).
