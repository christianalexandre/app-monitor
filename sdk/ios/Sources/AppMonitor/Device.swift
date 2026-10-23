#if DEBUG
import Foundation

let sdkVersion = "0.1.0-dev"

#if targetEnvironment(simulator)
let isSimulator = true
#else
let isSimulator = false
#endif

/// The install's device id kept in `file`, excluded from backup; a missing or unreadable one is
/// replaced by a new random UUID (ADR-0036 D).
func deviceID(file: URL) -> String {
    if let stored = try? String(contentsOf: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
        UUID(uuidString: stored) != nil
    {
        return stored
    }
    let id = UUID().uuidString.lowercased()
    try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? Data(id.utf8).write(to: file, options: .atomic)
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    var excluded = file
    try? excluded.setResourceValues(values)
    return id
}

/// Where the device id lives: the app's Application Support directory.
func defaultDeviceIDFile() -> URL {
    let support =
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        ?? FileManager.default.temporaryDirectory
    return support.appendingPathComponent("appmonitor-device-id")
}

/// The model identifier, never the user-set name (ADR-0036 D): `iPhone16,2`, or
/// `SIMULATOR_MODEL_IDENTIFIER` plus " Simulator" on the simulator.
func deviceName(environment: [String: String] = ProcessInfo.processInfo.environment) -> String {
    if isSimulator, let model = environment["SIMULATOR_MODEL_IDENTIFIER"] { return "\(model) Simulator" }
    var system = utsname()
    uname(&system)
    return withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
}

/// `Hello` for this install (ADR-0036 D); reads or creates the device id file.
func appleHello(deviceIDFile: URL = defaultDeviceIDFile(), bundle: Bundle = .main) -> Appmonitor_V1_Hello {
    let os = ProcessInfo.processInfo.operatingSystemVersion
    var hello = Appmonitor_V1_Hello()
    hello.protocolVersion = ProtocolConstants.version
    hello.platform = .ios
    hello.deviceID = deviceID(file: deviceIDFile)
    hello.deviceName = deviceName()
    #if os(iOS)
    hello.osVersion = "iOS \(os.majorVersion).\(os.minorVersion)"
    #else
    hello.osVersion = "macOS \(os.majorVersion).\(os.minorVersion)"
    #endif
    if os.patchVersion != 0 { hello.osVersion += ".\(os.patchVersion)" }
    hello.appID = bundle.bundleIdentifier ?? ProcessInfo.processInfo.processName
    hello.appVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    hello.sdkVersion = sdkVersion
    hello.channels = [ProtocolConstants.channelCore, ProtocolConstants.channelNetwork]
    return hello
}
#endif
