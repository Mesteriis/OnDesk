import Foundation
public struct WireRequest: Codable, Sendable {
    public var version = 1
    public var op: String
    public var token: String?
    public var deviceID: String?
    public var deviceName: String?
    public var secret: String?
    public var transfer: TransferMetadata?
    public var id: String?
    public var url: String?
    public var offset: Int64?
    public var payloadLength: Int?
    public init(op: String) { self.op = op }
}
public struct WireReply: Codable, Sendable {
    public var version = 1
    public var ok = true
    public var error: String?
    public var token: String?
    public var serverID: String?
    public var offset: Int64?
    public var complete: Bool?
    public var savedName: String?
    public var opened: Bool?
    public var cameraID: String?
    public var cameraTTLMillis: Int?
    public init() {}
    public init(status: TransferStatus) { offset = status.offset; complete = status.complete; savedName = status.savedName }
    public static func failure(_ error: String) -> WireReply { var reply = WireReply(); reply.ok = false; reply.error = error; return reply }
}
public enum WireFrame {
    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let body = try JSONEncoder().encode(value)
        guard (1...16384).contains(body.count) else { throw TransferError.invalid }
        var size = UInt32(body.count).bigEndian
        return withUnsafeBytes(of: &size) { Data($0) } + body
    }
    public static func length(_ prefix: Data) throws -> Int {
        guard prefix.count == 4 else { throw TransferError.invalid }
        let count = prefix.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard (1...16384).contains(count) else { throw TransferError.invalid }
        return Int(count)
    }
}
