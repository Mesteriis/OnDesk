import Carbon
import Foundation

/// Carbon reserves the chord globally without reading keyboard input.
@MainActor final class CameraHotKey {
    private var hotKey: EventHotKeyRef?
    private var handler: EventHandlerRef?
    private let action: () -> Void
    init(action: @escaping () -> Void) { self.action = action }
    func register() -> Bool {
        var type = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let installed = InstallEventHandler(GetApplicationEventTarget(), { _, event, context in
            guard let context, let event else { return OSStatus(eventNotHandledErr) }
            var id = EventHotKeyID()
            guard GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &id) == noErr,
                  id.signature == 0x4f44434d, id.id == 1 else { return OSStatus(eventNotHandledErr) }
            MainActor.assumeIsolated { Unmanaged<CameraHotKey>.fromOpaque(context).takeUnretainedValue().action() }
            return noErr
        }, 1, &type, Unmanaged.passUnretained(self).toOpaque(), &handler)
        guard installed == noErr else { return false }
        return RegisterEventHotKey(UInt32(kVK_ANSI_1), UInt32(cmdKey | shiftKey), EventHotKeyID(signature: 0x4f44434d, id: 1), GetApplicationEventTarget(), 0, &hotKey) == noErr
    }
    isolated deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let handler { RemoveEventHandler(handler) }
    }
}
