# The desktop app

## Install it

From `1.0.0`, with a package manager:

```sh
# macOS (Apple silicon), Homebrew
brew install --cask christianalexandre/tap/app-monitor
```

```powershell
# Windows, Scoop
scoop bucket add christianalexandre https://github.com/christianalexandre/homebrew-tap
scoop install christianalexandre/app-monitor
```

`brew upgrade --cask app-monitor` and `scoop update app-monitor` take the next release. Both
come from [christianalexandre/homebrew-tap](https://github.com/christianalexandre/homebrew-tap),
written by the release.

Or download from the [release](https://github.com/christianalexandre/app-monitor/releases);
each one attaches the desktop's packages:

| OS | Package |
| --- | --- |
| macOS (Apple silicon) | `app-monitor-X.Y.Z-macos-arm64.dmg` |
| Windows (x64) | `app-monitor-X.Y.Z-windows-x64.msi`, or the `.zip` to run without installing |
| Linux (x64) | `app-monitor-X.Y.Z-linux-amd64.deb`, `app-monitor-X.Y.Z-linux-x86_64.rpm` |

The packages are not signed by Apple or Microsoft, so each system asks once before the first
run:

- **macOS.** Drag *App Monitor* to *Applications*, or install it with Homebrew, and open it. macOS says it cannot check
  the app; close that message. Then open *System Settings → Privacy & Security*, scroll to
  *Security*, click *Open Anyway* next to *App Monitor*, and confirm. Since macOS 15,
  Control-click → *Open* no longer skips the check
  ([Apple](https://support.apple.com/en-lb/guide/mac-help/mchleab3a043/15.0/mac/15.5)). If no
  *Open Anyway* shows, this does the same from Terminal:

  ```sh
  xattr -dr com.apple.quarantine "/Applications/App Monitor.app"
  ```

- **Windows.** SmartScreen shows *Windows protected your PC*: click *More info*, then *Run
  anyway*.
- **Linux.** `sudo apt install ./app-monitor-X.Y.Z-linux-amd64.deb` (Debian, Ubuntu) or
  `sudo dnf install ./app-monitor-X.Y.Z-linux-x86_64.rpm` (Fedora, RHEL). Nothing asks.

## Updates

At launch and once a day the desktop asks GitHub for the latest release. When a newer one is
out, the status bar says *Version X.Y.Z available*; a click opens its page. The request carries
only the desktop's version, and nothing about the machine, its devices or its traffic.
*Settings → Updates* turns it off.

## Run it from the repository

With JDK 21:

```sh
git clone https://github.com/christianalexandre/app-monitor
cd app-monitor
make desktop
```

To build a package for your OS instead, the same one a release attaches (it lands in
`desktop/app/build/compose/binaries/main/`):

```sh
./gradlew :desktop:app:packageDmg   # macOS
./gradlew :desktop:app:packageMsi   # Windows
./gradlew :desktop:app:packageDeb   # Linux, Debian and Ubuntu
./gradlew :desktop:app:packageRpm   # Linux, Fedora and RHEL (needs rpmbuild)
```

It listens for devices on port `47474` and announces itself on the local
network. The history stays across restarts, in named runs, bounded by the
retention set in *Settings*. `APPMONITOR_DATA_DIR=<dir>` moves it.

## Approving a device

The first time an app connects, the desktop asks whether to accept it. The
answer is remembered. *Settings → Devices* forgets one device or all of
them.

## Physical devices

The emulator reaches the desktop on `10.0.2.2` and the simulator on
`127.0.0.1`, with nothing to set up. A phone needs a few things:

- **The same network.** The phone and the computer on the same Wi-Fi. The
  SDK finds the desktop by mDNS (`_appmonitor._tcp`). A network that blocks
  mDNS between devices (some guest or corporate Wi-Fi), or a firewall that
  blocks port `47474` on the computer, hides it.
- **Local network permission.** Android 17 and newer ask for it once, on
  the app's first screen. iOS asks once for *Local Network*, and needs the
  Run Script's keys in `Info.plist`
  ([iOS guide](ios.md#4-paste-the-run-script)). If it was denied: *Settings
  → Privacy & Security → Local Network* on iOS, the app's permissions on
  Android.
- **Approve it** in the desktop, as above.
- **One desktop per device.** A device keeps to the first desktop that
  approved it, so a colleague's desktop on the same Wi-Fi never gets its
  traffic. To move it to another desktop, clear the app's data, reinstall
  it, or set `host`
  ([ADR-0168](../adr/0168-a-device-pins-the-first-desktop-that-approved-it.md)).
- **Skip discovery** with the computer's address:
  `AppMonitorOptions(host = "192.168.1.20")` on Android,
  `AppMonitorOptions(host: "192.168.1.20")` on iOS.

When nothing shows up, the SDK logs why once: in Logcat under the tag
`AppMonitor` on Android, and in Xcode's console (subsystem `AppMonitor`) on
iOS.
