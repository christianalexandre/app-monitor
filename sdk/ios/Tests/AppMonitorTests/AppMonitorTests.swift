import Foundation
import Testing

@testable import AppMonitor

struct DeviceTests {
    static func tempFile() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("appmonitor-\(UUID().uuidString)")
            .appendingPathComponent("appmonitor-device-id")
    }

    @Test func deviceIDIsCreatedOnceExcludedFromBackupAndReused() throws {
        let file = Self.tempFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let id = deviceID(file: file)
        #expect(UUID(uuidString: id) != nil)
        #expect(try String(contentsOf: file, encoding: .utf8) == id)
        #expect(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        #expect(deviceID(file: file) == id)
    }

    @Test func anUnreadableDeviceIDIsReplaced() throws {
        let file = Self.tempFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("not a uuid".utf8).write(to: file)
        let id = deviceID(file: file)
        #expect(UUID(uuidString: id) != nil)
        #expect(deviceID(file: file) == id)
    }

    @Test func racingWritersOnAnEmptyDirectoryAllReturnTheIDLeftInTheFile() async throws {
        for round in 0..<50 {
            let file = Self.tempFile()
            defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            let ids = await withTaskGroup(of: String.self) { group in
                for _ in 0..<8 { group.addTask { deviceID(file: file) } }
                return await group.reduce(into: []) { $0.append($1) }
            }
            let stored = try String(contentsOf: file, encoding: .utf8)
            #expect(ids == Array(repeating: stored, count: 8), "round \(round)")
            let left = try FileManager.default.contentsOfDirectory(atPath: file.deletingLastPathComponent().path)
            #expect(left == ["appmonitor-device-id"])
        }
    }

    @Test func helloNamesThisInstall() {
        let file = Self.tempFile()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let hello = appleHello(deviceIDFile: file)
        #expect(hello.protocolVersion == ProtocolConstants.version)
        #expect(hello.platform == .ios)
        #expect(hello.deviceID == deviceID(file: file))
        #expect(!hello.deviceName.isEmpty)
        #expect(!hello.osVersion.isEmpty)
        #expect(!hello.appID.isEmpty)
        #expect(hello.sdkVersion == sdkVersion)
        #expect(hello.sdkName == "swift")
        #expect(hello.appName.isEmpty)
        #expect(appleHello(deviceIDFile: file, appName: "Sample").appName == "Sample")
        #expect(
            hello.channels == [
                ProtocolConstants.channelCore, ProtocolConstants.channelNetwork, ProtocolConstants.channelInteraction,
            ])
    }
}

struct AppMonitorOptionsTests {
    @Test func defaultsMatchTheProtocol() {
        let options = AppMonitorOptions()
        #expect(options.enabled && options.masking)
        #expect(options.host == nil && options.appName == nil)
        #expect(options.port == Int(ProtocolConstants.defaultPort))
        #expect(options.maxBodyBytes == ProtocolConstants.maxBodyBytes)
        #expect(options.maskedHeaders.isEmpty && options.maskedQueryParameters.isEmpty)
    }

    @Test func anOutOfRangePortStopsTheApp() async {
        await #expect(processExitsWith: .failure) { _ = AppMonitorOptions(port: 0) }
    }

    @Test func anOutOfRangeBodyLimitStopsTheApp() async {
        await #expect(processExitsWith: .failure) { _ = AppMonitorOptions(maxBodyBytes: (1 << 20) + 1) }
    }

    @Test func aPortChangedAfterInitIsCheckedAtStart() async {
        await #expect(processExitsWith: .failure) {
            var options = AppMonitorOptions(enabled: false)
            options.port = 70000
            AppMonitor.start(options)
        }
    }
}

@Suite(.serialized)
struct MonitorHolderTests {
    static func idleClient() -> MonitorClient {
        MonitorClientTests.client({ _, _ in {} })
    }

    @Test func disabledDoesNothing() {
        let holder = MonitorHolder()
        holder.start(AppMonitorOptions(enabled: false)) { _ in Self.idleClient() }
        #expect(holder.client == nil)
    }

    @Test func aSecondStartIsIgnoredWhileAClientRuns() async {
        let holder = MonitorHolder()
        holder.start(AppMonitorOptions(host: "first")) { _ in Self.idleClient() }
        let first = holder.client
        holder.start(AppMonitorOptions(host: "second")) { _ in Self.idleClient() }
        #expect(holder.client === first)
        #expect(holder.options?.host == "first")
        #expect(await eventually { first?.state == .discovering })
        holder.stop()
        await first?.join()
    }

    @Test func stopReleasesTheClientAndStartRunsANewOne() async {
        let holder = MonitorHolder()
        holder.start(AppMonitorOptions()) { _ in Self.idleClient() }
        let first = holder.client
        holder.stop()
        await first?.join()
        #expect(first?.isReleased == true)
        #expect(holder.client == nil)
        holder.start(AppMonitorOptions()) { _ in Self.idleClient() }
        #expect(holder.client != nil && holder.client !== first)
        let second = holder.client
        holder.stop()
        await second?.join()
    }

    @Test func discoveryFeedsTheClientThroughTheManualHost() async throws {
        let desktop = TestDesktop()
        try await desktop.start()
        defer { desktop.stop() }
        let holder = MonitorHolder()
        let options = AppMonitorOptions(host: desktop.endpoint.host, port: Int(desktop.port))
        holder.start(options) {
            MonitorClientTests.client(discovery($0, mdns: FakeBrowser(), onSimulator: true, log: { _ in }))
        }
        #expect(await eventually { holder.client?.state == .connected })
        let client = holder.client
        holder.stop()
        await client?.join()
    }
}
