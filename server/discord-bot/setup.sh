#!/usr/bin/env bash
# One-shot installer for the Craft Studio Discord bot (Ubuntu/Debian VPS).
#   unzip cs-discord-bot.zip && cd cs-discord-bot && sudo bash setup.sh
# Installs python, creates a venv, asks for the bot token once, installs + starts a systemd service.
set -euo pipefail
cd "$(dirname "$0")"
DIR="$(pwd)"
SVC=cs-discord-bot

say() { printf '\033[1;36m[cs-bot]\033[0m %s\n' "$*"; }

# 1) python ------------------------------------------------------------------
if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import venv' 2>/dev/null; then
  say "Installing python3 + venv..."
  if command -v apt-get >/dev/null; then
    apt-get update -qq && apt-get install -y -qq python3 python3-venv python3-pip >/dev/null
  elif command -v dnf >/dev/null; then dnf install -y -q python3 python3-pip
  elif command -v apk >/dev/null; then apk add -q python3 py3-pip
  elif command -v pkg >/dev/null; then pkg install -y python
  fi
fi
PYV=$(python3 -c 'import sys;print(sys.version_info>=(3,9))')
[ "$PYV" = "True" ] || { echo "Python 3.9+ required"; exit 1; }

# 2) venv + deps --------------------------------------------------------------
if [ ! -x .venv/bin/python ]; then
  say "Creating virtual environment..."
  python3 -m venv .venv
fi
say "Installing dependencies..."
.venv/bin/pip install -q --upgrade pip >/dev/null
.venv/bin/pip install -q -r requirements.txt

# 3) token ----------------------------------------------------------------------
[ -f .env ] || cp .env.example .env
TOKEN="${DISCORD_BOT_TOKEN:-}"
CUR=$(grep -E '^DISCORD_BOT_TOKEN=' .env | cut -d= -f2- || true)
if [ -z "$CUR" ] && [ -z "$TOKEN" ]; then
  echo
  echo "  Discord Developer Portal -> Applications -> 1563136590275153960 (CRAFT STUDIO)"
  echo "  -> Bot -> Reset Token -> Copy.   (Also turn ON 'Server Members Intent' on that page.)"
  echo
  read -r -p "  Paste the bot token: " TOKEN
fi
if [ -n "$TOKEN" ]; then
  TOKEN=$(printf '%s' "$TOKEN" | tr -d '[:space:]')
  python3 - "$TOKEN" <<'PY'
import re,sys,io
tok=sys.argv[1]; s=open('.env',encoding='utf-8').read()
s=re.sub(r'^DISCORD_BOT_TOKEN=.*$', 'DISCORD_BOT_TOKEN='+tok, s, flags=re.M)
open('.env','w',encoding='utf-8').write(s)
PY
fi
chmod 600 .env
mkdir -p data

# 4) quick check ----------------------------------------------------------------
say "Checking token + Supabase..."
.venv/bin/python - <<'PY'
import os,sys,asyncio,aiohttp
from dotenv import load_dotenv; load_dotenv()
tok=os.getenv("DISCORD_BOT_TOKEN",""); url=os.getenv("SUPABASE_URL"); key=os.getenv("SUPABASE_SERVICE_KEY")
async def main():
    async with aiohttp.ClientSession() as s:
        async with s.get("https://discord.com/api/v10/users/@me",headers={"Authorization":"Bot "+tok}) as r:
            if r.status!=200: print("  !! Discord rejected the bot token (HTTP %d). Fix DISCORD_BOT_TOKEN in .env"%r.status); sys.exit(1)
            me=await r.json(); print("  Discord OK: %s#%s (%s)"%(me['username'],me.get('discriminator','0'),me['id']))
            app=me['id']
        async with s.get(url+"/rest/v1/discord_link_events?select=id&limit=1",headers={"apikey":key,"Authorization":"Bearer "+key}) as r:
            if r.status!=200: print("  !! Supabase rejected the service key (HTTP %d)"%r.status); sys.exit(1)
            print("  Supabase OK")
        gid=os.getenv("GUILD_ID","")
        async with s.get(f"https://discord.com/api/v10/guilds/{gid}",headers={"Authorization":"Bot "+tok}) as r:
            if r.status!=200:
                print("\n  The bot is NOT in the Craft Studio server yet. Open this link once and click Authorize:\n")
                print(f"  https://discord.com/oauth2/authorize?client_id={app}&scope=bot%20applications.commands&permissions=268454929&guild_id={gid}\n")
            else:
                g=await r.json(); print("  Server OK: %s"%g['name'])
asyncio.run(main())
PY

# 5) service ----------------------------------------------------------------------
if command -v systemctl >/dev/null 2>&1 && [ "$(id -u)" = "0" ]; then
  say "Installing systemd service '$SVC'..."
  RUN_USER=${SUDO_USER:-root}
  chown -R "$RUN_USER" "$DIR"
  cat > /etc/systemd/system/$SVC.service <<UNIT
[Unit]
Description=Craft Studio Discord bot (CS Launcher Plus account links)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$DIR
EnvironmentFile=$DIR/.env
ExecStart=$DIR/.venv/bin/python $DIR/bot.py
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable --now $SVC
  sleep 4
  systemctl --no-pager --lines=15 status $SVC || true
  echo
  say "Done. Logs:  journalctl -u $SVC -f     Restart:  systemctl restart $SVC"
else
  say "No systemd/root - starting in the foreground (Ctrl+C to stop). Use ./run.sh next time."
  exec .venv/bin/python bot.py
fi
