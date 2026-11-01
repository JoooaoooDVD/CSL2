#!/usr/bin/env bash
# Downloads the Horizon engine artefacts built by craftstudioteam/horizon-android (branch cs-host,
# workflow "CS engine") and places them where the CS Launcher Plus build expects them:
#   app_cs/src/main/jniLibs/<abi>/libhorizon_android.so
#   app_cs/src/main/assets/horizon/{engine-data.zip,LICENSE.txt,THIRD_PARTY_NOTICES.md,BUILD.json}
#
# Horizon is MIT licensed (Ludovic Marie and the Horizon contributors; Android port by Vasysik).
# Minecraft resources are never bundled: the engine downloads them on the device from Mojang.
#
#   usage: scripts/fetch_horizon_engine.sh [release-tag]     (default: cs-engine-latest)
# The build keeps working without the engine (the Horizon card is hidden) if the download fails
# and HORIZON_REQUIRED is not set to 1.
set -uo pipefail

TAG="${1:-cs-engine-latest}"
REPO="craftstudioteam/horizon-android"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/app_cs/src/main/jniLibs"
ASSET_DIR="$ROOT/app_cs/src/main/assets/horizon"
BASE="https://github.com/${REPO}/releases/download/${TAG}"
ABIS=("arm64-v8a" "x86_64")

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "[horizon] $1"
  if [[ "${HORIZON_REQUIRED:-0}" == "1" ]]; then exit 1; fi
  echo "[horizon] continuing without the Horizon engine"
  exit 0
}

echo "[horizon] fetching ${REPO} ${TAG}"
curl -fsSL -o "$WORK/SHA256SUMS.txt" "$BASE/SHA256SUMS.txt" || fail "no SHA256SUMS.txt at $BASE (engine release not published yet?)"
for f in BUILD.json engine-data.zip LICENSE.txt THIRD_PARTY_NOTICES.md; do
  curl -fsSL -o "$WORK/$f" "$BASE/$f" || fail "download failed: $f"
done
got_any=0
for abi in "${ABIS[@]}"; do
  if curl -fsSL -o "$WORK/libhorizon_android-$abi.so" "$BASE/libhorizon_android-$abi.so"; then
    got_any=1
  else
    rm -f "$WORK/libhorizon_android-$abi.so"
    echo "[horizon] no library for $abi in this release"
  fi
done
[[ "$got_any" == "1" ]] || fail "no engine library found in release $TAG"

( cd "$WORK" && grep -E "engine-data.zip|LICENSE.txt|THIRD_PARTY_NOTICES.md|libhorizon_android-" SHA256SUMS.txt | while read -r sum name; do
    [[ -f "$name" ]] || continue
    echo "$sum  $name" | sha256sum -c - >/dev/null || { echo "[horizon] checksum mismatch: $name"; exit 1; }
  done ) || fail "checksum verification failed"

mkdir -p "$ASSET_DIR"
cp "$WORK/engine-data.zip" "$WORK/LICENSE.txt" "$WORK/THIRD_PARTY_NOTICES.md" "$WORK/BUILD.json" "$ASSET_DIR/"
for abi in "${ABIS[@]}"; do
  if [[ -f "$WORK/libhorizon_android-$abi.so" ]]; then
    mkdir -p "$JNI_DIR/$abi"
    cp "$WORK/libhorizon_android-$abi.so" "$JNI_DIR/$abi/libhorizon_android.so"
    echo "[horizon] $abi: $(stat -c %s "$JNI_DIR/$abi/libhorizon_android.so") bytes"
  fi
done
echo "[horizon] engine: $(python3 -c "import json;d=json.load(open('$ASSET_DIR/BUILD.json'));print(d.get('version'),d.get('commit'))" 2>/dev/null)"
