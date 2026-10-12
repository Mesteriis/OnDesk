import XCTest
import UserNotifications
@testable import OnDeskCore
@testable import OnDeskMac

final class FileNotificationsTests: XCTestCase, @unchecked Sendable {
    @MainActor func testNativeContentContainsReceiptRatherThanFilesystemPath() {
        let id = UUID().uuidString
        let file = URL(fileURLWithPath: "/private/received/document (1).pdf")
        let request = FileNotifications.request(id: id, owner: "phone", file: file, sound: false)
        XCTAssertEqual(request.content.title, "Файл получен")
        XCTAssertEqual(request.content.body, "document (1).pdf")
        XCTAssertEqual(request.content.categoryIdentifier, FileNotifications.category)
        XCTAssertEqual(request.content.userInfo as? [String: String], ["transferID": id, "owner": "phone"])
        XCTAssertNil(request.content.sound)
        XCTAssertNil(request.trigger)
        XCTAssertEqual(request.identifier, FileNotifications.request(id: id, owner: "phone", file: file, sound: true).identifier)
        XCTAssertNotNil(FileNotifications.request(id: id, owner: "phone", file: file, sound: true).content.sound)
    }
    func testClaimRequiresCompletedFileAndSurvivesRestartWithLegacyReceipt() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let out = root.appendingPathComponent("out"), state = root.appendingPathComponent("state")
        let store = try TransferStore(root: state, folders: [.documents: out])
        let id = UUID().uuidString
        let metadata = TransferMetadata(id: id, name: "test.txt", mime: "text/plain", size: 0, sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        _ = try await store.begin(metadata, owner: "phone")
        do { _ = try await store.claimCompletionNotification(id: id, owner: "phone"); XCTFail("Incomplete file") } catch {}
        _ = try await store.finish(id: id, owner: "phone")
        // Receipts created before this version lack notificationClaimed.
        let receipt = state.appendingPathComponent(id.lowercased() + ".json")
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: receipt)) as? [String: Any])
        legacy.removeValue(forKey: "notificationClaimed")
        try JSONSerialization.data(withJSONObject: legacy).write(to: receipt)
        do { _ = try await store.claimCompletionNotification(id: id, owner: "other"); XCTFail("Wrong owner") } catch {}
        let claimed = try await store.claimCompletionNotification(id: id, owner: "phone")
        XCTAssertEqual(claimed, out.appendingPathComponent("test.txt"))
        let restarted = try TransferStore(root: state, folders: [:])
        _ = try await restarted.finish(id: id, owner: "phone")
        let duplicate = try await restarted.claimCompletionNotification(id: id, owner: "phone")
        XCTAssertNil(duplicate)
    }
    @MainActor func testOpenAndFinderResolveDurableDestinationAndRejectMissingOrWrongOwner() async throws {
        let fixture = TransferStoreTests()
        let (root, out, store) = try fixture.fixture()
        defer { try? FileManager.default.removeItem(at: root) }
        let metadata = fixture.metadata(Data(), name: "received.txt")
        _ = try await store.begin(metadata, owner: "phone")
        _ = try await store.finish(id: metadata.id, owner: "phone")
        for action in [FileNotifications.openAction, FileNotifications.revealAction, UNNotificationDefaultActionIdentifier] {
            let file = try await FileNotifications.destination(action: action, id: metadata.id, owner: "phone", store: store)
            XCTAssertEqual(file, out.appendingPathComponent("received.txt"))
        }
        let dismiss = try await FileNotifications.destination(action: UNNotificationDismissActionIdentifier, id: "invalid", owner: "phone", store: store)
        XCTAssertNil(dismiss)
        do { _ = try await FileNotifications.destination(action: FileNotifications.openAction, id: metadata.id, owner: "other", store: store); XCTFail("Wrong owner") } catch {}
        try FileManager.default.removeItem(at: out.appendingPathComponent("received.txt"))
        do { _ = try await FileNotifications.destination(action: FileNotifications.openAction, id: metadata.id, owner: "phone", store: store); XCTFail("Missing file") } catch {}
        try FileManager.default.createSymbolicLink(at: out.appendingPathComponent("received.txt"), withDestinationURL: root)
        do { _ = try await FileNotifications.destination(action: FileNotifications.openAction, id: metadata.id, owner: "phone", store: store); XCTFail("Symlink replacement") } catch {}
    }
}
