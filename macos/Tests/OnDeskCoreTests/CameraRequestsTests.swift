import XCTest
@testable import OnDeskCore

final class CameraRequestsTests: XCTestCase, @unchecked Sendable {
    func testOnlyLiveOwnerCanReceiveAndCompleteOnce() async throws {
        let requests = CameraRequests()
        let now = Date()
        await requests.poll(owner: "phone", now: now)
        let id = try await requests.request(owner: "phone", now: now)
        let foreign = await requests.poll(owner: "other", now: now)
        XCTAssertNil(foreign)
        let lifetime = await requests.launchLifetime(id: id, owner: "phone", now: now.addingTimeInterval(29))
        XCTAssertEqual(lifetime, 1000)
        let staleLifetime = await requests.launchLifetime(id: id, owner: "phone", now: now.addingTimeInterval(31))
        XCTAssertEqual(staleLifetime, 0)
        let command = await requests.poll(owner: "phone", now: now)
        XCTAssertEqual(command, id)
        let replay = await requests.poll(owner: "phone", now: now)
        XCTAssertNil(replay)
        let wrong = await requests.consume(id: id, owner: "other", now: now)
        XCTAssertFalse(wrong)
        let accepted = await requests.consume(id: id, owner: "phone", now: now)
        XCTAssertTrue(accepted)
        let duplicate = await requests.consume(id: id, owner: "phone", now: now)
        XCTAssertFalse(duplicate)
    }
    func testOfflineExpiryCancellationAndRestartLeaveClipboardUntouched() async throws {
        let requests = CameraRequests(), now = Date()
        do { _ = try await requests.request(owner: "phone", now: now); XCTFail("Offline accepted") } catch {}
        await requests.poll(owner: "phone", now: now)
        let expired = try await requests.request(owner: "phone", now: now)
        let stale = await requests.poll(owner: "phone", now: now.addingTimeInterval(31))
        XCTAssertNil(stale)
        let late = await requests.consume(id: expired, owner: "phone", now: now.addingTimeInterval(32))
        XCTAssertFalse(late)
        let id = try await requests.request(owner: "phone", now: now.addingTimeInterval(32))
        _ = await requests.poll(owner: "phone", now: now.addingTimeInterval(32))
        await requests.cancel(id: id, owner: "other")
        do { _ = try await requests.request(owner: "phone", now: now.addingTimeInterval(32)); XCTFail("Concurrent request") } catch {}
        await requests.cancel(id: id, owner: "phone")
        let cancelled = await requests.consume(id: id, owner: "phone", now: now.addingTimeInterval(33))
        XCTAssertFalse(cancelled)
        let restarted = CameraRequests()
        let forgotten = await restarted.consume(id: id, owner: "phone", now: now)
        XCTAssertFalse(forgotten)
    }
}
