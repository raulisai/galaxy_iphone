import Foundation
import Security
import WristBridgeProtocol

/// Keychain storage for the own-channel identity (draft 8.6): this iPhone's static key and the
/// pinned watch key. `AfterFirstUnlockThisDeviceOnly`, no iCloud sync, so the link can reconnect
/// in the background with the phone locked and the keys never leave this device.
/// Nothing here is ever logged or shared.
struct P2IdentityStore {
    private let service = "dev.wristbridge.p2"
    private enum Account: String { case localStatic = "local-static", pinnedWatch = "pinned-watch" }

    private func query(_ account: Account) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account.rawValue,
         kSecAttrSynchronizable as String: false]
    }
    private func read(_ account: Account) -> [UInt8]? {
        var q = query(account)
        q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return Array(data)
    }
    @discardableResult private func write(_ account: Account, _ bytes: [UInt8]) -> Bool {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = Data(bytes)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    /// This iPhone's static key; created on first use.
    func localKey() throws -> P2StaticKey {
        if let raw = read(.localStatic), let key = try? P2StaticKey(rawPrivate: raw) { return key }
        let key = P2StaticKey.generate()
        guard write(.localStatic, key.rawPrivate) else { throw P2Error.notAuthorized }
        return key
    }
    var pinnedWatch: [UInt8]? { read(.pinnedWatch) }
    @discardableResult func pinWatch(_ publicKey: [UInt8]) -> Bool { write(.pinnedWatch, publicKey) }
    /// "Olvidar reloj": drops the pinned watch key and rotates our own key (a new registration is required).
    func forget() {
        SecItemDelete(query(.pinnedWatch) as CFDictionary)
        SecItemDelete(query(.localStatic) as CFDictionary)
    }
}
