// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "AppMonitor",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "AppMonitor", targets: ["AppMonitor"]),
        .library(name: "AppMonitorGRPC", targets: ["AppMonitorGRPC"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.39.0"),
        .package(url: "https://github.com/grpc/grpc-swift-2.git", from: "2.3.0"),
    ],
    targets: [
        .target(
            name: "AppMonitor",
            dependencies: ["AppMonitorConnections", .product(name: "SwiftProtobuf", package: "swift-protobuf")]
        ),
        .target(
            name: "AppMonitorConnections",
            exclude: ["fishhook"]
        ),
        .target(
            name: "AppMonitorGRPC",
            dependencies: [
                "AppMonitor",
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ]
        ),
        .testTarget(
            name: "AppMonitorTests",
            dependencies: [
                "AppMonitor",
                "AppMonitorGRPC",
                .product(name: "GRPCInProcessTransport", package: "grpc-swift-2"),
            ]
        ),
    ]
)
