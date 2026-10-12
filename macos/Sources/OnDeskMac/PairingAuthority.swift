import Foundation
import Security
import OnDeskCore

public protocol CredentialVault: Sendable {
    func read() throws -> Data?
    func write(_ data: Data) throws
}
public struct KeychainVault: CredentialVault {
    private let account: String
    public init(account: String) { self.account = account }
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.ondesk", kSecAttrAccount as String: account] }
    public func read() throws -> Data? {
        var q = query; q[kSecReturnData as String] = true
        var item: CFTypeRef?
        let result = SecItemCopyMatching(q as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess else { throw VaultError.status(result) }
        return item as? Data
    }
    public func write(_ data: Data) throws {
        let result = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if result == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(q as CFDictionary, nil)
            guard added == errSecSuccess else { throw VaultError.status(added) }
        } else if result != errSecSuccess { throw VaultError.status(result) }
    }
}
public enum VaultError: Error { case status(OSStatus), random }
public func randomSecret() throws -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw VaultError.random }
    return bytes.map { String(format: "%02x", $0) }.joined()
}
public struct Peer: Codable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let token: String
}
public actor PairingAuthority {
    private let vault: any CredentialVault
    private var devices: [Peer]
    private var invitation: (secret: String, expiry: Date)?
    public init(vault: any CredentialVault) throws {
        self.vault = vault
        devices = try vault.read().map { try JSONDecoder().decode([Peer].self, from: $0) } ?? []
    }
    public func challenge(now: Date = Date()) throws -> String {
        let secret = try randomSecret()
        invitation = (secret, now.addingTimeInterval(300))
        return secret
    }
    public func hasChallenge() -> Bool { invitation.map { Date() < $0.expiry } ?? false }
    public func cancelChallenge() { invitation = nil }
    public func pair(secret: String, deviceID: String, name: String, now: Date = Date()) throws -> String {
        guard let invitation, now < invitation.expiry, equal(secret, invitation.secret) else { throw TransferError.unauthorized }
        guard UUID(uuidString: deviceID) != nil, !name.isEmpty, name.utf8.count <= 120, devices.count < 32 || devices.contains(where: { $0.id == deviceID }) else { throw TransferError.invalid }
        let token = try randomSecret()
        var updated = devices.filter { $0.id != deviceID }
        updated.append(Peer(id: deviceID, name: name, token: token))
        try vault.write(JSONEncoder().encode(updated))
        devices = updated; self.invitation = nil
        return token
    }
    public func owner(token: String) throws -> String {
        guard token.utf8.count == 64, let peer = devices.first(where: { equal(token, $0.token) }) else { throw TransferError.unauthorized }
        return peer.id
    }
    public func revoke(_ id: String) throws {
        let updated = devices.filter { $0.id != id }
        try vault.write(JSONEncoder().encode(updated)); devices = updated
    }
    public func peers() -> [Peer] { devices }
    private func equal(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
