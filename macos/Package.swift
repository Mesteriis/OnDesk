// swift-tools-version: 6.2
import PackageDescription
let package = Package(
    name: "OnDesk",
    platforms: [.macOS("27.0")],
    products: [.executable(name: "OnDesk", targets: ["OnDesk"])],
    targets: [
        .target(name: "OnDeskCore"),
        .target(name: "OnDeskMac", dependencies: ["OnDeskCore"]),
        .executableTarget(name: "OnDeskTestServer", dependencies: ["OnDeskMac", "OnDeskCore"], path: "Integration"),
        .executableTarget(name: "OnDesk", dependencies: ["OnDeskMac", "OnDeskCore"]),
        .testTarget(name: "OnDeskCoreTests", dependencies: ["OnDeskCore", "OnDeskMac"])
    ]
)
