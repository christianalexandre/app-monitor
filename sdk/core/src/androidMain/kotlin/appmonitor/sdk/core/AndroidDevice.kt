package appmonitor.sdk.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.os.Build
import appmonitor.protocol.Protocol
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import java.io.File

private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

/** `Build.HARDWARE` of the Android emulator: `ranchu`, or `goldfish` on older images (ADR-0024). */
internal fun isEmulator(): Boolean = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

/** False on Android 17+ without `ACCESS_LOCAL_NETWORK`, whatever the app targets (ADR-0017 E, ADR-0030). */
internal fun localNetworkGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < ANDROID_17 ||
        context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/** The mDNS source of SDD 04: skipped, with one log line per client, without the local network permission. */
internal fun nsdBrowser(context: Context): () -> MdnsBrowser? {
    var logged = false
    return {
        if (localNetworkGranted(context)) {
            NsdBrowser(context.getSystemService(NsdManager::class.java))
        } else {
            if (!logged) platformLog("mDNS skipped: $ACCESS_LOCAL_NETWORK is not granted; request it, or set AppMonitorOptions.host")
            logged = true
            null
        }
    }
}

/** `Hello` for this install (ADR-0018 D): a random id in no-backup storage, the model, never the user-set name. */
internal fun androidHello(context: Context): Hello {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_ANDROID,
        device_id = deviceId(File(context.noBackupFilesDir, "appmonitor-device-id")),
        device_name = "${Build.MANUFACTURER} ${Build.MODEL}",
        os_version = "Android ${Build.VERSION.RELEASE}",
        app_id = context.packageName,
        app_version = packageInfo.versionName.orEmpty(),
        sdk_version = SDK_VERSION,
        channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
    )
}
