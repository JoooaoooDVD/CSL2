# Discord Rich Presence

Package: `app_cs/src/main/java/com/craftstudio/cslauncher/discord/`

## How it reaches Discord
* Official **Discord Social SDK** (`libs/discord_partner_sdk.aar`, `jni/discord_bridge.cpp`).
* On Android (SDK 1.10+) the SDK publishes presence through the **installed Discord app's local
  RPC service** – no account link / OAuth is required. Linking an account (`DiscordAuthManager`)
  is optional and only personalises the card in the launcher.
* The presence has no options. `DiscordPresenceManager` composes it from what is running:

| Scene | details | state | large image (hover text) | small image |
|---|---|---|---|---|
| HOME | Home screen | Profile: *profile* | CS logo (CS Launcher Plus · Minecraft Java on Android) | – |
| LAUNCHING | Launching *profile* | 1.21.4 · Fabric · 42 mods | profile icon (*profile* · Minecraft 1.21.4 · Fabric · 42 mods) | CS logo |
| LOADING / MENU | *profile* | Loading… / Main menu | profile icon (full stack) | CS logo |
| SINGLEPLAYER | *profile* | World: *world* | profile icon (full stack) | loader icon (Fabric 1.21.4) |
| LAN_HOST | *profile* | LAN world: *world* | profile icon (full stack) | loader icon |
| MULTIPLAYER | *server name* | Multiplayer · *profile* | **server icon** from `servers.dat` (falls back to profile icon) | profile icon (or loader icon when no server icon) |

Every line is trimmed to 32 characters (`fit()`), so nothing wraps on the Discord mobile card; the
long version/loader/mod-count text lives in the hover text of the large image.

### Multiplayer: server name + icon

`MinecraftServerList` is a tiny NBT reader for `<game dir>/servers.dat` (gzip or plain). When the game
logs `Connecting to host, port`, the manager looks the host[:port] up in the saved servers list:

* `details` becomes the name the player gave the server ("Hypixel") instead of the address;
  unsaved servers fall back to the host, private/LAN addresses to "Private server".
* The cached server icon (64×64 PNG, base64 or byte array) is published through
  `DiscordProfileIconPublisher.publishPng()` — same write-once SHA-1 bucket as profile icons — and
  becomes the large image; the profile icon moves to the small badge. Lookups and uploads are cached
  per host:port for the session.

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

## Account linking → community bot

Linking requests the scopes `identify guilds.join`. After a successful link (and on unlink) the
launcher inserts a row into the write-only Supabase table `discord_link_events`
(`DiscordLinkNotifier`). The self-hosted bot in `server/discord-bot/` consumes those rows: it adds
the member to the Craft Studio server with the OAuth token, gives the "Linked" role, sends a welcome
DM, and then deletes the token from the row. See `server/discord-bot/README.md`.
