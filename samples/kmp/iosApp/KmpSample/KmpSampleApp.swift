import AppMonitor
import Shared
import SwiftUI

@main
struct KmpSampleApp: App {
    init() {
        // `-host H -port N` at launch pin the desktop for both SDKs, as `make e2e-ios` does (ADR-0057).
        let host = UserDefaults.standard.string(forKey: "host")
        let port = UserDefaults.standard.integer(forKey: "port")
        var options = AppMonitorOptions(appName: "KMP sample")
        options.host = host
        if port > 0 { options.port = port }
        AppMonitor.start(options)
        MainViewControllerKt.startMonitor(host: host, port: Int32(port))
    }

    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea() }
    }
}

/// The shared Compose UI; `-runAll YES` at launch taps every button in turn.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController(runAll: UserDefaults.standard.bool(forKey: "runAll"))
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
