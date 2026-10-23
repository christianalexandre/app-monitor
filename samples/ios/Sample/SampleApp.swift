import AppMonitor
import SwiftUI

@main
struct SampleApp: App {
    init() {
        AppMonitor.start(AppMonitorOptions(appName: "iOS sample"))
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
