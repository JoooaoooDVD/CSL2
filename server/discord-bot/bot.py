#!/usr/bin/env python3
"""
Craft Studio community bot for CS Launcher Plus.

Pipeline
--------
Launcher (link / unlink)  ──INSERT──▶  Supabase `discord_link_events`  ──poll──▶  this bot
    ▸ adds the player to the Craft Studio server (OAuth guilds.join)      ▸ gives the "Linked" role
    ▸ welcome DM with buttons                                              ▸ log channel embed
    ▸ remembers everything in `discord_links` / `discord_bot_stats`        ▸ wipes the OAuth token

Also: zero-config auto setup (role, private log channel, stats channel), retries with back-off,
DM anti-spam, member welcome, periodic role sync (works even without the Members intent), daily
digest, milestones, Supabase outage alerts, and a bunch of slash commands:

  /links me|stats|top|lookup|recent|sync|unlink|queue|setup|export
  /launcher download|faq|features|links|presence|stats|servers|notice
  /bot status|invite|preview-dm

Launcher integration (linked players only): the launcher reports play sessions
(`launcher_events`) → "playing now" counter, favourite versions / servers, personal playtime,
leaderboard. Staff can push a notice to every launcher with `/launcher notice set`.
  (user context menu) "Launcher link info"
"""

from __future__ import annotations

import asyncio
import csv
import io
import json
import logging
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

import aiohttp
import discord
from discord import app_commands
from discord.ext import tasks

try:  # optional, lets you keep secrets in a .env file next to bot.py
    from dotenv import load_dotenv

    load_dotenv()
except Exception:  # pragma: no cover
    pass

BOT_VERSION = "2.1.0"

# =============================================================================== configuration


def _env(name: str, default: Optional[str] = None, required: bool = False) -> str:
    value = os.getenv(name, default)
    if required and not value:
        print(f"[config] Missing required environment variable {name}", file=sys.stderr)
        sys.exit(2)
    return (value or "").strip()


def _env_int(name: str, default: int = 0) -> int:
    raw = _env(name, str(default))
    try:
        return int(raw) if raw else 0
    except ValueError:
        print(f"[config] {name} must be a number, got {raw!r}", file=sys.stderr)
        sys.exit(2)


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name, "true" if default else "false").lower()
    return raw in ("1", "true", "yes", "on")


BOT_TOKEN = _env("DISCORD_BOT_TOKEN", required=True)
SUPABASE_URL = _env("SUPABASE_URL", required=True).rstrip("/")
SUPABASE_SERVICE_KEY = _env("SUPABASE_SERVICE_KEY", required=True)

GUILD_ID = _env_int("GUILD_ID")                    # 0 = auto (single server)
LINKED_ROLE_ID = _env_int("LINKED_ROLE_ID")        # 0 = auto-create
LINKED_ROLE_NAME = _env("LINKED_ROLE_NAME", "Linked")
LOG_CHANNEL_ID = _env_int("LOG_CHANNEL_ID")        # 0 = auto-create private channel
LOG_CHANNEL_NAME = _env("LOG_CHANNEL_NAME", "launcher-links")
WELCOME_CHANNEL_ID = _env_int("WELCOME_CHANNEL_ID")  # 0 = server's system channel
WELCOME_ENABLED = _env_bool("WELCOME_ENABLED", True)
STATS_CHANNEL_ENABLED = _env_bool("STATS_CHANNEL", True)
STATS_CHANNEL_ID = _env_int("STATS_CHANNEL_ID")
PLAYING_CHANNEL_ID = _env_int("PLAYING_CHANNEL_ID")
PLAYING_CHANNEL_ENABLED = _env_bool("PLAYING_CHANNEL", True)
LOG_PLAY_SESSIONS = _env_bool("LOG_PLAY_SESSIONS", False)
ADMIN_ROLE_ID = _env_int("ADMIN_ROLE_ID")
POLL_SECONDS = max(3, _env_int("POLL_SECONDS", 5))
DM_COOLDOWN_MIN = max(0, _env_int("DM_COOLDOWN_MINUTES", 60))
MAX_ATTEMPTS = max(1, _env_int("MAX_ATTEMPTS", 5))
INVITE_URL = _env("DISCORD_INVITE_URL", "https://discord.gg/dmteYqZZyY")
YOUTUBE_URL = _env("YOUTUBE_URL", "https://youtube.com/@craft-studio-official")
WEBSITE_URL = _env("WEBSITE_URL", "")
LAUNCHER_NAME = _env("LAUNCHER_NAME", "CS Launcher Plus")
COMMUNITY_NAME = _env("COMMUNITY_NAME", "Craft Studio")
EMBED_COLOR = int(_env("EMBED_COLOR", "0x5865F2"), 16)
DM_ON_UNLINK = _env_bool("DM_ON_UNLINK", True)
DIGEST_HOUR_UTC = _env_int("DIGEST_HOUR_UTC", 18)  # 18:00 UTC = 23:30 IST
LOG_LEVEL = _env("LOG_LEVEL", "INFO").upper()
STATE_FILE = Path(_env("STATE_FILE", str(Path(__file__).with_name("data") / "state.json")))
MILESTONES = (10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000)

logging.basicConfig(level=getattr(logging, LOG_LEVEL, logging.INFO),
                    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")
log = logging.getLogger("cs-bot")


def _load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text("utf-8"))
    except Exception:
        return {}


def _save_state(state: dict) -> None:
    try:
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        STATE_FILE.write_text(json.dumps(state, indent=2), "utf-8")
    except Exception as e:  # pragma: no cover
        log.warning("Could not save state: %s", e)


_STATE = _load_state()
GUILD_ID = GUILD_ID or int(_STATE.get("guild_id") or 0)
LINKED_ROLE_ID = LINKED_ROLE_ID or int(_STATE.get("linked_role_id") or 0)
LOG_CHANNEL_ID = LOG_CHANNEL_ID or int(_STATE.get("log_channel_id") or 0)
STATS_CHANNEL_ID = STATS_CHANNEL_ID or int(_STATE.get("stats_channel_id") or 0)
PLAYING_CHANNEL_ID = PLAYING_CHANNEL_ID or int(_STATE.get("playing_channel_id") or 0)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _fmt_ts(iso: Optional[str]) -> str:
    if not iso:
        return "–"
    try:
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
        return f"<t:{int(dt.timestamp())}:R>"
    except Exception:
        return iso[:16].replace("T", " ")


# =============================================================================== Supabase


class Supabase:
    """Small async PostgREST client (service-role key → bypasses RLS)."""

    def __init__(self, url: str, key: str) -> None:
        self._rest = f"{url}/rest/v1"
        self._headers = {"apikey": key, "Authorization": f"Bearer {key}", "Content-Type": "application/json"}
        self._session: Optional[aiohttp.ClientSession] = None
        self.healthy = True
        self.last_error: Optional[str] = None

    async def start(self) -> None:
        self._session = aiohttp.ClientSession(headers=self._headers, timeout=aiohttp.ClientTimeout(total=20))

    async def close(self) -> None:
        if self._session:
            await self._session.close()

    async def req(self, method: str, path: str, *, params: dict | None = None, json: Any = None,
                  prefer: str | None = None, headers: dict | None = None) -> Any:
        assert self._session is not None
        hdrs = dict(headers or {})
        if prefer:
            hdrs["Prefer"] = prefer
        try:
            async with self._session.request(method, f"{self._rest}/{path}", params=params, json=json,
                                             headers=hdrs) as resp:
                text = await resp.text()
                if resp.status >= 400:
                    raise RuntimeError(f"Supabase {method} {path} -> {resp.status}: {text[:300]}")
                self.healthy = True
                if not text:
                    return None
                try:
                    return await resp.json(content_type=None)
                except Exception:
                    return text
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            self.healthy = False
            self.last_error = str(e)
            raise RuntimeError(f"Supabase unreachable: {e}") from e

    async def count(self, table: str, params: dict | None = None) -> int:
        assert self._session is not None
        p = {"select": "id" if table != "discord_links" else "discord_user_id", **(params or {})}
        async with self._session.get(f"{self._rest}/{table}", params=p,
                                     headers={"Prefer": "count=exact", "Range": "0-0"}) as resp:
            cr = resp.headers.get("Content-Range", "*/0")
            try:
                return int(cr.split("/")[-1])
            except ValueError:
                return 0

    # events ----------------------------------------------------------------
    async def pending_events(self, limit: int = 25) -> list[dict]:
        return await self.req("GET", "discord_link_events", params={
            "processed_at": "is.null",
            "or": f"(next_attempt_at.is.null,next_attempt_at.lte.{_now()})",
            "order": "id.asc", "limit": str(limit),
        }) or []

    async def finish_event(self, event_id: int, result: str) -> None:
        await self.req("PATCH", "discord_link_events", params={"id": f"eq.{event_id}"},
                       json={"processed_at": _now(), "result": result[:500], "access_token": None},
                       prefer="return=minimal")

    async def retry_event(self, event_id: int, attempts: int, error: str) -> None:
        delay = min(3600, 30 * (2 ** attempts))
        when = (datetime.now(timezone.utc) + timedelta(seconds=delay)).isoformat()
        await self.req("PATCH", "discord_link_events", params={"id": f"eq.{event_id}"},
                       json={"attempts": attempts + 1, "next_attempt_at": when, "result": f"retry: {error}"[:500]},
                       prefer="return=minimal")

    # links -----------------------------------------------------------------
    async def get_link(self, user_id: int | str) -> Optional[dict]:
        rows = await self.req("GET", "discord_links", params={"discord_user_id": f"eq.{user_id}", "limit": "1"})
        return rows[0] if rows else None

    async def upsert_link(self, row: dict) -> None:
        await self.req("POST", "discord_links", json=row, prefer="resolution=merge-duplicates,return=minimal")

    async def update_link(self, user_id: int | str, patch: dict) -> None:
        await self.req("PATCH", "discord_links", params={"discord_user_id": f"eq.{user_id}"}, json=patch,
                       prefer="return=minimal")

    async def active_links(self, select: str = "discord_user_id,username,role_given,joined_guild") -> list[dict]:
        return await self.req("GET", "discord_links", params={
            "unlinked_at": "is.null", "select": select, "limit": "10000"}) or []

    async def all_links(self) -> list[dict]:
        return await self.req("GET", "discord_links", params={"order": "linked_at.desc", "limit": "10000"}) or []

    async def recent_events(self, limit: int = 12) -> list[dict]:
        return await self.req("GET", "discord_link_events", params={
            "select": "id,kind,discord_user_id,username,created_at,result,app_version,attempts",
            "order": "id.desc", "limit": str(limit)}) or []

    # stats -----------------------------------------------------------------
    async def bump_stat(self, field: str, n: int = 1) -> None:
        day = _today()
        try:
            rows = await self.req("GET", "discord_bot_stats", params={"day": f"eq.{day}", "limit": "1"})
            if rows:
                await self.req("PATCH", "discord_bot_stats", params={"day": f"eq.{day}"},
                               json={field: int(rows[0].get(field) or 0) + n, "updated_at": _now()},
                               prefer="return=minimal")
            else:
                await self.req("POST", "discord_bot_stats", json={"day": day, field: n},
                               prefer="resolution=merge-duplicates,return=minimal")
        except Exception as e:
            log.debug("bump_stat %s: %s", field, e)

    async def stats(self) -> dict:
        today = datetime.now(timezone.utc).strftime("%Y-%m-%dT00:00:00Z")
        week = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()
        return {
            "linked": await self.count("discord_links", {"unlinked_at": "is.null"}),
            "unlinked": await self.count("discord_links", {"unlinked_at": "not.is.null"}),
            "in_guild": await self.count("discord_links", {"unlinked_at": "is.null", "joined_guild": "is.true"}),
            "events_total": await self.count("discord_link_events"),
            "events_today": await self.count("discord_link_events", {"created_at": f"gte.{today}"}),
            "links_week": await self.count("discord_link_events", {"kind": "eq.link", "created_at": f"gte.{week}"}),
            "pending": await self.count("discord_link_events", {"processed_at": "is.null"}),
            "failed": await self.count("discord_link_events", {"result": "like.error*"}),
        }

    async def daily_series(self, days: int = 7) -> list[dict]:
        since = (datetime.now(timezone.utc) - timedelta(days=days - 1)).strftime("%Y-%m-%d")
        return await self.req("GET", "discord_bot_stats", params={"day": f"gte.{since}", "order": "day.asc"}) or []

    # play sessions (reported by the launcher for linked players) -------------
    async def sessions(self, *, since_hours: Optional[int] = None, user_id: Optional[int | str] = None,
                       limit: int = 5000) -> list[dict]:
        params: dict[str, str] = {"order": "started_at.desc", "limit": str(limit)}
        if since_hours:
            params["started_at"] = f"gte.{(datetime.now(timezone.utc) - timedelta(hours=since_hours)).isoformat()}"
        if user_id is not None:
            params["discord_user_id"] = f"eq.{user_id}"
        return await self.req("GET", "launcher_sessions", params=params) or []

    async def playing_now(self) -> list[dict]:
        """Sessions started in the last 12 h without an end event."""
        rows = await self.req("GET", "launcher_sessions", params={
            "ended_at": "is.null",
            "started_at": f"gte.{(datetime.now(timezone.utc) - timedelta(hours=12)).isoformat()}",
            "order": "started_at.desc", "limit": "500"}) or []
        seen: set[str] = set()
        out = []
        for r in rows:  # one entry per player
            if r["discord_user_id"] in seen:
                continue
            seen.add(r["discord_user_id"])
            out.append(r)
        return out

    async def set_notice(self, value: dict) -> None:
        await self.req("POST", "site_settings", json={"key": "launcher_notice", "value": value, "updated_at": _now()},
                       prefer="resolution=merge-duplicates,return=minimal")

    # website CMS (read-only) -------------------------------------------------
    async def site_setting(self, key: str) -> dict:
        rows = await self.req("GET", "site_settings", params={"key": f"eq.{key}", "limit": "1"})
        return (rows[0].get("value") or {}) if rows else {}

    async def latest_release(self, platform: str = "android") -> Optional[dict]:
        rows = await self.req("GET", "releases", params={"platform": f"eq.{platform}", "order": "published_at.desc",
                                                         "limit": "1"})
        return rows[0] if rows else None

    async def faq(self) -> list[dict]:
        return await self.req("GET", "faq", params={"enabled": "is.true", "order": "sort.asc", "limit": "50"}) or []

    async def features(self) -> list[dict]:
        return await self.req("GET", "features", params={"enabled": "is.true", "order": "sort.asc", "limit": "30"}) or []


# =============================================================================== views


class LinksView(discord.ui.View):
    """Buttons under the DMs. Persistent (timeout=None) so the help button keeps working after restarts."""

    def __init__(self) -> None:
        super().__init__(timeout=None)
        self.add_item(discord.ui.Button(label=f"{COMMUNITY_NAME} Discord", url=INVITE_URL, emoji="💬"))
        self.add_item(discord.ui.Button(label="YouTube", url=YOUTUBE_URL, emoji="▶️"))
        if WEBSITE_URL:
            self.add_item(discord.ui.Button(label="Website", url=WEBSITE_URL, emoji="🌐"))

    @discord.ui.button(label="Rich Presence help", style=discord.ButtonStyle.secondary, emoji="❔",
                       custom_id="cs:presence_help")
    async def presence_help(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.send_message(embed=presence_help_embed(), ephemeral=True)


def presence_help_embed() -> discord.Embed:
    e = discord.Embed(title="Discord Rich Presence · how it works", color=EMBED_COLOR)
    e.add_field(name="What shows up", inline=False, value=(
        "• **Home screen** / profile name while you're in the launcher\n"
        "• **Launching · Loading · Main menu**\n"
        "• **Singleplayer** → world name · **LAN** → world name\n"
        "• **Multiplayer** → the server's saved name + its icon"))
    e.add_field(name="Not showing?", inline=False, value=(
        "1. Discord app installed and logged in on the same phone\n"
        "2. Discord → *Settings → Activity Privacy → Share your detected activities* ON\n"
        "3. Launcher → *Settings → Discord Rich Presence* enabled\n"
        "4. Discord is *not* battery-restricted (Android settings)"))
    e.add_field(name="Linking", inline=False, value=(
        "Linking is optional — presence works without it. Linking gives you the "
        f"**{LINKED_ROLE_NAME}** role here and adds you to the {COMMUNITY_NAME} server. "
        "Unlink any time from the same settings page."))
    e.set_footer(text=LAUNCHER_NAME)
    return e


# =============================================================================== the bot


class CraftStudioBot(discord.Client):
    def __init__(self, members_intent: bool = True) -> None:
        intents = discord.Intents.default()
        intents.members = members_intent
        self.members_intent = members_intent
        super().__init__(intents=intents)
        self.tree = app_commands.CommandTree(self)
        self.db = Supabase(SUPABASE_URL, SUPABASE_SERVICE_KEY)
        self.started_at = time.time()
        self.processed = 0
        self.failed = 0
        self._guild: Optional[discord.Guild] = None
        self._ready_once = False
        self._outage_reported = False
        self._outage_since: Optional[float] = None

    # ----------------------------------------------------------------- lifecycle
    async def setup_hook(self) -> None:
        await self.db.start()
        self.add_view(LinksView())
        for group in (links_group, launcher_group, bot_group):
            group.bot = self  # type: ignore[attr-defined]
        link_info_ctx.bot = self  # type: ignore[attr-defined]

    async def close(self) -> None:
        for loop in (self.poll_events, self.update_status, self.sync_roles, self.daily_digest, self.update_stats_channel):
            loop.cancel()
        await self.db.close()
        await super().close()

    async def on_ready(self) -> None:
        global GUILD_ID
        log.info("Logged in as %s (%s) · bot v%s · discord.py %s", self.user, self.user.id if self.user else "?",
                 BOT_VERSION, discord.__version__)
        if not GUILD_ID:
            guilds = list(self.guilds)
            if len(guilds) == 1:
                GUILD_ID = guilds[0].id
                _STATE["guild_id"] = GUILD_ID
                _save_state(_STATE)
                log.info("Auto-detected server: %s (%s)", guilds[0].name, GUILD_ID)
            elif not guilds:
                log.error("The bot is not in any server yet. Invite it with:\n\n  %s\n", self.invite_url())
                return
            else:
                log.error("Bot is in %d servers - set GUILD_ID in .env: %s", len(guilds),
                          ", ".join(f"{g.name}={g.id}" for g in guilds))
                return
        self._guild = self.get_guild(GUILD_ID)
        if self._guild is None:
            try:
                self._guild = await self.fetch_guild(GUILD_ID)
            except discord.HTTPException:
                self._guild = None
        if self._guild is None:
            log.error("The bot is NOT in the %s server yet. Open this link once, pick the server and click "
                      "Authorize - the bot continues automatically:\n\n  %s\n", COMMUNITY_NAME, self.invite_url())
            return
        log.info("Server: %s (%s members)", self._guild.name, self._guild.member_count)
        if not self._ready_once:
            self._ready_once = True
            await self.auto_setup()
            for group in (links_group, launcher_group, bot_group):
                self.tree.add_command(group, guild=discord.Object(GUILD_ID))
            self.tree.add_command(link_info_ctx, guild=discord.Object(GUILD_ID))
            await self.sync_commands()
            self.poll_events.start()
            self.update_status.start()
            self.sync_roles.start()
            self.daily_digest.start()
            if STATS_CHANNEL_ENABLED or PLAYING_CHANNEL_ENABLED:
                self.update_stats_channel.start()
            if not self.members_intent:
                log.warning("Running WITHOUT Server Members Intent (Developer Portal -> Bot -> Server Members "
                            "Intent -> ON gives instant role-on-join + welcome messages). Fallback: role sync every 5 min.")
            await self.log_embed(discord.Embed(
                title="🟢 Bot online", color=0x57F287,
                description=f"v{BOT_VERSION} · polling every {POLL_SECONDS}s · members intent: "
                            f"{'on' if self.members_intent else 'off'}"))
        self._check_permissions()

    _commands_synced = False

    async def sync_commands(self) -> bool:
        if self._commands_synced:
            return True
        try:
            synced = await self.tree.sync(guild=discord.Object(GUILD_ID))
            self._commands_synced = True
            log.info("Slash commands synced (%d)", len(synced))
        except discord.HTTPException as e:
            log.warning("Slash command sync failed: %s\n  -> The bot was added WITHOUT the applications.commands "
                        "scope. Open this link once more (no need to kick the bot) and click Authorize - the bot "
                        "retries every 5 minutes:\n\n  %s\n", e, self.invite_url())
        return self._commands_synced

    async def on_guild_join(self, guild: discord.Guild) -> None:
        if GUILD_ID and guild.id != GUILD_ID:
            log.info("Added to another server %s (%s) - ignoring (GUILD_ID=%s)", guild.name, guild.id, GUILD_ID)
            return
        log.info("Bot was added to %s - finishing setup", guild.name)
        await self.on_ready()

    def invite_url(self) -> str:
        app_id = self.user.id if self.user else "APP_ID"
        guild = f"&guild_id={GUILD_ID}" if GUILD_ID else ""
        return (f"https://discord.com/oauth2/authorize?client_id={app_id}"
                f"&scope=bot%20applications.commands&permissions=268454929{guild}")

    # ----------------------------------------------------------------- auto setup
    async def auto_setup(self) -> None:
        global LINKED_ROLE_ID, LOG_CHANNEL_ID, STATS_CHANNEL_ID
        guild = self._guild
        if guild is None or guild.me is None:
            return
        perms = guild.me.guild_permissions

        if LINKED_ROLE_ID and guild.get_role(LINKED_ROLE_ID) is None:
            log.warning("Configured role %s no longer exists - creating a new one", LINKED_ROLE_ID)
            LINKED_ROLE_ID = 0
        if not LINKED_ROLE_ID:
            existing = discord.utils.get(guild.roles, name=LINKED_ROLE_NAME)
            if existing is not None and existing < guild.me.top_role:
                LINKED_ROLE_ID = existing.id
                log.info("Using existing role '%s' (%s)", existing.name, existing.id)
            elif perms.manage_roles:
                try:
                    role = await guild.create_role(name=LINKED_ROLE_NAME, colour=discord.Colour(EMBED_COLOR),
                                                   mentionable=False,
                                                   reason=f"{LAUNCHER_NAME}: role for players who linked their account")
                    try:
                        await role.edit(position=max(1, guild.me.top_role.position - 1))
                    except discord.HTTPException:
                        pass
                    LINKED_ROLE_ID = role.id
                    log.info("Created role '%s' (%s)", role.name, role.id)
                except discord.HTTPException as e:
                    log.warning("Could not create the Linked role: %s", e)
            else:
                log.warning("No Manage Roles permission - cannot create the Linked role")
            if LINKED_ROLE_ID:
                _STATE["linked_role_id"] = LINKED_ROLE_ID
                _save_state(_STATE)

        if LOG_CHANNEL_ID and guild.get_channel(LOG_CHANNEL_ID) is None:
            log.warning("Configured log channel %s no longer exists - creating a new one", LOG_CHANNEL_ID)
            LOG_CHANNEL_ID = 0
        if not LOG_CHANNEL_ID:
            existing_ch = discord.utils.get(guild.text_channels, name=LOG_CHANNEL_NAME)
            if existing_ch is not None:
                LOG_CHANNEL_ID = existing_ch.id
                log.info("Using existing channel #%s (%s)", existing_ch.name, existing_ch.id)
            elif perms.manage_channels:
                try:
                    overwrites = {
                        guild.default_role: discord.PermissionOverwrite(view_channel=False),
                        guild.me: discord.PermissionOverwrite(view_channel=True, send_messages=True,
                                                              embed_links=True, attach_files=True),
                    }
                    channel = await guild.create_text_channel(
                        LOG_CHANNEL_NAME, overwrites=overwrites,
                        topic=f"{LAUNCHER_NAME} account link / unlink log · bot only",
                        reason=f"{LAUNCHER_NAME}: private log channel")
                    LOG_CHANNEL_ID = channel.id
                    log.info("Created private channel #%s (%s)", channel.name, channel.id)
                except discord.HTTPException as e:
                    log.warning("Could not create the log channel: %s -> give the bot 'Manage Channels' or create "
                                "a channel named #%s and let the bot see it", e, LOG_CHANNEL_NAME)
            else:
                log.warning("No Manage Channels permission - log channel disabled")
            if LOG_CHANNEL_ID:
                _STATE["log_channel_id"] = LOG_CHANNEL_ID
                _save_state(_STATE)

        if STATS_CHANNEL_ENABLED:
            if STATS_CHANNEL_ID and guild.get_channel(STATS_CHANNEL_ID) is None:
                STATS_CHANNEL_ID = 0
            if not STATS_CHANNEL_ID and perms.manage_channels:
                existing_vc = next((c for c in guild.voice_channels if c.name.startswith("🔗 Linked:")), None)
                if existing_vc is not None:
                    STATS_CHANNEL_ID = existing_vc.id
                else:
                    try:
                        overwrites = {guild.default_role: discord.PermissionOverwrite(connect=False, view_channel=True),
                                      guild.me: discord.PermissionOverwrite(manage_channels=True, view_channel=True)}
                        vc = await guild.create_voice_channel("🔗 Linked: …", overwrites=overwrites, position=0,
                                                              reason=f"{LAUNCHER_NAME}: live linked-player counter")
                        STATS_CHANNEL_ID = vc.id
                        log.info("Created stats channel (%s)", vc.id)
                    except discord.HTTPException as e:
                        log.warning("Could not create the stats channel: %s", e)
                if STATS_CHANNEL_ID:
                    _STATE["stats_channel_id"] = STATS_CHANNEL_ID
                    _save_state(_STATE)
        await self._ensure_playing_channel()

    async def _ensure_playing_channel(self) -> None:
        global PLAYING_CHANNEL_ID
        guild = self._guild
        if guild is None or guild.me is None or not PLAYING_CHANNEL_ENABLED:
            return
        if PLAYING_CHANNEL_ID and guild.get_channel(PLAYING_CHANNEL_ID) is None:
            PLAYING_CHANNEL_ID = 0
        if PLAYING_CHANNEL_ID or not guild.me.guild_permissions.manage_channels:
            return
        existing = next((c for c in guild.voice_channels if c.name.startswith("🎮 Playing now:")), None)
        if existing is not None:
            PLAYING_CHANNEL_ID = existing.id
        else:
            try:
                overwrites = {guild.default_role: discord.PermissionOverwrite(connect=False, view_channel=True),
                              guild.me: discord.PermissionOverwrite(manage_channels=True, view_channel=True)}
                vc = await guild.create_voice_channel("🎮 Playing now: …", overwrites=overwrites,
                                                      reason=f"{LAUNCHER_NAME}: live players counter")
                PLAYING_CHANNEL_ID = vc.id
                log.info("Created playing-now channel (%s)", vc.id)
            except discord.HTTPException as e:
                log.warning("Could not create the playing-now channel: %s", e)
        if PLAYING_CHANNEL_ID:
            _STATE["playing_channel_id"] = PLAYING_CHANNEL_ID
            _save_state(_STATE)

    def _check_permissions(self) -> list[str]:
        guild = self._guild
        if guild is None or guild.me is None:
            return ["not in server"]
        perms = guild.me.guild_permissions
        problems = []
        if not perms.create_instant_invite:
            problems.append("Create Invite (needed for auto-join)")
        if LINKED_ROLE_ID and not perms.manage_roles:
            problems.append("Manage Roles (needed for the Linked role)")
        role = guild.get_role(LINKED_ROLE_ID) if LINKED_ROLE_ID else None
        if LINKED_ROLE_ID and role is None:
            problems.append(f"role {LINKED_ROLE_ID} not found")
        elif role is not None and guild.me.top_role <= role:
            problems.append(f"bot's top role must be ABOVE '{role.name}' in the role list")
        if LOG_CHANNEL_ID:
            ch = guild.get_channel(LOG_CHANNEL_ID)
            if ch is None:
                problems.append(f"log channel {LOG_CHANNEL_ID} not visible to the bot (give the bot role View Channel there)")
            elif not ch.permissions_for(guild.me).send_messages or not ch.permissions_for(guild.me).embed_links:
                problems.append(f"bot cannot send embeds in #{ch.name} (needs Send Messages + Embed Links)")
        if not self._commands_synced:
            problems.append("slash commands not registered (re-authorize with the invite link)")
        if not self.members_intent:
            problems.append("Server Members Intent off (Developer Portal → Bot) — role-on-join delayed up to 5 min")
        if problems:
            log.warning("Permission check: %s", "; ".join(problems))
        else:
            log.info("Permission check OK")
        return problems

    # ----------------------------------------------------------------- helpers
    @property
    def guild(self) -> Optional[discord.Guild]:
        return self._guild or self.get_guild(GUILD_ID)

    async def member_of(self, user_id: int) -> Optional[discord.Member]:
        guild = self.guild
        if guild is None:
            return None
        member = guild.get_member(user_id)
        if member is not None:
            return member
        try:
            return await guild.fetch_member(user_id)
        except discord.NotFound:
            return None
        except discord.HTTPException as e:
            log.warning("fetch_member(%s) failed: %s", user_id, e)
            return None

    async def join_guild(self, user_id: int, access_token: str) -> str:
        """PUT /guilds/{g}/members/{u} → 'joined' | 'already'. Raises RuntimeError with a readable reason."""
        payload: dict[str, Any] = {"access_token": access_token}
        if LINKED_ROLE_ID:
            payload["roles"] = [str(LINKED_ROLE_ID)]
        route = discord.http.Route("PUT", "/guilds/{guild_id}/members/{user_id}", guild_id=GUILD_ID, user_id=user_id)
        try:
            data = await self.http.request(route, json=payload)
        except discord.HTTPException as e:
            reasons = {
                30001: "user is in 100+ servers (Discord limit)",
                40007: "user is banned from the server",
                50013: "bot lacks Create Invite / Manage Roles permission",
                50025: "OAuth token invalid or expired — user must re-link in the launcher",
                50035: "token has no guilds.join scope (old launcher build) — user must re-link",
            }
            why = reasons.get(e.code or 0, e.text)
            err = RuntimeError(f"auto-join failed: {why} (HTTP {e.status}, code {e.code})")
            err.transient = e.status >= 500 or e.status == 429  # type: ignore[attr-defined]
            raise err from e
        return "already" if data is None or data == "" else "joined"

    async def give_role(self, member: discord.Member, reason: str) -> bool:
        if not LINKED_ROLE_ID:
            return False
        role = member.guild.get_role(LINKED_ROLE_ID)
        if role is None:
            return False
        if role in member.roles:
            return True
        try:
            await member.add_roles(role, reason=reason)
            return True
        except discord.Forbidden:
            log.warning("No permission to add role %s to %s (move the bot role above it)", role.name, member)
        except discord.HTTPException as e:
            log.warning("add_roles failed for %s: %s", member, e)
        return False

    async def remove_role(self, member: discord.Member, reason: str) -> bool:
        if not LINKED_ROLE_ID:
            return False
        role = member.guild.get_role(LINKED_ROLE_ID)
        if role is None or role not in member.roles:
            return False
        try:
            await member.remove_roles(role, reason=reason)
            return True
        except discord.HTTPException as e:
            log.warning("remove_roles failed for %s: %s", member, e)
            return False

    async def dm(self, user_id: int, embed: discord.Embed, view: Optional[discord.ui.View] = None) -> str:
        """→ 'sent' | 'blocked' | 'failed'"""
        try:
            user = self.get_user(user_id) or await self.fetch_user(user_id)
            await user.send(embed=embed, view=view)
            return "sent"
        except discord.Forbidden:
            log.info("DM to %s blocked (privacy settings)", user_id)
            return "blocked"
        except discord.HTTPException as e:
            log.warning("DM to %s failed: %s", user_id, e)
            return "failed"

    async def log_embed(self, embed: discord.Embed, file: Optional[discord.File] = None) -> None:
        if not LOG_CHANNEL_ID:
            return
        channel = self.get_channel(LOG_CHANNEL_ID)
        if channel is None:
            try:
                channel = await self.fetch_channel(LOG_CHANNEL_ID)
            except discord.HTTPException:
                return
        try:
            await channel.send(embed=embed, file=file)  # type: ignore[union-attr,arg-type]
        except discord.HTTPException as e:
            log.warning("log channel send failed: %s", e)

    # ----------------------------------------------------------------- embeds
    def welcome_embed(self, ev: dict, joined: str, role_ok: bool) -> discord.Embed:
        name = ev.get("display_name") or ev.get("username") or "there"
        e = discord.Embed(
            title="✅ Account linked",
            description=(f"Hey **{name}**! Your Discord is now linked with **{LAUNCHER_NAME}**.\n"
                         "Friends can see what you're playing — the profile, the world or the server — "
                         "right on your Discord profile."),
            color=EMBED_COLOR, timestamp=datetime.now(timezone.utc))
        perks = []
        if joined == "joined":
            perks.append(f"🎉 You've been added to the **{COMMUNITY_NAME}** server — welcome!")
        elif joined == "already":
            perks.append(f"💬 You're already in the **{COMMUNITY_NAME}** server — nice.")
        else:
            perks.append(f"💬 Join the **{COMMUNITY_NAME}** server with the button below.")
        if role_ok and LINKED_ROLE_ID:
            perks.append(f"🏷️ You got the **{LINKED_ROLE_NAME}** role.")
        elif LINKED_ROLE_ID and joined == "none":
            perks.append(f"🏷️ The **{LINKED_ROLE_NAME}** role is waiting for you when you join.")
        e.add_field(name="What happened", value="\n".join(perks), inline=False)
        e.add_field(name="Tips", inline=False, value=(
            "• Tap **Rich Presence help** below if your status isn't showing\n"
            "• `/links me` in the server shows your link status any time\n"
            "• Unlink from *Settings → Discord Rich Presence* whenever you want"))
        e.add_field(name="Privacy", inline=False, value=(
            "Only what you're playing is shared, and only while the game is open. "
            "Nothing is posted anywhere on your behalf."))
        if ev.get("avatar_url"):
            e.set_thumbnail(url=ev["avatar_url"])
        version = ev.get("app_version")
        e.set_footer(text=f"{LAUNCHER_NAME}" + (f" {version}" if version else "") + " · Happy crafting! ⛏️")
        return e

    def unlink_embed(self, ev: dict) -> discord.Embed:
        e = discord.Embed(
            title="Account unlinked",
            description=(f"Your Discord was unlinked from **{LAUNCHER_NAME}**. Rich Presence still works "
                         "without linking — link again any time from the launcher settings."),
            color=0x9C9CA4, timestamp=datetime.now(timezone.utc))
        if LINKED_ROLE_ID:
            e.add_field(name="Role", value=f"The **{LINKED_ROLE_NAME}** role was removed.", inline=False)
        e.set_footer(text=LAUNCHER_NAME)
        return e

    def member_welcome_embed(self, member: discord.Member, linked: bool) -> discord.Embed:
        e = discord.Embed(
            title=f"Welcome to {COMMUNITY_NAME}, {member.display_name}! 👋",
            color=EMBED_COLOR, timestamp=datetime.now(timezone.utc))
        if linked:
            e.description = (f"You linked **{LAUNCHER_NAME}** — the **{LINKED_ROLE_NAME}** role is yours. "
                             "Enjoy the server!")
        else:
            e.description = (f"Home of **{LAUNCHER_NAME}** — Minecraft: Java Edition on Android.\n"
                             "Link your Discord in the launcher (*Settings → Discord Rich Presence*) to get the "
                             f"**{LINKED_ROLE_NAME}** role, or type `/links me`.")
        e.set_thumbnail(url=member.display_avatar.url)
        e.set_footer(text=f"Member #{member.guild.member_count}")
        return e

    def log_event_embed(self, ev: dict, result: str, ok: bool) -> discord.Embed:
        kind = ev.get("kind", "link")
        uid = ev.get("discord_user_id")
        e = discord.Embed(
            title=("🔗 Linked" if kind == "link" else "🔓 Unlinked") + ("" if ok else " · problem"),
            color=(EMBED_COLOR if kind == "link" else 0x9C9CA4) if ok else 0xE05A5A,
            timestamp=datetime.now(timezone.utc))
        e.add_field(name="User", value=f"<@{uid}> · `{ev.get('username') or '?'}` · `{uid}`", inline=False)
        e.add_field(name="Result", value=result[:1000] or "–", inline=False)
        meta = " · ".join(x for x in [ev.get("app_version") and f"v{ev['app_version']}", ev.get("device")] if x)
        if meta:
            e.set_footer(text=meta)
        if ev.get("avatar_url"):
            e.set_thumbnail(url=ev["avatar_url"])
        return e

    # ----------------------------------------------------------------- event processing
    @tasks.loop(seconds=POLL_SECONDS)
    async def poll_events(self) -> None:
        try:
            events = await self.db.pending_events()
        except Exception as e:
            await self._note_outage(str(e))
            return
        await self._note_recovery()
        for ev in events:
            try:
                await self.handle_event(ev)
            except Exception as e:
                attempts = int(ev.get("attempts") or 0)
                transient = getattr(e, "transient", False) or "unreachable" in str(e) or "HTTP 5" in str(e)
                log.exception("Event %s failed (attempt %d)", ev.get("id"), attempts + 1)
                try:
                    if transient and attempts + 1 < MAX_ATTEMPTS:
                        await self.db.retry_event(int(ev["id"]), attempts, str(e))
                    else:
                        self.failed += 1
                        await self.db.finish_event(int(ev["id"]), f"error: {e}")
                        await self.log_embed(self.log_event_embed(ev, f"error: {e}", False))
                except Exception as e2:
                    log.error("Could not mark event %s: %s", ev.get("id"), e2)

    @poll_events.before_loop
    async def _wait_ready(self) -> None:
        await self.wait_until_ready()

    async def _note_outage(self, error: str) -> None:
        if self._outage_since is None:
            self._outage_since = time.time()
        log.warning("Supabase poll failed: %s", error)
        if not self._outage_reported and time.time() - self._outage_since > 180:
            self._outage_reported = True
            await self.log_embed(discord.Embed(title="🔴 Supabase unreachable", description=error[:500], color=0xE05A5A))

    async def _note_recovery(self) -> None:
        if self._outage_since is not None:
            down = int(time.time() - self._outage_since)
            self._outage_since = None
            if self._outage_reported:
                self._outage_reported = False
                await self.log_embed(discord.Embed(title="🟢 Supabase back", description=f"Down for ~{down // 60} min",
                                                   color=0x57F287))

    async def handle_event(self, ev: dict) -> None:
        event_id = int(ev["id"])
        kind = ev.get("kind") or "link"
        try:
            user_id = int(ev["discord_user_id"])
        except (TypeError, ValueError):
            await self.db.finish_event(event_id, "error: bad user id")
            return
        log.info("Event #%s %s for %s (%s)", event_id, kind, user_id, ev.get("username"))
        if kind == "unlink":
            await self._handle_unlink(ev, user_id)
        else:
            await self._handle_link(ev, user_id)
        self.processed += 1

    async def _handle_link(self, ev: dict, user_id: int) -> None:
        event_id = int(ev["id"])
        steps: list[str] = []
        ok = True
        existing = await self.db.get_link(user_id)

        # 1) membership / auto-join
        joined = "none"
        token = ev.get("access_token")
        member = await self.member_of(user_id)
        if member is not None:
            joined = "already"
        elif token and "guilds.join" in (ev.get("scopes") or ""):
            try:
                joined = await self.join_guild(user_id, token)
                if joined == "joined":
                    await self.db.bump_stat("joins")
                    for _ in range(4):  # give the gateway a moment to deliver the member
                        await asyncio.sleep(0.75)
                        member = await self.member_of(user_id)
                        if member is not None:
                            break
            except Exception as e:
                if getattr(e, "transient", False):
                    raise
                ok = False
                steps.append(str(e))
                log.warning("Auto-join failed for %s: %s", user_id, e)
        else:
            steps.append("invited by DM (no guilds.join token)")
        steps.append({"joined": "joined the server", "already": "already a member", "none": "not in the server"}[joined])

        # 2) role
        role_ok = False
        if member is not None:
            role_ok = await self.give_role(member, f"Linked Discord in {LAUNCHER_NAME}")
            if LINKED_ROLE_ID:
                steps.append("role given" if role_ok else "role FAILED (check role order / Manage Roles)")

        # 3) DM (with anti-spam cooldown for re-links)
        dm_state = "skipped"
        last_dm = existing.get("last_dm_at") if existing else None
        recently = False
        if last_dm and DM_COOLDOWN_MIN:
            try:
                recently = datetime.now(timezone.utc) - datetime.fromisoformat(last_dm.replace("Z", "+00:00")) \
                    < timedelta(minutes=DM_COOLDOWN_MIN)
            except Exception:
                recently = False
        if recently:
            steps.append("DM skipped (sent recently)")
        else:
            dm_state = await self.dm(user_id, self.welcome_embed(ev, joined, role_ok), LinksView())
            steps.append({"sent": "DM sent", "blocked": "DM blocked (user's privacy settings)", "failed": "DM failed"}[dm_state])
            if dm_state == "sent":
                await self.db.bump_stat("dms")

        # 4) persist
        row = {
            "discord_user_id": str(user_id),
            "username": ev.get("username"),
            "display_name": ev.get("display_name"),
            "avatar_url": ev.get("avatar_url"),
            "linked_at": _now(),
            "unlinked_at": None,
            "joined_guild": member is not None,
            "role_given": role_ok,
            "dm_sent": dm_state == "sent" or (recently and bool(existing and existing.get("dm_sent"))),
            "link_count": (int(existing.get("link_count") or 0) + 1) if existing else 1,
        }
        if dm_state == "sent":
            row["last_dm_at"] = _now()
        elif existing and existing.get("last_dm_at"):
            row["last_dm_at"] = existing["last_dm_at"]
        await self.db.upsert_link(row)
        await self.db.bump_stat("links")

        result = " · ".join(steps)
        await self.db.finish_event(event_id, ("ok: " if ok else "partial: ") + result)
        await self.log_embed(self.log_event_embed(ev, result, ok))
        await self._maybe_milestone()

    async def _handle_unlink(self, ev: dict, user_id: int) -> None:
        event_id = int(ev["id"])
        steps: list[str] = []
        member = await self.member_of(user_id)
        if member is not None:
            removed = await self.remove_role(member, f"Unlinked Discord in {LAUNCHER_NAME}")
            steps.append("role removed" if removed else "no role to remove")
        else:
            steps.append("not in the server")
        if DM_ON_UNLINK:
            state = await self.dm(user_id, self.unlink_embed(ev))
            steps.append({"sent": "DM sent", "blocked": "DM blocked", "failed": "DM failed"}[state])
        try:
            await self.db.update_link(user_id, {"unlinked_at": _now(), "role_given": False})
        except Exception as e:
            steps.append(f"db: {e}")
        await self.db.bump_stat("unlinks")
        result = " · ".join(steps)
        await self.db.finish_event(event_id, "ok: " + result)
        await self.log_embed(self.log_event_embed(ev, result, True))

    async def _maybe_milestone(self) -> None:
        try:
            total = await self.db.count("discord_links", {"unlinked_at": "is.null"})
        except Exception:
            return
        reached = [m for m in MILESTONES if m <= total]
        if not reached:
            return
        top = reached[-1]
        if int(_STATE.get("milestone") or 0) >= top:
            return
        _STATE["milestone"] = top
        _save_state(_STATE)
        await self.log_embed(discord.Embed(
            title=f"🏆 {top:,} linked players!",
            description=f"**{total:,}** players have linked {LAUNCHER_NAME} with Discord. Keep it up!",
            color=0xFEE75C))

    # ----------------------------------------------------------------- gateway events
    async def on_member_join(self, member: discord.Member) -> None:
        if member.guild.id != GUILD_ID or member.bot:
            return
        linked = False
        try:
            link = await self.db.get_link(member.id)
            linked = bool(link and not link.get("unlinked_at"))
        except Exception as e:
            log.warning("get_link on join failed: %s", e)
        if linked and LINKED_ROLE_ID:
            if await self.give_role(member, "Linked account joined the server"):
                await self.db.update_link(member.id, {"joined_guild": True, "role_given": True})
                log.info("Gave %s role to %s on join", LINKED_ROLE_NAME, member)
        if WELCOME_ENABLED:
            channel = member.guild.get_channel(WELCOME_CHANNEL_ID) if WELCOME_CHANNEL_ID else member.guild.system_channel
            if channel is not None and channel.permissions_for(member.guild.me).send_messages:
                try:
                    await channel.send(content=member.mention, embed=self.member_welcome_embed(member, linked),
                                       allowed_mentions=discord.AllowedMentions(users=True))
                except discord.HTTPException as e:
                    log.debug("welcome failed: %s", e)

    async def on_member_remove(self, member: discord.Member) -> None:
        if member.guild.id != GUILD_ID or member.bot:
            return
        try:
            link = await self.db.get_link(member.id)
            if link and not link.get("unlinked_at"):
                await self.db.update_link(member.id, {"joined_guild": False, "role_given": False})
        except Exception:
            pass

    # ----------------------------------------------------------------- background loops
    @tasks.loop(minutes=10)
    async def update_status(self) -> None:
        try:
            linked = await self.db.count("discord_links", {"unlinked_at": "is.null"})
            await self.change_presence(activity=discord.Activity(
                type=discord.ActivityType.watching, name=f"{linked:,} linked players · {LAUNCHER_NAME}"))
        except Exception as e:
            log.debug("status update failed: %s", e)

    @update_status.before_loop
    async def _status_wait(self) -> None:
        await self.wait_until_ready()

    @tasks.loop(minutes=5)
    async def sync_roles(self) -> None:
        """Give the Linked role to linked players who are in the server but don't have it yet."""
        if not self._commands_synced:
            await self.sync_commands()
        if not PLAYING_CHANNEL_ID and PLAYING_CHANNEL_ENABLED:
            await self._ensure_playing_channel()
        if not LINKED_ROLE_ID:
            return
        try:
            links = await self.db.active_links()
        except Exception:
            return
        for link in links:
            if link.get("role_given") and link.get("joined_guild"):
                continue
            try:
                member = await self.member_of(int(link["discord_user_id"]))
            except (TypeError, ValueError):
                continue
            if member is None:
                continue
            if await self.give_role(member, "Linked account found in server (sync)"):
                await self.db.update_link(member.id, {"joined_guild": True, "role_given": True})
                log.info("sync: gave %s role to %s", LINKED_ROLE_NAME, member)
            await asyncio.sleep(0.5)

    @sync_roles.before_loop
    async def _sync_wait(self) -> None:
        await self.wait_until_ready()
        await asyncio.sleep(20)

    @tasks.loop(minutes=10)
    async def update_stats_channel(self) -> None:
        # Discord allows 2 channel renames / 10 min per channel - this loop matches that budget
        try:
            if STATS_CHANNEL_ID and (channel := self.get_channel(STATS_CHANNEL_ID)) is not None:
                linked = await self.db.count("discord_links", {"unlinked_at": "is.null"})
                name = f"🔗 Linked: {linked:,}"
                if channel.name != name:
                    await channel.edit(name=name, reason="linked counter")  # type: ignore[union-attr]
            if PLAYING_CHANNEL_ID and (channel := self.get_channel(PLAYING_CHANNEL_ID)) is not None:
                playing = len(await self.db.playing_now())
                name = f"🎮 Playing now: {playing:,}"
                if channel.name != name:
                    await channel.edit(name=name, reason="players counter")  # type: ignore[union-attr]
        except Exception as e:
            log.debug("counter channels: %s", e)

    @update_stats_channel.before_loop
    async def _stats_wait(self) -> None:
        await self.wait_until_ready()
        await asyncio.sleep(10)

    @tasks.loop(minutes=1)
    async def daily_digest(self) -> None:
        now = datetime.now(timezone.utc)
        if now.hour != DIGEST_HOUR_UTC or now.minute != 0:
            return
        if _STATE.get("digest_day") == now.strftime("%Y-%m-%d"):
            return
        _STATE["digest_day"] = now.strftime("%Y-%m-%d")
        _save_state(_STATE)
        try:
            await self.log_embed(await self.stats_embed(title="📊 Daily digest"))
        except Exception as e:
            log.debug("digest failed: %s", e)

    @daily_digest.before_loop
    async def _digest_wait(self) -> None:
        await self.wait_until_ready()

    async def stats_embed(self, title: str = f"{LAUNCHER_NAME} · Discord links") -> discord.Embed:
        s = await self.db.stats()
        series = await self.db.daily_series(7)
        by_day = {r["day"]: r for r in series}
        bars = []
        peak = max([int(r.get("links") or 0) for r in series] + [1])
        for i in range(6, -1, -1):
            day = (datetime.now(timezone.utc) - timedelta(days=i)).strftime("%Y-%m-%d")
            n = int((by_day.get(day) or {}).get("links") or 0)
            bars.append(f"`{day[5:]}` {'█' * max(0, round(n / peak * 12))}{'▏' if n == 0 else ''} {n}")
        uptime = int(time.time() - self.started_at)
        e = discord.Embed(title=title, color=EMBED_COLOR, timestamp=datetime.now(timezone.utc))
        e.add_field(name="Linked now", value=f"**{s['linked']:,}**")
        e.add_field(name="In the server", value=f"{s['in_guild']:,}")
        e.add_field(name="Unlinked", value=f"{s['unlinked']:,}")
        e.add_field(name="Links · 7 days", value=f"{s['links_week']:,}")
        e.add_field(name="Events today", value=f"{s['events_today']:,}")
        e.add_field(name="Queue / failed", value=f"{s['pending']} / {s['failed']}")
        try:
            e.add_field(name="Playing now", value=str(len(await self.db.playing_now())))
        except Exception:
            pass
        e.add_field(name="Links per day", value="\n".join(bars), inline=False)
        e.set_footer(text=f"bot v{BOT_VERSION} · up {uptime // 3600}h {uptime % 3600 // 60}m · "
                          f"{self.processed} processed · {self.failed} failed")
        return e


# =============================================================================== play sessions


def _fmt_dur(seconds: int | float | None) -> str:
    s = int(seconds or 0)
    if s < 60:
        return f"{s}s"
    if s < 3600:
        return f"{s // 60}m"
    return f"{s // 3600}h {s % 3600 // 60:02d}m"


def _top(counter: dict[str, int], n: int = 5) -> list[tuple[str, int]]:
    return sorted(counter.items(), key=lambda kv: -kv[1])[:n]


def _bar(n: int, peak: int, width: int = 10) -> str:
    return "█" * max(1, round(n / peak * width)) if peak else ""


async def play_stats_embed(bot: "CraftStudioBot", hours: int = 168) -> discord.Embed:
    rows = await bot.db.sessions(since_hours=hours)
    now_playing = await bot.db.playing_now()
    e = discord.Embed(title=f"{LAUNCHER_NAME} · Play stats · last {hours // 24 if hours >= 48 else hours}"
                            f"{'d' if hours >= 48 else 'h'}", color=EMBED_COLOR, timestamp=datetime.now(timezone.utc))
    if not rows:
        e.description = ("No play sessions yet. Linked players' launchers report sessions automatically "
                         "(current launcher build required).")
        return e
    players = {r["discord_user_id"] for r in rows}
    total_s = sum(int(r.get("duration_s") or 0) for r in rows)
    finished = [r for r in rows if r.get("duration_s")]
    avg = total_s / len(finished) if finished else 0
    versions: dict[str, int] = {}
    loaders: dict[str, int] = {}
    profiles: dict[str, int] = {}
    scenes: dict[str, int] = {}
    for r in rows:
        versions[r.get("version") or "?"] = versions.get(r.get("version") or "?", 0) + 1
        loaders[(r.get("loader") or "vanilla").capitalize()] = loaders.get((r.get("loader") or "vanilla").capitalize(), 0) + 1
        profiles[r.get("profile") or "?"] = profiles.get(r.get("profile") or "?", 0) + 1
        sc = r.get("last_scene")
        if sc:
            scenes[sc] = scenes.get(sc, 0) + 1
    e.add_field(name="Playing now", value=f"**{len(now_playing)}**")
    e.add_field(name="Sessions", value=f"{len(rows):,}")
    e.add_field(name="Players", value=f"{len(players):,}")
    e.add_field(name="Total playtime", value=_fmt_dur(total_s))
    e.add_field(name="Avg session", value=_fmt_dur(avg))
    busiest = {}
    for r in rows:
        try:
            h = datetime.fromisoformat(r["started_at"].replace("Z", "+00:00")).astimezone(
                timezone(timedelta(hours=5, minutes=30))).hour
            busiest[h] = busiest.get(h, 0) + 1
        except Exception:
            pass
    if busiest:
        h = max(busiest, key=busiest.get)
        e.add_field(name="Peak hour (IST)", value=f"{h:02d}:00–{(h + 1) % 24:02d}:00")
    tv = _top(versions)
    peak = tv[0][1] if tv else 1
    e.add_field(name="Versions", value="\n".join(f"`{v:<8}` {_bar(n, peak)} {n}" for v, n in tv) or "–", inline=False)
    tl = _top(loaders)
    e.add_field(name="Loaders", value=" · ".join(f"**{l}** {n}" for l, n in tl) or "–", inline=False)
    if scenes:
        label = {"singleplayer": "Singleplayer", "multiplayer": "Multiplayer", "lan_host": "LAN", "menu": "Menu only",
                 "loading": "Loading", "launching": "Launching", "home": "Home"}
        e.add_field(name="How people play", value=" · ".join(f"{label.get(k, k)} {n}" for k, n in _top(scenes, 4)),
                    inline=False)
    if now_playing:
        lines = [f"<@{r['discord_user_id']}> · {r.get('profile') or '?'} ({r.get('version') or '?'})"
                 for r in now_playing[:8]]
        e.add_field(name="Online right now", value="\n".join(lines) + (f"\n…and {len(now_playing) - 8} more" if len(now_playing) > 8 else ""),
                    inline=False)
    e.set_footer(text="Only players who linked Discord in the launcher are counted")
    return e


# =============================================================================== slash commands


def _is_admin(interaction: discord.Interaction) -> bool:
    user = interaction.user
    if isinstance(user, discord.Member):
        if user.guild_permissions.administrator or user.guild_permissions.manage_guild:
            return True
        if ADMIN_ROLE_ID and any(r.id == ADMIN_ROLE_ID for r in user.roles):
            return True
    return False


async def _deny(interaction: discord.Interaction) -> bool:
    if _is_admin(interaction):
        return False
    await interaction.response.send_message("Only staff can use this.", ephemeral=True)
    return True


links_group = app_commands.Group(name="links", description=f"{LAUNCHER_NAME} account links")
launcher_group = app_commands.Group(name="launcher", description=f"{LAUNCHER_NAME} info")
bot_group = app_commands.Group(name="bot", description="Craft Studio bot")


def _link_embed(user: discord.abc.User, link: Optional[dict]) -> discord.Embed:
    if not link:
        return discord.Embed(title=f"{user} · not linked", color=0x9C9CA4,
                             description=f"Has not linked {LAUNCHER_NAME}.")
    status = "unlinked" if link.get("unlinked_at") else "linked"
    e = discord.Embed(title=f"{user} · {status}", color=EMBED_COLOR if status == "linked" else 0x9C9CA4)
    e.add_field(name="Launcher name", value=link.get("display_name") or link.get("username") or "–")
    e.add_field(name="Linked", value=_fmt_ts(link.get("linked_at")))
    if link.get("unlinked_at"):
        e.add_field(name="Unlinked", value=_fmt_ts(link.get("unlinked_at")))
    e.add_field(name="Times linked", value=str(link.get("link_count", 1)))
    e.add_field(name="In server", value="yes" if link.get("joined_guild") else "no")
    e.add_field(name="Role", value="yes" if link.get("role_given") else "no")
    e.add_field(name="DM", value="sent" if link.get("dm_sent") else "blocked")
    if link.get("avatar_url"):
        e.set_thumbnail(url=link["avatar_url"])
    return e


async def _with_play_info(bot: "CraftStudioBot", user_id: int, e: discord.Embed) -> discord.Embed:
    try:
        mine = await bot.db.sessions(user_id=user_id, limit=300)
    except Exception:
        return e
    if mine:
        last = mine[0]
        total = sum(int(r.get("duration_s") or 0) for r in mine)
        where = last.get("server_name") or last.get("world") or ""
        e.add_field(name="Playtime", value=f"{_fmt_dur(total)} · {len(mine)} sessions")
        e.add_field(name="Last played", value=f"{last.get('profile') or '?'} ({last.get('version') or '?'})"
                                             f"{' · ' + where if where else ''} {_fmt_ts(last.get('started_at'))}",
                    inline=False)
    return e


@links_group.command(name="me", description="Is my Discord linked with the launcher?")
async def links_me(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True)
    link = await bot.db.get_link(interaction.user.id)
    if link and not link.get("unlinked_at"):
        msg = f"✅ Linked with **{LAUNCHER_NAME}** {_fmt_ts(link.get('linked_at'))}."
        if LINKED_ROLE_ID and isinstance(interaction.user, discord.Member):
            if not any(r.id == LINKED_ROLE_ID for r in interaction.user.roles):
                if await bot.give_role(interaction.user, "/links me"):
                    await bot.db.update_link(interaction.user.id, {"joined_guild": True, "role_given": True})
                    msg += f" Gave you the **{LINKED_ROLE_NAME}** role."
            else:
                msg += f" You have the **{LINKED_ROLE_NAME}** role."
        try:
            mine = await bot.db.sessions(user_id=interaction.user.id, limit=500)
        except Exception:
            mine = []
        if mine:
            total = sum(int(r.get("duration_s") or 0) for r in mine)
            last = mine[0]
            week = [r for r in mine if r["started_at"] >= (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()]
            msg += (f"\n🎮 **{len(mine)}** sessions · **{_fmt_dur(total)}** total · "
                    f"{_fmt_dur(sum(int(r.get('duration_s') or 0) for r in week))} this week\n"
                    f"Last played: **{last.get('profile') or '?'}** ({last.get('version') or '?'}"
                    f"{' · ' + last['loader'].capitalize() if last.get('loader') and last['loader'] != 'vanilla' else ''})"
                    f" {_fmt_ts(last.get('started_at'))}")
    else:
        msg = (f"❌ Not linked. Open **{LAUNCHER_NAME} → Settings → Discord Rich Presence → Link** — "
               f"you'll get the **{LINKED_ROLE_NAME}** role and a DM here.")
    await interaction.followup.send(msg, embed=presence_help_embed() if not link else None, ephemeral=True)


@links_group.command(name="top", description="Playtime leaderboard (linked players)")
@app_commands.describe(period="Time window")
@app_commands.choices(period=[app_commands.Choice(name="7 days", value=168), app_commands.Choice(name="30 days", value=720),
                              app_commands.Choice(name="All time", value=0)])
async def links_top(interaction: discord.Interaction, period: Optional[app_commands.Choice[int]] = None) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer()
    hours = period.value if period else 168
    rows = await bot.db.sessions(since_hours=hours or None, limit=10000)
    total: dict[str, int] = {}
    count: dict[str, int] = {}
    for r in rows:
        uid = r["discord_user_id"]
        total[uid] = total.get(uid, 0) + int(r.get("duration_s") or 0)
        count[uid] = count.get(uid, 0) + 1
    top = _top(total, 10)
    e = discord.Embed(title=f"🏆 Playtime leaderboard · {period.name if period else '7 days'}", color=0xFEE75C)
    if not top:
        e.description = "No sessions yet."
    else:
        medals = ["🥇", "🥈", "🥉"]
        e.description = "\n".join(
            f"{medals[i] if i < 3 else f'`{i + 1:>2}.`'} <@{uid}> — **{_fmt_dur(sec)}** · {count[uid]} sessions"
            for i, (uid, sec) in enumerate(top))
    e.set_footer(text="Linked players only · reported by the launcher")
    await interaction.followup.send(embed=e)


@links_group.command(name="stats", description="How many players linked their Discord account")
async def links_stats(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True, thinking=True)
    await interaction.followup.send(embed=await bot.stats_embed(), ephemeral=True)


@links_group.command(name="lookup", description="Staff: check whether a member linked the launcher")
@app_commands.describe(user="Member to look up")
async def links_lookup(interaction: discord.Interaction, user: discord.User) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    e = await _with_play_info(bot, user.id, _link_embed(user, await bot.db.get_link(user.id)))
    await interaction.followup.send(embed=e, ephemeral=True)


@links_group.command(name="recent", description="Staff: last link / unlink events")
async def links_recent(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    rows = await bot.db.recent_events(12)
    if not rows:
        await interaction.followup.send("No events yet.", ephemeral=True)
        return
    lines = []
    for r in rows:
        icon = "🔗" if r.get("kind") == "link" else "🔓"
        res = (r.get("result") or "pending")[:70]
        lines.append(f"{icon} {_fmt_ts(r.get('created_at'))} <@{r['discord_user_id']}> `{r.get('username') or '?'}` — {res}")
    await interaction.followup.send(embed=discord.Embed(title="Recent link events", description="\n".join(lines),
                                                        color=EMBED_COLOR), ephemeral=True)


@links_group.command(name="sync", description="Staff: give the Linked role to every linked member in the server")
async def links_sync(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    if not LINKED_ROLE_ID:
        await interaction.response.send_message("No Linked role configured.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    links = await bot.db.active_links()
    given = present = 0
    for link in links:
        member = await bot.member_of(int(link["discord_user_id"]))
        if member is None:
            continue
        present += 1
        role = member.guild.get_role(LINKED_ROLE_ID)
        had = role in member.roles if role else True
        if await bot.give_role(member, "/links sync") and not had:
            given += 1
            await bot.db.update_link(member.id, {"joined_guild": True, "role_given": True})
    await interaction.followup.send(
        f"Checked **{len(links)}** linked accounts · **{present}** in the server · role given to **{given}**.",
        ephemeral=True)


@links_group.command(name="unlink", description="Staff: remove a member's Linked role and mark them unlinked")
@app_commands.describe(user="Member to unlink")
async def links_unlink(interaction: discord.Interaction, user: discord.Member) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    removed = await bot.remove_role(user, f"/links unlink by {interaction.user}")
    try:
        await bot.db.update_link(user.id, {"unlinked_at": _now(), "role_given": False,
                                           "notes": f"unlinked by {interaction.user} via /links unlink"})
    except Exception:
        pass
    await interaction.followup.send(f"{user.mention}: role {'removed' if removed else 'was not present'}; marked unlinked.",
                                    ephemeral=True)


@links_group.command(name="queue", description="Staff: process pending link events now")
async def links_queue(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    before = await bot.db.count("discord_link_events", {"processed_at": "is.null"})
    await bot.poll_events()
    after = await bot.db.count("discord_link_events", {"processed_at": "is.null"})
    await interaction.followup.send(f"Queue had **{before}** event(s); **{after}** left (retries wait for their back-off).",
                                    ephemeral=True)


@links_group.command(name="setup", description="Staff: re-run auto setup and the permission check")
async def links_setup(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    await bot.auto_setup()
    problems = bot._check_permissions()
    role = f"<@&{LINKED_ROLE_ID}>" if LINKED_ROLE_ID else "—"
    ch = f"<#{LOG_CHANNEL_ID}>" if LOG_CHANNEL_ID else "—"
    stats = f"<#{STATS_CHANNEL_ID}>" if STATS_CHANNEL_ID else "—"
    e = discord.Embed(title="Setup", color=EMBED_COLOR if not problems else 0xFEE75C)
    e.add_field(name="Role", value=role)
    e.add_field(name="Log channel", value=ch)
    e.add_field(name="Counter", value=stats)
    e.add_field(name="Checks", value="\n".join(f"⚠️ {p}" for p in problems) or "✅ all good", inline=False)
    await interaction.followup.send(embed=e, ephemeral=True)


@links_group.command(name="export", description="Staff: download all links as CSV")
async def links_export(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    rows = await bot.db.all_links()
    buf = io.StringIO()
    cols = ["discord_user_id", "username", "display_name", "linked_at", "unlinked_at", "joined_guild", "role_given",
            "dm_sent", "link_count"]
    w = csv.DictWriter(buf, fieldnames=cols, extrasaction="ignore")
    w.writeheader()
    for r in rows:
        w.writerow(r)
    data = io.BytesIO(buf.getvalue().encode("utf-8"))
    await interaction.followup.send(f"{len(rows)} rows", file=discord.File(data, filename=f"links-{_today()}.csv"),
                                    ephemeral=True)


# --- /launcher -----------------------------------------------------------------


@launcher_group.command(name="download", description=f"Where to get {LAUNCHER_NAME}")
async def launcher_download(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer()
    android = pc = {}
    rel = None
    try:
        android = await bot.db.site_setting("android")
        pc = await bot.db.site_setting("pc")
        rel = await bot.db.latest_release("android")
    except Exception:
        pass
    e = discord.Embed(title=f"{LAUNCHER_NAME} · Download", color=EMBED_COLOR)
    version = (rel or {}).get("version") or android.get("version") or "latest"
    url = (rel or {}).get("url") or android.get("url")
    size = (rel or {}).get("size") or android.get("size")
    e.add_field(name="Android", inline=False, value=(
        f"**v{version}**" + (f" · {size}" if size else "") + f" · {android.get('min_android') or 'Android 5.0+'}\n"
        + (f"[Download APK]({url})" if url else "Download link is posted in the announcements channel.")))
    if pc.get("status") == "available" and pc.get("url"):
        e.add_field(name="PC", value=f"[Download]({pc['url']})" + (f" · v{pc['version']}" if pc.get("version") else ""))
    else:
        e.add_field(name="PC", value="Coming soon")
    notes = (rel or {}).get("notes") or android.get("notes")
    if notes:
        e.add_field(name="What's new", value=notes[:900], inline=False)
    e.set_footer(text="You need to own Minecraft: Java Edition to play online with a Microsoft account.")
    await interaction.followup.send(embed=e, view=LinksView())


@launcher_group.command(name="faq", description="Frequently asked questions")
@app_commands.describe(search="Optional keyword")
async def launcher_faq(interaction: discord.Interaction, search: Optional[str] = None) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True)
    try:
        items = await bot.db.faq()
    except Exception:
        items = []
    if search:
        s = search.lower()
        items = [i for i in items if s in (i.get("question") or "").lower() or s in (i.get("answer") or "").lower()]
    if not items:
        await interaction.followup.send("Nothing found." if search else "No FAQ entries yet.", ephemeral=True)
        return
    e = discord.Embed(title=f"{LAUNCHER_NAME} · FAQ" + (f" · “{search}”" if search else ""), color=EMBED_COLOR)
    for i in items[:8]:
        e.add_field(name=(i.get("question") or "?")[:256], value=(i.get("answer") or "–")[:1024], inline=False)
    await interaction.followup.send(embed=e, ephemeral=True)


@launcher_group.command(name="features", description="What the launcher can do")
async def launcher_features(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True)
    try:
        items = await bot.db.features()
    except Exception:
        items = []
    e = discord.Embed(title=f"{LAUNCHER_NAME} · Features", color=EMBED_COLOR)
    for i in items[:12]:
        badge = f" `{i['badge']}`" if i.get("badge") else ""
        e.add_field(name=f"{i.get('title', '?')}{badge}", value=(i.get("body") or "–")[:300], inline=False)
    if not items:
        e.description = "Feature list is being updated."
    await interaction.followup.send(embed=e, ephemeral=True)


@launcher_group.command(name="links", description="Official Craft Studio links")
async def launcher_links(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    links = {}
    try:
        links = await bot.db.site_setting("links")
    except Exception:
        pass
    e = discord.Embed(title=f"{COMMUNITY_NAME} · Links", color=EMBED_COLOR)
    e.add_field(name="Discord", value=links.get("discord") or INVITE_URL, inline=False)
    e.add_field(name="YouTube", value=links.get("youtube") or YOUTUBE_URL, inline=False)
    if links.get("github"):
        e.add_field(name="GitHub", value=links["github"], inline=False)
    if WEBSITE_URL:
        e.add_field(name="Website", value=WEBSITE_URL, inline=False)
    await interaction.response.send_message(embed=e, ephemeral=True)


@launcher_group.command(name="presence", description="Discord Rich Presence: setup & troubleshooting")
async def launcher_presence(interaction: discord.Interaction) -> None:
    await interaction.response.send_message(embed=presence_help_embed(), ephemeral=True)


@launcher_group.command(name="stats", description="Who is playing what (linked players)")
@app_commands.describe(period="Time window")
@app_commands.choices(period=[app_commands.Choice(name="24 hours", value=24), app_commands.Choice(name="7 days", value=168),
                              app_commands.Choice(name="30 days", value=720)])
async def launcher_stats(interaction: discord.Interaction, period: Optional[app_commands.Choice[int]] = None) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer()
    await interaction.followup.send(embed=await play_stats_embed(bot, period.value if period else 168))


@launcher_group.command(name="servers", description="Most played servers (linked players, 30 days)")
async def launcher_servers(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer()
    rows = await bot.db.sessions(since_hours=720, limit=10000)
    by_server: dict[str, dict] = {}
    for r in rows:
        name = r.get("server_name")
        if not name or name == "Private server":
            continue
        d = by_server.setdefault(name, {"sessions": 0, "players": set(), "time": 0, "host": r.get("server_host")})
        d["sessions"] += 1
        d["players"].add(r["discord_user_id"])
        d["time"] += int(r.get("duration_s") or 0)
    top = sorted(by_server.items(), key=lambda kv: (-len(kv[1]["players"]), -kv[1]["sessions"]))[:10]
    e = discord.Embed(title="🌐 Most played servers · 30 days", color=EMBED_COLOR)
    if not top:
        e.description = "No multiplayer sessions recorded yet."
    else:
        e.description = "\n".join(
            f"`{i + 1:>2}.` **{name}**" + (f" · `{d['host']}`" if d.get("host") else "") +
            f" — {len(d['players'])} players · {d['sessions']} sessions · {_fmt_dur(d['time'])}"
            for i, (name, d) in enumerate(top))
    e.set_footer(text="Private / LAN addresses are never recorded")
    await interaction.followup.send(embed=e)


notice_group = app_commands.Group(name="notice", description="Staff: message shown inside every launcher",
                                  parent=launcher_group)


@notice_group.command(name="set", description="Staff: push a notice popup to all launchers (shown once per device)")
@app_commands.describe(text="Message (plain text)", title="Popup title", link="Optional https:// link for the button",
                       button="Button label (default: Open)", days="Auto-expire after N days (0 = never)",
                       min_version="Only show on launcher versions >= this (e.g. 1.5.0)")
async def notice_set(interaction: discord.Interaction, text: str, title: Optional[str] = None,
                     link: Optional[str] = None, button: Optional[str] = None, days: Optional[int] = 7,
                     min_version: Optional[str] = None) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    if link and not link.startswith(("https://", "http://")):
        await interaction.response.send_message("Link must start with https://", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True)
    value = {
        "id": str(int(time.time() * 1000)), "enabled": True, "title": (title or "").strip()[:60],
        "text": text.strip()[:600], "link": (link or "").strip(), "button": (button or "").strip()[:24],
        "min_app_version": (min_version or "").strip(),
        "ends_at_ms": int((time.time() + days * 86400) * 1000) if days and days > 0 else 0,
        "author": str(interaction.user), "created_at": _now(),
    }
    await bot.db.set_notice(value)
    e = discord.Embed(title=value["title"] or "Message from Craft Studio", description=value["text"], color=EMBED_COLOR)
    if value["link"]:
        e.add_field(name=value["button"] or "Open", value=value["link"])
    e.set_footer(text=f"Live · launchers check every ~4 h · expires {'in %d days' % days if days else 'never'}")
    await interaction.followup.send("Notice is live. Preview:", embed=e, ephemeral=True)
    await bot.log_embed(discord.Embed(title="📣 Launcher notice published", description=value["text"],
                                      color=EMBED_COLOR).set_footer(text=f"by {interaction.user}"))


@notice_group.command(name="clear", description="Staff: take the launcher notice down")
async def notice_clear(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    current = await bot.db.site_setting("launcher_notice")
    current["enabled"] = False
    await bot.db.set_notice(current)
    await interaction.followup.send("Notice disabled.", ephemeral=True)


@notice_group.command(name="show", description="Staff: show the current launcher notice")
async def notice_show(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = launcher_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    v = await bot.db.site_setting("launcher_notice")
    if not v or not v.get("text"):
        await interaction.followup.send("No notice set.", ephemeral=True)
        return
    e = discord.Embed(title=v.get("title") or "Message from Craft Studio", description=v.get("text"),
                      color=EMBED_COLOR if v.get("enabled") else 0x9C9CA4)
    e.add_field(name="Status", value="enabled" if v.get("enabled") else "disabled")
    if v.get("link"):
        e.add_field(name="Link", value=v["link"])
    if v.get("ends_at_ms"):
        e.add_field(name="Expires", value=f"<t:{int(v['ends_at_ms']) // 1000}:R>")
    e.set_footer(text=f"by {v.get('author', '?')} · id {v.get('id', '?')}")
    await interaction.followup.send(embed=e, ephemeral=True)


# --- /bot ----------------------------------------------------------------------


@bot_group.command(name="status", description="Bot health")
async def bot_status(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = bot_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True)
    uptime = int(time.time() - bot.started_at)
    pending = "?"
    try:
        pending = str(await bot.db.count("discord_link_events", {"processed_at": "is.null"}))
    except Exception:
        pass
    e = discord.Embed(title="Craft Studio bot", color=0x57F287 if bot.db.healthy else 0xE05A5A)
    e.add_field(name="Version", value=f"v{BOT_VERSION} · discord.py {discord.__version__}")
    e.add_field(name="Uptime", value=f"{uptime // 86400}d {uptime % 86400 // 3600}h {uptime % 3600 // 60}m")
    e.add_field(name="Latency", value=f"{bot.latency * 1000:.0f} ms")
    e.add_field(name="Supabase", value="ok" if bot.db.healthy else f"down: {bot.db.last_error}"[:200])
    e.add_field(name="Queue", value=pending)
    e.add_field(name="Processed / failed", value=f"{bot.processed} / {bot.failed}")
    e.add_field(name="Members intent", value="on" if bot.members_intent else "off (enable in Developer Portal)")
    e.add_field(name="Poll", value=f"every {POLL_SECONDS}s")
    problems = bot._check_permissions()
    e.add_field(name="Checks", value="\n".join(f"⚠️ {p}" for p in problems) or "✅ all good", inline=False)
    await interaction.followup.send(embed=e, ephemeral=True)


@bot_group.command(name="invite", description="Staff: bot invite link (re-authorize with correct permissions)")
async def bot_invite(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = bot_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.send_message(bot.invite_url(), ephemeral=True)


@bot_group.command(name="preview-dm", description="Staff: send yourself the welcome DM to preview it")
async def bot_preview_dm(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = bot_group.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    ev = {"display_name": interaction.user.display_name, "username": interaction.user.name,
          "avatar_url": interaction.user.display_avatar.url, "app_version": "preview"}
    state = await bot.dm(interaction.user.id, bot.welcome_embed(ev, "already", True), LinksView())
    await interaction.followup.send(f"DM {state}.", ephemeral=True)


# --- context menu -----------------------------------------------------------------


@app_commands.context_menu(name="Launcher link info")
async def link_info_ctx(interaction: discord.Interaction, user: discord.Member) -> None:
    bot: CraftStudioBot = link_info_ctx.bot  # type: ignore[attr-defined]
    if await _deny(interaction):
        return
    await interaction.response.defer(ephemeral=True)
    e = await _with_play_info(bot, user.id, _link_embed(user, await bot.db.get_link(user.id)))
    await interaction.followup.send(embed=e, ephemeral=True)


# =============================================================================== main


def main() -> None:
    for members_intent in (True, False):
        bot = CraftStudioBot(members_intent=members_intent)
        try:
            bot.run(BOT_TOKEN, log_handler=None)
            return
        except discord.PrivilegedIntentsRequired:
            if members_intent:
                log.warning("Server Members Intent is OFF in the Developer Portal - restarting without it. "
                            "(Developer Portal -> Bot -> Privileged Gateway Intents -> Server Members Intent -> ON)")
                continue
            raise
        except discord.LoginFailure:
            log.error("Discord rejected DISCORD_BOT_TOKEN - reset the token in the Developer Portal and update .env")
            sys.exit(1)


if __name__ == "__main__":
    main()
