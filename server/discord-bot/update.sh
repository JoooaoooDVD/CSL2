#!/usr/bin/env bash
# Re-install dependencies and restart the service after replacing bot.py.
cd "$(dirname "$0")"
.venv/bin/pip install -q -r requirements.txt
systemctl restart cs-discord-bot 2>/dev/null && journalctl -u cs-discord-bot -n 20 --no-pager || ./run.sh
