#!/usr/bin/env bash
# Downloads the pinned EasyTier release binaries used by the Virtual LAN Multiplayer feature and
# places them into the jniLibs folders as executable "lib*.so" files (the same trick the launcher
# already uses for libunpack200.so: with useLegacyPackaging the files are extracted to
# nativeLibraryDir, which is the only app-owned location Android still allows exec() from).
#
#   usage: scripts/fetch_easytier.sh [path/to/app_cs/src/main/jniLibs]
#
# EasyTier is Apache-2.0 licensed: https://github.com/EasyTier/EasyTier
set -euo pipefail

VERSION="v2.6.4"
JNI_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/app_cs/src/main/jniLibs}"
BASE_URL="https://github.com/EasyTier/EasyTier/releases/download/${VERSION}"

# release arch -> Android ABI, plus the pinned SHA-256 of each release zip.
# (No 32-bit x86 build exists upstream, so x86 devices get a clear "not supported" message.)
TARGETS=(
  "aarch64 arm64-v8a   f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f"
  "armv7   armeabi-v7a 93b1d2831e45db1fd3ca1d8d68c191b300bd69d331ca1858394a0cf884363cc3"
  "x86_64  x86_64      61b659eaedba658fa66fe47d17e1426cdd77e5d02fa15fed447bb4357c09dfd6"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

for entry in "${TARGETS[@]}"; do
  read -r arch abi expected <<<"$entry"
  zip="easytier-linux-${arch}-${VERSION}.zip"
  out_dir="${JNI_DIR}/${abi}"
  if [[ -x "${out_dir}/libeasytier_core.so" && -x "${out_dir}/libeasytier_cli.so" && -f "${out_dir}/.easytier-${VERSION}" ]]; then
    echo "[easytier] ${abi}: already present (${VERSION})"
    continue
  fi
  echo "[easytier] ${abi}: downloading ${zip}"
  curl -fsSL --retry 4 --retry-delay 3 -o "${WORK}/${zip}" "${BASE_URL}/${zip}"
  actual="$(sha256 "${WORK}/${zip}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "[easytier] ${abi}: SHA-256 mismatch! expected ${expected} got ${actual}" >&2
    exit 1
  fi
  mkdir -p "${WORK}/${arch}" "${out_dir}"
  unzip -q -o "${WORK}/${zip}" -d "${WORK}/${arch}"
  src="${WORK}/${arch}/easytier-linux-${arch}"
  install -m 0755 "${src}/easytier-core" "${out_dir}/libeasytier_core.so"
  install -m 0755 "${src}/easytier-cli"  "${out_dir}/libeasytier_cli.so"
  rm -f "${out_dir}"/.easytier-v*
  echo "${VERSION}" > "${out_dir}/.easytier-${VERSION}"
  echo "[easytier] ${abi}: installed core ($(du -h "${out_dir}/libeasytier_core.so" | cut -f1)) + cli ($(du -h "${out_dir}/libeasytier_cli.so" | cut -f1))"
done
echo "[easytier] done"
