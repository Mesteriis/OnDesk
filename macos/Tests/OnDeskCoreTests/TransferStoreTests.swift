import XCTest
import CryptoKit
@testable import OnDeskCore

final class TransferStoreTests: XCTestCase, @unchecked Sendable {
    func fixture() throws -> (URL, URL, TransferStore) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let out = root.appendingPathComponent("output")
        return (root, out, try TransferStore(root: root.appendingPathComponent("state"), folders: Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) })))
    }
    func metadata(_ data: Data, name: String = "photo.png", id: String = UUID().uuidString) -> TransferMetadata {
        TransferMetadata(id: id, name: name, mime: "image/png", size: Int64(data.count), sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }
    func testResumeAfterRestartAndRepeatFinishDoNotDuplicateFile() async throws {
        let (root, out, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let data = Data("abcdef".utf8), meta = metadata(Data("abcdef".utf8))
        _ = try await store.begin(meta, owner: "phone")
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: data.prefix(3))
        let restarted = try TransferStore(root: root.appendingPathComponent("state"), folders: Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) }))
        let status = try await restarted.begin(meta, owner: "phone")
        XCTAssertEqual(status.offset, 3)
        _ = try await restarted.append(id: meta.id, owner: "phone", offset: 3, data: data.suffix(3))
        let done = try await restarted.finish(id: meta.id, owner: "phone")
        XCTAssertTrue(done.complete)
        let repeated = try await restarted.finish(id: meta.id, owner: "phone")
        XCTAssertEqual(repeated.savedName, "photo.png")
        XCTAssertEqual(try Data(contentsOf: out.appendingPathComponent("photo.png")), data)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: out.path).count, 1)
    }
    func testCollisionKeepsExistingFile() async throws {
        let (root, out, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        try Data("old".utf8).write(to: out.appendingPathComponent("photo.png"))
        let data = Data("new".utf8), meta = metadata(Data("new".utf8))
        _ = try await store.begin(meta, owner: "phone")
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: data)
        let done = try await store.finish(id: meta.id, owner: "phone")
        XCTAssertEqual(done.savedName, "photo (1).png")
        XCTAssertEqual(try String(contentsOf: out.appendingPathComponent("photo.png"), encoding: .utf8), "old")
    }
    func testRejectsTraversalAndInvalidMetadata() async throws {
        let (root, _, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        for name in ["../outside", "/tmp/out", "a\\b", ".", "..", "", "bad\u{0}name"] {
            do { _ = try await store.begin(metadata(Data(), name: name), owner: "phone"); XCTFail("Accepted unsafe name") } catch {}
        }
        let bad = TransferMetadata(id: "../../bad", name: "ok", mime: "text/plain", size: -1, sha256: "bad")
        do { _ = try await store.begin(bad, owner: "phone"); XCTFail("Accepted invalid metadata") } catch {}
    }
    func testRejectsWrongOwnerOffsetChecksumAndMetadataReuse() async throws {
        let (root, _, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let meta = metadata(Data("abc".utf8))
        _ = try await store.begin(meta, owner: "phone")
        do { _ = try await store.begin(meta, owner: "attacker"); XCTFail("Wrong owner") } catch {}
        do { _ = try await store.begin(metadata(Data("def".utf8), id: meta.id), owner: "phone"); XCTFail("Changed metadata") } catch {}
        do { _ = try await store.append(id: meta.id, owner: "phone", offset: 1, data: Data([1])); XCTFail("Wrong offset") } catch {}
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: Data("def".utf8))
        do { _ = try await store.finish(id: meta.id, owner: "phone"); XCTFail("Bad checksum") } catch {}
    }
    func testUnacknowledgedTailIsTruncatedOnResume() async throws {
        let (root, out, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let meta = metadata(Data("abc".utf8))
        _ = try await store.begin(meta, owner: "phone")
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: Data("a".utf8))
        let part = root.appendingPathComponent("state/\(meta.id.lowercased()).part")
        let handle = try FileHandle(forWritingTo: part); try handle.seekToEnd(); try handle.write(contentsOf: Data("wrong tail".utf8)); try handle.close()
        let resumed = try TransferStore(root: root.appendingPathComponent("state"), folders: Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) }))
        let status = try await resumed.begin(meta, owner: "phone")
        XCTAssertEqual(status.offset, 1)
        XCTAssertEqual(try Data(contentsOf: part), Data("a".utf8))
        _ = try await resumed.append(id: meta.id, owner: "phone", offset: 1, data: Data("bc".utf8))
        let done = try await resumed.finish(id: meta.id, owner: "phone"); XCTAssertTrue(done.complete)
    }
    func testEmptyFileAndUnavailableDestinationPreserveQueuedBytes() async throws {
        let (root, out, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let empty = metadata(Data(), name: "empty.txt")
        _ = try await store.begin(empty, owner: "phone")
        let done = try await store.finish(id: empty.id, owner: "phone")
        XCTAssertTrue(done.complete)
        XCTAssertEqual(try Data(contentsOf: out.appendingPathComponent("empty.txt")).count, 0)
        let blocked = root.appendingPathComponent("not-a-folder")
        try Data("block".utf8).write(to: blocked)
        await store.setFolders(Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, blocked) }))
        let meta = metadata(Data("abc".utf8))
        _ = try await store.begin(meta, owner: "phone")
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: Data("abc".utf8))
        do { _ = try await store.finish(id: meta.id, owner: "phone"); XCTFail("Saved into a file") } catch {}
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("state/\(meta.id.lowercased()).part")), Data("abc".utf8))
        await store.setFolders(Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) }))
        let retried = try await store.finish(id: meta.id, owner: "phone"); XCTAssertTrue(retried.complete)
    }
    func testRecoversPublicationBeforeReceiptWithoutCreatingDuplicate() async throws {
        let (root, out, store) = try fixture(); defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("abc".utf8), meta = metadata(Data("abc".utf8))
        _ = try await store.begin(meta, owner: "phone")
        _ = try await store.append(id: meta.id, owner: "phone", offset: 0, data: bytes)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let pending = out.appendingPathComponent(".ondesk-\(meta.id.lowercased()).pending")
        try bytes.write(to: pending)
        let final = out.appendingPathComponent(meta.name)
        try FileManager.default.linkItem(at: pending, to: final)
        let journal = root.appendingPathComponent("state/\(meta.id.lowercased()).json")
        var record = try JSONSerialization.jsonObject(with: Data(contentsOf: journal)) as! [String: Any]
        record["destination"] = final.path
        try JSONSerialization.data(withJSONObject: record).write(to: journal)
        let restarted = try TransferStore(root: root.appendingPathComponent("state"), folders: Dictionary(uniqueKeysWithValues: FileCategory.allCases.map { ($0, out) }))
        let receipt = try await restarted.begin(meta, owner: "phone")
        XCTAssertTrue(receipt.complete)
        XCTAssertEqual(receipt.savedName, "photo.png")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: out.path), ["photo.png"])
    }
    func testMIMEWinsThenExtensionThenOther() {
        XCTAssertEqual(FileCategory.classify(mime: "video/mp4", name: "x.jpg"), .videos)
        XCTAssertEqual(FileCategory.classify(mime: "application/octet-stream", name: "x.HEIC"), .images)
        XCTAssertEqual(FileCategory.classify(mime: "application/pdf", name: "x"), .documents)
        XCTAssertEqual(FileCategory.classify(mime: "application/zip", name: "x"), .archives)
        XCTAssertEqual(FileCategory.classify(mime: "audio/flac", name: "x"), .audio)
        XCTAssertEqual(FileCategory.classify(mime: "application/octet-stream", name: "x.unknown"), .other)
    }
}
