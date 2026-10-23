# Craft Studio Discord bot

Companion bot for **CS Launcher Plus**. When a player links their Discord account inside the
launcher (Settings → Discord Rich Presence → Link), the bot:

1. **adds them to the Craft Studio Discord server** (OAuth `guilds.join`, no invite link needed),
2. gives them the **Linked** role,
3. sends a **welcome DM** ("Account linked") with community buttons,
4. posts the event to a **log channel**,
5. remembers the link in Supabase (`discord_links`).

On unlink the role is removed and a short DM is sent. If a linked player joins the server later
(e.g. they declined the auto-join), the role is given on join.

Slash commands (guild only): `/links stats`, `/links lookup @user`, `/links sync`, `/links queue`
(`lookup`/`sync`/`queue` are staff-only: Administrator / Manage Server / `ADMIN_ROLE_ID`).

## Features (v2)

| Area | What the bot does |
|---|---|
| **Account link** | auto-join via `guilds.join` · Linked role · welcome DM with buttons + persistent *Rich Presence help* button · log embed · token wiped |
| **Unlink** | role removed · short DM · log |
| **Zero-config** | auto-detects the server, creates the **Linked** role, a private **#launcher-links** log channel and a locked **🔗 Linked: N** counter channel; remembers IDs in `data/state.json` |
| **Reliability** | transient Discord/network errors retried with back-off (max 5), poison rows never block the queue, Supabase outage/recovery alerts, graceful fallback when the Members intent is off |
| **Anti-spam** | re-linking within 60 min doesn't re-send the DM |
| **Members** | welcome embed for new members (system channel), instant role for linked players on join, role sync every 5 min, role flags cleared when someone leaves |
| **Stats** | per-day counters (`discord_bot_stats`), 7-day bar chart, daily digest in the log channel, milestone posts (10 · 25 · 50 · 100 · …), bot status "Watching N linked players" |
| **Launcher integration** | linked players' launchers report play sessions → **🎮 Playing now** counter, `/launcher stats` (versions, loaders, peak hour, who's online), `/launcher servers` (most played servers), personal playtime in `/links me`, `/links top` leaderboard · staff can push a **popup notice into every launcher** with `/launcher notice set` (title, text, link button, expiry, min version) |
| **Commands** | `/links me` · `/links stats` · staff: `lookup`, `recent`, `sync`, `unlink`, `queue`, `setup`, `export` (CSV) · `/launcher download` / `faq [search]` / `features` / `links` / `presence` / `stats` / `servers` / `notice set|clear|show` (live from the website CMS) · `/bot status` / `invite` / `preview-dm` · right-click user → *Launcher link info* |

## How it works

```
Launcher (link OK) ──INSERT──▶ Supabase table discord_link_events (anon: INSERT only)
                                        │
                                        ▼  poll every POLL_SECONDS (service key)
                                   bot.py ──▶ PUT /guilds/{g}/members/{uid}  (access_token)
                                          ──▶ add role · DM · log channel
                                          ──▶ PATCH row: processed_at, result, access_token = NULL
```

The launcher can only *insert* rows (RLS), it cannot read anything back. The OAuth token in the row
is deleted right after it is used.

## Setup — 2 minutes

Everything is pre-configured in `.env` (server id, Supabase, invite link). The bot creates the
**Linked** role and a private **#launcher-links** channel by itself on first start.

```bash
unzip cs-discord-bot.zip && cd cs-discord-bot
sudo bash setup.sh
```

`setup.sh` installs Python, the dependencies, asks for the **bot token** once (Developer Portal →
Applications → *CS Launcher Plus* `1563136590275153960` → **Bot** → *Reset Token*; on the same page
turn ON **Server Members Intent**), verifies token + Supabase + server membership, and installs a
systemd service (`cs-discord-bot`) that auto-starts on reboot.

If the bot is not in the server yet, setup prints a one-click **invite link** — open it, Authorize, done.

Commands afterwards:

```bash
journalctl -u cs-discord-bot -f      # live logs
systemctl restart cs-discord-bot     # restart
./update.sh                          # after replacing bot.py
./run.sh                             # foreground run (Termux / no systemd)
```

Docker / Railway / Render: `Dockerfile` + `Procfile` are included; set the same variables as `.env`.

Slash commands: `/links me`, `/links stats`, `/links top` (anyone), and for staff `/links lookup`, `/links recent`,
`/links sync`, `/links unlink`, `/links queue`, `/links setup`.

## Testing

1. Start the bot, then link an account in the launcher (or re-link: unlink → link).
2. Within `POLL_SECONDS` you should get the DM, the role, and a log-channel post.
3. `/links queue` processes pending rows immediately; `/links stats` shows totals.
4. Row results are stored in `discord_link_events.result` (e.g. `ok: joined the server · role given · DM sent`).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `guilds.join failed: HTTP 403 code 50013` | Bot lacks **Create Invite** or role is above the bot's role |
| `guilds.join failed: HTTP 400 code 50025` / `50035` | Token not issued for this application or without `guilds.join` → check app id matches, user must re-link with the new launcher build |
| `guilds.join failed: … code 40007` | User is banned from the server |
| `DM blocked` | User disabled DMs from server members — role and join still work |
| `role FAILED` | Move the bot role above the Linked role; needs Manage Roles |
| Nothing happens | `SUPABASE_SERVICE_KEY` wrong (poll error in log) or launcher build is older than the link-event feature |
