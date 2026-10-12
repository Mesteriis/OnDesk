import Foundation

/// Ephemeral intent: restarting the Mac never restores clipboard side effects.
public actor CameraRequests {
    private struct Pending { let id: String; let owner: String; let created: Date; var delivered = false }
    private var pending: Pending?
    private var lastSeen: [String: Date] = [:]
    public init() {}
    private func expire(_ now: Date) {
        if let p = pending, now.timeIntervalSince(p.created) >= (p.delivered ? 600 : 30) { pending = nil }
    }
    @discardableResult public func poll(owner: String, now: Date = Date()) -> String? {
        expire(now); lastSeen[owner] = now
        guard var p = pending, p.owner == owner, !p.delivered else { return nil }
        p.delivered = true; pending = p
        return p.id
    }
    public func request(owner: String, now: Date = Date()) throws -> String {
        expire(now)
        guard pending == nil else { throw TransferError.conflict }
        guard let seen = lastSeen[owner], now.timeIntervalSince(seen) < 10 else { throw TransferError.incomplete }
        let id = UUID().uuidString.lowercased()
        pending = Pending(id: id, owner: owner, created: now)
        return id
    }
    public func launchLifetime(id: String, owner: String, now: Date = Date()) -> Int {
        guard let p = pending, p.id == id, p.owner == owner else { return 0 }
        return max(0, Int((30 - now.timeIntervalSince(p.created)) * 1000))
    }
    public func consume(id: String, owner: String, now: Date = Date()) -> Bool {
        expire(now)
        guard let p = pending, p.id == id.lowercased(), p.owner == owner, p.delivered else { return false }
        pending = nil
        return true
    }
    public func cancel(id: String, owner: String) {
        if pending?.id == id.lowercased(), pending?.owner == owner { pending = nil }
    }
    public func reset() { pending = nil; lastSeen.removeAll() }
}
