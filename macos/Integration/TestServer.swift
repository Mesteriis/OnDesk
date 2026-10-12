import Foundation
import OnDeskCore
import OnDeskMac

private final class MemoryVault: CredentialVault, @unchecked Sendable {
    private let lock = NSLock(); private var value: Data?
    func read() -> Data? { lock.withLock { value } }
    func write(_ data: Data) { lock.withLock { value = data } }
}
@main struct TestServer {
    static func main() async throws {
        guard CommandLine.arguments.count == 2 else { throw TransferError.invalid }
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let identity = try TLSIdentity.load(vault: MemoryVault())
        let authority = try PairingAuthority(vault: MemoryVault())
        let folders = Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, root.appendingPathComponent("received/\($0.rawValue)")) })
        let store = try TransferStore(root: root.appendingPathComponent("state"), folders: folders)
        let id = UUID().uuidString
        let server = Receiver(identity: identity, serverID: id, authority: authority, store: store)
        let port = try await server.start(localOnly: true)
        let secret = try await authority.challenge()
        let invitation: [String: Any] = ["version": 1, "serverID": id, "host": "10.0.2.2", "port": Int(port), "fingerprint": identity.fingerprint, "secret": secret]
        let file = root.appendingPathComponent("pairing.json")
        try DurableFile.write(JSONSerialization.data(withJSONObject: invitation, options: [.sortedKeys]), to: file)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        print("Test TLS receiver ready (loopback only)")
        while !FileManager.default.fileExists(atPath: root.appendingPathComponent("stop").path) {
            try await Task.sleep(for: .seconds(1))
        }
        await server.stop()
    }
}
