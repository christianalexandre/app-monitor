package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient

/** The entry point the Gradle plugin calls at the start of `OkHttpClient.Builder.build()` in debug builds (ADR-0112). */
public object AppMonitorOkHttp {
    /** Adds an [AppMonitorInterceptor] to [builder] unless it already has one, as an application or network interceptor. */
    @JvmStatic
    public fun install(builder: OkHttpClient.Builder) {
        val present = (builder.interceptors() + builder.networkInterceptors()).any { it is AppMonitorInterceptor }
        if (!present) builder.addInterceptor(AppMonitorInterceptor())
    }
}
