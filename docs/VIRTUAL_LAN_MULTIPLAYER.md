# Virtual LAN Multiplayer

Play a Minecraft Java "Open to LAN" world with friends who are **not** on your Wi-Fi: mobile data
vs. home Wi-Fi, different cities, behind carrier-grade NAT, no router configuration, no public IP,
no system VPN permission. The host creates a session, shares a 12-character code, guests enter the
code and join the world from Minecraft's Multiplayer screen.

This document records the research, the design decisions, the architecture, the security model,
the relay/cost situation and **what was actually tested** vs. what still needs real devices.

---

## 1. Research summary

### How the others do it

| Product | Model | Why we did / did not copy it |
|---|---|---|
| **Hamachi** | Central LogMeIn servers, virtual L2 network, system TAP driver | Proprietary service, no Android, needs a driver. Only the *UX* (join a named network, see members) was borrowed. |
| **ZeroTier** | Public "planet" root servers for discovery + hole punching, relay fallback through roots, L2 virtual network | Excellent model. Android needs `VpnService`; the public roots are free but ZeroTier's BSL licence forbids embedding their controller as a service; SDK integration is heavy. |
| **Tailscale** | WireGuard + DERP relays + coordination server (proprietary; Headscale is OSS) | WireGuard alone has **no discovery, no NAT traversal, no relay**. Requires `VpnService` and a coordination server we would have to host. |
| **Zalith Launcher 2 → Terracotta** (AGPL, Rust) | EasyTier under the hood, `VpnService`, a "scaffolding" HTTP service between host and guests, legacy 0xFE ping as a gate | Validated EasyTier as the engine. Its unreliability report (`PingHostFail`, issue #1486) comes from **gating the whole flow on a ping that fails on modern servers / busy phones**, plus `VpnService` consent/conflict problems with other VPNs. We removed both failure modes. |

### Engine choice: EasyTier (Apache-2.0)

EasyTier (https://github.com/EasyTier/EasyTier) is a decentralised mesh VPN written in Rust:

* **No TUN / no root mode** (`no_tun = true` + `use_smoltcp`): the node is reachable by its virtual
  IP without any system VPN. Outbound access is provided through **port forwards**
  (`--port-forward tcp://127.0.0.1:<local>/10.144.144.1:<port>`). This is exactly the shape of our
  problem (one TCP port in each direction), so **no `VpnService` is needed at all** — the least
  invasive option the task allowed.
* Built-in **NAT traversal** (STUN, UDP/TCP hole punching, symmetric NAT support), **relay
  fallback** through any node that allows relaying, **KCP proxy** for lossy mobile links,
  **encryption** (AES-GCM, per-network secret), `private_mode` (nodes with a different network
  secret cannot even see us), per-port whitelists, DHCP for guests, a JSON RPC/CLI that gives us the
  *real* peer table (direct vs relay, latency, NAT type).
* Static, single-file executables for `aarch64`, `armv7`, `x86_64` (no 32-bit x86 build exists).
  Android lets an app `exec()` files from its `nativeLibraryDir`, so the binaries are shipped as
  `libeasytier_core.so` / `libeasytier_cli.so` in `jniLibs/<abi>` (the launcher already does this for
  `libunpack200.so`). The APK keeps `useLegacyPackaging = true` and the two files are excluded from
  stripping (`keepDebugSymbols`) because they are UPX-packed executables, not real shared libraries.

Evaluation against the required criteria:

| Criterion | EasyTier no-TUN | Notes |
|---|---|---|
| Android compatibility | ✅ API 21+, arm64/arm32/x86_64 | x86 (32-bit) → "device not supported" message, nothing else breaks |
| Latency | ✅ direct P2P when NAT allows, else relay | real values are read from the engine, never estimated |
| Reliability / recovery | ✅ engine auto-reconnects; we add watchdog + restart (≤3) + session heartbeat | |
| NAT traversal | ✅ STUN + hole punching + symmetric NAT tricks | not guaranteed on every network, relay covers the rest |
| Relay | ✅ any public node with relay enabled | see §6 for the costs / self-host |
| Battery / memory / CPU | ✅ ~15–30 MB RSS, idle CPU ≈ 0, no polling from Java except a 2.5 s state tick | runs only during a session |
| Install ease | ✅ nothing to install, no consent dialogs | notification only |
| Maintenance | ✅ one pinned version + SHA-256 in `scripts/fetch_easytier.sh` | bump = change 4 lines |
| Licensing | ✅ Apache-2.0, binaries unmodified | credit in this doc |
| Hosting cost | ✅ zero to start (community nodes) ; optional €4/month VPS for a private relay | §6 |

---

## 2. User flows

**Host**
1. Launch Minecraft → pause → *Open to LAN*.
2. In-game menu → **Virtual LAN Multiplayer** → **Create Session** (or do it from the launcher
   home before launching – the session survives the launch because it lives in the game process).
3. The world is detected automatically (LAN port, name, version, player count) and the session
   code is shown with Copy / Share. Players appear in the list as they connect; each can be removed.
4. **End Session** stops everything; closing the world only marks it *closed* for guests.

**Guest**
1. Launcher → top dock **Multiplayer** (or in-game menu) → **Join Session** → enter the code.
2. Status goes *Starting → Discovering peers → Establishing tunnel → Connected directly /
   Connected through relay*. "Connected" is only shown once the host's directory answered through
   the tunnel.
3. **Join World** writes a `[Virtual LAN]` entry into the profile's `servers.dat` pointing at the
   local forwarded port and launches the game (or, in game, tells the user to open Multiplayer).
   The address (`127.0.0.1:<port>`) is also shown for Direct Connect. The port the entry points at
   never changes during a session, even if the host reopens the world on a new LAN port.

---

## 3. Architecture

```
app_cs/src/main/java/com/craftstudio/cslauncher/multiplayer/
├── core/                          pure Java, no Android imports – unit/E2E-testable on a desktop
│   ├── SessionCode                12 chars (no 0/O/1/I), 60 bits of entropy → network name,
│   │                              network secret, directory auth token (all SHA-256 derived)
│   ├── RelayNodes                 built-in node list + optional remote list + fast probe
│   ├── TunnelConfig / TunnelManager   writes the engine TOML, starts the engine under a shell
│   │                              watchdog (dies with the parent), reads peers/node via CLI JSON,
│   │                              adds port forwards, kills stale engines from previous crashes
│   ├── LanHostDetector            host side: multicast 224.0.2.60:4445 listener + "Started serving
│   │                              on <port>" log tap + Minecraft status ping (name, version, players)
│   ├── LanPortProxy               host side: fixed virtual port 25565 → current LAN port (the LAN
│   │                              port changes every "Open to LAN"; guests never notice)
│   ├── SessionDirectoryServer/Client  host-run HTTP/JSON service inside the tunnel: join /
│   │                              heartbeat / leave / kick, world status, member list
│   ├── MinecraftPinger            modern status ping (used for display only, never as a gate)
│   ├── VirtualLanState            immutable snapshot (phase, reason, members, world, diagnostics)
│   └── VirtualLanSession          the state machine (single worker thread + command queue)
├── VirtualLanService              foreground service in the :game process, Messenger IPC
├── VirtualLanClient               UI-side binder; delivers VirtualLanState on the main thread
├── VirtualLanStateBundler         state <-> Bundle
├── VirtualLanRepository           recent sessions, display name, custom relays (SharedPreferences)
├── AndroidTunnelEnvironment       binaries from nativeLibraryDir, work dir, pid helpers
├── LogTap                         fan-out for the native game log (log view + LAN detector)
├── ServersDat                     adds/removes the [Virtual LAN] entry (uncompressed NBT)
└── ui/
    ├── VirtualLanPanel            controller for view_virtual_lan.xml (home / session / result)
    ├── VirtualLanFragment         launcher screen (right pane on wide layouts)
    ├── VirtualLanPopup            in-game overlay (same panel)
    └── MultiplayerMotion          animations, all gated by the launcher/system animation setting
```

### Why the service lives in the `:game` process

The tunnel must never outlive the game and must never be orphaned. `GameActivity`/`GameService`
already run in `:game`; the session runs there too, so:

* game exit → `GameRunner` calls `VirtualLanService.shutdownForProcessExit()` (guests get
  `HOST_ENDED`, engine stopped) → process dies;
* game crash / force-stop → the shell watchdog started with the engine notices the parent PID is
  gone and kills the engine within a second; the next service start also sweeps stale PID files;
* launcher UI (main process) just binds; if `:game` disappears the client reports `GAME_CLOSED`.

### Session state machine

```
IDLE → STARTING → DISCOVERING → ESTABLISHING → CONNECTED_DIRECT ⇄ CONNECTED_RELAY
                                                   ↓ (path lost)
                                              RECONNECTING → (recovered) / HOST_LOST
any → FAILED(reason) / ENDED(reason)
```

Host: start proxy (25565, falls back to a free port) + directory (25560) + LAN detector, start the
engine with hostname `cslp-host-<proxyPort>-<directoryPort>` and virtual IP `10.144.144.1`, wait
for ≥1 relay node (30 s), then **Connected**. Members' latency / direct-vs-relay come from the
engine's peer table, not from guesses. Ending with members connected waits up to 5 s so the
directory can tell them `ending`.

Guest: engine with DHCP, wait for a peer whose hostname matches the prefix (60 s), read the ports
from the hostname, add two forwards (game → host proxy on a free local port, directory → ephemeral
port), `join` with the code-derived auth token (45 s), then heartbeat every tick. 3 failed
heartbeats → `RECONNECTING`; 90 s → `HOST_LOST`; 403 → `KICKED`; 404 → re-join; `ending` →
`HOST_ENDED`. Network switches (ConnectivityManager callback) tell the engine to re-evaluate paths.

Timeouts, the 12 h maximum duration and the engine restart limit (3) are in `VirtualLanSession`.

---

## 4. Security model

* **Session code** = 12 symbols of a 32-symbol alphabet (≈ 60 bits), `SecureRandom`. It is the only
  secret the user handles. Network name, network secret and the directory auth token are SHA-256
  derivations with distinct prefixes; the raw code is never transmitted.
* **Tunnel**: EasyTier `private_mode` (peers with another secret are rejected at the handshake),
  AES-GCM encryption, no relay of other networks' traffic through our nodes (we do not enable
  relaying on the phone).
* **Only two ports are exposed** on the host (`tcp_whitelist` = proxy port + directory port). The
  host's real LAN, the phone's other apps and the guest's device are not reachable through the
  tunnel. Guests expose nothing (`tcp_whitelist = []`).
* **Directory**: every request carries the auth token; members receive a random per-join token;
  kick invalidates it so a kicked guest cannot keep heart-beating; stale members are pruned after
  15 s without heartbeat; session lifetime is bounded (12 h, configurable).
* **Logging**: the engine echoes its config (including the secret) into its log; `TunnelManager`
  scrubs `network_secret`, tokens and the code before anything is kept, and the raw engine log is
  only reachable through the Diagnostics button (already scrubbed).
* **No long-lived secrets** on disk: recent sessions store only the code the user already shared
  (needed for "Rejoin"); clearing the list removes them.
* The remote relay list (`multiplayer/relay_nodes.json` in this repository, fetched over HTTPS at
  most every 6 h) contains only public node URIs; a malicious list could at worst point users at a
  slow relay – the tunnel stays end-to-end encrypted.

---

## 5. Lifecycle handling

| Event | Behaviour |
|---|---|
| Host closes the world | Detector sees the port die → `WORLD_CLOSED` event, directory reports `worldOpen=false`, guests' Join World is disabled, host card says "Waiting for Open to LAN". Session stays up. |
| Host reopens the world (new port) | Proxy retargets, guests keep the same local address. |
| Host ends the session | Directory answers `ending`, guests → `HOST_ENDED`, engines stopped, notification removed, `servers.dat` entry removed on the guest. |
| Host loses network | Guests: heartbeats fail → `RECONNECTING` (engine keeps punching) → recovered or `HOST_LOST` after 90 s with a Rejoin button. |
| Guest disconnects | `leave` is sent synchronously; host list updates within one tick; the guest can rejoin with the same code. |
| Kick | Member token invalidated → guest `KICKED` on next heartbeat (≤ 2.5 s). |
| Game crash / exit | Watchdog + `shutdownForProcessExit`; no orphan `easytier-core` (verified: `remaining easytier processes: 0` in the harness). |
| Launcher killed from recents | `onTaskRemoved`: if no game is running the session ends. |
| Wi-Fi ↔ mobile data | `ConnectivityManager` callback → `onNetworkChanged` → engine re-detects paths; UI shows `RECONNECTING`/path events. |
| Launcher restart with stale state | Service start sweeps stale PID files; the service keeps no state across process death, so a fresh open always shows the real situation. |
| Device without engine (x86) | Home screen shows the unsupported banner; Create/Join only shake it. |

Battery: the engine idles at ~0 % CPU; the Java side ticks every 2.5 s (one CLI call, one HTTP
request). The foreground notification is silent and offers an "End session" action.

---

## 6. Relay / bootstrap nodes and costs

EasyTier needs at least one reachable node to **meet** (exchange candidates); once peers are
connected directly, relays carry no game traffic. When NAT prevents a direct path, the game traffic
is relayed by that node.

* **Built-in list** (`RelayNodes.BUILT_IN`, mirrored in `multiplayer/relay_nodes.json`): community
  nodes that were up on 2026-10-17 (uptime page: https://info.qtet.cn/uptime/easytier). The official
  `public.easytier.cn/top` nodes were **down** during research, so they are not used. The launcher
  probes the list at session start and connects to the best 6; the repo JSON lets us rotate nodes
  without an app update.
* **User nodes**: Advanced → "Custom relay nodes" accepts `tcp://`, `udp://`, `wss://` URIs; they are
  tried first and never replaced by the remote list.
* **Self-hosting (recommended for a community)**: a 1 vCPU / 1 GB VPS (Hetzner CX22 ≈ €4/month,
  Oracle Cloud free tier works) running

  ```bash
  ./easytier-core --no-tun --relay-network-whitelist "cslp-*" \
      -l tcp://0.0.0.0:11010 -l udp://0.0.0.0:11010 -l wss://0.0.0.0:11012 \
      --private-mode false --disable-p2p
  ```

  Bandwidth is the only real cost: a relayed Minecraft session is ~50–150 kB/s per player; 20 TB
  of monthly traffic on such a VPS covers hundreds of relayed sessions. Then add
  `tcp://your-vps:11010` to `multiplayer/relay_nodes.json` (first entry) and users pick it up
  automatically.

Nothing in the UI depends on a backend that does not exist: with every public node down the
feature fails fast with `NO_RELAY_REACHABLE` and a clear hint to add a relay node.

---

## 7. Test results

Legend: ✅ executed, 🟡 executed in a desktop simulation of the component, ⬜ needs real devices /
networks (procedure given so it can be run by the maintainers).

| # | Scenario | Status | Evidence / procedure |
|---|---|---|---|
| A | Same Wi-Fi, host + guest | 🟡 | Desktop harness (`tools/vlan-harness`): host + guest sessions in one JVM, real engine, real relays. Guest → `CONNECTED_DIRECT`, status ping through the tunnel returned `Raj - Survival World 1.20.1 1/8`. On phones: expected identical; verify the `[Virtual LAN]` entry appears in Multiplayer. |
| B | Host Wi-Fi, guest mobile data | ⬜ | Two phones, different networks. Expect `Connected directly` or `Connected through relay` within 60 s; join the world; check latency shown ≈ ping. |
| C | Different cities / ISPs | ⬜ | Same as B; relay path more likely. |
| D | Restrictive NAT → relay | 🟡 | Harness showed the guest first `CONNECTED_RELAY` (hostLat 1000 ms placeholder from the engine's initial route) then `CONNECTED_DIRECT` once the punch succeeded; `PATH_*` events fired. On phones behind symmetric NAT expect the relay pill to stay. |
| E | Vanilla and modded profiles | ⬜ | Version/mod mismatch is **not** hidden: the host card shows the host's version and the help text says all players need the same version & mods. Procedure: Fabric 1.20.1 host, vanilla guest → Minecraft shows "incompatible", as expected. |
| F | Guest disconnect / reconnect | ✅ | Harness: `leave` removes the member on the host within a tick; rejoin with the same code works (`PLAYER_JOINED` event again). Kick → guest `ENDED KICKED`. |
| G | Host closes the world | ✅ | Harness: `-- closing world` → both sides `world=false`, `-- reopening world` on a new port → both `world=true lan=<newport>`, ping through the tunnel succeeded on the new port without changing the guest address. |
| H | VPN permission denied | n/a | No `VpnService` is used; nothing to deny. Verified by design (no `BIND_VPN_SERVICE` in the manifest). |
| I | Launcher restart with stale state | ✅ (logic) | `TunnelManager.killStale()` tested by leaving a PID file from a dead process → removed; a live engine from a previous crashed process is killed at service start. |
| J | Low-end device | ⬜ | Measure with `adb shell top -p $(pidof libeasytier_core.so)`; expected < 30 MB RSS, idle CPU ≈ 0; UI ticks only re-render changed fields. |
| — | Orphan processes | ✅ | Every harness run ends with `remaining easytier processes: 0`. |
| — | Host end → guests | ✅ | Guest `ENDED HOST_ENDED` within one tick of the host's End Session. |
| — | Code validation | ✅ | Unit checks in `SessionCode` (dashes, forbidden symbols, prefix stripping, live formatting). |

Harness transcript (abridged, 2026-10-17):

```
[GUEST] phase=ESTABLISHING relays=6 hostVip=10.144.144.1
[guest] forwards: game 127.0.0.1:25575 -> 10.144.144.1:25566, directory 127.0.0.1:36011 -> 10.144.144.1:25560
[GUEST] phase=CONNECTED_RELAY ... world=true/Survival World/1.20.1 lan=43815
PING THROUGH TUNNEL -> Status{Raj - Survival World, 1.20.1/763, 1/8, 23ms}
[GUEST] phase=CONNECTED_DIRECT vip=10.144.144.2 hostLat=1.75
-- closing world   → guest worldOpen now false
-- reopening world → guest worldOpen now true lan=33785 ping=Status{...4ms}
-- host ending     → [GUEST] FINISHED ENDED HOST_ENDED
remaining easytier processes: 0
```

Not tested on real Android hardware in this change (no device in the build environment): the
`exec()` of the UPX-packed engine from `nativeLibraryDir` on API 21–35, the foreground-service
start on Android 14/15, and real cellular NAT behaviour. These are the first things to check on a
device; all of them have known-good precedents (`libunpack200.so` exec, `GameService` FGS,
EasyTier's own Android builds).

### 7.1 Device regression found after the first build (fixed)

**Symptom (real phones, same Wi-Fi and Wi-Fi/data):** guest reached *Establishing tunnel* and
after 45 s failed with *"The host was found but a path to it could not be established"*
(`HOST_UNREACHABLE`). The engine, peer discovery and port-forwards were all fine.

**Root cause:** the app targets SDK 36, and `res/xml/netconfig.xml` only whitelists `ely.by` for
cleartext HTTP. `SessionDirectoryClient` used `HttpURLConnection` for
`http://127.0.0.1:<forwarded-port>/v1/join`; Android applies the cleartext policy to loopback
too, so every join threw `IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted` — in
the guest's own process, before a single byte entered the tunnel. The desktop harness (plain
JVM) cannot reproduce this, which is why it passed.

**Fix (`Fix[multiplayer]`):** the directory client now speaks its tiny HTTP/1.1 subset over a
plain `java.net.Socket` (the traffic is loopback → encrypted tunnel, so there is nothing to
protect with the cleartext policy). No manifest / network-security change, no timeout change.

**Diagnostics added (no secrets):** every session now records a timestamped *stage trail* that is
shown live under the status line while connecting and included in *Copy diagnostics*:

```
+0.4s host services up: proxy 0.0.0.0:25565, directory 0.0.0.0:25560, hostname cslp-host-25565-25560
+0.5s engine started (rpc 127.0.0.1:17175)
+2.9s LAN world detected on port 37461 -> proxy retargeted
+5.3s relay network joined (5 nodes, vip 10.144.144.1)
--- guest ---
+5.2s relay network joined (5 nodes, vip 10.144.144.2, nat PortRestricted)
+7.8s host peer found: 10.144.144.1 path=relay(2) lat=501ms (proxy 25565, directory 25560)
+8.0s port-forwards added: game 127.0.0.1:25575 -> 10.144.144.1:25565, directory 127.0.0.1:41709 -> 10.144.144.1:25560
+8.6s directory join OK through tunnel (relay)
```

Failure lines are stage specific: `port-forward add failed: cli port-forward exit 1: …`,
`directory join failed via 127.0.0.1:41709 -> 10.144.144.1:25560 — ConnectException: …`,
`directory rejected join: HTTP 401 …`, `engine died (exit 137), restart 1/3`,
`heartbeat lost x3 (SocketTimeoutException …), reconnecting`. The failure card shows the
specific detail under the generic reason.

**Relay path really carries traffic:** the harness gained a `relay` mode
(`disable_p2p = true` on the guest, `Params.relayOnly`). Run 2026-10-17: guest
`CONNECTED_RELAY`, `PING THROUGH TUNNEL -> Status{Raj - Survival World, 1.20.1/763, 1/8, 356ms}`
through a public community node, world close/reopen mirrored, host end → `HOST_ENDED`. So the
relay fallback is proven to forward our private network's data, not just to list peers.

### 7.2 On-device test procedure (A / B / C)

Install the CI `app-debug` APK on two phones. On the host: launch a world → *Open to LAN* →
pause menu → *Virtual LAN* → *Create session* → share the code. On the guest: *Virtual LAN
Multiplayer* → *Join* → code → wait for *Connected directly / through relay* → *Join World*
(or Multiplayer → `[Virtual LAN]` entry).

| Test | Networks | Pass criteria |
|---|---|---|
| A | both on the same Wi-Fi | stage trail reaches `directory join OK`; status *Connected directly* (expected) or *through relay* (allowed: some routers isolate clients); world joins and chunks load |
| B | host Wi-Fi, guest mobile data | same; *through relay* is normal on CGNAT; latency shown is the real engine RTT |
| C | host mobile data, guest Wi-Fi | same; also confirms the host's LAN port is found from the game log when cellular blocks multicast |

If anything stops, press *Copy diagnostics* on **both** phones and read the last stage line; it
names the exact hop (engine / relay / host peer / port-forward / directory / heartbeat).
`adb logcat -s VirtualLAN` shows the same trail with the scrubbed engine log.

---

## 8. Known limitations

* No 32-bit x86 engine build upstream → feature disabled on such devices (clear message).
* Bedrock / Minecraft PE is out of scope (Java Edition LAN only).
* Direct P2P is not possible on every network pair; relay throughput depends on the volunteer node
  (some cap at ~500 kB/s). Self-hosting a node (§6) is the reliable answer for a community.
* The guest's `[Virtual LAN]` entry is added to the **selected profile's** `servers.dat`; switching
  profiles requires pressing Join World again.
* Mods that bind the LAN server to a fixed port or to `127.0.0.1` only (rare) are not detectable;
  the host card keeps saying "Waiting for Open to LAN".

---

## 9. Maintenance

* Bump the engine: edit `VERSION` and the three SHA-256 values in `scripts/fetch_easytier.sh`
  (`sha256sum` of each release zip). CI runs the script before Gradle.
* Rotate relay nodes: edit `multiplayer/relay_nodes.json` (and `RelayNodes.BUILT_IN` for new
  installs). Check https://info.qtet.cn/uptime/easytier for live community nodes.
* Core changes: run `tools/vlan-harness` before pushing.

EasyTier © EasyTier contributors, Apache License 2.0. The launcher ships unmodified release
binaries; see https://github.com/EasyTier/EasyTier.
