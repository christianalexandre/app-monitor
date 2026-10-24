package appmonitor.sample.kmp

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

/** Asks for the permission the SDK declares (ADR-0017 E); without it Android 17 blocks the desktop (ADR-0030). */
fun ComponentActivity.requestLocalNetwork() {
    if (Build.VERSION.SDK_INT < ANDROID_17) return
    if (checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(ACCESS_LOCAL_NETWORK)
}
