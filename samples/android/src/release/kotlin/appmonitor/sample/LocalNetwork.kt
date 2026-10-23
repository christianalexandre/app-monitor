package appmonitor.sample

import androidx.activity.ComponentActivity

/** Release links `sdk/no-op`, which talks to no desktop: nothing to ask. */
fun ComponentActivity.requestLocalNetwork() = Unit
