import AppKit
import SwiftUI
import Combine
import ServiceManagement
import Network
import ImageIO
import UniformTypeIdentifiers
import OnDeskCore
import OnDeskMac

@MainActor final class AppModel: ObservableObject {
    @Published var status = "Запуск…"
    @Published var peers: [Peer] = []
    @Published var invitation: String?
    @Published var expiresAt: Date?
    @Published var folders: [FileCategory: URL] = [:]
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled
    @Published var error: String?
    @Published var ready = false
    @Published var cameraPhone = UserDefaults.standard.string(forKey: "cameraPhone") ?? ""
    @Published var hotKeyReady = false
    @Published var cleanupEnabled = UserDefaults.standard.bool(forKey: "cleanup.images.enabled")
    @Published var cleanupDays = max(1, min(365, UserDefaults.standard.object(forKey: "cleanup.images.days") as? Int ?? 3))
    @Published var cleanupStatus = "Очистка выключена"
    let notifications = FileNotifications()
    private let imageCleanup = ImageCleanup()
    private var cleanupTimer: Task<Void, Never>?
    private var cleanupRun: Task<Void, Never>?
    private var hotKey: CameraHotKey?
    private var receiver: Receiver?
    private var starting = false
    private var authority: PairingAuthority?
    private var store: TransferStore?
    private var identity: TLSIdentity?
    private var invitationTimer: Task<Void, Never>?
    private var peerTimer: Task<Void, Never>?
    private var port: UInt16 = 0
    let serverID: String
    init() {
        let defaults = UserDefaults.standard
        serverID = defaults.string(forKey: "serverID") ?? UUID().uuidString
        defaults.set(serverID, forKey: "serverID")
        for category in FileCategory.allCases {
            folders[category] = defaults.string(forKey: "folder.\(category.rawValue)").map { URL(fileURLWithPath: $0) } ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/OnDesk/\(category.title)")
        }
        hotKey = CameraHotKey { [weak self] in Task { await self?.takePhoto() } }
        hotKeyReady = hotKey?.register() ?? false
        Task { await start() }
        Task { await notifications.refresh(requestPermission: true) }
        cleanupTimer = Task { [weak self] in
            self?.cleanImages()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3600))
                guard !Task.isCancelled else { break }
                self?.cleanImages()
            }
        }
    }
    func start() async {
        guard receiver == nil, !starting else { return }
        starting = true
        defer { starting = false }
        status = "Загрузка сопряжения из связки ключей…"
        do {
            let (identity, authority) = try await Task.detached {
                let identity = try TLSIdentity.load(vault: KeychainVault(account: "tls-identity"))
                let authority = try PairingAuthority(vault: KeychainVault(account: "phones"))
                return (identity, authority)
            }.value
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OnDesk/transfers")
            let store = try TransferStore(root: root, folders: folders)
            let links = try LinkStore(root: root.deletingLastPathComponent().appendingPathComponent("links"))
            let receiver = Receiver(identity: identity, serverID: serverID, authority: authority, store: store, links: links, openLink: { url in
                await MainActor.run { NSWorkspace.shared.open(url) }
            }, copyPhoto: { url in
                await MainActor.run { Self.copyPhoto(url) }
            }, receivedFile: { [notifications] id, owner, url in
                await notifications.received(id: id, owner: owner, file: url)
            }) { [weak self] message in
                Task { @MainActor in
                    guard let self else { return }
                    self.status = message
                    self.ready = await self.receiver?.isRunning ?? false
                    if !self.ready { self.clearInvitation(); await self.authority?.cancelChallenge() }
                    await self.refreshPeers()
                }
            }
            self.identity = identity; self.authority = authority; self.store = store; self.receiver = receiver
            port = try await receiver.start()
            ready = true
            await refreshPeers()
            peerTimer?.cancel()
            peerTimer = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(2))
                    if Task.isCancelled { break }
                    await self?.refreshPeers()
                }
            }
        } catch {
            receiver = nil; ready = false; status = "Приёмник недоступен"
            self.error = "Не удалось запустить OnDesk. Проверьте доступ к связке ключей и локальной сети."
        }
    }
    func configureCleanup(enabled: Bool, days: Int) {
        cleanupEnabled = enabled; cleanupDays = max(1, min(365, days))
        UserDefaults.standard.set(cleanupEnabled, forKey: "cleanup.images.enabled")
        UserDefaults.standard.set(cleanupDays, forKey: "cleanup.images.days")
        cleanupRun?.cancel()
        if enabled { cleanImages() } else { cleanupStatus = "Очистка выключена" }
    }
    func cleanImages() {
        guard cleanupEnabled, let folder = folders[.images] else { return }
        cleanupRun?.cancel()
        let days = cleanupDays
        cleanupStatus = "Проверка изображений…"
        cleanupRun = Task { [weak self, imageCleanup] in
            do {
                let count = try await imageCleanup.run(folder: folder, days: days)
                guard !Task.isCancelled else { return }
                self?.cleanupStatus = count == 0 ? "Проверено: просроченных изображений нет" : "Перемещено в Корзину: \(count)"
            } catch is CancellationError { }
            catch { if !Task.isCancelled { self?.cleanupStatus = "Не удалось завершить очистку. Проверьте доступ к папке и Корзине." } }
        }
    }
    func takePhoto() async {
        guard let receiver, ready else { status = "Приёмник недоступен"; return }
        let phone = peers.count == 1 ? peers.first?.id : peers.first(where: { $0.id == cameraPhone })?.id
        guard let phone else { status = "Выберите телефон для камеры в настройках"; return }
        do {
            _ = try await receiver.cameras.request(owner: phone)
            status = "Сделайте фото на телефоне и подтвердите снимок"
        } catch TransferError.conflict { status = "Съёмка уже запрошена. Завершите или отмените её на телефоне." }
        catch { status = "Телефон недоступен. Разблокируйте его и включите службу OnDesk." }
    }
    private static func copyPhoto(_ url: URL) -> Bool {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, Int64(width) * Int64(height) <= 100_000_000,
              let image = NSImage(contentsOf: url), let data = image.tiffRepresentation else { return false }
        let item = NSPasteboardItem()
        item.setData(data, forType: .tiff)
        NSPasteboard.general.clearContents()
        return NSPasteboard.general.writeObjects([item])
    }
    func restart() async {
        await receiver?.stop(); receiver = nil; ready = false
        await start()
    }
    func refreshPeers() async {
        guard let authority else { return }
        peers = await authority.peers()
        if invitation != nil, peers.count > 0 {
            // The invitation is single-use; a consumed challenge is never shown again.
            if !(await authority.hasChallenge()) { clearInvitation() }
        }
    }
    func makeInvitation() async {
        guard let authority, let identity, ready else { return }
        do {
            let secret = try await authority.challenge()
            let host = Host.current().localizedName.map { _ in ProcessInfo.processInfo.hostName } ?? "localhost"
            let payload = PairingQR(version: 1, serverID: serverID, host: host, port: Int(port), fingerprint: identity.fingerprint, secret: secret)
            invitation = String(data: try JSONEncoder().encode(payload), encoding: .utf8)
            expiresAt = Date().addingTimeInterval(300)
            invitationTimer?.cancel()
            invitationTimer = Task { [weak self] in
                try? await Task.sleep(for: .seconds(300))
                guard !Task.isCancelled else { return }
                await self?.authority?.cancelChallenge(); self?.clearInvitation()
            }
        } catch { self.error = "Не удалось создать код сопряжения." }
    }
    func clearInvitation() { invitation = nil; expiresAt = nil; invitationTimer?.cancel() }
    func revoke(_ peer: Peer) async {
        do { try await authority?.revoke(peer.id); await refreshPeers() }
        catch { self.error = "Не удалось отозвать доступ телефона." }
    }
    func chooseFolder(_ category: FileCategory) {
        let panel = NSOpenPanel(); panel.canChooseFiles = false; panel.canChooseDirectories = true; panel.canCreateDirectories = true
        panel.directoryURL = folders[category]; panel.prompt = "Выбрать"
        if panel.runModal() == .OK, let url = panel.url {
            folders[category] = url
            UserDefaults.standard.set(url.path, forKey: "folder.\(category.rawValue)")
            if category == .images { cleanImages() }
            let snapshot = folders
            Task { await store?.setFolders(snapshot) }
        }
    }
    func setLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            launchAtLogin = SMAppService.mainApp.status == .enabled
            if enabled && !launchAtLogin { error = "Разрешите запуск OnDesk в системных настройках → Объекты входа." }
        } catch { launchAtLogin = SMAppService.mainApp.status == .enabled; self.error = "Не удалось изменить запуск при входе." }
    }
    func openFolder() {
        let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/OnDesk")
        do { try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); NSWorkspace.shared.open(root) }
        catch { self.error = "Не удалось открыть папку OnDesk." }
    }
}
struct PairingQR: Encodable {
    let version: Int; let serverID: String; let host: String; let port: Int; let fingerprint: String; let secret: String
}
extension FileCategory {
    var title: String {
        switch self { case .images: "Изображения"; case .videos: "Видео"; case .audio: "Аудио"; case .documents: "Документы"; case .archives: "Архивы"; case .other: "Прочее" }
    }
    var symbol: String {
        switch self { case .images: "photo"; case .videos: "film"; case .audio: "waveform"; case .documents: "doc"; case .archives: "archivebox"; case .other: "folder" }
    }
}
