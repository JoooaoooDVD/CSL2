#!/usr/bin/env bash
# Run in the foreground (Termux / no systemd). Use setup.sh first.
cd "$(dirname "$0")"
[ -x .venv/bin/python ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
exec .venv/bin/python bot.py
