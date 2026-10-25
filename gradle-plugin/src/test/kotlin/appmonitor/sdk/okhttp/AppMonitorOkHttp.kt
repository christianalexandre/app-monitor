package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient

/** Stands in for the SDK's entry point: counts the builders it is given. */
object AppMonitorOkHttp {
    @JvmField
    var installs: Int = 0

    @JvmStatic
    fun install(builder: OkHttpClient.Builder) {
        installs++
    }
}
