---
phase: 04-sd-archive
plan: 02
subsystem: p2p-transport
tags: [tutk, ioctrl, archive, camera-link, stream-mode]
requires: ["04-00"]
provides:
  - TutkCameraLink.sendIoCtrl (queued on tutk-<n>-tx after the start command)
  - TutkCameraLink.ioCtrlReplies (every avRecvIOCtrl reply except 4882)
  - TutkCameraLink.nextUseCount (one link-wide counter shared with requestResolution)
  - TutkCameraLink.transportBigEndian = false
  - TutkCameraLink.setStreamMode (-20012 limit only in LIVE)
affects: [04-03, 04-04, 04-05, 04-06]
tech-stack:
  added: []
  patterns:
    - "Hot MutableSharedFlow (buffer 16, DROP_OLDEST) for non-consumed IOCTRL replies, same as TNP"
    - "Stream mode as a @Volatile field read by the video reader on every -20012 result"
key-files:
  created:
    - .planning/phases/04-sd-archive/04-02-SUMMARY.md
  modified:
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkCameraLinkTest.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
decisions:
  - "TUTK sendIoCtrl / nextUseCount share the requestResolution guard (txRunning, not stopping, not closing)"
  - "Outside LIVE the -20012 counter is reset to 0 on every no-data poll; pause() still runs, so polling pace is unchanged"
  - "FakeTutkNative paces recvFrame with a Semaphore (recvPermits) instead of a frameScript queue, since an empty queue already yields -20012"
metrics:
  duration: 25min
  completed: 2026-10-30
  tasks: 2
  files: 3
---

# Phase 4 Plan 02: TUTK archive port Summary

The TUTK link now implements every archive member of CameraLink. `sendIoCtrl` queues any IOCTRL on the single tutk-<n>-tx writer once the start command has succeeded. The rxc reader publishes every reply except 4882 on `ioCtrlReplies` (793 pages, 817). `requestResolution` and `nextUseCount` draw from one link-wide useCount (start 1, then 2, 3, ...). `transportBigEndian` is false. `setStreamMode(ARCHIVE | PAUSED)` stops `-20012` polls from ending the session, while LIVE keeps the Phase 2 limit of 300 x 30 ms.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | RED: TUTK archive IOCTRL, replies, useCount and stream-mode tests; FakeTutkNative.recvPermits | 508d9dc |
| 2 | GREEN: TutkCameraLink sendIoCtrl, ioCtrlReplies, nextUseCount, transportBigEndian, setStreamMode | 13bf24d |

## Verification (actually run)

- Task 1: `:app:compileDebugUnitTestKotlin` gave BUILD SUCCESSFUL, exit=0. A targeted TutkCameraLinkTest run then had 7 of 46 tests failing on behaviour, not on compilation. `sendIoCtrl before the start command returns false` already passed because the interface default is false.
- Task 2: `:app:testDebugUnitTest --tests "com.yicamalt.p2p.*" --rerun` was run 3 times in a row, each BUILD SUCCESSFUL, exit=0. The JUnit XML shows 149 p2p tests with 0 failures/errors; TutkCameraLinkTest alone is 46 tests, 0 failures.
- Full JVM suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, exit=0, 734 tests with 0 failures/errors (counted from the JUnit XML).
- Acceptance greps: `IoCtrlMessages.tutkStart(START_USE_COUNT)` 1 line; `resolutionUseCount` 0 lines; START_ count = 3 and END_ count = 3 for SEND_IOCTRL / IOCTRL_REPLY / STREAM_MODE; `streamMode == StreamMode.LIVE` 1 line. Test file: `setStreamMode(StreamMode` 3 occurrences (the 2 mode tests share a helper that also sets LIVE), and `ioCtrlReplies` is present.

## Deviations from Plan

**1. [Test design] recvPermits semaphore instead of a frameScript queue**
- An empty `recvQueue` already returns AV_ER_DATA_NOREADY, so scripting -20012 results was not the problem. The real gap was pacing: with `maxNoDataPolls = 5` the link would fail before the test could call `setStreamMode`. `recvPermits` (a Semaphore, released in bulk by `stop`) lets the test allow exactly 1 frame + 50 no-data polls, check that the state is still Streaming, then switch back to LIVE.

**2. [Scope] PAUSED and ARCHIVE tests share one helper**
- `assertModeSuspendsNoDataLimit(mode)` runs the same scenario for both modes. The upper bound on recvFrame calls after the switch back to LIVE is 51 + 5.

docs/*.xml were not changed, because this plan does not list them. M-TUTK-LINK and V-M-TUTK-LINK already exist there.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt, app/src/test/java/com/yicamalt/p2p/tutk/TutkCameraLinkTest.kt, app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
- FOUND commits: 508d9dc, 13bf24d
