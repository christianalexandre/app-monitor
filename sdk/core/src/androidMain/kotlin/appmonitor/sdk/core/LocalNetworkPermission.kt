package appmonitor.sdk.core

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

// FragmentActivity refuses request codes above 16 bits.
private const val REQUEST_CODE = 0x4D4F

private val registered = AtomicBoolean(false)

/**
 * Asks for the permission the SDK declares on the first resumed Activity, once per process (ADR-0156): a
 * dismissed dialog resumes the Activity, and asking again there would loop. Not granted: discovery falls back.
 */
internal fun requestLocalNetworkOnFirstActivity(app: Application) {
    if (Build.VERSION.SDK_INT < ANDROID_17 || app.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
    if (!registered.compareAndSet(false, true)) return
    app.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.isFinishing) return
                app.unregisterActivityLifecycleCallbacks(this)
                if (activity.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), REQUEST_CODE)
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        },
    )
}
