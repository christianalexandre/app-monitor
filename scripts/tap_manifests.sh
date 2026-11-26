#!/usr/bin/env bash
# Usage: tap_manifests.sh VERSION PACKAGES_DIR OUT_DIR
# Writes OUT_DIR/Casks/app-monitor.rb (Homebrew), OUT_DIR/bucket/app-monitor.json (Scoop) and the README for the
# release VERSION, from the macOS .dmg and the Windows .zip in PACKAGES_DIR (ADR-0229).
# RELEASES_URL moves the download URLs, for a test.
set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 VERSION PACKAGES_DIR OUT_DIR" >&2; exit 2; }
version="$1" packages="$2" out="$3"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tap-manifests: $version is not X.Y.Z" >&2; exit 2; }
releases="${RELEASES_URL:-https://github.com/christianalexandre/app-monitor/releases/download}"
dmg="app-monitor-$version-macos-arm64.dmg"
zip="app-monitor-$version-windows-x64.zip"

sha256() {
  [[ -f "$packages/$1" ]] || { echo "tap-manifests: no $1 in $packages" >&2; exit 1; }
  if command -v sha256sum >/dev/null; then sha256sum "$packages/$1"; else shasum -a 256 "$packages/$1"; fi | cut -d' ' -f1
}
dmg_sha="$(sha256 "$dmg")"
zip_sha="$(sha256 "$zip")"

mkdir -p "$out/Casks" "$out/bucket"
cat >"$out/Casks/app-monitor.rb" <<RUBY
cask "app-monitor" do
  version "$version"
  sha256 "$dmg_sha"

  url "$releases/#{version}/app-monitor-#{version}-macos-arm64.dmg",
      verified: "github.com/christianalexandre/app-monitor/"
  name "App Monitor"
  desc "Network monitor and debug toolkit for mobile apps"
  homepage "https://github.com/christianalexandre/app-monitor"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on arch: :arm64
  depends_on :macos

  app "App Monitor.app"

  zap trash: "~/Library/Application Support/io.github.christianalexandre.appmonitor.desktop"

  caveats <<~EOS
    App Monitor is not notarized by Apple. On its first launch, open
    System Settings > Privacy & Security and click "Open Anyway".
  EOS
end
RUBY

cat >"$out/bucket/app-monitor.json" <<JSON
{
    "version": "$version",
    "description": "Network monitor and debug toolkit for mobile apps",
    "homepage": "https://github.com/christianalexandre/app-monitor",
    "license": "MIT",
    "architecture": {
        "64bit": {
            "url": "$releases/$version/$zip",
            "hash": "$zip_sha"
        }
    },
    "extract_dir": "App Monitor",
    "shortcuts": [
        [
            "App Monitor.exe",
            "App Monitor"
        ]
    ],
    "checkver": "github",
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "$releases/\$version/app-monitor-\$version-windows-x64.zip"
            }
        }
    }
}
JSON
cat >"$out/README.md" <<MD
# App Monitor's Homebrew tap and Scoop bucket

Written by [App Monitor](https://github.com/christianalexandre/app-monitor)'s publish workflow on each
release; edits here are overwritten. Current version: \`$version\`.

macOS (Apple silicon):

\`\`\`sh
brew install --cask christianalexandre/tap/app-monitor
\`\`\`

Windows:

\`\`\`powershell
scoop bucket add christianalexandre https://github.com/christianalexandre/homebrew-tap
scoop install christianalexandre/app-monitor
\`\`\`

The app is not signed by Apple or Microsoft; the first launch asks once
([desktop guide](https://github.com/christianalexandre/app-monitor/blob/main/docs/guide/desktop.md)).
MD
echo "tap-manifests: Casks/app-monitor.rb, bucket/app-monitor.json and README.md for $version"
