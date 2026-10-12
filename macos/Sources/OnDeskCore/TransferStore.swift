import Foundation
import CryptoKit
import Darwin

public enum FileCategory: String, CaseIterable, Codable, Sendable {
    case images, videos, audio, documents, archives, other
    public static func classify(mime: String, name: String) -> FileCategory {
        let type = mime.lowercased().split(separator: ";").first.map(String.init) ?? ""
        if type.hasPrefix("image/") { return .images }
        if type.hasPrefix("video/") { return .videos }
        if type.hasPrefix("audio/") { return .audio }
        if type.hasPrefix("text/") || ["application/pdf", "application/msword", "application/rtf"].contains(type) || type.contains("officedocument") || type.contains("opendocument") { return .documents }
        if ["application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-tar"].contains(type) { return .archives }
        let ext = (name as NSString).pathExtension.lowercased()
        if ["jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "tiff", "svg", "avif", "dng"].contains(ext) { return .images }
        if ["mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp"].contains(ext) { return .videos }
        if ["mp3", "m4a", "wav", "flac", "ogg", "opus", "aac"].contains(ext) { return .audio }
        if ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "json", "epub"].contains(ext) { return .documents }
        if ["zip", "rar", "7z", "gz", "tar", "bz2", "xz"].contains(ext) { return .archives }
        return .other
    }
}
public struct TransferMetadata: Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let mime: String
    public let size: Int64
    public let sha256: String
    public init(id: String, name: String, mime: String, size: Int64, sha256: String) {
        self.id = id; self.name = name; self.mime = mime; self.size = size; self.sha256 = sha256
    }
    public func validate() throws {
        guard UUID(uuidString: id) != nil, size >= 0, !name.isEmpty, name != ".", name != "..",
              name.utf8.count <= 220, !name.contains("/"), !name.contains("\\"),
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              mime.utf8.count <= 256, sha256.count == 64,
              sha256.allSatisfy({ "0123456789abcdef".contains($0) }) else { throw TransferError.invalid }
    }
}
public struct TransferStatus: Codable, Sendable {
    public let offset: Int64
    public let complete: Bool
    public let savedName: String?
}
public enum TransferError: String, Error, Sendable { case invalid, conflict, offset, incomplete, checksum, storage, unauthorized }

public enum DurableFile {
    public static func write(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: .atomic)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
        try syncDirectory(url.deletingLastPathComponent())
    }
    public static func syncDirectory(_ url: URL) throws {
        let fd = open(url.path, O_RDONLY)
        guard fd >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        defer { close(fd) }
        guard fsync(fd) == 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
    }
    public static func sha256(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var hash = SHA256()
        while let bytes = try file.read(upToCount: 1024 * 1024), !bytes.isEmpty { hash.update(data: bytes) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

public actor TransferStore {
    private struct Record: Codable {
        let metadata: TransferMetadata
        let owner: String
        var offset: Int64 = 0
        var destination: String?
        var complete = false
        var notificationClaimed: Bool?
    }
    private let root: URL
    private var folders: [FileCategory: URL]
    public init(root: URL, folders: [FileCategory: URL]) throws {
        self.root = root; self.folders = folders
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }
    public func setFolders(_ folders: [FileCategory: URL]) { self.folders = folders }
    private func recordURL(_ id: String) throws -> URL {
        guard UUID(uuidString: id) != nil else { throw TransferError.invalid }
        return root.appendingPathComponent(id.lowercased() + ".json")
    }
    private func partURL(_ id: String) -> URL { root.appendingPathComponent(id.lowercased() + ".part") }
    private func load(_ id: String, owner: String) throws -> Record {
        let record = try JSONDecoder().decode(Record.self, from: Data(contentsOf: recordURL(id)))
        guard record.owner == owner else { throw TransferError.unauthorized }
        return record
    }
    private func save(_ record: Record) throws {
        try DurableFile.write(JSONEncoder().encode(record), to: recordURL(record.metadata.id))
    }
    private func status(_ record: Record) -> TransferStatus {
        TransferStatus(offset: record.offset, complete: record.complete, savedName: record.complete ? record.destination.map { URL(fileURLWithPath: $0).lastPathComponent } : nil)
    }
    public func begin(_ metadata: TransferMetadata, owner: String) throws -> TransferStatus {
        try metadata.validate()
        let url = try recordURL(metadata.id)
        if FileManager.default.fileExists(atPath: url.path) {
            var record = try load(metadata.id, owner: owner)
            guard record.metadata == metadata else { throw TransferError.conflict }
            if !record.complete {
                if try recoverPublication(&record) { return status(record) }
                let handle = try FileHandle(forWritingTo: partURL(metadata.id))
                defer { try? handle.close() }
                let length = try handle.seekToEnd()
                guard length >= UInt64(record.offset) else { throw TransferError.storage }
                try handle.truncate(atOffset: UInt64(record.offset))
                try handle.synchronize()
            }
            return status(record)
        }
        let part = partURL(metadata.id)
        guard FileManager.default.createFile(atPath: part.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw TransferError.storage }
        let record = Record(metadata: metadata, owner: owner)
        try save(record)
        return status(record)
    }
    public func append(id: String, owner: String, offset: Int64, data: Data) throws -> TransferStatus {
        var record = try load(id, owner: owner)
        guard !record.complete, record.destination == nil, offset == record.offset else { throw TransferError.offset }
        guard !data.isEmpty, data.count <= 4 * 1024 * 1024, Int64(data.count) <= record.metadata.size - offset else { throw TransferError.invalid }
        let handle = try FileHandle(forWritingTo: partURL(id))
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        try handle.write(contentsOf: data)
        try handle.synchronize()
        record.offset += Int64(data.count)
        try save(record)
        return status(record)
    }
    private func recoverPublication(_ record: inout Record) throws -> Bool {
        guard !record.complete, let path = record.destination else { return record.complete }
        let destination = URL(fileURLWithPath: path)
        // Publication links the fully synced pending inode. The unique pending file
        // survives until the receipt is durable, so recovery never mistakes an
        // unrelated file with the same name/content for our publication.
        let pending = destination.deletingLastPathComponent().appendingPathComponent(".ondesk-\(record.metadata.id.lowercased()).pending")
        if FileManager.default.fileExists(atPath: destination.path), FileManager.default.fileExists(atPath: pending.path) {
            let a = try FileManager.default.attributesOfItem(atPath: destination.path)
            let b = try FileManager.default.attributesOfItem(atPath: pending.path)
            if (a[.systemFileNumber] as? NSNumber) == (b[.systemFileNumber] as? NSNumber), (a[.systemNumber] as? NSNumber) == (b[.systemNumber] as? NSNumber) {
                try DurableFile.syncDirectory(destination.deletingLastPathComponent())
                record.complete = true; try save(record)
                try? FileManager.default.removeItem(at: pending)
                try? FileManager.default.removeItem(at: partURL(record.metadata.id))
                return true
            }
        }
        return false
    }
    public func completedPhoto(id: String, owner: String) throws -> URL {
        let record = try load(id, owner: owner)
        guard record.complete, record.metadata.mime == "image/jpeg", let path = record.destination else { throw TransferError.invalid }
        return URL(fileURLWithPath: path)
    }
    public func completedFile(id: String, owner: String) throws -> URL {
        let record = try load(id, owner: owner)
        guard record.complete, let path = record.destination else { throw TransferError.incomplete }
        return URL(fileURLWithPath: path)
    }
    // Claim before notifying: retries (including after restart) must not repeat banners.
    public func claimCompletionNotification(id: String, owner: String) throws -> URL? {
        var record = try load(id, owner: owner)
        guard record.complete, let path = record.destination else { throw TransferError.incomplete }
        guard record.notificationClaimed != true else { return nil }
        record.notificationClaimed = true
        try save(record)
        return URL(fileURLWithPath: path)
    }
    public func finish(id: String, owner: String) throws -> TransferStatus {
        var record = try load(id, owner: owner)
        if record.complete { return status(record) }
        if try recoverPublication(&record) { return status(record) }
        guard record.offset == record.metadata.size else { throw TransferError.incomplete }
        let part = partURL(id)
        guard try DurableFile.sha256(part) == record.metadata.sha256 else { throw TransferError.checksum }
        let folder: URL
        if let destination = record.destination { folder = URL(fileURLWithPath: destination).deletingLastPathComponent() }
        else {
            guard let selected = folders[FileCategory.classify(mime: record.metadata.mime, name: record.metadata.name)] else { throw TransferError.storage }
            folder = selected
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let pending = folder.appendingPathComponent(".ondesk-\(id.lowercased()).pending")
        // Remove an incomplete cross-volume copy left before publication.
        if FileManager.default.fileExists(atPath: pending.path) { try FileManager.default.removeItem(at: pending) }
        try FileManager.default.copyItem(at: part, to: pending)
        let handle = try FileHandle(forWritingTo: pending)
        try handle.synchronize(); try handle.close()
        let original = record.metadata.name as NSString
        let ext = original.pathExtension
        let stem = original.deletingPathExtension
        var suffix = 0
        while true {
            let name = suffix == 0 ? record.metadata.name : "\(stem) (\(suffix))" + (ext.isEmpty ? "" : ".\(ext)")
            let destination = folder.appendingPathComponent(name)
            record.destination = destination.path
            try save(record) // journal intent before atomic, non-overwriting publication
            if link(pending.path, destination.path) == 0 { break }
            guard errno == EEXIST else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
            suffix += 1
        }
        try DurableFile.syncDirectory(folder)
        record.complete = true
        try save(record)
        try? FileManager.default.removeItem(at: pending)
        try? FileManager.default.removeItem(at: part)
        return status(record)
    }
}
