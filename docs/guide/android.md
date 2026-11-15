# Android

Two steps. Release builds get the no-op artifacts, and a release build that
still resolves the real SDK fails
([ADR-0157](../adr/0157-the-gradle-plugin-adds-the-sdk-and-fails-a-release-that-keeps-it.md)).

## 1. Apply the plugin

In the app module:

```kotlin
// app/build.gradle.kts
plugins {
    id("io.github.christianalexandre.appmonitor") version "0.1.0"
}
```

The plugin is on Maven Central, so the project's `settings.gradle.kts` lists
it among the plugin repositories (new Android Studio projects already do):

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

In debuggable variants, the plugin adds the SDK and puts the capture into
every `OkHttpClient`, library clients included (Retrofit, Apollo, Ktor's
OkHttp engine). WebSocket frames from `OkHttpClient.newWebSocket` are
captured the same way
([ADR-0159](../adr/0159-okhttp-websockets-captured-by-the-gradle-plugin.md)).
Other variants get the no-ops.

## 2. Start it

In `Application.onCreate`:

```kotlin
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions

AppMonitor.start(this, AppMonitorOptions(appName = "My App"))
```

That's all. A working app is in [`samples/android`](../../samples/android).

## Good to know

- **Kotlin 2.2 or newer**, AGP 9's built-in Kotlin included
  ([ADR-0169](../adr/0169-release-builds-keep-their-guarantees.md)).
- **OkHttp stays yours.** The SDK never brings OkHttp to an app or changes
  its version.
- **One SDK version.** The plugin adds the SDK at its own version. A debug
  build that forces another `appmonitor-okhttp` fails, with a one-line
  message.
- **Android 17 and newer.** The SDK asks for `ACCESS_LOCAL_NETWORK` itself,
  once, on the first screen. It reaches the desktop's `ws://` whatever the
  app's network security config says
  ([ADR-0156](../adr/0156-android-sdk-asks-for-its-permission-and-ignores-the-cleartext-policy.md)).
- **Dynamic feature modules** need nothing: the app's APK carries the SDK
  for them
  ([ADR-0161](../adr/0161-release-binaries-and-dynamic-features-hold-no-sdk.md)).
- **Adding the capture by hand.** `appMonitor { injectOkHttp = false }` turns
  the plugin's rewrite off; `AppMonitorInterceptor()` and
  `AppMonitorWebSocketFactory(client)` then add the capture by hand
  ([SDD 05](../sdd/05_okhttp_capture.md)).

## Nothing in the desktop?

The SDK logs why once, in Logcat under the tag `AppMonitor`: the permission
was denied, it cannot reach a desktop, or the desktop has not approved it.
`AppMonitorOptions(host = "192.168.1.20")` skips discovery. See also
[physical devices](desktop.md#physical-devices).
