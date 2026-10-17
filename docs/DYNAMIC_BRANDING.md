# CS Dynamic Branding — "Minecraft Appearance"

Per-profile switch between the stock Minecraft look and a Craft Studio look, implemented
**entirely with a generated resource pack** (no client mod, no jar patching).

| Option | What happens |
|---|---|
| **Vanilla Experience** (default) | Launcher pack is removed from `options.txt` → `resourcePacks` / `incompatibleResourcePacks`; the `darkMojangStudiosBackgroundColor` value that was there before is restored (or the line removed if it never existed). The pack folder stays on disk for reuse; nothing else is touched. |
| **CS Custom Experience** | At launch (and right after saving the profile) the launcher reads the profile's client jar, builds/reuses `resourcepacks/CS-Dynamic-Branding`, appends `file/CS-Dynamic-Branding` to the **end** of `resourcePacks` (highest priority, other packs and their order preserved), removes it from the incompatible list, sets `darkMojangStudiosBackgroundColor:true`, then re-reads both the pack and `options.txt` before reporting success. |

## 1. Where things live

| Piece | Path |
|---|---|
| Profile setting | `Instance.appearance` (`null`/`"vanilla"` or `"custom"`), `Instance.appearanceParts` (comma list of the selected parts, `null` = everything) and `Instance.appearanceRestore` (JSON restore record) in the profile's `instance.json` — same framework as renderer/JVM args; unknown values sanitize to vanilla / everything. |
| Scope model | `game/branding/BrandingPart` — `MENU` (title, panorama, splashes), `WIDGETS` (buttons, sliders, text fields, scrollbars, checkboxes), `SCREENS` (menu backgrounds + separators), `TEXTS` (en_us loading/connecting texts), `DARK_LOADING` (the options flag). Every generator mapping is tagged with one part; the pack stamp carries the selection, so changing it rebuilds the pack. |
| Editor UI | Profile editor row **Minecraft Appearance** (`pe_row_appearance`, phone + w600dp layouts) → `AppearancePickerDialog`: two preview cards (Vanilla / CS Custom) and, for Custom, a "What changes" section with presets *Everything* / *Home screen only* / *Custom* plus one toggle row per part (at least one must stay on). Nothing is saved until **Apply** + the editor's Save. |
| Orchestration | `game/branding/BrandingManager` (Android): resolve game dir (shared vs isolated via `Instance.getGameDirectory()`), locate jar, generate, activate, verify, restore. |
| Generator | `game/branding/BrandingPackGenerator` + `ClientJarFacts` (pure Java, desktop-testable). |
| options.txt editing | `game/branding/GameOptionsFile` — line-preserving, JSON-list aware, atomic save. |
| Bundled assets | `app_cs/src/main/assets/cs_branding/` (≈1.5 MB): title, edition line, 6 panorama faces (512²), 3 button sprites, menu/list tiles, separators, `pack.png`, `splashes.txt`, `lang_overrides.json`. All derived from the launcher's own `cs_logo.png` + `minecraft_ten.ttf`. |
| Launch hook | `GameRunner.launchGame` → `applyBrandingBeforeLaunch` (worker thread, after the other options fixes, then `MCOptionUtils.load()` refreshes the cache). Failures toast an actionable message and the game still launches with the vanilla look. A second guard, `BrandingManager.reassertBeforeExec`, runs right before the JVM starts: if any later options write dropped the entry it is put back, and the final `resourcePacks` line is written to the launcher log as `[CSBranding] …`. |
| Launch status pill | `layout/view_launch_screen.xml` (included by `activity_basemain.xml` as `main_loading_screen`) — a small floating pill at the bottom (CS mark, "Launching Minecraft <version> • <profile>", sweeping platinum line; crash hint only after 25 s). No scrim, no touch interception: the controls stay visible and editable while the game starts. Hidden by the existing `GameActivity.hideLoadingScreen()` as soon as the game window appears. |
| Harness | `tools/branding-harness/` — runs the generator against real client jars. |

## 2. Version awareness (1.21 → 26.3 and beyond)

There is **no version table**. For the exact jar that will run:

1. `version.json` → `pack_version`. `{"resource": 34}` (1.21–1.21.8) or
   `{"resource_major": 75, "resource_minor": 0}` (1.21.9+). Anything below 34 ⇒ *unsupported*
   (clear message, pack deactivated, nothing installed).
2. `pack.mcmeta` is written in the matching syntax:
   * 1.21.1: `{"pack":{"pack_format":34,…}}`
   * 1.21.11: `{"pack":{"pack_format":75,"min_format":75,"max_format":75,…}}`
   * 26.3: `{"pack":{"pack_format":97,"min_format":97,"max_format":97,…}}`

   `min_format`/`max_format` are written as plain ints on purpose: the game's `PackFormat`
   codecs expand an int to `[major, 0]` for the lower bound and `[major, MAX_INT]` for the upper
   bound (verified in the 26.3 client: `PackFormat.BOTTOM_CODEC` / `TOP_CODEC`), so every minor
   revision of the major stays *compatible* and `Options.loadSelectedResourcePacks` never drops the
   entry as "no longer compatible". An exact `[97,1]` upper bound would silently break on 97.2.
3. Every texture is only emitted if **that jar ships the same path** (the asset layout was verified
   identical in 1.21.1, 1.21.11 and 26.3: `textures/gui/title/*`, `title/background/panorama_*`,
   `sprites/widget/button*`, `menu_background`, separators, `texts/splashes.txt`). A future rename
   loses one override instead of breaking the pack.
4. `.mcmeta` sidecars (nine-slice for buttons, `blur` on the 1.21.1 title) are copied from the jar.
5. Loading-text overrides (`lang/en_us.json`) are intersected with the jar's real keys
   (17 keys on 1.21.1, 12 on 1.21.11/26.3 because Mojang renamed some).
6. Snapshots / newer releases with the same layout work automatically (reported with their own id).

Marker `.cs-dynamic-branding.json` stores `stamp = r<generatorRevision>:<versionId>:<packFormat>`
and the file list. Same stamp + all files present ⇒ **reused** (0–1 ms, no rewrite). A shared-data
profile that alternates between two versions rebuilds the pack on switch (≈10–60 ms, ~1.5 MB copy).

## 3. Safety

* Temp folder `.CS-Dynamic-Branding.tmp` → rename swap with `.old` fallback → verify → done.
* A `CS-Dynamic-Branding` folder **without the marker is never replaced** (refuses with a message).
* `options.txt`: only `resourcePacks`, `incompatibleResourcePacks`, `darkMojangStudiosBackgroundColor`
  lines change; everything else keeps its bytes and order (unlike the HashMap writer). Atomic save.
* Restore record is captured once (first activation) and cleared on vanilla restore; corrupted record ⇒ game default.
* Isolated profiles get the pack inside their own game dir; shared-data profiles share the one in the shared dir (that is where their `options.txt` lives too).
* No deletion of user packs, worlds, mods or settings in any path.

## 4. What the custom look changes — and what it cannot

Replaced by the pack: title logo ("CRAFT STUDIO" + CS mark), edition line ("CS LAUNCHER PLUS"),
night-time panorama (dark obsidian/platinum, Minecraft blocky landscape), widget sprites in the
launcher's night palette (buttons normal/hover/disabled with rounded 1 px platinum strokes and a
soft hover glow, sliders + handles, text fields, scrollbars, checkboxes), option/list screen
backgrounds and separators, splash lines, several loading/connecting texts (English only — other
UI languages keep Mojang's text), pack icon. Singleplayer / Multiplayer / Realms / Options / Quit
are untouched and keep working.

**Scoping.** The user decides what the custom look replaces. *Everything* gives the full
experience; *Home screen only* writes just the title/panorama/splash files (the pack then contains
13 files on 1.21.1, 11 on newer versions) and leaves buttons, option screens, texts and the loading
flag vanilla. Any mix is allowed; a selection with only *Dark loading screen* needs no pack at all —
the launcher then removes its pack entry and only sets the option. Deselecting the dark flag
restores the user's previous value from the restore record, exactly like switching to Vanilla.

**Widgets.** The widget sprites use the launcher's pill language: `hm_field` fill, 1 px
`hm_inner_stroke` stroke, radius 9 on the 20 px buttons/slider, radius 6 on text fields and
checkboxes, radius 3 on slider handles, with the hover state in the pressed-card tone + platinum
stroke and a soft glow. Because these corners are larger than vanilla's, the generator ships its
**own** `.mcmeta` nine-slice metadata (`border` 10/9/10/10 for buttons and the slider, 7 for text
fields, 3/3/3/4 for handles, 2 for the scroller) instead of copying the jar's; bundled metadata wins,
the jar's metadata is the fallback (e.g. the 1.21.1 title `blur` flag).

**Loading screen.** The first Mojang overlay cannot be re-skinned by a pack (it is read from the
built-in vanilla pack before packs load). Inside the game only `darkMojangStudiosBackgroundColor`
can change it, which is what the *Dark loading screen* part does. The earlier launcher-drawn
"loading curtain" over the game surface was removed on request, and the old full-screen "Waiting
for a game window…" overlay is now a small non-blocking **launch status pill**
(`view_launch_screen.xml`) so the controls can be seen and customised from the first frame.

Honest limitations (resource packs simply cannot do these; a client mod would be needed, which was
explicitly left out of scope):

* **The very first Mojang Studios loading screen** is drawn from the built-in vanilla pack before
  any resource pack loads; inside the game only `darkMojangStudiosBackgroundColor` can change it.
  No launcher overlay is drawn over it any more.
* Menu **layout** (button positions, extra buttons, removing the Realms button) stays vanilla.
* The "Copyright Mojang AB" footer text stays (it is a hard-coded string).
* Button text colour and font remain vanilla.
* Lang overrides apply to `en_us` only.
* Loading-text overrides only exist for keys the running version still has.

## 5. Performance

Measured on the desktop harness (same code path): facts + generate 9–57 ms for a fresh pack,
0–1 ms when reused. Pack size 1.5 MB (dominated by six 512² panorama faces; vanilla's are 1024²).
Nothing runs when a profile is on Vanilla except one `options.txt` read at launch; no background
work when the game is not being launched.

## 6. Test matrix

| # | Test | Status |
|---|---|---|
| A | Vanilla experience: no pack entry, previous options intact | ✅ harness (options restore byte-exact) · device pending |
| B | Custom experience: pack generated, enabled, verified | ✅ harness (1.21.1, 1.21.11, 26.3) · device pending (visual) |
| C | Loading screen dark option set/restored | ✅ harness · device pending |
| C2 | Scoping: "Home screen only" rebuilds with 11–13 files and no widget/screen/lang entries; switching back to Everything regenerates; dark-only needs no pack; (de)serialization | ✅ harness |
| C3 | Widget sprites ship the launcher-pill nine-slice metadata (not vanilla's) | ✅ harness |
| D | Profile isolation (isolated vs shared dir) | code path via `Instance.getGameDirectory()` · device pending |
| E | Switching back and forth | ✅ harness round trip · device pending |
| F | Repeated launches reuse the pack | ✅ harness (second run reused) |
| G | Compatibility: 1.20.1 ⇒ unsupported, 1.21.1/1.21.11/26.3 ⇒ supported | ✅ harness |
| H | Performance | ✅ harness numbers above · device numbers pending |

### Device test steps

1. Profile editor → **Minecraft Appearance** → *CS Custom Experience* → (optionally pick the parts)
   → Apply → Save. Toast reports either "active for <version>" or "prepared on first launch"
   (version not downloaded yet). The editor row shows the selected parts.
2. Launch: logcat tag `CSBranding` shows `Custom experience … pack generated/reused … options updated`.
3. In game: Options → Resource Packs shows "CS Dynamic Branding" selected (top of the list); title
   screen shows the Craft Studio logo + night panorama; buttons dark; loading screen black.
4. Switch to *Vanilla Experience* → Save → launch: stock menu; `options.txt` no longer lists
   `file/CS-Dynamic-Branding`; `darkMojangStudiosBackgroundColor` back to its earlier value/absent.
5. Try a 1.20.x profile with Custom: toast "needs Minecraft 1.21 or newer", game launches vanilla.
6. Scoping: pick *Home screen only* → Save → launch: title/panorama are CS, buttons and option
   screens are stock, loading screen red/vanilla; `options.txt` still lists the pack. Then pick only
   *Dark loading screen*: pack entry disappears from `options.txt`, the flag is `true`.
7. Every launch: a small pill at the bottom shows "Launching Minecraft <version> • <profile>" with a
   moving platinum line; the controls behind it stay touchable/editable; it fades when the game
   window appears.
