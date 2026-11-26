#!/usr/bin/env bash
# Usage: desktop_packages.sh build OS OUT_DIR
#        desktop_packages.sh collect OS VERSION BINARIES_DIR OUT_DIR
# Builds the desktop's packages for OS (macos, windows, linux) and copies them to OUT_DIR under
# the names the GitHub Release, the Homebrew cask and the Scoop manifest use (ADR-0227), at
# DESKTOP_VERSION_NAME without its suffix (ADR-0232).
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { echo "usage: $0 build OS OUT_DIR | collect OS VERSION BINARIES_DIR OUT_DIR" >&2; exit 2; }

one() { # glob → the single file or directory it matches
  local matches=()
  for m in $1; do [[ -e "$m" ]] && matches+=("$m"); done
  [[ ${#matches[@]} -eq 1 ]] || { echo "desktop-packages: expected one match for $1, got ${#matches[@]}" >&2; exit 1; }
  printf '%s' "${matches[0]}"
}

zip_dir() { # directory, archive: the directory itself at the archive's root
  local parent name
  parent="$(dirname "$1")"
  name="$(basename "$1")"
  rm -f "$2"
  if command -v zip >/dev/null; then
    (cd "$parent" && zip -qr "$2" "$name")
  else
    (cd "$parent" && 7z a -tzip -bd "$2" "$name" >/dev/null)
  fi
}

collect() { # os, version, binaries dir, out dir
  local os="$1" version="$2" bin="$3" out="$4" arch dmg msi app deb rpm
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"
  case "$os" in
    macos)
      arch="$(uname -m)"
      dmg="$(one "$bin/dmg/*-$version.dmg")"
      cp "$dmg" "$out/app-monitor-$version-macos-$arch.dmg"
      ;;
    windows)
      msi="$(one "$bin/msi/*-$version.msi")"
      app="$(one "$bin/app/*")"
      cp "$msi" "$out/app-monitor-$version-windows-x64.msi"
      zip_dir "$app" "$out/app-monitor-$version-windows-x64.zip"
      ;;
    linux)
      # Compose names them app-monitor_X.Y.Z_amd64.deb and app-monitor-X.Y.Z-1.x86_64.rpm.
      deb="$(one "$bin/deb/*_${version}_*.deb")"
      rpm="$(one "$bin/rpm/*-$version-*.rpm")"
      cp "$deb" "$out/app-monitor-$version-linux-amd64.deb"
      cp "$rpm" "$out/app-monitor-$version-linux-x86_64.rpm"
      ;;
    *) usage ;;
  esac
  ls -1 "$out"
}

case "${1:-}" in
  build)
    [[ $# -eq 3 ]] || usage
    version="$(sed -n 's/^DESKTOP_VERSION_NAME=//p' gradle.properties)"
    version="${version%%-*}"
    case "$2" in
      macos) tasks=(:desktop:app:packageDmg) ;;
      windows) tasks=(:desktop:app:packageMsi :desktop:app:createDistributable) ;;
      linux) tasks=(:desktop:app:packageDeb :desktop:app:packageRpm) ;;
      *) usage ;;
    esac
    ./gradlew "${tasks[@]}"
    collect "$2" "$version" desktop/app/build/compose/binaries/main "$3"
    ;;
  collect)
    [[ $# -eq 5 ]] || usage
    collect "$2" "$3" "$4" "$5"
    ;;
  *) usage ;;
esac
