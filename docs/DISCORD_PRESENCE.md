# Discord Rich Presence

Package: `app_cs/src/main/java/com/craftstudio/cslauncher/discord/`

## How it reaches Discord
* Official **Discord Social SDK** (`libs/discord_partner_sdk.aar`, `jni/discord_bridge.cpp`).
* On Android (SDK 1.10+) the SDK publishes presence through the **installed Discord app's local
  RPC service** – no account link / OAuth is required. Linking an account (`DiscordAuthManager`)
  is optional and only personalises the card in the launcher.
* The presence has no options. `DiscordPresenceManager` composes it from what is running:

| Scene | details | state | large image | small image |
|---|---|---|---|---|
| HOME | On the home screen | Selected: profile · version | CS logo | – |
| LAUNCHING | Launching *profile* | Minecraft x.y · Loader · n mods | profile icon | CS logo |
| LOADING / MENU | Playing *profile* | Loading the game / In the main menu · … | profile icon | CS logo |
| SINGLEPLAYER | Playing *profile* | Singleplayer: *world* | profile icon | loader icon |
| LAN_HOST | Playing *profile* | Hosting LAN world: *world* | profile icon | loader icon |
| MULTIPLAYER | Playing *profile* | Multiplayer: *server* (raw IPs hidden) | profile icon | loader icon |

Plus: session timer, two buttons (Discord invite, YouTube), `StatusDisplayType = Details` in game
so the friends list reads "Playing *profile*".

## Profile icon
Discord only shows https images. `DiscordProfileIconPublisher` renders the profile icon exactly as
the profile list does (custom picture or built-in art), hashes the 256 px PNG and uploads it once to
the public, write-once Supabase bucket `presence-icons` (key = SHA-1, anon INSERT only, 256 KB PNG
limit). The URL is cached in `files/discord_presence_icons.json`. Fallbacks: modpack `iconUrl`,
loader icon from `cslauncher-assets`, CS logo.

## Processes
* Launcher process: `LauncherActivity.onResume → setLauncherIdle`, PLAY → `setMinecraftLaunching`,
  `onStop → releaseFromLauncher` (clears and drops the SDK client).
* Game process (`:game`): `GameActivity`/`GameRunner → onGameProcessStarted` (waits 1.5 s so the
  launcher has released the connection), then follows the game log through `LogTap`
  (`Connecting to`, `Preparing level`, `Started serving on`, `Stopping server`, …). Process exit
  → `onGameExiting`.

## UI
`CSDiscordRpcSheet` = `SettingsPopup` with a custom view: delivery status strip, live preview card
(mirrors the snapshot, ticking timer, scene chip, buttons) and the optional account row. Footer:
Link/Unlink (stays open) + Done. Settings → Miscellaneous keeps the single on/off switch.
