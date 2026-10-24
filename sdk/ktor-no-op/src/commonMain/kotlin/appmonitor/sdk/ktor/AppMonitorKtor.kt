package appmonitor.sdk.ktor

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/** The release-build plugin (ADR-0062 A.1): `install(AppMonitorKtor)` adds no hook, every call goes through untouched. */
public val AppMonitorKtor: ClientPlugin<Unit> = createClientPlugin("AppMonitorKtor") {}
