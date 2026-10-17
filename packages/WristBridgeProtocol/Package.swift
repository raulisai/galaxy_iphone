// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WristBridgeProtocol",
    platforms: [.iOS("26.0"), .macOS(.v14)],
    products: [.library(name: "WristBridgeProtocol", targets: ["WristBridgeProtocol"])],
    targets: [
        .target(name: "WristBridgeProtocol"),
        .testTarget(name: "WristBridgeProtocolTests", dependencies: ["WristBridgeProtocol"])
    ]
)
