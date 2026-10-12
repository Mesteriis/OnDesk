import XCTest
@testable import OnDeskCore

private actor OpenedLinks {
    var urls: [URL] = []
    func open(_ url: URL) -> Bool { urls.append(url); return true }
    func count() -> Int { urls.count }
}
final class LinkStoreTests: XCTestCase, @unchecked Sendable {
    func testOnlyWebURLsAllowed() throws {
        XCTAssertEqual(try WebLink.validate("https://example.org/a?q=one%20two#part").absoluteString, "https://example.org/a?q=one%20two#part")
        for text in ["file:///tmp/a", "javascript:alert(1)", "https://user:pass@example.org/", "https://", "https://example.org/\n", "https://example.org\\evil", "https://example.org:0/"] {
            XCTAssertThrowsError(try WebLink.validate(text), text)
        }
    }
    func testRetryAndRestartNeverOpenTwiceAndOwnerIsChecked() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let opened = OpenedLinks(), id = UUID().uuidString
        let store = try LinkStore(root: root)
        try await store.open(id: id, url: "https://example.org/", owner: "one") { await opened.open($0) }
        let restarted = try LinkStore(root: root)
        try await restarted.open(id: id, url: "https://example.org/", owner: "one") { await opened.open($0) }
        let count = await opened.count(); XCTAssertEqual(count, 1)
        do { try await restarted.open(id: id, url: "https://example.org/", owner: "two") { await opened.open($0) }; XCTFail("Foreign owner accepted") }
        catch { XCTAssertEqual(error as? LinkError, .unauthorized) }
        do { try await restarted.open(id: id, url: "https://other.org/", owner: "one") { await opened.open($0) }; XCTFail("Changed URL accepted") }
        catch { XCTAssertEqual(error as? LinkError, .conflict) }
    }
    func testCrashAfterDispatchCheckpointRequiresAttentionInsteadOfReopening() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = UUID().uuidString, store = try LinkStore(root: root)
        // Simulate process loss after the persisted dispatch checkpoint.
        do { try await store.open(id: id, url: "https://example.org/", owner: "one") { _ in throw CancellationError() }; XCTFail() } catch {}
        let restarted = try LinkStore(root: root), opened = OpenedLinks()
        do { try await restarted.open(id: id, url: "https://example.org/", owner: "one") { await opened.open($0) }; XCTFail() }
        catch { XCTAssertEqual(error as? LinkError, .openUncertain) }
        let count = await opened.count(); XCTAssertEqual(count, 0)
    }
    func testBrowserRefusalCanBeRetried() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = UUID().uuidString, store = try LinkStore(root: root)
        do { try await store.open(id: id, url: "https://example.org/", owner: "one") { _ in false }; XCTFail() }
        catch { XCTAssertEqual(error as? LinkError, .openFailed) }
        try await store.open(id: id, url: "https://example.org/", owner: "one") { _ in true }
    }
}
