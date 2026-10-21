#!/usr/bin/env bash
# CS Launcher Plus - Virtual LAN relay node installer (Debian 12 / Ubuntu 22.04+, x86_64 or arm64)
#
# Installs the SAME EasyTier core the launcher ships (pinned v2.6.4, Apache-2.0) as a shared
# public node + systemd service. The node needs no code of ours: the launcher's engine simply
# uses it for discovery, NAT hole punching and relaying. Run as root:
#     curl -fsSL https://raw.githubusercontent.com/JoooaoooDVD/CSL2/main/server/relay/install.sh | sudo bash
set -euo pipefail

VERSION="${EASYTIER_VERSION:-v2.6.4}"
INSTALL_DIR="/opt/easytier"
NODE_NAME="${NODE_NAME:-cslp-relay-$(hostname -s)}"

arch="$(uname -m)"
case "$arch" in
  x86_64)  pkg="easytier-linux-x86_64-${VERSION}.zip";  dir="easytier-linux-x86_64" ;;
  aarch64) pkg="easytier-linux-aarch64-${VERSION}.zip"; dir="easytier-linux-aarch64" ;;
  *) echo "unsupported arch: $arch"; exit 1 ;;
esac

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl unzip ca-certificates >/dev/null

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
echo "[relay] downloading EasyTier ${VERSION} (${arch})"
curl -fsSL -o "$tmp/et.zip" "https://github.com/EasyTier/EasyTier/releases/download/${VERSION}/${pkg}"
unzip -q "$tmp/et.zip" -d "$tmp"
mkdir -p "$INSTALL_DIR"
install -m 0755 "$tmp/$dir/easytier-core" "$INSTALL_DIR/easytier-core"
install -m 0755 "$tmp/$dir/easytier-cli"  "$INSTALL_DIR/easytier-cli"

# Unprivileged service user; the node never needs a TUN device or root
id -u easytier >/dev/null 2>&1 || useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin easytier
chown -R easytier:easytier "$INSTALL_DIR"

cat > /etc/default/easytier-relay <<CFG
# Edit, then: systemctl restart easytier-relay
NODE_NAME=${NODE_NAME}
# Listeners the launcher can reach: tcp+udp 11010 (primary), ws 11011, wss 11012 (fallback on strict Wi-Fi)
LISTENERS=-l tcp://0.0.0.0:11010 -l udp://0.0.0.0:11010 -l ws://0.0.0.0:11011 -l wss://0.0.0.0:11012
# Local RPC for 'easytier-cli' diagnostics only (never exposed)
RPC=-r 127.0.0.1:15888
CFG

cat > /etc/systemd/system/easytier-relay.service <<'UNIT'
[Unit]
Description=CS Launcher Plus Virtual LAN relay node (EasyTier shared public node)
Documentation=https://github.com/JoooaoooDVD/CSL2/blob/main/docs/GOOGLE_CLOUD_RELAY.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=easytier
Group=easytier
EnvironmentFile=/etc/default/easytier-relay
WorkingDirectory=/opt/easytier
# No network name/secret on purpose: a shared public node relays any private network that
# connects to it, it cannot read the traffic (end-to-end encrypted between the players).
ExecStart=/opt/easytier/easytier-core --hostname ${NODE_NAME} $LISTENERS $RPC --relay-all-peer-rpc --multi-thread --no-tun --latency-first
Restart=always
RestartSec=3
LimitNOFILE=65536
# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/opt/easytier

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now easytier-relay
sleep 2
systemctl --no-pager --lines=5 status easytier-relay || true
echo
echo "[relay] done. Node URI for the launcher:  tcp://$(curl -fsSL -4 ifconfig.me 2>/dev/null || echo <PUBLIC_IP>):11010"
echo "[relay] check:  sudo /opt/easytier/easytier-cli -p 127.0.0.1:15888 node"
