import Foundation

public enum LinkError: String, Error, Sendable { case invalid, unauthorized, conflict, openFailed = "open_failed", openUncertain = "open_uncertain" }
public enum WebLink {
    public static func validate(_ text: String) throws -> URL {
        guard !text.isEmpty, text.utf8.count <= 4096,
              !text.contains("\\"), !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0) }),
              let parts = URLComponents(string: text), ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.port == nil || (1...65535).contains(parts.port!), let url = parts.url else { throw LinkError.invalid }
        return url
    }
}
public actor LinkStore {
    private struct Record: Codable {
        let owner: String
        let url: String
        var state: String
    }
    private let root: URL
    public init(root: URL) throws {
        self.root = root
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try DurableFile.syncDirectory(root.deletingLastPathComponent())
    }
    public func open(id: String, url: String, owner: String, using opener: @Sendable (URL) async throws -> Bool) async throws {
        guard let uuid = UUID(uuidString: id) else { throw LinkError.invalid }
        let target = try WebLink.validate(url)
        let path = root.appendingPathComponent(uuid.uuidString.lowercased() + ".json")
        if FileManager.default.fileExists(atPath: path.path) {
            let record = try JSONDecoder().decode(Record.self, from: Data(contentsOf: path))
            guard record.owner == owner else { throw LinkError.unauthorized }
            guard record.url == url else { throw LinkError.conflict }
            if record.state == "opened" { return }
            guard record.state == "pending" else { throw LinkError.openUncertain }
        }
        var record = Record(owner: owner, url: url, state: "dispatching")
        // Browser launch is an external side effect: checkpoint first, never replay
        // an uncertain launch after a crash or lost persistence acknowledgement.
        try DurableFile.write(JSONEncoder().encode(record), to: path)
        guard try await opener(target) else {
            record.state = "pending"
            try DurableFile.write(JSONEncoder().encode(record), to: path)
            throw LinkError.openFailed
        }
        record.state = "opened"
        try DurableFile.write(JSONEncoder().encode(record), to: path)
    }
}
