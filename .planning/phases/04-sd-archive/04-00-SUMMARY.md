---
phase: 04-sd-archive
plan: 00
subsystem: p2p-transport
tags: [tnp, ioctrl, archive, camera-link, port-contract]
requires: []
provides:
  - CameraLink.sendIoCtrl / ioCtrlReplies / nextUseCount / transportBigEndian / setStreamMode (default bodies)
  - LinkIoCtrlReply, StreamMode, FrameObservation value types
  - TNP readers on PPPP ch4/5/7 (recorded video) routed to VideoUnitSink
  - TNP generic IOCTRL send + reply flow, one link-wide useCount
affects: [04-01, 04-02, 04-03, 04-04, 04-05, 04-06, 04-07, 04-08, 04-09]
tech-stack:
  added: []
  patterns:
    - "Port members with default bodies so existing links and fakes compile unchanged"
    - "Hot MutableSharedFlow (buffer 16, DROP_OLDEST) for non-consumed IOCTRL replies"
key-files:
  created:
    - .planning/phases/04-sd-archive/04-00-SUMMARY.md
  modified:
    - app/src/main/java/com/yicamalt/stream/CameraLink.kt
    - app/src/main/java/com/yicamalt/stream/StreamTypes.kt
    - app/src/main/java/com/yicamalt/stream/TnpCodec.kt
    - app/src/main/java/com/yicamalt/p2p/tnp/TnpCameraLink.kt
    - app/src/test/java/com/yicamalt/p2p/tnp/TnpCameraLinkTest.kt
    - app/src/test/java/com/yicamalt/stream/TnpCodecTest.kt
    - app/src/test/java/com/yicamalt/p2p/FakePpppNative.kt
decisions:
  - "TNP canSend() gate (connected/streaming, burst queued, not closing) shared by requestResolution, sendIoCtrl, nextUseCount"
  - "TNP ioCtrlReplies publishes only authResult-0 ch0 replies other than 4882 / 9033; kick branch now returns explicitly"
  - "TNP setStreamMode keeps the default no-op (no no-data limit after the first frame)"
metrics:
  duration: 35min
  completed: 2026-10-30
  tasks: 3
  files: 7
---

# Phase 4 Plan 00: Archive port contract and TNP transport Summary

CameraLink gained archive members with default bodies (sendIoCtrl, ioCtrlReplies, nextUseCount, transportBigEndian, setStreamMode). The TNP link now reads recorded video on PPPP ch4/5/7, sends any IOCTRL through the single writer once the start burst is queued, publishes the ch0 replies it does not consume itself, and takes every useCount after the burst's fixed 1 / 2 from one link-wide counter.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | Port contract: LinkIoCtrlReply, StreamMode, FrameObservation, CameraLink default members | 2941feb |
| 2 | RED: TNP archive channel / ioctrl / useCount tests, FakePpppNative.enqueueVideo, codec limits | 97bafbb |
| 3 | GREEN: TnpCodec CH_RECORD_I/P, CH_NEW_RECORD; TnpCameraLink readers 0..7, sendIoCtrl, ioCtrlReplies, nextUseCount | a5334ac |

## Verification (actually run)

- Task 1: `:app:compileDebugUnitTestKotlin` BUILD SUCCESSFUL, exit=0; the three acceptance greps return 3 / 3 / 1 line.
- Task 2 RED: compile succeeded; a targeted run had 8 failures (7 TnpCameraLinkTest + TnpCodecTest record limits) on behaviour, not compilation. `transportBigEndian is true` and `sendIoCtrl before the burst returns false` passed already, because the interface defaults are true and false.
- Task 3: `:app:testDebugUnitTest --tests "com.yicamalt.p2p.*" --tests "com.yicamalt.stream.*"` passed 3 times in a row. Run 1 compiled and executed the tests. Runs 2 and 3 used `--rerun`, because a plain re-invocation is UP-TO-DATE and would not execute anything. All three gave BUILD SUCCESSFUL, exit=0. TnpCameraLinkTest: 41 tests, 0 failures. TnpCodecTest: 12 tests, 0 failures.
- Full JVM suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, exit=0, 695 tests, 0 failures/errors (counted from the JUnit XML reports).
- Task 3 greps: START/END SEND_IOCTRL + IOCTRL_REPLY = 2 / 2; `resolutionUseCount` = 0; USE_COUNT_SET_RES / USE_COUNT_START_RT present (5 lines); CH_RECORD_* in TnpCodec = 6.

## Deviations from Plan

**1. [Acceptance count] `@Test` count in TnpCameraLinkTest is 41, not >= 44**
- The plan assumed a baseline of 38 tests. The file actually had 33 before this plan. 8 tests were added, which is more than the intended +6, so the intent is met and no filler tests were added.

**2. [Rule 3 - Blocking] RED tests use literal channel constants 4 / 5 / 7**
- The RED tests have to compile before `TnpCodec.CH_RECORD_*` exists, so TnpCameraLinkTest defines private `RECORD_I = 4`, `RECORD_P = 5` and `NEW_RECORD = 7`.

**3. [Clarification] Kick branch in onCommandUnit returns after terminate**
- An explicit `return` was added so a 9033 reply never reaches the publishing branch. Kick behaviour is unchanged.

**4. [Test design] sendIoCtrl before-burst test blocks connect**
- The test sets `connectBlocks = true` so that the "before the burst" case is deterministic, then asserts that nothing was written.

## Known Stubs

None. `setStreamMode` deliberately keeps the default no-op on TNP, and MODULE_MAP records this. TUTK implementations of the new members are left to later plans in this phase.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/stream/CameraLink.kt, StreamTypes.kt, TnpCodec.kt, p2p/tnp/TnpCameraLink.kt
- FOUND commits: 2941feb, 97bafbb, a5334ac
