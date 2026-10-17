#!/usr/bin/env bash
# Builds hev-socks5-tunnel (MIT) - the TUN <-> SOCKS5 bridge of the Virtual LAN VpnService -
# for every shipped ABI into app_cs/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so.
# Pinned to a release tag; sources are cloned with submodules (lwip, yaml, hev-task-system).
set -euo pipefail

HEV_TAG="${HEV_TAG:-2.19.0}"
HEV_REPO="https://github.com/heiher/hev-socks5-tunnel"
ABIS="arm64-v8a armeabi-v7a x86_64"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app_cs/src/main/jniLibs}"
WORK="${HEV_WORK:-/tmp/hev-socks5-tunnel}"

# Locate an NDK: explicit env, GitHub runner's latest, or the newest under the SDK.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -x "$NDK/ndk-build" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$NDK" ] || [ ! -x "$NDK/ndk-build" ]; then
  echo "build_hev_tunnel: no Android NDK found (set ANDROID_NDK_HOME)" >&2
  exit 1
fi
echo "build_hev_tunnel: NDK $NDK, tag $HEV_TAG"

stamp_ok=1
for abi in $ABIS; do
  [ -f "$OUT/$abi/libhev-socks5-tunnel.so" ] && [ "$(cat "$OUT/$abi/.hev-tag" 2>/dev/null)" = "$HEV_TAG" ] || stamp_ok=0
done
if [ "$stamp_ok" = 1 ]; then
  echo "build_hev_tunnel: up to date"
  exit 0
fi

rm -rf "$WORK"
git clone --quiet --depth 1 --recurse-submodules --shallow-submodules --branch "$HEV_TAG" "$HEV_REPO" "$WORK"
cd "$WORK"
"$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
  APP_ABI="$ABIS" APP_PLATFORM=android-21 APP_OPTIM=release NDK_LIBS_OUT="$WORK/libs" NDK_OUT="$WORK/obj" \
  -j"$(nproc 2>/dev/null || echo 4)" >/dev/null

for abi in $ABIS; do
  mkdir -p "$OUT/$abi"
  cp "$WORK/libs/$abi/libhev-socks5-tunnel.so" "$OUT/$abi/"
  echo "$HEV_TAG" > "$OUT/$abi/.hev-tag"
  echo "  $abi: $(stat -c %s "$OUT/$abi/libhev-socks5-tunnel.so") bytes"
done
echo "build_hev_tunnel: done"
