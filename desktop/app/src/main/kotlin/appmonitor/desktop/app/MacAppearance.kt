package appmonitor.desktop.app

import appmonitor.desktop.domain.ThemeMode
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

internal const val MAC_APPEARANCE = "apple.awt.application.appearance"

/** The macOS appearance of the window's title bar, menus and system dialogs for [theme]. */
internal fun macAppearanceOf(theme: ThemeMode): String =
    when (theme) {
        ThemeMode.System -> "system"
        ThemeMode.Light -> "NSAppearanceNameAqua"
        ThemeMode.Dark -> "NSAppearanceNameDarkAqua"
    }

/**
 * Moves the running app's title bar, menus and system dialogs to [theme] (ADR-0178); does nothing off macOS. A failure is printed,
 * and the title bar takes the theme at the next launch.
 */
internal fun applyMacAppearance(theme: ThemeMode) {
    if (!isMacOs()) return
    try {
        ObjC.setAppearance(macAppearanceOf(theme).takeIf { theme != ThemeMode.System })
    } catch (e: LinkageError) {
        System.err.println("Title bar keeps its appearance until the next launch: $e")
    }
}

private fun isMacOs() = System.getProperty("os.name").startsWith("Mac")

// AppKit's calls, through objc_msgSend; every argument and result is a pointer, but the last BOOL. ADR-0178
internal object ObjC {
    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val msgSend by lazy { objc.getFunction("objc_msgSend") }

    /** Null [name]: back to the system's appearance. AppKit needs its main thread, which AWT's run loop serves. */
    fun setAppearance(name: String?) =
        inAutoreleasePool {
            val appearance = name?.let { send(cls("NSAppearance"), "appearanceNamed:", string(it)) }
            msgSend.invokeVoid(
                arrayOf(
                    application(),
                    selector("performSelectorOnMainThread:withObject:waitUntilDone:"),
                    selector("setAppearance:"),
                    appearance,
                    false,
                ),
            )
        }

    /** The name of the appearance set on the app, null when it follows the system's. */
    fun appearanceName(): String? =
        inAutoreleasePool {
            send(application(), "appearance")?.let { send(it, "name") }?.let { send(it, "UTF8String") }?.getString(0)
        }

    private fun application() = send(cls("NSApplication"), "sharedApplication")

    private fun string(value: String) = msgSend.invokePointer(arrayOf(cls("NSString"), selector("stringWithUTF8String:"), value))

    private fun send(
        receiver: Pointer?,
        selector: String,
        vararg arguments: Pointer?,
    ): Pointer? = msgSend.invokePointer(arrayOf(receiver, selector(selector), *arguments))

    private fun cls(name: String) = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

    private fun selector(name: String) = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))

    private fun <T> inAutoreleasePool(block: () -> T): T {
        val pool = objc.getFunction("objc_autoreleasePoolPush").invokePointer(emptyArray())
        return try {
            block()
        } finally {
            objc.getFunction("objc_autoreleasePoolPop").invokeVoid(arrayOf(pool))
        }
    }
}
