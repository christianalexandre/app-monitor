package appmonitor.gradle

import org.gradle.api.provider.Property

/** The `appMonitor { }` block of a module that applies the plugin (ADR-0112). */
public abstract class AppMonitorExtension {
    /** Adds the OkHttp interceptor to every client of a debuggable variant; `true` by default. */
    public abstract val injectOkHttp: Property<Boolean>
}
