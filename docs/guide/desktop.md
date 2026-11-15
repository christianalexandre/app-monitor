# The desktop app

## Run it

Packages are not published yet. Until they are, run the desktop from the
repository, with JDK 21:

```sh
git clone https://github.com/christianalexandre/app-monitor
cd app-monitor
make desktop
```

To install it instead, build an unsigned package for your OS. The package
lands in `desktop/app/build/compose/binaries/main/`:

```sh
./gradlew :desktop:app:packageDmg   # macOS
./gradlew :desktop:app:packageDeb   # Linux
./gradlew :desktop:app:packageMsi   # Windows
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
