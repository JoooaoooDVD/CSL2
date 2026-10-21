#!/usr/bin/env bash
# Creates the relay VM in Mumbai with the installer as startup script (idempotent: re-running the
# installer just updates the service). Usage: PROJECT=my-project ./gcloud-create-vm.sh
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT=<gcp project id>}"
ZONE="${ZONE:-asia-south1-b}"          # Mumbai: lowest latency for Indian players
MACHINE="${MACHINE:-e2-small}"          # 2 vCPU (shared), 2 GB - plenty for hundreds of sessions
NAME="${NAME:-cslp-relay-in}"
gcloud compute addresses create "${NAME}-ip" --project "$PROJECT" --region "${ZONE%-*}" 2>/dev/null || true
IP="$(gcloud compute addresses describe "${NAME}-ip" --project "$PROJECT" --region "${ZONE%-*}" --format='value(address)')"
gcloud compute instances create "$NAME" \
  --project "$PROJECT" --zone "$ZONE" --machine-type "$MACHINE" \
  --image-family debian-12 --image-project debian-cloud \
  --boot-disk-size 10GB --boot-disk-type pd-balanced \
  --address "$IP" --tags cslp-relay \
  --metadata startup-script='#!/bin/bash
if ! systemctl is-enabled easytier-relay >/dev/null 2>&1; then
  curl -fsSL https://raw.githubusercontent.com/JoooaoooDVD/CSL2/main/server/relay/install.sh | bash
fi'
echo "Relay node URI:  tcp://${IP}:11010   (also udp://${IP}:11010)"
echo "Add it to multiplayer/relay_nodes.json and RelayNodes.BUILT_IN (see docs/GOOGLE_CLOUD_RELAY.md)."
