import Foundation
import Security
import CryptoKit

public struct TLSIdentity: @unchecked Sendable {
    public let identity: SecIdentity
    public let certificateDER: Data
    public var fingerprint: String { SHA256.hash(data: certificateDER).map { String(format: "%02x", $0) }.joined() }
    public init(key: SecKey, certificateDER: Data) throws {
        guard let cert = SecCertificateCreateWithData(nil, certificateDER as CFData), let identity = SecIdentityCreate(nil, cert, key) else { throw VaultError.status(errSecDecode) }
        self.identity = identity; self.certificateDER = certificateDER
    }
    private struct Stored: Codable { let key: Data; let certificate: Data }
    public static func load(vault: any CredentialVault) throws -> TLSIdentity {
        if let bytes = try vault.read() {
            let stored = try JSONDecoder().decode(Stored.self, from: bytes)
            var error: Unmanaged<CFError>?
            guard let key = SecKeyCreateWithData(stored.key as CFData, [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyClass: kSecAttrKeyClassPrivate] as CFDictionary, &error) else { throw VaultError.status(errSecDecode) }
            if !canonicalSerial(stored.certificate) {
                let renewed = try certificate(key: key, name: "OnDesk")
                try vault.write(JSONEncoder().encode(Stored(key: stored.key, certificate: renewed)))
                return try TLSIdentity(key: key, certificateDER: renewed)
            }
            return try TLSIdentity(key: key, certificateDER: stored.certificate)
        }
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits: 256] as CFDictionary, &error),
              let raw = SecKeyCopyExternalRepresentation(key, &error) as Data? else { throw VaultError.status(errSecAllocate) }
        let der = try certificate(key: key, name: "OnDesk")
        let identity = try TLSIdentity(key: key, certificateDER: der)
        try vault.write(JSONEncoder().encode(Stored(key: raw, certificate: der)))
        return identity
    }
    // Compatibility migration for pre-release certificates with a redundant
    // INTEGER sign byte. Keep the private key; replacing the certificate requires
    // a new QR pairing for any existing pin.
    private static func canonicalSerial(_ data: Data) -> Bool {
        let bytes = Array(data)
        func content(_ start: Int) -> (Int, Int)? {
            guard start >= 0, start + 1 < bytes.count else { return nil }
            let first = Int(bytes[start + 1]), count = first & 127
            if first < 128 {
                let end = start + 2 + first
                return end <= bytes.count ? (start + 2, end) : nil
            }
            guard count > 0, count <= 4, start + 2 + count <= bytes.count else { return nil }
            var length = 0
            for index in 0..<count { length = (length << 8) | Int(bytes[start + 2 + index]) }
            let begin = start + 2 + count, end = begin + length
            return end <= bytes.count ? (begin, end) : nil
        }
        guard let outer = content(0), let tbs = content(outer.0), let version = content(tbs.0),
              version.1 < bytes.count, bytes[version.1] == 2, let serial = content(version.1), serial.0 < serial.1 else { return false }
        return bytes[serial.0] < 128 && !(serial.1 - serial.0 > 1 && bytes[serial.0] == 0 && bytes[serial.0 + 1] < 128)
    }
    public static func certificate(key: SecKey, name: String, now: Date = Date()) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let publicKey = SecKeyCopyPublicKey(key), let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else { throw VaultError.status(errSecParam) }
        let signatureAlgorithm = DER.sequence(DER.oid([0x2a,0x86,0x48,0xce,0x3d,0x04,0x03,0x02]))
        let subject = DER.sequence(DER.tag(0x31, DER.sequence(DER.oid([0x55,0x04,0x03]) + DER.tag(0x0c, Data(name.utf8)))))
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX"); format.timeZone = TimeZone(secondsFromGMT: 0); format.dateFormat = "yyyyMMddHHmmss'Z'"
        let validity = DER.sequence(DER.tag(0x18, Data(format.string(from: now.addingTimeInterval(-3600)).utf8)) + DER.tag(0x18, Data(format.string(from: now.addingTimeInterval(10 * 365 * 86400)).utf8)))
        let spki = DER.sequence(DER.sequence(DER.oid([0x2a,0x86,0x48,0xce,0x3d,0x02,0x01]) + DER.oid([0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07])) + DER.tag(0x03, Data([0]) + point))
        let constraints = DER.sequence(DER.oid([0x55,0x1d,0x13]) + DER.tag(0x01, Data([0xff])) + DER.tag(0x04, DER.sequence(Data())))
        let extendedUsage = DER.sequence(DER.oid([0x55,0x1d,0x25]) + DER.tag(0x04, DER.sequence(DER.oid([0x2b,0x06,0x01,0x05,0x05,0x07,0x03,0x01]))))
        var serial = Data(SHA256.hash(data: point).prefix(16))
        while serial.count > 1 && serial.first == 0 { serial.removeFirst() }
        if let first = serial.first, first & 0x80 != 0 { serial.insert(0, at: serial.startIndex) }
        let tbs = DER.sequence(DER.tag(0xa0, DER.tag(0x02, Data([2]))) + DER.tag(0x02, serial) + signatureAlgorithm + subject + validity + subject + spki + DER.tag(0xa3, DER.sequence(constraints + extendedUsage)))
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) as Data? else { throw VaultError.status(errSecInternalError) }
        return DER.sequence(tbs + signatureAlgorithm + DER.tag(0x03, Data([0]) + signature))
    }
}
private enum DER {
    static func tag(_ tag: UInt8, _ bytes: Data) -> Data {
        var result = Data([tag])
        if bytes.count < 128 { result.append(UInt8(bytes.count)) }
        else {
            var value = bytes.count, octets = [UInt8]()
            while value > 0 { octets.insert(UInt8(value & 255), at: 0); value >>= 8 }
            result.append(0x80 | UInt8(octets.count)); result.append(contentsOf: octets)
        }
        result.append(bytes); return result
    }
    static func sequence(_ bytes: Data) -> Data { tag(0x30, bytes) }
    static func oid(_ bytes: [UInt8]) -> Data { tag(0x06, Data(bytes)) }
}
