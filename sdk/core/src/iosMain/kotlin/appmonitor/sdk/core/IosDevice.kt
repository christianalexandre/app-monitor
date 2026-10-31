package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.uname
import platform.posix.utsname

/** True on `iosSimulatorArm64`, known at compile time. */
internal expect val isSimulator: Boolean

/** The Swift SDK's device id file, so both SDKs in one app send one id (ADR-0064 B.1). */
internal fun deviceIdFile(): Path {
    val support = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as NSURL?
    return Path(support?.path ?: NSTemporaryDirectory(), DEVICE_ID_FILE_NAME)
}

internal const val DEVICE_ID_FILE_NAME: String = "appmonitor-device-id"

/** The model identifier, never the user-set name (ADR-0018 D): `iPhone16,2`, or `SIMULATOR_MODEL_IDENTIFIER` plus " Simulator". */
@OptIn(ExperimentalForeignApi::class)
internal fun deviceName(environment: Map<Any?, Any?> = NSProcessInfo.processInfo.environment): String {
    val model = environment["SIMULATOR_MODEL_IDENTIFIER"] as String?
    if (isSimulator && model != null) return "$model Simulator"
    return memScoped {
        val system = alloc<utsname>()
        uname(system.ptr)
        system.machine.toKString()
    }
}

/** `Hello` for this install (ADR-0018 D), as the Swift SDK builds it; reads or creates [file]; [appName] from the options. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosHello(
    file: Path = deviceIdFile(),
    appName: String? = null,
): Hello {
    val id = deviceId(file)
    NSURL.fileURLWithPath(file.toString()).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    val os =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents {
            "iOS $majorVersion.$minorVersion" + if (patchVersion != 0L) ".$patchVersion" else ""
        }
    val bundle = NSBundle.mainBundle
    return Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_IOS,
        device_id = id,
        device_name = deviceName(),
        os_version = os,
        app_id = bundle.bundleIdentifier ?: NSProcessInfo.processInfo.processName,
        app_version = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as String? ?: "",
        sdk_version = SDK_VERSION,
        channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
        sdk_name = SDK_NAME,
        app_name = appName.orEmpty(),
    )
}
