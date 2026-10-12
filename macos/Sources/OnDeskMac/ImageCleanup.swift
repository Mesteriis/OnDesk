import Foundation
import OnDeskCore

public actor ImageCleanup {
    public init() {}
    public func run(folder: URL, days: Int, now: Date = Date()) throws -> Int {
        let files = try ImageRetention.candidates(folder: folder, days: days, now: now)
        var removed = 0
        for var file in files {
            try Task.checkCancellation()
            // Refresh attributes before moving: a file may have changed since enumeration.
            file.removeAllCachedResourceValues()
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .isAliasFileKey, .contentModificationDateKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, values.isAliasFile != true,
                  let modified = values.contentModificationDate, modified < now.addingTimeInterval(-Double(days) * 86400) else { continue }
            try FileManager.default.trashItem(at: file, resultingItemURL: nil)
            removed += 1
        }
        return removed
    }
}
