# Kotlin Multiplatform

## With Ktor's OkHttp and Darwin engines

Follow the [Android guide](android.md) and the [iOS guide](ios.md), nothing
more. Ktor's OkHttp engine on Android is OkHttp, so the plugin captures its
calls and WebSocket frames. Its Darwin engine on iOS runs on `URLSession`,
which the Swift SDK captures. There is nothing to install in `commonMain` and
nothing to start in `iosMain`
([ADR-0158](../adr/0158-a-kmp-app-on-ios-is-captured-by-the-swift-sdk-alone.md)).

## With another engine (CIO, Java, …)

Also apply the plugin in the module that builds the `HttpClient`. It adds
`appmonitor-ktor`, which you then install:

```kotlin
val client = HttpClient(CIO) {
    install(AppMonitorKtor)
}
```

That module needs Kotlin 2.4 or newer for its iOS targets: a Kotlin compiler
does not read klibs built by a newer one
([ADR-0218](../adr/0218-published-coordinates-consumers-and-the-ios-klib-floor.md)).

A working app is in [`samples/kmp`](../../samples/kmp).
