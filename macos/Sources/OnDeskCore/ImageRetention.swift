import Foundation

public enum ImageRetention {
    public static func candidates(folder: URL, days: Int, now: Date = Date()) throws -> [URL] {
        guard (1...365).contains(days) else { throw TransferError.invalid }
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey, .isAliasFileKey, .contentModificationDateKey]
        let cutoff = now.addingTimeInterval(-Double(days) * 86400)
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])
        return try files.filter { url in
            guard FileCategory.classify(mime: "", name: url.lastPathComponent) == .images else { return false }
            let values = try url.resourceValues(forKeys: keys)
            return values.isRegularFile == true && values.isSymbolicLink != true && values.isAliasFile != true && (values.contentModificationDate.map { $0 < cutoff } ?? false)
        }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
}
