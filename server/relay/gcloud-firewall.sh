#!/usr/bin/env bash
# One-time Google Cloud firewall rules for the relay VM (run in Cloud Shell or with gcloud installed).
# Usage: PROJECT=my-project ./gcloud-firewall.sh
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT=<gcp project id>}"
gcloud compute firewall-rules create cslp-relay-easytier \
  --project "$PROJECT" --network default --direction INGRESS --action ALLOW \
  --rules tcp:11010,udp:11010,tcp:11011,tcp:11012 \
  --source-ranges 0.0.0.0/0 --target-tags cslp-relay \
  --description "CS Launcher Plus Virtual LAN relay (EasyTier)"
echo "Attach the network tag 'cslp-relay' to the VM (done automatically by gcloud-create-vm.sh)."
