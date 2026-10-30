package appmonitor.sdk.core

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

// FragmentActivity refuses request codes above 16 bits.
private const val REQUEST_CODE = 0x4D4F

private val registered = AtomicBoolean(false)

// A splash or trampoline Activity that hands over sooner never gets the dialog.
private const val SETTLED_MS = 500L

/**
 * Asks for the permission the SDK declares on the first Activity that stays resumed [SETTLED_MS], once per
 * process (ADR-0156): a dismissed dialog resumes the Activity, and asking again there would loop. That resume
 * logs a denial once.
 */
internal fun requestLocalNetworkOnFirstActivity(app: Application) {
    if (Build.VERSION.SDK_INT < ANDROID_17 || app.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
    if (!registered.compareAndSet(false, true)) return
    val main = Handler(Looper.getMainLooper())
    app.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            private val first =
                FirstSettledScreen<Activity>(
                    settleMs = SETTLED_MS,
                    schedule = { delay, block ->
                        val runnable = Runnable(block)
                        val cancel = { main.removeCallbacks(runnable) }
                        main.postDelayed(runnable, delay)
                        cancel
                    },
                    gone = { it.isFinishing || it.isDestroyed },
                    ask = { activity ->
                        if (activity.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                            activity.requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), REQUEST_CODE)
                        } else {
                            app.unregisterActivityLifecycleCallbacks(this)
                        }
                    },
                    answered = { activity ->
                        app.unregisterActivityLifecycleCallbacks(this)
                        if (activity.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                            platformLog(
                                "$ACCESS_LOCAL_NETWORK denied: the desktop cannot be reached on the local network. " +
                                    "Allow Nearby devices in the app's system settings, then restart the app",
                            )
                        }
                    },
                )

            override fun onActivityResumed(activity: Activity) = first.resumed(activity)

            override fun onActivityPaused(activity: Activity) = first.paused(activity)

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        },
    )
}
