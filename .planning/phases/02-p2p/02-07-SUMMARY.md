---
phase: 02-p2p
plan: 07
subsystem: p2p-tnp-link
tags: [p2p, tnp, pppp, session, threads, hilt, tdd]
requires:
  - "02-00: CameraLink port, PpppNative seam, FakePpppNative"
  - "02-03: PpppRuntime, P2pRuntimeModule (PpppNative binding)"
  - "02-04: YiP2pAuth, TnpCodec, IoCtrlMessages"
  - "02-06: CRITERION_1_RUNTIME PASS (native seam proven on the device)"
provides:
  - "TnpCameraLink: CameraLink over PpppNative in the vendor call order, typed failures, bounded idempotent teardown"
  - "TnpLinkFactory: CameraLinkFactory with typed refusals and one session per camera"
  - "P2pLinkModule: Hilt @Binds CameraLinkFactory -> TnpLinkFactory"
  - "FakePpppNative v0.2.0: enqueueUnit, replyIoCtrl, checkBlocks, 'data ch=<n> bytes=<k>' trace entry"
affects: [02-09, 02-11, 02-12]
tech-stack:
  added: []
  patterns: ["named platform threads per link (connect/watch/rx<ch>/tx/close)", "single teardown path guarded by AtomicBoolean", "injected clock for timer tests"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/p2p/tnp/TnpCameraLink.kt
    - app/src/main/java/com/yicamalt/p2p/tnp/TnpLinkFactory.kt
    - app/src/main/java/com/yicamalt/p2p/P2pLinkModule.kt
    - app/src/test/java/com/yicamalt/p2p/tnp/TnpCameraLinkTest.kt
    - app/src/test/java/com/yicamalt/p2p/tnp/TnpLinkFactoryTest.kt
  modified:
    - app/src/test/java/com/yicamalt/p2p/FakePpppNative.kt
    - docs/verification-plan.xml
decisions:
  - "Teardown runs on its own named thread p2p-<n>-close, so Connect_Break/ForceClose never run on the caller (main) thread; external callers wait for it up to joinMs + stopDrainMs, link threads do not wait"
  - "The burst waits until each of the five readers has made its first read (latch released after the first native read returns, bounded by joinMs); this makes 'readers before burst' deterministic and costs at most one read timeout (300 ms) on the device"
  - "The connect timeout covers connect + check (until Connected); the first-frame timeout starts when the burst is queued"
  - "Header version: only a version in 1..3 is adopted (the defined TNP values); anything else is ignored"
  - "authResult outside 0/1/2/4 (AuthOutcome.Unknown) fails like 4: PROTOCOL -9000, no retry"
  - "A check() failure is reported as INTERNAL with the raw code (plan), not via classify"
metrics:
  duration: 40min
  completed: 2026-10-15
  tasks: 2
  files: 7
---

# Phase 2 Plan 07: TNP session (TnpCameraLink, factory, Hilt binding) Summary

`TnpCameraLink` drives one PPPP session per camera in the vendor order: connect with the device_info DID and flag 0x49/0xDF, then check, then five readers, then the 4881/9029/768 burst from a single writer. It has typed failures for every code path, two timers, and a bounded idempotent teardown. `TnpLinkFactory` adds credential checks, process-once runtime init and a one-session-per-camera registry. Both run on the JVM against `FakePpppNative` only; no live camera was contacted.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | TnpCameraLink (connect, check, readers, burst, auth, version, kick, timers, teardown) + FakePpppNative helpers + 18 tests | fc90ee7 |
| 2 | TnpLinkFactory + P2pLinkModule + 5 tests; V-M-TNP-LINK scenarios and markers | d86c641 |

## Verification

- Task 1, three consecutive runs of `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.p2p.*" --rerun-tasks`: each gave BUILD SUCCESSFUL and `--- gradle exit=0` (logs gradle-56884, -53932, -40884). Every run: PpppContractTest 13/0, PpppRuntimeTest 16/0, ShimContractTest 5/0, TnpCameraLinkTest 18/0 failures.
- Task 2, `:app:testDebugUnitTest --tests "com.yicamalt.p2p.*" :app:compileDebugKotlin`: BUILD SUCCESSFUL, exit=0. `hiltJavaCompileDebug` ran, so the Hilt graph resolves `CameraLinkFactory`. TnpLinkFactoryTest 5/0.
- Mutation evidence (RED substitute, see Deviations):
  - TnpCameraLink got 6 mutations at once: flag 0x48, 9029 before 4881, -3003 not accumulated, lan without the P2P check, Connect_Break skipped once a handle exists, ch1 treated as video. Result: 14 of 18 tests failed, exit=1.
  - TnpLinkFactory got 3 mutations: no registry check, encrypted-without-password accepted, InitFailed code dropped. Result: 3 of 5 tests failed, exit=1.
  - The sources were restored both times and the runs went green again.
- Acceptance greps:
  - `0x49|0xDF`: 4 lines.
  - `START_BLOCK_CONNECT|...|START_BLOCK_CLOSE`: 6.
  - `Dispatchers|DeInitialize|KICK)`: none.
  - `ALREADY_OPEN` in the factory: 2 lines.
  - `bindCameraLinkFactory`: 1 line.
  - No `/*` in p2p/ or the new tests.

## Log markers (as implemented, value-free)

```
[P2P][tnp][BLOCK_CONNECT] cam=<n> relayForced=<b> wakeup=<b> result=<ok|NAME> ms=<n>
[P2P][tnp][BLOCK_CHECK_SESSION] cam=<n> mode=<MODE> lan=<b> connectMs=<n>
[P2P][tnp][BLOCK_START_BURST] cam=<n> cmds=4881,9029,768 resolution=<n> version=<n>
[P2P][tnp][BLOCK_AUTH_REPLY] cam=<n> authResult=<n> outcome=<OK|WRONG_PASSWORD|RETRY|UNKNOWN> version=<n>
[P2P][tnp][BLOCK_READ_LOOP] cam=<n> ch=<n> exit=<closed|error|dirty> code=<NAME> units=<n>
[P2P][tnp][BLOCK_FIRST_VIDEO] cam=<n> ch=<n> codecId=<n> key=<b> ms=<n>        (ms since start())
[P2P][tnp][BLOCK_CLOSE] cam=<n> reason=<user|kicked|screen-left|error|timeout> units=<n> joinMs=<n>
[P2P][tnp][BLOCK_HEADER_VERSION] cam=<n> version=<n> resent=4881              (extra)
[P2P][tnp][BLOCK_KICKED] cam=<n> code=<APP_KICKED|APP_KICK_MAX_SESSION>      (extra)
[P2P][factory][BLOCK_OPEN_LINK] cam=<n> transport=<T> result=<started|UNSUPPORTED_TRANSPORT|NO_CREDENTIALS|NATIVE_UNAVAILABLE|ALREADY_OPEN>
```

`units` in BLOCK_CLOSE counts video units. In BLOCK_READ_LOOP it counts all units on that channel. Every test's captured buffer, and the toString/state of links, passes `RedactionScanner().assertClean` with `ALL_SECRETS + SYNTH1234567890 + SYNTHLIC01` (checked in `@AfterEach` for every test, plus a dedicated hygiene test).

## Timeouts defaults

`Timeouts(connectMs = 30_000, firstFrameMs = 20_000, readTimeoutMs = 300, joinMs = 2_000, stopDrainMs = 200, pollMs = 20)`. The clock is injected as `now: () -> Long` (default `System.nanoTime() / 1e6`). Join and close bounds always use real time.

## Deviations from the vendor order

- **Readers prime before the burst.** The vendor starts the reader threads and writes at once. We also wait (bounded by `joinMs`) until each reader's first `PPPP_Read` has returned. Writes still happen only after the readers run, which Pitfall 3 requires. The cost is up to one read timeout (300 ms) before 4881.
- **Finite read timeout (300 ms) instead of the vendor's -1.** This is planned (Pattern 7). Partial `size[0]` on -3003 is accumulated. The device checkpoint (02-12) must still show that frames arrive.
- **STOP (767) on teardown is sent whenever streaming was reached**, for every reason including error and kick. It is best effort, and the writer gets at most 200 ms.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RED commit with `TODO()` stubs replaced by a mutation check**
- **Issue:** a local write hook rejects files that contain only `TODO()` stubs (same as plans 02-03 and 02-04), so the `test(02-07): stubs + failing tests` commit could not be produced.
- **Fix:** I wrote implementation and tests together, then proved the tests can fail by mutating the sources (14/18 and 3/5 failures, exit=1) and restoring them. Compilation for the wave stayed green at every commit.
- **Commits:** fc90ee7, d86c641. The plan's commit list (test, then feat, then grace) became one feat commit and one grace commit.

**2. [Rule 2 - Correctness] Teardown on a dedicated `p2p-<n>-close` thread**
- **Issue:** `close()` may be called from the UI thread, but the phase rule requires every native call to run on a named thread of the link.
- **Fix:** Connect_Break, ForceClose and the STOP enqueue run on `p2p-<n>-close`. External callers join it with a bound, and link threads never wait on it, which avoids a self-join deadlock.

**3. [Rule 2 - Correctness] Connect attempt guarded by the thread lock**
- **Issue:** a teardown racing the start of `connect` could skip Connect_Break, and the connect would then block until the vendor timeout.
- **Fix:** `connectAttempted` is set under the same lock that sets `stopping`. Teardown therefore either breaks the connect or the connect never starts.

**4. [Rule 2 - Test evidence] Fake trace entry `data ch=<n> bytes=<k>`**
- **Purpose:** proves that no ch0 data was read between the three burst writes, because a plain `read ch=0` entry cannot tell an empty read from one that returned data. Existing PpppContractTest assertions are unaffected.

**5. Extra markers BLOCK_HEADER_VERSION and BLOCK_KICKED** were added to V-M-TNP-LINK, together with scenarios 9-11 (header version, dirty head, first-frame timeout). Module STATUS stays `planned` until the device checkpoint, as in prior Phase 2 plans.

## Notes for later plans

- `TnpLinkFactory.open()` may block up to 10 s on the first PPPP init, so call it off the main thread. Every opened link must be closed, because the registry entry is released only by teardown.
- `FailedLink` is internal. Refusals are links in `Failed(error)`, never exceptions.
- Requirements LIVE-01 and LIVE-02 are deliberately NOT marked complete. They close after the device checkpoint (02-12).

## Known Stubs

None.

## Self-Check: PASSED

- Files exist: TnpCameraLink.kt, TnpLinkFactory.kt, P2pLinkModule.kt, TnpCameraLinkTest.kt, TnpLinkFactoryTest.kt, FakePpppNative.kt (modified)
- Commits fc90ee7 and d86c641 exist and carry the Co-Authored-By trailer
- TEST XML: TnpCameraLinkTest 18/0 failures, TnpLinkFactoryTest 5/0 failures; docs/verification-plan.xml parses (XML_OK)
