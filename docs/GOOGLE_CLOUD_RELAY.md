# Google Cloud relay node for Virtual LAN Multiplayer

Technical guidance for running our own relay node on Google Cloud (Free Trial $300). Written
after inspecting the shipped implementation (`app_cs/src/main/java/com/craftstudio/cslauncher/multiplayer/**`,
`scripts/fetch_easytier.sh`, `multiplayer/relay_nodes.json`, `docs/VIRTUAL_LAN_MULTIPLAYER.md`).

---

## 0. Short answer to the key question

**Yes — the current relay can be deployed to Google Cloud directly. No separate server-side code
is needed.**

Our Virtual LAN does not have a relay server written by us. The relay is the open-source
**EasyTier** core (`easytier-core`, Apache-2.0, pinned **v2.6.4** in `scripts/fetch_easytier.sh`),
the *same binary the launcher ships inside the APK*. On a VPS it runs as a "shared public node":
no network name, no secret, no code of ours. Everything that is ours (session codes, the session
directory, the LAN port proxy, the TUN bridge) runs on the players' phones and only needs the node
for three things: **peer discovery, NAT hole punching, and relaying when a direct path fails**.

Player traffic is encrypted end-to-end between the phones (per-session `network_secret` derived
from the session code); the node cannot read it. So the only server-side artefacts are a systemd
unit and the firewall — both are in `server/relay/`.

---

## 1. Current relay architecture (as shipped)

| Piece | Where | What it does |
|---|---|---|
| EasyTier core v2.6.4 | phone (`jniLibs/<abi>/libeasytier_core.so`) | the mesh engine: `--no-tun`, smoltcp, KCP proxy, zstd compression, latency-first, **private_mode = true**, DHCP virtual IPs (host fixed `10.144.144.1`) |
| Relay / bootstrap nodes | `core/RelayNodes.java` (built-in) + `multiplayer/relay_nodes.json` (remote list, refreshed ≤ every 6 h, cached) | public EasyTier nodes we connect to as `[[peer]]`; probed in parallel, best `MAX_NODES = 6` used; hostnames are resolved in Java (static musl binary can't use Android DNS) |
| Session directory | `core/SessionDirectoryServer/Client.java` | tiny HTTP service the **host phone** serves over the tunnel (port whitelisted in EasyTier) — replaces LAN broadcast |
| LAN port proxy | `core/LanPortProxy.java` | host side: forwards the tunnel port to Minecraft's "Open to LAN" port |
| TUN bridge | `VirtualLanVpnService` + hev-socks5-tunnel → EasyTier SOCKS5 | guest side: Minecraft connects to `10.144.144.1:port` |
| STUN | `RelayNodes.STUN_HOSTS` | NAT-type detection (Google/Cloudflare STUN — nothing to host) |

**Protocols / ports used towards a node:** EasyTier tunnel over **TCP 11010** (primary, probed),
**UDP 11010** (hole punching + UDP tunnels), optional **WS 11011 / WSS 11012** fallbacks for
restrictive Wi-Fi. The node needs **no inbound ports of ours** — nothing else runs on it.

Current built-in nodes are all community-run (CN/US/JP). That is exactly why a node we control
matters: latency for India (Mumbai region ≈ 10–40 ms vs 100–300 ms to China) and reliability.

---

## 2. Google Cloud requirements

| Item | Recommendation | Why |
|---|---|---|
| Machine | **e2-small** (2 shared vCPU, 2 GB RAM) | relaying is bandwidth-bound; one e2-small handles hundreds of concurrent sessions. `e2-micro` (1 GB) also works for a start but is in the free-tier only in US regions. |
| Region / zone | **asia-south1 (Mumbai)**, zone `asia-south1-b` | closest to the player base. Add `asia-southeast1` or `us-central1` later for other regions — the launcher probes RTT and picks the best. |
| OS | Debian 12 (bookworm) image | small, systemd, what the installer targets (Ubuntu 22.04+ also fine) |
| Disk | 10 GB pd-balanced | the binary is ~20 MB; logs go to journald |
| Network | Standard public IPv4, **static** external address (reserved) | the IP goes into the node list; it must not change on reboot |
| Bandwidth | nothing to configure; GCP VMs do multi-Gbit. Budget egress (see §7) | |
| IPv6 | optional; our client uses IPv4 nodes | |

Services to enable (one-time): **Compute Engine API** only. No load balancer, no Cloud NAT, no
managed DB — the node is a single VM.

---

## 3. Manual steps in Google Cloud Console (owner does these)

1. Start the Free Trial, create a project (e.g. `cs-launcher-relay`), note the **Project ID**.
2. Enable **Compute Engine API** (Console → APIs & Services → Enable).
3. Open **Cloud Shell** (top-right terminal icon) and run the two scripts from the repo:
   ```bash
   git clone --depth 1 https://github.com/JoooaoooDVD/CSL2.git && cd CSL2/server/relay
   PROJECT=<your-project-id> ./gcloud-firewall.sh      # firewall rule, tag cslp-relay
   PROJECT=<your-project-id> ./gcloud-create-vm.sh     # reserved static IP + VM + startup installer
   ```
   The last line prints `Relay node URI: tcp://<IP>:11010`.
   (Equivalent Console clicks: VPC network → Firewall → Create rule *cslp-relay-easytier*, ingress,
   targets tag `cslp-relay`, source `0.0.0.0/0`, ports `tcp:11010,11011,11012` + `udp:11010`;
   VPC → IP addresses → Reserve static; Compute Engine → Create instance e2-small, Debian 12,
   asia-south1-b, network tag `cslp-relay`, that static IP.)
4. Wait ~1 minute, then verify from Cloud Shell:
   ```bash
   gcloud compute ssh cslp-relay-in --zone asia-south1-b --command \
     'sudo systemctl is-active easytier-relay && sudo /opt/easytier/easytier-cli -p 127.0.0.1:15888 node'
   ```
   Expected: `active` and a table listing `Listener` rows for tcp/udp 11010, ws 11011, wss 11012.
5. Send the IP to the developer (me) → client list update (§5).
6. Billing → **Budgets & alerts**: budget $300, alerts at 50/75/90 %. Trial credit expires after
   **90 days** regardless of usage; before that, upgrade the billing account to keep the VM alive
   (no charge until credit is exhausted).

---

## 4. Deployment (what the scripts do, for manual installs)

`server/relay/install.sh` (runs as the VM startup script, re-runnable):

```bash
# as root on Debian/Ubuntu
curl -fsSL https://raw.githubusercontent.com/JoooaoooDVD/CSL2/main/server/relay/install.sh | sudo bash
```

It downloads EasyTier **v2.6.4** for the VM's arch, installs to `/opt/easytier`, creates the
unprivileged `easytier` user, writes `/etc/default/easytier-relay` and the systemd unit, and starts it.

Effective command line (verified to start cleanly, 2026-10-21):

```
/opt/easytier/easytier-core --hostname cslp-relay-<host> \
  -l tcp://0.0.0.0:11010 -l udp://0.0.0.0:11010 -l ws://0.0.0.0:11011 -l wss://0.0.0.0:11012 \
  -r 127.0.0.1:15888 --relay-all-peer-rpc --multi-thread --no-tun --latency-first
```

Why these flags: no `--network-name/--network-secret` → shared public node (serves every private
network that connects); `--no-tun` → no root / no TUN needed; `--relay-all-peer-rpc` → lets peers of
*different* networks meet through the node (needed for hole punching between host and guest);
RPC bound to localhost only.

Useful operations:

```bash
sudo systemctl status easytier-relay            # health
sudo journalctl -u easytier-relay -f            # live log
sudo /opt/easytier/easytier-cli -p 127.0.0.1:15888 peer      # who is connected right now
sudo /opt/easytier/easytier-cli -p 127.0.0.1:15888 stats     # traffic counters
sudo nano /etc/default/easytier-relay && sudo systemctl restart easytier-relay
# upgrade (only together with a launcher update that bumps scripts/fetch_easytier.sh!):
sudo EASYTIER_VERSION=v2.6.4 bash install.sh
```

Keep the node on the **same EasyTier version the launcher ships** (protocol compatibility is good
across 2.x, but pinning both to one version removes a variable when debugging).

---

## 5. 24/7 operation

* `Restart=always`, `RestartSec=3` → the process is restarted within 3 s of any crash.
* `systemctl enable` → starts at boot; the VM itself auto-restarts on host maintenance
  (default `automaticRestart = true`, `onHostMaintenance = MIGRATE`).
* The VM runs independently of any phone/browser — Cloud Shell/SSH can be closed any time.
* Hardened unit: dedicated user, `NoNewPrivileges`, `ProtectSystem=strict`, 65k file descriptors.
* Optional: Compute Engine → VM → *Observability* gives CPU/network graphs; add an
  **uptime check** (Monitoring → Uptime checks, TCP, port 11010) with an email alert — free tier.
* Unattended security updates: `sudo apt install unattended-upgrades` (Debian images have it on).

---

## 6. Client integration (developer tasks — done in the project)

Already in place, no new mechanism needed:

1. **Remote node list** — `multiplayer/relay_nodes.json` is fetched by every launcher at most every
   6 h. Adding the node there reaches existing installs **without an app update**. Entry to add,
   first in the list:
   ```json
   { "uri": "tcp://<IP>:11010", "name": "Craft Studio (Mumbai)", "region": "IN", "relay": true },
   { "uri": "udp://<IP>:11010", "name": "Craft Studio (Mumbai, UDP)", "region": "IN", "relay": true }
   ```
2. **Built-in list** — same two entries at the top of `RelayNodes.BUILT_IN` (offline fallback and
   first launch before the remote list is cached). Ships with the next APK.
3. Nothing else changes: probing already orders nodes by RTT, so an Indian player gets the Mumbai
   node first; host and guest both keep up to 6 nodes so they always share one. Users can already
   add a node manually in the Virtual LAN panel ("Custom node") — handy for testing before the list
   is updated: paste `tcp://<IP>:11010`.
4. Optional later: pin the node as *preferred* (always included even if RTT ranking would drop it)
   — small change in `RelayNodes.probe()`; not needed for launch.

Both list edits are prepared as a one-line follow-up once the IP exists (`<IP>` is unknown until
the VM is created).

---

## 7. Cost estimate (asia-south1, on-demand, Oct 2026 list prices — verify in the Pricing Calculator)

| Item | Monthly |
|---|---|
| e2-small, 730 h | ≈ $15–17 |
| 10 GB pd-balanced | ≈ $1.2 |
| Static external IPv4 in use | ≈ $3.7 ($0.005/h) |
| Egress to internet (Asia) | ≈ $0.12/GB; first 200 GB/mo ≈ $24, realistic early usage 20–80 GB → $2–10 |
| **Total** | **≈ $22–35 / month** |

Traffic model: a relayed Minecraft session moves roughly 30–100 KB/s per guest (more with many
entities/mods) → ~0.1–0.35 GB per guest-hour **only when relaying**. Hole punching succeeds for a
large share of sessions (then the node only carries keep-alives). The launcher's zstd compression
already reduces this.

Maximising the $300 trial:
* The credit expires after **90 days** — spend ≈ $100/month max, so one e2-small in Mumbai
  (≈ $30) fits comfortably; a second node (e.g. `us-central1` e2-micro ≈ $8 + IP) is still inside
  the budget if players outside India appear.
* Do **not** use the free-tier e2-micro only (US regions, 1 GB egress/month free, then paid) for
  Indian players — latency defeats the purpose.
* Use **Standard** network tier? No: Standard tier is cheaper but not available for all features
  and adds latency; Premium is the default and fine at this volume.
* After the trial: either keep paying ≈ $30/month, or move to a cheaper provider — the installer
  is provider-agnostic (any Debian VPS: Oracle Cloud Always-Free ARM, Hetzner, DigitalOcean).
  Only the IP in the node list changes.

---

## 8. Testing plan

1. **Node alive**: from any PC `nc -vz <IP> 11010` → open. From a phone with the launcher: Virtual
   LAN → Custom node → `tcp://<IP>:11010` → Create session → diagnostics card shows the node under
   *relay nodes connected* with its latency.
2. **Mobile data ↔ mobile data (both CGNAT, typical Jio/Airtel)**: host on phone A (4G/5G),
   guest on phone B (different operator if possible). Expected: `CONNECTED_RELAY` first, often
   upgrading to `CONNECTED_DIRECT` after 10–30 s when UDP punching succeeds; latency in the
   diagnostics ≈ node RTT × 2 when relayed.
3. **Restrictive Wi-Fi** (college/office: UDP blocked, only TCP 80/443): join should still work via
   TCP 11010 → if blocked too, the WSS 11012 listener. (If a network blocks everything except 443,
   add `-l wss://0.0.0.0:443` to `LISTENERS` and `tcp:443` to the firewall rule — the node needs no
   web server, so 443 is free.)
4. **Direct-path forcing**: Virtual LAN → advanced → *relay only* (`disable_p2p`) to measure the
   pure relay path; compare ping in-game (F3) with/without.
5. **Crash/boot test**: `sudo kill -9 $(pidof easytier-core)` → service back in ≤ 3 s;
   `sudo reboot` → back within a minute; players reconnect automatically (the client retries nodes).
6. **Load sanity**: `easytier-cli peer` during a 4–6 player session; CPU on e2-small should stay
   < 10 %.
7. Keep the community nodes in the list as fallbacks; the node list order is RTT-based so they
   are only used when ours is unreachable.

---

## 9. Division of work

| Owner (Console / money) | Developer (repo) |
|---|---|
| Start trial, create project, enable Compute Engine API | `server/relay/install.sh`, systemd unit, firewall + VM scripts (done) |
| Run the two gcloud scripts (or click through §3) | Node list: `relay_nodes.json` + `RelayNodes.BUILT_IN` once IP is known |
| Budget alerts; upgrade billing before day 90 | This document; diagnostics already show which node is used |
| Send the public IP | Optional: "preferred node" pinning, uptime badge in the Virtual LAN panel |
