package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class AppMonitorOkHttpTest {
    private fun OkHttpClient.Builder.installed(): OkHttpClient = apply { AppMonitorOkHttp.install(this) }.build()

    private fun OkHttpClient.monitors(): Int = (interceptors + networkInterceptors).count { it is AppMonitorInterceptor }

    @Test
    fun `adds the interceptor to an empty builder`() {
        val client = OkHttpClient.Builder().installed()

        assertEquals(1, client.monitors())
        assertEquals(1, client.interceptors.size)
    }

    @Test
    fun `adds none to a client made with newBuilder`() {
        val client = OkHttpClient.Builder().installed()

        assertEquals(1, client.newBuilder().installed().monitors())
    }

    @Test
    fun `adds none beside a hand-written line`() {
        val application = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor()).installed()
        val network = OkHttpClient.Builder().addNetworkInterceptor(AppMonitorInterceptor()).installed()

        assertEquals(1, application.monitors())
        assertEquals(1, network.monitors())
        assertEquals(0, network.interceptors.size)
    }
}
