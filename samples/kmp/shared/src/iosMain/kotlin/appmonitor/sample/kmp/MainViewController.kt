package appmonitor.sample.kmp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** The Swift host's root view controller. */
fun mainViewController(
    runAll: Boolean,
    target: String?,
): UIViewController = ComposeUIViewController { App(runAll, target) }
