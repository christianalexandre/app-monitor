package appmonitor.sdk.core

/** Whether the app's network security policy lets the SDK open `ws://` to [host]; CIO's sockets ignore it (ADR-0023). */
internal expect fun isCleartextPermitted(host: String): Boolean

internal expect fun platformLog(message: String)

/** `Hello.sdk_version` of this build. */
internal const val SDK_VERSION: String = "0.1.0-dev"
