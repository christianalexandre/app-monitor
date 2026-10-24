// swift-tools-version: 6.2
import PackageDescription

// ADR-0054: the manifest apps resolve; sdk/ios/Package.swift builds the same sources with the tests.
let package = Package(
    name: "AppMonitor",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "AppMonitor", targets: ["AppMonitor"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.39.0")
    ],
    targets: [
        .target(
            name: "AppMonitor",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")],
            path: "sdk/ios/Sources/AppMonitor",
            exclude: ["Generated/proto.sha256"]
        )
    ]
)
