import XCTest
import Foundation
@testable import OnDeskCore
@testable import OnDeskMac

private final class MemoryVault: CredentialVault, @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Data?
    func read() -> Data? { lock.withLock { bytes } }
    func write(_ data: Data) { lock.withLock { bytes = data } }
}
final class WireAndPairingTests: XCTestCase, @unchecked Sendable {
    func testFrameUsesBigEndianAndRejectsUnboundedHeader() throws {
        let frame = try WireFrame.encode(WireRequest(op: "finish"))
        let length = try WireFrame.length(frame.prefix(4))
        XCTAssertEqual(length, frame.count - 4)
        let object = try JSONSerialization.jsonObject(with: frame.dropFirst(4)) as! [String: Any]
        XCTAssertEqual(object["op"] as? String, "finish")
        for prefix in [Data([0,0,0,0]), Data([0,0,64,1]), Data([255,255,255,255]), Data([1])] {
            XCTAssertThrowsError(try WireFrame.length(prefix))
        }
    }
    func testPairingSingleUseExpiryPersistenceAndRevocation() async throws {
        let vault = MemoryVault(), now = Date(timeIntervalSince1970: 1000), id = UUID().uuidString
        let auth = try PairingAuthority(vault: vault)
        let secret = try await auth.challenge(now: now)
        let token = try await auth.pair(secret: secret, deviceID: id, name: "Phone", now: now)
        let owner = try await auth.owner(token: token); XCTAssertEqual(owner, id)
        do { _ = try await auth.pair(secret: secret, deviceID: UUID().uuidString, name: "Other", now: now); XCTFail("Reused QR") } catch {}
        let restarted = try PairingAuthority(vault: vault)
        let persisted = try await restarted.owner(token: token); XCTAssertEqual(persisted, id)
        try await restarted.revoke(id)
        do { _ = try await restarted.owner(token: token); XCTFail("Revoked token") } catch {}
        let expiring = try await auth.challenge(now: now)
        do { _ = try await auth.pair(secret: expiring, deviceID: id, name: "Phone", now: now.addingTimeInterval(301)); XCTFail("Expired QR") } catch {}
        do { _ = try await auth.owner(token: ""); XCTFail("Empty token") } catch {}
    }
    func testSharedFixtureDecodesWith64BitOffsets() throws {
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("protocol/fixtures/begin.json")
        let request = try JSONDecoder().decode(WireRequest.self, from: Data(contentsOf: fixture))
        XCTAssertEqual(request.transfer?.name, "hello.txt")
        let reply = try JSONDecoder().decode(WireReply.self, from: Data("{\"version\":1,\"ok\":true,\"offset\":5368709120}".utf8))
        XCTAssertEqual(reply.offset, 5_368_709_120)
    }
}
