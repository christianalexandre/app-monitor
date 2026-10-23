#!/bin/sh
# Xcode Run Script phase (ADR-0036 E, ADR-0049): adds the local network keys to
# the built Info.plist only when the configuration name contains "Debug", as the
# SDK compiles (ADR-0048). Keys the app already has are kept.
set -eu

: "${CONFIGURATION:?run from an Xcode Run Script build phase}"
plist="${TARGET_BUILD_DIR:?}/${INFOPLIST_PATH:?}"
service="_appmonitor._tcp"
default_usage="AppMonitor sends the network traffic of this debug build to the AppMonitor desktop app on your local network."
usage="${APPMONITOR_LOCAL_NETWORK_USAGE:-$default_usage}"

case "$CONFIGURATION" in
  *Debug*) ;;
  *)
    echo "AppMonitor: $CONFIGURATION is not a Debug configuration, Info.plist left as is"
    exit 0
    ;;
esac

if ! plutil -extract NSLocalNetworkUsageDescription raw -o /dev/null "$plist" 2>/dev/null; then
  plutil -insert NSLocalNetworkUsageDescription -string "$usage" "$plist"
fi
if ! plutil -extract NSBonjourServices raw -o /dev/null "$plist" 2>/dev/null; then
  plutil -insert NSBonjourServices -array "$plist"
fi
if ! plutil -extract NSBonjourServices json -o - "$plist" | grep -q "\"$service\""; then
  plutil -insert NSBonjourServices -string "$service" -append "$plist"
fi
echo "AppMonitor: local network keys in $plist"
