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
Applications → *CS Launcher Plus* `1551939107549225000` → **Bot** → *Reset Token*; on the same page
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

Slash commands: `/links me` (anyone), `/links stats`, and for staff `/links lookup`, `/links recent`,
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
