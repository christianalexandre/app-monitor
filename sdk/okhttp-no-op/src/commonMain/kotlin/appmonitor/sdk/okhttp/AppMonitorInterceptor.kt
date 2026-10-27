package appmonitor.sdk.okhttp

import okhttp3.Interceptor
import okhttp3.Response

/** The release-build interceptor (ADR-0017): proceeds with the call untouched. */
public class AppMonitorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
