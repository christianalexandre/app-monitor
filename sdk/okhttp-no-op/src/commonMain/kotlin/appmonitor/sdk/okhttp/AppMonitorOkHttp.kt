package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient

/** The release-build entry point (ADR-0112): adds nothing. */
public object AppMonitorOkHttp {
    /** Does nothing. */
    @JvmStatic
    public fun install(builder: OkHttpClient.Builder) {
    }
}
