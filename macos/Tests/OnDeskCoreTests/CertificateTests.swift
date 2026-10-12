import XCTest
import Security
import CryptoKit
@testable import OnDeskMac

final class CertificateTests: XCTestCase {
    func testSerialIntegerUsesCanonicalDERForAndroidCompatibility() throws {
        // Conscrypt rejects redundant zero prefixes even when Security.framework accepts them.
        for scalar in UInt8(1)...UInt8(8) {
            let p256 = try P256.Signing.PrivateKey(rawRepresentation: Data(repeating: scalar, count: 32))
            let key = try XCTUnwrap(SecKeyCreateWithData(p256.x963Representation as CFData, [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyClass: kSecAttrKeyClassPrivate] as CFDictionary, nil))
            let bytes = Array(try TLSIdentity.certificate(key: key, name: "OnDesk DER test"))
            func content(_ start: Int) -> (Int, Int) {
                let lengthByte = Int(bytes[start + 1])
                if lengthByte < 128 { return (start + 2, start + 2 + lengthByte) }
                let count = lengthByte & 127
                var length = 0
                for index in 0..<count { length = (length << 8) | Int(bytes[start + 2 + index]) }
                return (start + 2 + count, start + 2 + count + length)
            }
            let outer = content(0), tbs = content(outer.0), version = content(tbs.0)
            XCTAssertEqual(bytes[version.1], 2)
            let serial = content(version.1)
            let integer = Array(bytes[serial.0..<serial.1])
            XCTAssertFalse(integer.count > 1 && integer[0] == 0 && integer[1] < 128, "Non-canonical positive INTEGER")
            XCTAssertLessThan(integer[0], 128, "Negative serial number")
        }
    }
    func testGeneratedCertificateContainsMatchingKeyAndValidSignature() throws {
        var error: Unmanaged<CFError>?
        let key = try XCTUnwrap(SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits: 256] as CFDictionary, &error))
        let bytes = try TLSIdentity.certificate(key: key, name: "OnDesk Test")
        let cert = try XCTUnwrap(SecCertificateCreateWithData(nil, bytes as CFData))
        XCTAssertEqual(SecCertificateCopySubjectSummary(cert) as String?, "OnDesk Test")
        XCTAssertNotNil(SecIdentityCreate(nil, cert, key))
        var trust: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(cert, SecPolicyCreateBasicX509(), &trust), errSecSuccess)
        let value = try XCTUnwrap(trust)
        SecTrustSetAnchorCertificates(value, [cert] as CFArray)
        SecTrustSetAnchorCertificatesOnly(value, true)
        XCTAssertTrue(SecTrustEvaluateWithError(value, nil))
    }
}
