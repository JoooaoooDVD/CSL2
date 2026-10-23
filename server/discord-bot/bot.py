#!/usr/bin/env python3
"""
Craft Studio community bot for CS Launcher Plus.

What it does
------------
* Watches the Supabase table ``discord_link_events`` that the launcher writes to when a player
  links / unlinks a Discord account (see DiscordLinkNotifier.java).
* On **link**: adds the player to the Craft Studio Discord server (OAuth ``guilds.join``),
  gives them the "Linked" role, sends a welcome DM, posts to the log channel and remembers the link
  in ``discord_links``. The OAuth access token is wiped from the row right after use.
* On **unlink**: removes the role, sends a short DM and logs it.
* If a linked player joins the server later (e.g. they declined the auto-join), the role is
  given automatically on join.
* Slash commands: ``/links stats``, ``/links lookup``, ``/links sync``, ``/links queue``.

Everything is configured through environment variables (see ``.env.example``).
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
import sys
import time
from datetime import datetime, timezone
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

# --------------------------------------------------------------------------- configuration

def _env(name: str, default: Optional[str] = None, required: bool = False) -> str:
    value = os.getenv(name, default)
    if required and not value:
        print(f"[config] Missing required environment variable {name}", file=sys.stderr)
        sys.exit(2)
    return value or ""


def _env_int(name: str, default: int = 0, required: bool = False) -> int:
    raw = _env(name, str(default) if default else None, required)
    try:
        return int(raw) if raw else 0
    except ValueError:
        print(f"[config] {name} must be a number, got {raw!r}", file=sys.stderr)
        sys.exit(2)


import json
from pathlib import Path

BOT_TOKEN = _env("DISCORD_BOT_TOKEN", required=True)
GUILD_ID = _env_int("GUILD_ID")  # 0 = auto: the (single) server the bot is in
LINKED_ROLE_ID = _env_int("LINKED_ROLE_ID")  # 0 = auto-create "Linked" role
LINKED_ROLE_NAME = _env("LINKED_ROLE_NAME", "Linked")
LOG_CHANNEL_ID = _env_int("LOG_CHANNEL_ID")  # 0 = auto-create private #launcher-links
LOG_CHANNEL_NAME = _env("LOG_CHANNEL_NAME", "launcher-links")
ADMIN_ROLE_ID = _env_int("ADMIN_ROLE_ID")  # 0 = server admins only
SUPABASE_URL = _env("SUPABASE_URL", required=True).rstrip("/")
SUPABASE_SERVICE_KEY = _env("SUPABASE_SERVICE_KEY", required=True)
POLL_SECONDS = max(3, _env_int("POLL_SECONDS", 5))
INVITE_URL = _env("DISCORD_INVITE_URL", "https://discord.gg/dmteYqZZyY")
STATE_FILE = Path(_env("STATE_FILE", str(Path(__file__).with_name("data")) + "/state.json"))


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
        logging.getLogger("cs-bot").warning("Could not save state: %s", e)


_STATE = _load_state()
# ids remembered from a previous auto-setup win over "0" in .env
GUILD_ID = GUILD_ID or int(_STATE.get("guild_id") or 0)
LINKED_ROLE_ID = LINKED_ROLE_ID or int(_STATE.get("linked_role_id") or 0)
LOG_CHANNEL_ID = LOG_CHANNEL_ID or int(_STATE.get("log_channel_id") or 0)
YOUTUBE_URL = _env("YOUTUBE_URL", "https://youtube.com/@craft-studio-official")
WEBSITE_URL = _env("WEBSITE_URL", "")
LAUNCHER_NAME = _env("LAUNCHER_NAME", "CS Launcher Plus")
COMMUNITY_NAME = _env("COMMUNITY_NAME", "Craft Studio")
EMBED_COLOR = int(_env("EMBED_COLOR", "0x5865F2"), 16)
DM_ON_UNLINK = _env("DM_ON_UNLINK", "true").lower() in ("1", "true", "yes")
LOG_LEVEL = _env("LOG_LEVEL", "INFO").upper()

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)
log = logging.getLogger("cs-bot")

# --------------------------------------------------------------------------- Supabase (PostgREST)


class Supabase:
    """Tiny async PostgREST client using the service-role key (bypasses RLS)."""

    def __init__(self, url: str, key: str) -> None:
        self._rest = f"{url}/rest/v1"
        self._headers = {
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        }
        self._session: Optional[aiohttp.ClientSession] = None

    async def start(self) -> None:
        self._session = aiohttp.ClientSession(
            headers=self._headers, timeout=aiohttp.ClientTimeout(total=20)
        )

    async def close(self) -> None:
        if self._session:
            await self._session.close()

    async def _req(self, method: str, path: str, *, params: dict | None = None,
                   json: Any = None, prefer: str | None = None) -> Any:
        assert self._session is not None
        headers = {"Prefer": prefer} if prefer else {}
        async with self._session.request(method, f"{self._rest}/{path}", params=params,
                                         json=json, headers=headers) as resp:
            text = await resp.text()
            if resp.status >= 400:
                raise RuntimeError(f"Supabase {method} {path} -> {resp.status}: {text[:300]}")
            if not text:
                return None
            try:
                return await resp.json(content_type=None)
            except Exception:
                return text

    # events ---------------------------------------------------------------
    async def pending_events(self, limit: int = 25) -> list[dict]:
        return await self._req("GET", "discord_link_events", params={
            "processed_at": "is.null", "order": "id.asc", "limit": str(limit),
        }) or []

    async def pending_count(self) -> int:
        assert self._session is not None
        async with self._session.get(f"{self._rest}/discord_link_events",
                                     params={"processed_at": "is.null", "select": "id"},
                                     headers={"Prefer": "count=exact", "Range": "0-0"}) as resp:
            cr = resp.headers.get("Content-Range", "*/0")
            try:
                return int(cr.split("/")[-1])
            except ValueError:
                return 0

    async def finish_event(self, event_id: int, result: str) -> None:
        await self._req("PATCH", "discord_link_events", params={"id": f"eq.{event_id}"},
                        json={"processed_at": _now(), "result": result[:500], "access_token": None},
                        prefer="return=minimal")

    # links ----------------------------------------------------------------
    async def get_link(self, user_id: int | str) -> Optional[dict]:
        rows = await self._req("GET", "discord_links", params={"discord_user_id": f"eq.{user_id}", "limit": "1"})
        return rows[0] if rows else None

    async def upsert_link(self, row: dict) -> None:
        await self._req("POST", "discord_links", json=row,
                        prefer="resolution=merge-duplicates,return=minimal")

    async def update_link(self, user_id: int | str, patch: dict) -> None:
        await self._req("PATCH", "discord_links", params={"discord_user_id": f"eq.{user_id}"},
                        json=patch, prefer="return=minimal")

    async def active_links(self) -> list[dict]:
        return await self._req("GET", "discord_links", params={
            "unlinked_at": "is.null", "select": "discord_user_id,username,role_given,joined_guild",
            "limit": "10000",
        }) or []

    async def stats(self) -> dict:
        assert self._session is not None

        async def count(table: str, params: dict) -> int:
            async with self._session.get(f"{self._rest}/{table}", params={**params, "select": "*"},
                                         headers={"Prefer": "count=exact", "Range": "0-0"}) as resp:
                cr = resp.headers.get("Content-Range", "*/0")
                try:
                    return int(cr.split("/")[-1])
                except ValueError:
                    return 0

        today = datetime.now(timezone.utc).strftime("%Y-%m-%dT00:00:00Z")
        return {
            "linked": await count("discord_links", {"unlinked_at": "is.null"}),
            "unlinked": await count("discord_links", {"unlinked_at": "not.is.null"}),
            "events_total": await count("discord_link_events", {}),
            "events_today": await count("discord_link_events", {"created_at": f"gte.{today}"}),
            "pending": await count("discord_link_events", {"processed_at": "is.null"}),
        }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# --------------------------------------------------------------------------- the bot


class CraftStudioBot(discord.Client):
    def __init__(self) -> None:
        intents = discord.Intents.default()
        intents.members = True  # needed for on_member_join + role sync (enable in the dev portal!)
        super().__init__(intents=intents)
        self.tree = app_commands.CommandTree(self)
        self.db = Supabase(SUPABASE_URL, SUPABASE_SERVICE_KEY)
        self.started_at = time.time()
        self.processed = 0
        self.failed = 0
        self._guild: Optional[discord.Guild] = None

    # lifecycle ------------------------------------------------------------
    async def setup_hook(self) -> None:
        await self.db.start()
        links_group.bot = self  # type: ignore[attr-defined]
        self._ready_once = False

    async def close(self) -> None:
        self.poll_events.cancel()
        self.update_status.cancel()
        await self.db.close()
        await super().close()

    async def on_ready(self) -> None:
        global GUILD_ID
        log.info("Logged in as %s (%s)", self.user, self.user.id if self.user else "?")
        if not GUILD_ID:
            guilds = list(self.guilds)
            if len(guilds) == 1:
                GUILD_ID = guilds[0].id
                _STATE["guild_id"] = GUILD_ID
                _save_state(_STATE)
                log.info("Auto-detected server: %s (%s)", guilds[0].name, GUILD_ID)
            elif not guilds:
                log.error("The bot is not in any server yet. Invite it with:\n  %s", self.invite_url())
                return
            else:
                log.error("Bot is in %d servers - set GUILD_ID in .env to pick one: %s", len(guilds),
                          ", ".join(f"{g.name}={g.id}" for g in guilds))
                return
        self._guild = self.get_guild(GUILD_ID) or await self.fetch_guild(GUILD_ID)
        if self._guild is None:
            log.error("Bot is not a member of server %s. Invite it with:\n  %s", GUILD_ID, self.invite_url())
            return
        log.info("Server: %s (%s members)", self._guild.name, self._guild.member_count)
        if not self._ready_once:
            self._ready_once = True
            await self.auto_setup()
            self.tree.add_command(links_group, guild=discord.Object(GUILD_ID))
            await self.tree.sync(guild=discord.Object(GUILD_ID))
            log.info("Slash commands synced")
            self.poll_events.start()
            self.update_status.start()
        self._check_permissions()

    def invite_url(self) -> str:
        app_id = self.user.id if self.user else "APP_ID"
        return (f"https://discord.com/oauth2/authorize?client_id={app_id}"
                "&scope=bot%20applications.commands&permissions=268454929")

    async def auto_setup(self) -> None:
        """Create the Linked role and the private log channel when they are not configured."""
        global LINKED_ROLE_ID, LOG_CHANNEL_ID
        guild = self._guild
        if guild is None or guild.me is None:
            return
        perms = guild.me.guild_permissions
        # role
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
                    role = await guild.create_role(
                        name=LINKED_ROLE_NAME, colour=discord.Colour(EMBED_COLOR), mentionable=False,
                        reason=f"{LAUNCHER_NAME}: role for players who linked their account")
                    # put it right under the bot's own top role so we can always manage it
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
        # log channel
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
                        guild.me: discord.PermissionOverwrite(view_channel=True, send_messages=True, embed_links=True),
                    }
                    channel = await guild.create_text_channel(
                        LOG_CHANNEL_NAME, overwrites=overwrites,
                        topic=f"{LAUNCHER_NAME} account link / unlink log (bot only)",
                        reason=f"{LAUNCHER_NAME}: private log channel")
                    LOG_CHANNEL_ID = channel.id
                    log.info("Created private channel #%s (%s)", channel.name, channel.id)
                except discord.HTTPException as e:
                    log.warning("Could not create the log channel: %s", e)
            else:
                log.warning("No Manage Channels permission - log channel disabled")
            if LOG_CHANNEL_ID:
                _STATE["log_channel_id"] = LOG_CHANNEL_ID
                _save_state(_STATE)

    @tasks.loop(minutes=10)
    async def update_status(self) -> None:
        try:
            s = await self.db.stats()
            await self.change_presence(activity=discord.Activity(
                type=discord.ActivityType.watching,
                name=f"{s['linked']} linked players · {LAUNCHER_NAME}"))
        except Exception as e:
            log.debug("status update failed: %s", e)

    @update_status.before_loop
    async def _status_wait(self) -> None:
        await self.wait_until_ready()

    def _check_permissions(self) -> None:
        guild = self._guild
        if guild is None or guild.me is None:
            return
        perms = guild.me.guild_permissions
        problems = []
        if not perms.create_instant_invite:
            problems.append("Create Invite (needed for auto-join)")
        if LINKED_ROLE_ID and not perms.manage_roles:
            problems.append("Manage Roles (needed for the Linked role)")
        role = guild.get_role(LINKED_ROLE_ID) if LINKED_ROLE_ID else None
        if LINKED_ROLE_ID and role is None:
            problems.append(f"role {LINKED_ROLE_ID} not found in this server")
        elif role is not None and guild.me.top_role <= role:
            problems.append(f"bot's top role must be ABOVE '{role.name}' in the role list")
        if problems:
            log.warning("Permission check: %s", "; ".join(problems))
        else:
            log.info("Permission check OK")

    # helpers --------------------------------------------------------------
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
        """PUT /guilds/{guild}/members/{user}. Returns 'joined', 'already', or raises."""
        payload: dict[str, Any] = {"access_token": access_token}
        if LINKED_ROLE_ID:
            payload["roles"] = [str(LINKED_ROLE_ID)]
        route = discord.http.Route("PUT", "/guilds/{guild_id}/members/{user_id}",
                                   guild_id=GUILD_ID, user_id=user_id)
        try:
            data = await self.http.request(route, json=payload)
        except discord.HTTPException as e:
            # 30001 = max guilds, 40007 = banned, 50025 = invalid/expired oauth token,
            # 50013 = bot lacks permission, 50035 = invalid scope
            raise RuntimeError(f"guilds.join failed: HTTP {e.status} code {e.code}: {e.text}") from e
        return "already" if data is None or data == "" else "joined"

    async def give_role(self, member: discord.Member, reason: str) -> bool:
        if not LINKED_ROLE_ID:
            return False
        role = member.guild.get_role(LINKED_ROLE_ID)
        if role is None:
            log.warning("Linked role %s does not exist", LINKED_ROLE_ID)
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

    async def dm(self, user_id: int, embed: discord.Embed, view: Optional[discord.ui.View] = None) -> bool:
        try:
            user = self.get_user(user_id) or await self.fetch_user(user_id)
            await user.send(embed=embed, view=view)
            return True
        except discord.Forbidden:
            log.info("DM to %s blocked (privacy settings)", user_id)
        except discord.HTTPException as e:
            log.warning("DM to %s failed: %s", user_id, e)
        return False

    async def log_embed(self, embed: discord.Embed) -> None:
        if not LOG_CHANNEL_ID:
            return
        channel = self.get_channel(LOG_CHANNEL_ID)
        if channel is None:
            try:
                channel = await self.fetch_channel(LOG_CHANNEL_ID)
            except discord.HTTPException:
                return
        try:
            await channel.send(embed=embed)  # type: ignore[union-attr]
        except discord.HTTPException as e:
            log.warning("log channel send failed: %s", e)

    # embeds ---------------------------------------------------------------
    @staticmethod
    def links_view() -> discord.ui.View:
        view = discord.ui.View()
        view.add_item(discord.ui.Button(label=f"{COMMUNITY_NAME} Discord", url=INVITE_URL, emoji="💬"))
        view.add_item(discord.ui.Button(label="YouTube", url=YOUTUBE_URL, emoji="▶️"))
        if WEBSITE_URL:
            view.add_item(discord.ui.Button(label="Website", url=WEBSITE_URL, emoji="🌐"))
        return view

    def welcome_embed(self, ev: dict, joined: str, role_ok: bool) -> discord.Embed:
        name = ev.get("display_name") or ev.get("username") or "there"
        e = discord.Embed(
            title="✅ Account linked",
            description=(
                f"Hey **{name}**! Your Discord account is now linked with **{LAUNCHER_NAME}**.\n\n"
                "Your friends can see what you're playing through Rich Presence — the profile, the "
                "world or the server — straight on your Discord profile."
            ),
            color=EMBED_COLOR,
            timestamp=datetime.now(timezone.utc),
        )
        perks = []
        if joined == "joined":
            perks.append(f"🎉 You've been added to the **{COMMUNITY_NAME}** server — welcome!")
        elif joined == "already":
            perks.append(f"💬 You're already in the **{COMMUNITY_NAME}** server — nice.")
        else:
            perks.append(f"💬 Join the **{COMMUNITY_NAME}** server with the button below.")
        if role_ok and LINKED_ROLE_ID:
            perks.append(f"🏷️ You got the <@&{LINKED_ROLE_ID}> role.")
        e.add_field(name="What happened", value="\n".join(perks), inline=False)
        e.add_field(
            name="Privacy",
            value=("The launcher only shares what you're playing while the game is open. You can "
                   "unlink any time from *Settings → Discord Rich Presence*."),
            inline=False,
        )
        if ev.get("avatar_url"):
            e.set_thumbnail(url=ev["avatar_url"])
        version = ev.get("app_version")
        e.set_footer(text=f"{LAUNCHER_NAME}" + (f" {version}" if version else ""))
        return e

    def unlink_embed(self, ev: dict) -> discord.Embed:
        e = discord.Embed(
            title="Account unlinked",
            description=(f"Your Discord account was unlinked from **{LAUNCHER_NAME}**. Rich Presence "
                         "still works without linking — link again any time from the launcher settings."),
            color=0x9C9CA4,
            timestamp=datetime.now(timezone.utc),
        )
        e.set_footer(text=LAUNCHER_NAME)
        return e

    def log_event_embed(self, ev: dict, result: str, ok: bool) -> discord.Embed:
        kind = ev.get("kind", "link")
        uid = ev.get("discord_user_id")
        e = discord.Embed(
            title=("🔗 Linked" if kind == "link" else "🔓 Unlinked") + ("" if ok else " (problem)"),
            color=(EMBED_COLOR if kind == "link" else 0x9C9CA4) if ok else 0xE05A5A,
            timestamp=datetime.now(timezone.utc),
        )
        e.add_field(name="User", value=f"<@{uid}> · `{ev.get('username') or '?'}` · `{uid}`", inline=False)
        e.add_field(name="Result", value=result[:1000] or "–", inline=False)
        meta = " · ".join(x for x in [ev.get("app_version"), ev.get("device")] if x)
        if meta:
            e.set_footer(text=meta)
        if ev.get("avatar_url"):
            e.set_thumbnail(url=ev["avatar_url"])
        return e

    # event processing -----------------------------------------------------
    @tasks.loop(seconds=POLL_SECONDS)
    async def poll_events(self) -> None:
        try:
            events = await self.db.pending_events()
        except Exception as e:
            log.warning("Supabase poll failed: %s", e)
            return
        for ev in events:
            try:
                await self.handle_event(ev)
            except Exception as e:  # never let one bad row stall the queue
                log.exception("Event %s failed", ev.get("id"))
                self.failed += 1
                try:
                    await self.db.finish_event(int(ev["id"]), f"error: {e}")
                except Exception as e2:
                    log.error("Could not mark event %s: %s", ev.get("id"), e2)

    @poll_events.before_loop
    async def _wait_ready(self) -> None:
        await self.wait_until_ready()

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

        # 1) join the server (or just confirm membership)
        joined = "none"
        token = ev.get("access_token")
        member = await self.member_of(user_id)
        if member is not None:
            joined = "already"
        elif token and "guilds.join" in (ev.get("scopes") or "guilds.join"):
            try:
                joined = await self.join_guild(user_id, token)
                if joined == "joined":
                    await asyncio.sleep(1.0)  # let the gateway deliver the member
                member = await self.member_of(user_id)
            except Exception as e:
                ok = False
                steps.append(str(e))
                log.warning("Auto-join failed for %s: %s", user_id, e)
        else:
            steps.append("no oauth token / guilds.join scope — could not auto-join")
        steps.append({"joined": "joined the server", "already": "already a member",
                      "none": "not in the server"}[joined])

        # 2) role
        role_ok = False
        if member is not None:
            role_ok = await self.give_role(member, "Linked Discord in CS Launcher Plus")
            steps.append("role given" if role_ok else ("role skipped" if not LINKED_ROLE_ID else "role FAILED"))

        # 3) DM
        dm_ok = await self.dm(user_id, self.welcome_embed(ev, joined, role_ok), self.links_view())
        steps.append("DM sent" if dm_ok else "DM blocked")

        # 4) remember
        existing = await self.db.get_link(user_id)
        row = {
            "discord_user_id": str(user_id),
            "username": ev.get("username"),
            "display_name": ev.get("display_name"),
            "avatar_url": ev.get("avatar_url"),
            "linked_at": _now(),
            "unlinked_at": None,
            "joined_guild": member is not None,
            "role_given": role_ok,
            "dm_sent": dm_ok,
            "link_count": (existing.get("link_count", 0) + 1) if existing else 1,
        }
        await self.db.upsert_link(row)

        result = " · ".join(steps)
        await self.db.finish_event(event_id, ("ok: " if ok else "partial: ") + result)
        await self.log_embed(self.log_event_embed(ev, result, ok))

    async def _handle_unlink(self, ev: dict, user_id: int) -> None:
        event_id = int(ev["id"])
        steps: list[str] = []
        member = await self.member_of(user_id)
        if member is not None:
            removed = await self.remove_role(member, "Unlinked Discord in CS Launcher Plus")
            steps.append("role removed" if removed else "no role to remove")
        else:
            steps.append("not in the server")
        if DM_ON_UNLINK:
            steps.append("DM sent" if await self.dm(user_id, self.unlink_embed(ev)) else "DM blocked")
        try:
            await self.db.update_link(user_id, {"unlinked_at": _now(), "role_given": False})
        except Exception as e:
            steps.append(f"db: {e}")
        result = " · ".join(steps)
        await self.db.finish_event(event_id, "ok: " + result)
        await self.log_embed(self.log_event_embed(ev, result, True))

    # gateway events -------------------------------------------------------
    async def on_member_join(self, member: discord.Member) -> None:
        if member.guild.id != GUILD_ID or not LINKED_ROLE_ID:
            return
        try:
            link = await self.db.get_link(member.id)
        except Exception as e:
            log.warning("get_link on join failed: %s", e)
            return
        if link and not link.get("unlinked_at"):
            if await self.give_role(member, "Linked account joined the server"):
                await self.db.update_link(member.id, {"joined_guild": True, "role_given": True})
                log.info("Gave Linked role to %s on join", member)


# --------------------------------------------------------------------------- slash commands


def _is_admin(interaction: discord.Interaction) -> bool:
    user = interaction.user
    if isinstance(user, discord.Member):
        if user.guild_permissions.administrator or user.guild_permissions.manage_guild:
            return True
        if ADMIN_ROLE_ID and any(r.id == ADMIN_ROLE_ID for r in user.roles):
            return True
    return False


links_group = app_commands.Group(name="links", description=f"{LAUNCHER_NAME} account links")


@links_group.command(name="stats", description="How many players linked their Discord account")
async def links_stats(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True, thinking=True)
    s = await bot.db.stats()
    uptime = int(time.time() - bot.started_at)
    e = discord.Embed(title=f"{LAUNCHER_NAME} · Discord links", color=EMBED_COLOR)
    e.add_field(name="Linked now", value=f"**{s['linked']}**")
    e.add_field(name="Unlinked", value=str(s["unlinked"]))
    e.add_field(name="Events today", value=str(s["events_today"]))
    e.add_field(name="Events total", value=str(s["events_total"]))
    e.add_field(name="Queue", value=str(s["pending"]))
    e.add_field(name="Bot", value=f"up {uptime // 3600}h {uptime % 3600 // 60}m · {bot.processed} ok · {bot.failed} failed")
    await interaction.followup.send(embed=e, ephemeral=True)


@links_group.command(name="lookup", description="Check whether a member linked the launcher")
@app_commands.describe(user="Member to look up")
async def links_lookup(interaction: discord.Interaction, user: discord.User) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True)
    link = await bot.db.get_link(user.id)
    if not link:
        await interaction.followup.send(f"{user.mention} has **not** linked {LAUNCHER_NAME}.", ephemeral=True)
        return
    status = "unlinked" if link.get("unlinked_at") else "linked"
    e = discord.Embed(title=f"{user} · {status}", color=EMBED_COLOR if status == "linked" else 0x9C9CA4)
    e.add_field(name="Launcher name", value=link.get("display_name") or link.get("username") or "–")
    e.add_field(name="Linked at", value=(link.get("linked_at") or "–")[:19].replace("T", " "))
    e.add_field(name="Times linked", value=str(link.get("link_count", 1)))
    e.add_field(name="Role", value="yes" if link.get("role_given") else "no")
    e.add_field(name="DM", value="sent" if link.get("dm_sent") else "blocked")
    if link.get("avatar_url"):
        e.set_thumbnail(url=link["avatar_url"])
    await interaction.followup.send(embed=e, ephemeral=True)


@links_group.command(name="sync", description="Give the Linked role to every linked member in the server")
async def links_sync(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    if not LINKED_ROLE_ID:
        await interaction.response.send_message("LINKED_ROLE_ID is not configured.", ephemeral=True)
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
        f"Checked **{len(links)}** linked accounts · **{present}** are in the server · gave the role to **{given}**.",
        ephemeral=True)


@links_group.command(name="me", description="Is my Discord linked with the launcher?")
async def links_me(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    await interaction.response.defer(ephemeral=True)
    link = await bot.db.get_link(interaction.user.id)
    if link and not link.get("unlinked_at"):
        when = (link.get("linked_at") or "")[:10]
        msg = f"✅ Linked with **{LAUNCHER_NAME}** since {when}."
        if LINKED_ROLE_ID and isinstance(interaction.user, discord.Member):
            if not any(r.id == LINKED_ROLE_ID for r in interaction.user.roles):
                if await bot.give_role(interaction.user, "/links me"):
                    msg += f" Gave you the <@&{LINKED_ROLE_ID}> role."
    else:
        msg = (f"❌ Not linked. Open **{LAUNCHER_NAME} → Settings → Discord Rich Presence → Link** "
               "and you'll get the role + a DM here.")
    await interaction.followup.send(msg, ephemeral=True)


@links_group.command(name="recent", description="Last link / unlink events")
async def links_recent(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True)
    rows = await bot.db._req("GET", "discord_link_events", params={
        "select": "id,kind,discord_user_id,username,created_at,result,app_version",
        "order": "id.desc", "limit": "12"}) or []
    if not rows:
        await interaction.followup.send("No events yet.", ephemeral=True)
        return
    lines = []
    for r in rows:
        icon = "🔗" if r.get("kind") == "link" else "🔓"
        when = (r.get("created_at") or "")[:16].replace("T", " ")
        res = (r.get("result") or "pending")[:60]
        lines.append(f"{icon} `{when}` <@{r['discord_user_id']}> `{r.get('username') or '?'}` — {res}")
    e = discord.Embed(title="Recent link events", description="\n".join(lines), color=EMBED_COLOR)
    await interaction.followup.send(embed=e, ephemeral=True)


@links_group.command(name="unlink", description="Staff: remove a member's Linked role and mark them unlinked")
@app_commands.describe(user="Member to unlink")
async def links_unlink(interaction: discord.Interaction, user: discord.Member) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True)
    removed = await bot.remove_role(user, f"/links unlink by {interaction.user}")
    try:
        await bot.db.update_link(user.id, {"unlinked_at": _now(), "role_given": False})
    except Exception:
        pass
    await interaction.followup.send(
        f"{user.mention}: role {'removed' if removed else 'was not present'}; marked as unlinked.", ephemeral=True)


@links_group.command(name="setup", description="Staff: re-run auto setup (role, log channel, permission check)")
async def links_setup(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    await bot.auto_setup()
    bot._check_permissions()
    role = f"<@&{LINKED_ROLE_ID}>" if LINKED_ROLE_ID else "—"
    ch = f"<#{LOG_CHANNEL_ID}>" if LOG_CHANNEL_ID else "—"
    await interaction.followup.send(f"Role: {role} · Log channel: {ch} · Poll: every {POLL_SECONDS}s", ephemeral=True)


@links_group.command(name="queue", description="Show pending link events (and process them now)")
async def links_queue(interaction: discord.Interaction) -> None:
    bot: CraftStudioBot = links_group.bot  # type: ignore[attr-defined]
    if not _is_admin(interaction):
        await interaction.response.send_message("Only staff can use this.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    before = await bot.db.pending_count()
    await bot.poll_events()
    after = await bot.db.pending_count()
    await interaction.followup.send(f"Queue had **{before}** event(s); **{after}** left after processing.", ephemeral=True)


# --------------------------------------------------------------------------- main


def main() -> None:
    bot = CraftStudioBot()

    loop_stop = getattr(signal, "SIGTERM", None)
    if loop_stop is not None and sys.platform != "win32":
        try:
            asyncio.get_event_loop().add_signal_handler(loop_stop, lambda: asyncio.ensure_future(bot.close()))
        except (NotImplementedError, RuntimeError):
            pass

    bot.run(BOT_TOKEN, log_handler=None)


if __name__ == "__main__":
    main()
