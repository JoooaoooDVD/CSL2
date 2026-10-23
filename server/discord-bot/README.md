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

## Setup (one time)

### 1. Discord application

Use the **same application** as the launcher's Social SDK app id (`DiscordPresenceManager.APPLICATION_ID`)
— `guilds.join` only works with a token issued to the same app as the bot.

* *Bot* tab → **Add Bot** (if not yet) → copy the **token** → `DISCORD_BOT_TOKEN`.
* *Bot* tab → **Privileged Gateway Intents** → enable **Server Members Intent**.
* *OAuth2* tab → the Social SDK redirect `discord-<APP_ID>:/authorize/callback` must already be there
  (it is, from the Rich Presence setup). Nothing else to add — the launcher now requests
  `identify guilds.join` when linking.
* Invite the bot to the Craft Studio server with this URL (replace `APP_ID`):

  ```
  https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot%20applications.commands&permissions=268435457
  ```

  `268435457` = **Create Invite** (needed for `guilds.join`) + **Manage Roles** + **View Channels**.
  Give the bot **Send Messages + Embed Links** in the log channel.

### 2. Server

* Create a role, e.g. **Linked** → copy its id → `LINKED_ROLE_ID`.
* **Move the bot's role above the Linked role** in *Server Settings → Roles*, otherwise it cannot assign it.
* (Optional) create a private `#launcher-links` channel → `LOG_CHANNEL_ID`.
* Developer Mode (User Settings → Advanced) lets you right-click → *Copy ID* for the server/role/channel.

### 3. Supabase

The tables already exist in the project (`discord_link_events`, `discord_links`). The bot needs the
**service_role** key: Supabase dashboard → *Project Settings → API → service_role*. Keep it secret
(it is only ever on the machine running the bot).

### 4. Run it

```bash
cd server/discord-bot
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env     # fill in the values
python bot.py
```

Expected log:

```
INFO cs-bot: Slash commands synced to guild 123…
INFO cs-bot: Logged in as Craft Studio#1234 (…); guild: Craft Studio
INFO cs-bot: Permission check OK
```

If the permission check warns, fix exactly what it says (missing permission / role order).

#### systemd (VPS)

```bash
sudo useradd -r -m -d /opt/cs-discord-bot csbot
sudo cp -r . /opt/cs-discord-bot && sudo chown -R csbot:csbot /opt/cs-discord-bot
sudo -u csbot bash -c 'cd /opt/cs-discord-bot && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt'
sudo cp cs-discord-bot.service /etc/systemd/system/ && sudo systemctl enable --now cs-discord-bot
journalctl -u cs-discord-bot -f
```

#### Docker

```bash
docker build -t cs-discord-bot . && docker run -d --name cs-discord-bot --env-file .env --restart unless-stopped cs-discord-bot
```

Works on any free/cheap host too (Railway, Fly.io, Oracle free tier, a Raspberry Pi) — it only
needs outbound HTTPS.

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
