import AppMonitor
import SwiftUI

@main
struct SampleApp: App {
    init() {
        var options = AppMonitorOptions(appName: "iOS sample")
        // `-host H -port N` at launch pin the desktop, as `make e2e-ios` does (ADR-0057).
        options.host = UserDefaults.standard.string(forKey: "host")
        let port = UserDefaults.standard.integer(forKey: "port")
        if port > 0 { options.port = port }
        AppMonitor.start(options)
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
