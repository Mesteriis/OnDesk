import AppKit
import Combine
import UserNotifications
import OnDeskCore

@MainActor public final class FileNotifications: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    public static let category = "ondesk.received-file"
    public static let openAction = "ondesk.open-file"
    public static let revealAction = "ondesk.reveal-file"
    @Published public private(set) var enabled: Bool
    @Published public var sound: Bool {
        didSet { UserDefaults.standard.set(sound, forKey: "notifications.sound") }
    }
    @Published public private(set) var status = "Проверка разрешения…"
    private let center = UNUserNotificationCenter.current()
    private let root: URL

    public override init() {
        enabled = UserDefaults.standard.object(forKey: "notifications.enabled") as? Bool ?? true
        sound = UserDefaults.standard.object(forKey: "notifications.sound") as? Bool ?? true
        root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OnDesk/transfers")
        super.init()
        center.delegate = self
        let actions = [
            UNNotificationAction(identifier: Self.openAction, title: "Открыть", options: [.foreground]),
            UNNotificationAction(identifier: Self.revealAction, title: "Показать в Finder", options: [.foreground])
        ]
        center.setNotificationCategories([UNNotificationCategory(identifier: Self.category, actions: actions, intentIdentifiers: [])])
    }
    public func refresh(requestPermission: Bool = false) async {
        var settings = await center.notificationSettings()
        if enabled && requestPermission && settings.authorizationStatus == .notDetermined {
            do { _ = try await center.requestAuthorization(options: [.alert, .sound]) }
            catch { status = "Не удалось запросить разрешение на уведомления."; return }
            settings = await center.notificationSettings()
        }
        if !enabled { status = "Уведомления выключены" }
        else {
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: status = "Уведомления разрешены macOS"
            case .denied: status = "Уведомления запрещены. Разрешите OnDesk в Системных настройках → Уведомления."
            default: status = "Требуется разрешение macOS"
            }
        }
    }
    public func setEnabled(_ value: Bool) async {
        enabled = value
        UserDefaults.standard.set(value, forKey: "notifications.enabled")
        await refresh(requestPermission: value)
    }
    public static func request(id: String, owner: String, file: URL, sound: Bool) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "Файл получен"
        content.body = file.lastPathComponent
        content.categoryIdentifier = category
        content.threadIdentifier = "ondesk.received-files"
        content.userInfo = ["transferID": id, "owner": owner]
        if sound { content.sound = .default }
        return UNNotificationRequest(identifier: "ondesk.file.\(id.lowercased())", content: content, trigger: nil)
    }
    public func received(id: String, owner: String, file: URL) async {
        guard enabled else { return }
        do { try await center.add(Self.request(id: id, owner: owner, file: file, sound: sound)) }
        catch { status = "Файл сохранён, но macOS не приняла уведомление." }
    }
    // Resolve only durable, owner-checked receipts; notification payloads cannot supply paths.
    public static func destination(action: String, id: String, owner: String, store: TransferStore) async throws -> URL? {
        guard [openAction, revealAction, UNNotificationDefaultActionIdentifier].contains(action) else { return nil }
        let file = try await store.completedFile(id: id, owner: owner)
        let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .isAliasFileKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true, values.isAliasFile != true else { throw TransferError.invalid }
        return file
    }
    nonisolated public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void) {
        Task { @MainActor in completionHandler(enabled ? [.banner, .list, .sound] : []) }
    }
    nonisolated public func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping @Sendable () -> Void) {
        let info = response.notification.request.content.userInfo
        let id = info["transferID"] as? String, owner = info["owner"] as? String
        let action = response.actionIdentifier
        Task { @MainActor in
            defer { completionHandler() }
            guard let id, let owner else { return }
            do {
                let store = try TransferStore(root: root, folders: [:])
                guard let file = try await Self.destination(action: action, id: id, owner: owner, store: store) else { return }
                if action == Self.revealAction { NSWorkspace.shared.activateFileViewerSelecting([file]) }
                else if !NSWorkspace.shared.open(file) { status = "Не удалось открыть полученный файл." }
            } catch {
                status = "Полученный файл недоступен: возможно, он перемещён или удалён."
                let alert = NSAlert()
                alert.messageText = "Файл недоступен"
                alert.informativeText = "Возможно, файл перемещён, удалён или очищен по сроку хранения."
                alert.addButton(withTitle: "OK")
                NSApp.activate(ignoringOtherApps: true)
                alert.runModal()
            }
        }
    }
}
