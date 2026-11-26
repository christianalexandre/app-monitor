#!/usr/bin/env bash
# Tests scripts/desktop_packages.sh collect on the file names Compose writes (ADR-0227).
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/test-desktop-packages"
rm -rf "$work"
bin="$work/bin"
mkdir -p "$bin/dmg" "$bin/msi" "$bin/deb" "$bin/rpm" "$bin/app/App Monitor/app"
touch "$bin/dmg/App Monitor-1.2.3.dmg" "$bin/msi/App Monitor-1.2.3.msi" \
  "$bin/deb/app-monitor_1.2.3-1_amd64.deb" "$bin/rpm/app-monitor-1.2.3-1.x86_64.rpm" \
  "$bin/app/App Monitor/App Monitor.exe" "$bin/app/App Monitor/app/lib.jar"
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

scripts/desktop_packages.sh collect macos 1.2.3 "$bin" "$work/mac" >/dev/null
check "the dmg named with the version, OS and architecture" '[[ -f "$work/mac/app-monitor-1.2.3-macos-$(uname -m).dmg" ]]'
scripts/desktop_packages.sh collect windows 1.2.3 "$bin" "$work/win" >/dev/null
check "the msi named" '[[ -f "$work/win/app-monitor-1.2.3-windows-x64.msi" ]]'
listing="$(unzip -Z1 "$work/win/app-monitor-1.2.3-windows-x64.zip")"
check "the zip holds the app directory at its root" 'grep -qx "App Monitor/App Monitor.exe" <<<"$listing" && grep -qx "App Monitor/app/lib.jar" <<<"$listing"'
scripts/desktop_packages.sh collect linux 1.2.3 "$bin" "$work/linux" >/dev/null
check "the deb and rpm named" '[[ -f "$work/linux/app-monitor-1.2.3-linux-amd64.deb" && -f "$work/linux/app-monitor-1.2.3-linux-x86_64.rpm" ]]'
code=0
scripts/desktop_packages.sh collect macos 9.9.9 "$bin" "$work/none" >/dev/null 2>&1 || code=$?
check "another version's package refused" '[[ $code -eq 1 ]]'
touch "$bin/deb/app-monitor_1.2.3-2_amd64.deb"
code=0
scripts/desktop_packages.sh collect linux 1.2.3 "$bin" "$work/two" >/dev/null 2>&1 || code=$?
check "two matching packages refused" '[[ $code -eq 1 ]]'
code=0
scripts/desktop_packages.sh collect beos 1.2.3 "$bin" "$work/x" >/dev/null 2>&1 || code=$?
check "an unknown OS refused" '[[ $code -eq 2 ]]'
rm -rf "$work"
[[ $failures -eq 0 ]] || exit 1
