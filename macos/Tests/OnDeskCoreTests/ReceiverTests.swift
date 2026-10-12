import XCTest
import Network
import Security
import CryptoKit
@testable import OnDeskCore
@testable import OnDeskMac

private final class TestVault: CredentialVault, @unchecked Sendable {
    private let lock = NSLock(); private var value: Data?
    func read() -> Data? { lock.withLock { value } }
    func write(_ data: Data) { lock.withLock { value = data } }
}
private actor LinkProbe {
    var urls: [URL] = []
    func open(_ url: URL) -> Bool { urls.append(url); return true }
    func count() -> Int { urls.count }
}
final class ReceiverTests: XCTestCase, @unchecked Sendable {
    func testRemoteCameraCopiesOnlyRequestedCompletedTransferOnce() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let identity = try TLSIdentity.load(vault: TestVault()), auth = try PairingAuthority(vault: TestVault())
        let out = root.appendingPathComponent("out")
        let store = try TransferStore(root: root.appendingPathComponent("files"), folders: [.images: out])
        let probe = LinkProbe()
        let notifications = LinkProbe()
        let server = Receiver(identity: identity, serverID: UUID().uuidString, authority: auth, store: store, copyPhoto: { await probe.open($0) }, receivedFile: { _, _, file in _ = await notifications.open(file) })
        let port = try await server.start(localOnly: true)
        let client = connect(port: port, fingerprint: identity.fingerprint); client.start(queue: .global()); defer { client.cancel() }
        let owner = UUID().uuidString
        var command = WireRequest(op: "pair"); command.deviceID = owner; command.deviceName = "Camera test"; command.secret = try await auth.challenge()
        let paired = try await exchange(client, command), token = try XCTUnwrap(paired.token)
        command = WireRequest(op: "camera_poll"); command.token = token
        let empty = try await exchange(client, command); XCTAssertNil(empty.cameraID)
        let id = try await server.cameras.request(owner: owner)
        let poll = try await exchange(client, command)
        XCTAssertEqual(poll.cameraID, id); XCTAssertGreaterThan(poll.cameraTTLMillis ?? 0, 0)
        for fileID in [UUID().uuidString, id] {
            let meta = TransferMetadata(id: fileID, name: "photo.jpg", mime: "image/jpeg", size: 3, sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            command = WireRequest(op: "begin"); command.token = token; command.transfer = meta
            _ = try await exchange(client, command)
            command = WireRequest(op: "chunk"); command.token = token; command.id = fileID; command.offset = 0; command.payloadLength = 3
            _ = try await exchange(client, command, payload: Data("abc".utf8))
            command = WireRequest(op: "finish"); command.token = token; command.id = fileID
            let done = try await exchange(client, command); XCTAssertEqual(done.complete, true)
            _ = try await exchange(client, command)
            let count = await probe.count(); XCTAssertEqual(count, fileID == id ? 1 : 0)
            let noticeCount = await notifications.count(); XCTAssertEqual(noticeCount, fileID == id ? 2 : 1)
        }
        try await auth.revoke(owner)
        command = WireRequest(op: "camera_poll"); command.token = token
        let denied = try await exchange(client, command); XCTAssertEqual(denied.error, "unauthorized")
        await server.stop()
    }
    func testAuthenticatedLinkCommandAndLostReplyRetry() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let identity = try TLSIdentity.load(vault: TestVault()), auth = try PairingAuthority(vault: TestVault())
        let store = try TransferStore(root: root.appendingPathComponent("files"), folders: [:])
        let links = try LinkStore(root: root.appendingPathComponent("links")), probe = LinkProbe()
        let server = Receiver(identity: identity, serverID: UUID().uuidString, authority: auth, store: store, links: links, openLink: { await probe.open($0) })
        let port = try await server.start(localOnly: true)
        let client = connect(port: port, fingerprint: identity.fingerprint); client.start(queue: .global())
        let owner = UUID().uuidString
        var request = WireRequest(op: "pair"); request.deviceID = owner; request.deviceName = "Link test"; request.secret = try await auth.challenge()
        let paired = try await exchange(client, request)
        request = WireRequest(op: "open_link"); request.token = try XCTUnwrap(paired.token); request.id = UUID().uuidString; request.url = "https://example.org/"
        let first = try await exchange(client, request); XCTAssertEqual(first.opened, true)
        // Ignore the acknowledged result and retry on a new TLS connection.
        client.cancel()
        let retry = connect(port: port, fingerprint: identity.fingerprint); retry.start(queue: .global()); defer { retry.cancel() }
        let again = try await exchange(retry, request); XCTAssertEqual(again.opened, true)
        let count = await probe.count(); XCTAssertEqual(count, 1)
        try await auth.revoke(owner)
        let denied = try await exchange(retry, request); XCTAssertEqual(denied.error, "unauthorized")
        await server.stop()
    }
    func testRealTLSHandshakePairTransferResumeAndRevocation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let out = root.appendingPathComponent("out")
        let identity = try TLSIdentity.load(vault: TestVault())
        let auth = try PairingAuthority(vault: TestVault())
        let store = try TransferStore(root: root.appendingPathComponent("state"), folders: Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) }))
        let server = Receiver(identity: identity, serverID: UUID().uuidString, authority: auth, store: store)
        let port = try await server.start()
        let client = connect(port: port, fingerprint: identity.fingerprint)
        client.start(queue: .global())
        defer { client.cancel() }
        let id = UUID().uuidString
        var request = WireRequest(op: "pair"); request.deviceID = id; request.deviceName = "Test"; request.secret = try await auth.challenge()
        let paired = try await exchange(client, request)
        XCTAssertTrue(paired.ok); let token = try XCTUnwrap(paired.token)
        let bytes = Data("abc".utf8)
        let meta = TransferMetadata(id: UUID().uuidString, name: "hello.txt", mime: "text/plain", size: 3, sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        request = WireRequest(op: "begin"); request.token = token; request.transfer = meta
        let begun = try await exchange(client, request); XCTAssertEqual(begun.offset, 0)
        request = WireRequest(op: "chunk"); request.token = token; request.id = meta.id; request.offset = 0; request.payloadLength = 3
        let chunk = try await exchange(client, request, payload: bytes); XCTAssertEqual(chunk.offset, 3)
        client.cancel()
        let resumed = connect(port: port, fingerprint: identity.fingerprint); resumed.start(queue: .global()); defer { resumed.cancel() }
        request = WireRequest(op: "begin"); request.token = token; request.transfer = meta
        let status = try await exchange(resumed, request); XCTAssertEqual(status.offset, 3)
        request = WireRequest(op: "finish"); request.token = token; request.id = meta.id
        let done = try await exchange(resumed, request); XCTAssertEqual(done.complete, true)
        let again = try await exchange(resumed, request); XCTAssertEqual(again.savedName, "hello.txt")
        XCTAssertEqual(try Data(contentsOf: out.appendingPathComponent("hello.txt")), bytes)
        try await auth.revoke(id)
        let denied = try await exchange(resumed, request); XCTAssertEqual(denied.error, "unauthorized")
        await server.stop()
    }
    private func connect(port: UInt16, fingerprint: String) -> NWConnection {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, completion in
            let value = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let cert = (SecTrustCopyCertificateChain(value) as? [SecCertificate])?.first else { completion(false); return }
            let hash = SHA256.hash(data: SecCertificateCopyData(cert) as Data).map { String(format: "%02x", $0) }.joined()
            completion(hash == fingerprint)
        }, .global())
        return NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: tls))
    }
    private func exchange(_ client: NWConnection, _ request: WireRequest, payload: Data = Data()) async throws -> WireReply {
        let bytes = try WireFrame.encode(request) + payload
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            client.send(content: bytes, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
        let prefix = try await read(client, 4)
        return try JSONDecoder().decode(WireReply.self, from: await read(client, WireFrame.length(prefix)))
    }
    private func read(_ client: NWConnection, _ size: Int) async throws -> Data {
        var result = Data()
        while result.count < size {
            let data: Data = try await withCheckedThrowingContinuation { continuation in
                client.receive(minimumIncompleteLength: 1, maximumLength: size - result.count) { data, _, finished, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else { continuation.resume(throwing: TransferError.incomplete) }
                }
            }
            result.append(data)
        }
        return result
    }
}
