import Foundation
import Network
import Security
import OnDeskCore

public actor Receiver {
    private let identity: TLSIdentity
    private let serverID: String
    private let authority: PairingAuthority
    private let store: TransferStore
    private let links: LinkStore?
    private let openLink: @Sendable (URL) async throws -> Bool
    public let cameras = CameraRequests()
    private let copyPhoto: @Sendable (URL) async -> Bool
    private let receivedFile: @Sendable (String, String, URL) async -> Void
    private let event: @Sendable (String) -> Void
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private let queue = DispatchQueue(label: "app.ondesk.receiver")
    public init(identity: TLSIdentity, serverID: String, authority: PairingAuthority, store: TransferStore, links: LinkStore? = nil, openLink: @escaping @Sendable (URL) async throws -> Bool = { _ in false }, copyPhoto: @escaping @Sendable (URL) async -> Bool = { _ in false }, receivedFile: @escaping @Sendable (String, String, URL) async -> Void = { _, _, _ in }, event: @escaping @Sendable (String) -> Void = { _ in }) {
        self.identity = identity; self.serverID = serverID; self.authority = authority; self.store = store; self.event = event
        self.links = links; self.openLink = openLink; self.copyPhoto = copyPhoto
        self.receivedFile = receivedFile
    }
    public var isRunning: Bool {
        guard let listener else { return false }
        if case .ready = listener.state { return true }
        return false
    }
    public func start(port: UInt16 = 0, localOnly: Bool = false) async throws -> UInt16 {
        if let listener, let port = listener.port { return port.rawValue }
        let tls = NWProtocolTLS.Options()
        guard let native = sec_identity_create(identity.identity) else { throw TransferError.storage }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, native)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        let parameters = NWParameters(tls: tls)
        parameters.allowLocalEndpointReuse = true
        if localOnly { parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port) ?? .any) }
        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port) ?? .any)
        if !localOnly { listener.service = NWListener.Service(name: serverID, type: "_ondesk._tcp.") }
        listener.newConnectionHandler = { [weak self] connection in
            Task { await self?.accept(connection) }
        }
        self.listener = listener
        do {
            let value: UInt16 = try await withCheckedThrowingContinuation { continuation in
                let gate = ListenerGate(continuation)
                listener.stateUpdateHandler = { state in
                    switch state {
                    case .ready: gate.succeed(listener.port?.rawValue ?? 0)
                    case .failed(let error): gate.fail(error)
                    case .cancelled: gate.fail(TransferError.storage)
                    default: break
                    }
                }
                listener.start(queue: queue)
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed = state { Task { await self?.listenerFailed() } }
            }
            event("Готов к приёму")
            return value
        } catch {
            listener.cancel(); self.listener = nil; throw error
        }
    }
    private func listenerFailed() async {
        event("Ошибка локальной сети. Перезапустите приёмник в настройках.")
        await stop()
    }
    public func stop() async {
        await cameras.reset()
        listener?.cancel(); listener = nil
        for connection in connections.values { connection.cancel() }
        connections.removeAll()
    }
    private func accept(_ connection: NWConnection) {
        guard connections.count < 8 else { connection.cancel(); return }
        let id = UUID(); connections[id] = connection
        connection.start(queue: queue)
        Task {
            await serve(connection)
            connection.cancel()
            connections.removeValue(forKey: id)
        }
    }
    private func serve(_ connection: NWConnection) async {
        while !Task.isCancelled {
            do {
                let prefix = try await NetworkIO.read(connection, count: 4)
                let count = try WireFrame.length(prefix)
                let request = try JSONDecoder().decode(WireRequest.self, from: await NetworkIO.read(connection, count: count))
                guard request.version == 1 else { try await NetworkIO.send(connection, reply: .failure("incompatible")); return }
                let payloadLength = request.payloadLength ?? 0
                guard payloadLength >= 0, payloadLength <= 4 * 1024 * 1024, request.op == "chunk" || payloadLength == 0 else { throw TransferError.invalid }
                let reply: WireReply
                if request.op == "pair" {
                    guard let secret = request.secret, let id = request.deviceID, let name = request.deviceName else { throw TransferError.invalid }
                    var paired = WireReply()
                    paired.token = try await authority.pair(secret: secret, deviceID: id, name: name)
                    paired.serverID = serverID
                    reply = paired; event("Телефон сопряжён")
                } else {
                    let owner = try await authority.owner(token: request.token ?? "")
                    switch request.op {
                    case "camera_poll":
                        var result = WireReply()
                        result.cameraID = await cameras.poll(owner: owner)
                        if let id = result.cameraID { result.cameraTTLMillis = await cameras.launchLifetime(id: id, owner: owner) }
                        reply = result
                    case "camera_cancel":
                        guard let id = request.id else { throw TransferError.invalid }
                        await cameras.cancel(id: id, owner: owner)
                        reply = WireReply()
                        event("Съёмка отменена на телефоне")
                    case "open_link":
                        guard let links else { try await NetworkIO.send(connection, reply: .failure("incompatible")); return }
                        guard let id = request.id, let url = request.url else { throw LinkError.invalid }
                        try await links.open(id: id, url: url, owner: owner, using: openLink)
                        var result = WireReply(); result.complete = true; result.opened = true
                        reply = result; event("Ссылка передана браузеру")
                    case "begin":
                        guard let metadata = request.transfer else { throw TransferError.invalid }
                        reply = WireReply(status: try await store.begin(metadata, owner: owner))
                        event("Приём: \(metadata.name)")
                    case "chunk":
                        guard let id = request.id, let offset = request.offset, payloadLength > 0 else { throw TransferError.invalid }
                        let data = try await NetworkIO.read(connection, count: payloadLength)
                        reply = WireReply(status: try await store.append(id: id, owner: owner, offset: offset, data: data))
                    case "finish":
                        guard let id = request.id else { throw TransferError.invalid }
                        let result = try await store.finish(id: id, owner: owner)
                        reply = WireReply(status: result)
                        if (try? await authority.owner(token: request.token ?? "")) == owner, await cameras.consume(id: id, owner: owner) {
                            let photo = try? await store.completedPhoto(id: id, owner: owner)
                            let copied: Bool
                            if let photo { copied = await copyPhoto(photo) } else { copied = false }
                            event(copied ? "Фото в буфере обмена — вставьте ⌘V" : "Фото сохранено, но не удалось скопировать изображение")
                        } else { event("Сохранён: \(result.savedName ?? "файл")") }
                        // Optional desktop UI must never turn a saved file into a failed delivery.
                        if let file = try? await store.claimCompletionNotification(id: id, owner: owner) {
                            await receivedFile(id, owner, file)
                        }
                    default: throw TransferError.invalid
                    }
                }
                try await NetworkIO.send(connection, reply: reply)
            } catch {
                let code = (error as? TransferError)?.rawValue ?? (error as? LinkError)?.rawValue ?? ((error is DecodingError) ? "invalid" : "storage")
                try? await NetworkIO.send(connection, reply: .failure(code))
                return
            }
        }
    }
}
private final class ListenerGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<UInt16, Error>?
    init(_ continuation: CheckedContinuation<UInt16, Error>) { self.continuation = continuation }
    func succeed(_ port: UInt16) { lock.withLock { continuation?.resume(returning: port); continuation = nil } }
    func fail(_ error: Error) { lock.withLock { continuation?.resume(throwing: error); continuation = nil } }
}
private enum NetworkIO {
    static func read(_ connection: NWConnection, count: Int) async throws -> Data {
        let timeout = DispatchWorkItem { connection.cancel() }
        DispatchQueue.global().asyncAfter(deadline: .now() + 120, execute: timeout)
        defer { timeout.cancel() }
        var result = Data(); result.reserveCapacity(count)
        while result.count < count {
            let remaining = count - result.count
            let bytes: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, _, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else { continuation.resume(throwing: TransferError.incomplete) }
                }
            }
            result.append(bytes)
        }
        return result
    }
    static func send(_ connection: NWConnection, reply: WireReply) async throws {
        let timeout = DispatchWorkItem { connection.cancel() }
        DispatchQueue.global().asyncAfter(deadline: .now() + 120, execute: timeout)
        defer { timeout.cancel() }
        let frame = try WireFrame.encode(reply)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }
}
