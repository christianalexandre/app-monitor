import Shared
import SwiftUI

#if DEBUG
import AppMonitor
#endif

@main
struct KmpSampleApp: App {
    init() {
        // `-host H -port N` at launch pin the desktop, as `make e2e-ios` does (ADR-0057). The Swift SDK alone
        // captures Ktor's Darwin calls; `sdk/core` is never started on iOS (ADR-0158).
        let host = UserDefaults.standard.string(forKey: "host")
        let port = UserDefaults.standard.integer(forKey: "port")
        #if DEBUG
        var options = AppMonitorOptions(appName: "KMP sample")
        options.host = host
        if port > 0 { options.port = port }
        AppMonitor.start(options)
        #endif
    }

    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea() }
    }
}

/// The shared Compose UI; `-runAll YES` at launch taps every button in turn,
/// `-target URL` replaces the public services.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController(
            runAll: UserDefaults.standard.bool(forKey: "runAll"), target: UserDefaults.standard.string(forKey: "target")
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
