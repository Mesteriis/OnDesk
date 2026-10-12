import XCTest
@testable import OnDeskCore
import OnDeskMac

final class ImageRetentionTests: XCTestCase, @unchecked Sendable {
    func testOnlyOldRegularImagesInSelectedFolderAreEligible() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        for name in ["old.JPG", "new.png", "old.pdf", ".hidden.png", "boundary.heic"] {
            let url = folder.appendingPathComponent(name)
            try Data("fixture".utf8).write(to: url)
            try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(name == "new.png" ? -60 : name == "boundary.heic" ? -3 * 86400 : -4 * 86400)], ofItemAtPath: url.path)
        }
        let nested = folder.appendingPathComponent("album.jpg")
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: folder.appendingPathComponent("linked.png"), withDestinationURL: folder.appendingPathComponent("old.JPG"))
        let files = try ImageRetention.candidates(folder: folder, days: 3, now: now)
        XCTAssertEqual(files.map(\.lastPathComponent), ["old.JPG"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("old.JPG").path), "Planning must never delete")
    }
    func testCleanupUsesTrashAndLeavesRecentImagesAndDocuments() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let old = folder.appendingPathComponent("OnDesk-retention-test-\(UUID().uuidString).png")
        let recent = folder.appendingPathComponent("recent.jpg"), document = folder.appendingPathComponent("document.pdf")
        try Data("disposable fixture".utf8).write(to: old)
        try Data("recent fixture".utf8).write(to: recent)
        try Data("document fixture".utf8).write(to: document)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-4 * 86400)], ofItemAtPath: old.path)
        let count = try await ImageCleanup().run(folder: folder, days: 3)
        XCTAssertEqual(count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: old.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: recent.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: document.path))
        // Check only our unique fixture; do not enumerate the user's Trash.
        let trash = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".Trash").appendingPathComponent(old.lastPathComponent)
        XCTAssertTrue(FileManager.default.fileExists(atPath: trash.path), "The file must be recoverable in Trash")
        if FileManager.default.fileExists(atPath: trash.path) { try FileManager.default.removeItem(at: trash) }
    }
    func testInvalidRetentionAndMissingFolderFailExplicitly() throws {
        XCTAssertThrowsError(try ImageRetention.candidates(folder: URL(fileURLWithPath: "/nonexistent-ondesk-retention"), days: 3))
        XCTAssertThrowsError(try ImageRetention.candidates(folder: FileManager.default.temporaryDirectory, days: 0))
    }
}
