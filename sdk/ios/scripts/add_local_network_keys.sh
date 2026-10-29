#!/bin/sh
# AppMonitor Run Script (ADR-0155). A configuration whose EXCLUDED_SOURCE_FILE_NAMES
# excludes AppMonitor.o is left as is; any other links the SDK: it fails when
# optimized, else gets the local network keys. Keys the app already has are kept.
set -eu

plist="${TARGET_BUILD_DIR:?run from an Xcode Run Script build phase}/${INFOPLIST_PATH:?}"
usage="${APPMONITOR_LOCAL_NETWORK_USAGE:-AppMonitor sends the network traffic of this debug build to the AppMonitor desktop app on your local network.}"
service="_appmonitor._tcp"

set -f
for pattern in ${EXCLUDED_SOURCE_FILE_NAMES:-}; do
  case AppMonitor.o in
    $pattern)
      echo "AppMonitor: excluded from ${CONFIGURATION:-this build}, Info.plist left as is"
      exit 0
      ;;
  esac
done
set +f

if [ "${SWIFT_OPTIMIZATION_LEVEL:--Onone}" != "-Onone" ] && [ "${APPMONITOR_ALLOW_OPTIMIZED:-NO}" != YES ]; then
  echo "error: AppMonitor is linked into the optimized ${CONFIGURATION:-build}." \
    "Set EXCLUDED_SOURCE_FILE_NAMES = AppMonitor* on it, or APPMONITOR_ALLOW_OPTIMIZED = YES to keep the SDK."
  exit 1
fi

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
