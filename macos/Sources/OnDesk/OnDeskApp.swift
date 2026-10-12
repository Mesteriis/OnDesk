import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import OnDeskCore
import OnDeskMac

@main struct OnDeskApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel()
    var body: some Scene {
        MenuBarExtra {
            Text(model.status)
            Divider()
            Button("Снять на ПК    ⌘⇧1") { Task { await model.takePhoto() } }.disabled(!model.ready || model.peers.isEmpty)
            Button("Открыть папку OnDesk") { model.openFolder() }
            OpenSettingsButton()
            Divider()
            Button("Завершить OnDesk") { NSApplication.shared.terminate(nil) }
        } label: {
            MenuBarLabel()
        }
        Settings { SettingsView(model: model) }
    }
}
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) { NSApp.setActivationPolicy(.accessory) }
}
struct SettingsView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        TabView {
            connection.tabItem { Label("Подключение", systemImage: "link") }
            folders.tabItem { Label("Папки", systemImage: "folder") }
            general.tabItem { Label("Основные", systemImage: "gearshape") }
            NotificationsSettings(notifications: model.notifications).tabItem { Label("Уведомления", systemImage: "bell") }
        }
        .padding(20).frame(width: 580, height: 510)
        .alert("OnDesk", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .onAppear { NSApp.activate(ignoringOtherApps: true) }
    }
    private var connection: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Image(systemName: "tray.and.arrow.down.fill").font(.largeTitle).foregroundStyle(.tint)
                VStack(alignment: .leading) { Text("OnDesk").font(.title2.bold()); Text(model.status).foregroundStyle(.secondary).lineLimit(2) }
                Spacer()
                Circle().fill(model.ready ? .green : .orange).frame(width: 9, height: 9).accessibilityLabel(model.ready ? "Приёмник работает" : "Приёмник недоступен")
            }
            Divider()
            if let invitation = model.invitation {
                HStack(spacing: 20) {
                    QRImage(value: invitation).frame(width: 210, height: 210)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Подключите телефон").font(.headline)
                        Text("Откройте OnDesk на Android и отсканируйте код. Оба устройства должны находиться в одной сети.").foregroundStyle(.secondary)
                        if let expiry = model.expiresAt { Text("Код действует до \(expiry.formatted(date: .omitted, time: .shortened))").font(.caption) }
                        Button("Обновить код") { Task { await model.makeInvitation() } }
                    }
                }
            } else {
                Text("Отправляйте файлы с телефона прямо на этот Mac через «Поделиться».").foregroundStyle(.secondary)
                Button("Сопрячь телефон", systemImage: "qrcode") { Task { await model.makeInvitation() } }.disabled(!model.ready)
            }
            Text("Подключённые телефоны").font(.headline)
            if model.peers.isEmpty { Text("Пока нет подключённых телефонов").foregroundStyle(.secondary) }
            ScrollView {
                VStack(spacing: 8) {
                    ForEach(model.peers) { peer in
                        HStack { Image(systemName: "iphone"); Text(peer.name); Spacer(); Button("Отозвать доступ", role: .destructive) { Task { await model.revoke(peer) } } }
                    }
                }
            }
            Spacer(minLength: 0)
        }.padding(.top, 16)
    }
    private var folders: some View {
        ScrollView { VStack(alignment: .leading, spacing: 16) {
            Text("Куда сохранять файлы").font(.title2.bold())
            Text("OnDesk выбирает папку по типу файла. Совпадающие имена получают числовой суффикс.").foregroundStyle(.secondary)
            ForEach(FileCategory.allCases, id: \.self) { category in
                HStack(spacing: 12) {
                    Image(systemName: category.symbol).frame(width: 22).foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 3) { Text(category.title); Text(model.folders[category]?.path ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle) }
                    Spacer(); Button("Выбрать…") { model.chooseFolder(category) }
                }
            }
            Divider()
            Text("Автоочистка изображений").font(.headline)
            Toggle("Перемещать старые изображения в Корзину", isOn: Binding(get: { model.cleanupEnabled }, set: { model.configureCleanup(enabled: $0, days: model.cleanupDays) }))
            Stepper("Хранить дней: \(model.cleanupDays)", value: Binding(get: { model.cleanupDays }, set: { model.configureCleanup(enabled: model.cleanupEnabled, days: $0) }), in: 1...365)
            Text("Все изображения в выбранной выше папке старше указанного срока по дате изменения. Вложенные папки и ссылки пропускаются. Проверка при запуске и раз в час.").font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("Проверить сейчас") { model.cleanImages() }.disabled(!model.cleanupEnabled)
                Text(model.cleanupStatus).font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.top, 16) }
    }
    private var general: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Незаметно, пока не понадобится").font(.title2.bold())
            Toggle("Запускать OnDesk при входе", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLogin($0) }))
            Text("Приёмник работает, пока OnDesk запущен и Mac не спит. Файлы ожидают на телефоне, если компьютер недоступен.").foregroundStyle(.secondary)
            Text("⌘⇧1 — снять на телефоне и скопировать фото для ⌘V").font(.headline)
            if !model.hotKeyReady { Text("⌘⇧1 занято другим приложением. Используйте «Снять на ПК» в меню OnDesk.").foregroundStyle(.orange) }
            if model.peers.count > 1 {
                Picker("Телефон для камеры", selection: $model.cameraPhone) {
                    Text("Выберите телефон").tag("")
                    ForEach(model.peers) { Text($0.name).tag($0.id) }
                }.onChange(of: model.cameraPhone) { _, value in UserDefaults.standard.set(value, forKey: "cameraPhone") }
            }
            Text("Разблокируйте телефон. Требуется включённая служба OnDesk. После подтверждения фото сохранится в папке изображений и появится в буфере обмена.").font(.caption).foregroundStyle(.secondary)
            Button("Перезапустить приёмник") { Task { await model.restart() } }
            Divider()
            Text("OnDesk \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · Только локальная сеть").font(.caption).foregroundStyle(.secondary)
            Spacer()
        }.padding(.top, 16)
    }
}
private struct NotificationsSettings: View {
    @ObservedObject var notifications: FileNotifications
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Полученные файлы").font(.title2.bold())
            Toggle("Показывать уведомления о получении", isOn: Binding(get: { notifications.enabled }, set: { value in Task { await notifications.setEnabled(value) } }))
            Toggle("Со звуком", isOn: $notifications.sound).disabled(!notifications.enabled)
            Text("После сохранения файла macOS покажет уведомление. Нажмите «Открыть» или «Показать в Finder». Нажатие на само уведомление тоже открывает файл.").foregroundStyle(.secondary)
            Text(notifications.status).font(.caption).foregroundStyle(.secondary)
            Button("Разрешение macOS…") { Task { await notifications.refresh(requestPermission: true) } }.disabled(!notifications.enabled)
            Button("Открыть системные настройки уведомлений") {
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.notifications")!)
            }
            Spacer()
        }.padding(.top, 16).task { await notifications.refresh() }
    }
}
private struct QRImage: View {
    let value: String
    var body: some View {
        if let image = image {
            Image(nsImage: image).interpolation(.none).resizable().scaledToFit().padding(10).background(.white).clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityLabel("QR-код для сопряжения телефона")
        } else { Text("Не удалось создать QR-код") }
    }
    private var image: NSImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output.transformed(by: .init(scaleX: 8, y: 8)), from: output.extent.applying(.init(scaleX: 8, y: 8))) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }
}

private struct MenuBarLabel: View {
    @Environment(\.openSettings) private var openSettings
    var body: some View {
        Image(nsImage: MenuIcon.image).accessibilityLabel("OnDesk")
            .task {
                if CommandLine.arguments.contains("--settings") || !UserDefaults.standard.bool(forKey: "didShowSetup") {
                    UserDefaults.standard.set(true, forKey: "didShowSetup")
                    NSApp.activate(ignoringOtherApps: true)
                    openSettings()
                }
            }
    }
}

private struct OpenSettingsButton: View {
    @Environment(\.openSettings) private var openSettings
    var body: some View {
        Button("Настройки…") {
            NSApp.activate(ignoringOtherApps: true)
            openSettings()
        }
    }
}
