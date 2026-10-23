// swift-tools-version: 6.2
import PackageDescription

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
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")]
        ),
        .testTarget(name: "AppMonitorTests", dependencies: ["AppMonitor"]),
    ]
)
