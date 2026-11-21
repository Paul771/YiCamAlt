---
phase: 03-live-view
plan: 04
subsystem: p2p-tutk
tags: [tutk, audio, ioctrl, resolution, teardown, grace]
requires: ["03-00", "03-03"]
provides:
  - "TutkCameraLink.setAudioSink (before start) -> 768 on tutk-<n>-tx + avRecvAudioData reader tutk-<n>-rxa (LE FrameInfo, encrypted false)"
  - "TutkCameraLink.requestResolution -> LE 4881 [res, useCount] on tutk-<n>-tx, useCount 2, 3, ...; null before start command / after close"
  - "tutk-<n>-rxc avRecvIOCtrl reader (4882 logged)"
  - "teardown joins rx, rxa, rxc, tx before releaseOnce"
affects: [03-05, 03-06]
tech-stack:
  added: []
  patterns: ["single link-owned IOCTRL writer thread fed by a LinkedBlockingQueue; auxiliary readers never fail the link"]
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkCameraLinkTest.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
    - docs/verification-plan.xml
decisions:
  - "tx and rxc always start after a successful 8190/511 send; rxa and 768 only with an audio sink set before start"
  - "Audio / IOCTRL reader errors end only their own loop; only the video reader decides session loss"
  - "io join bound = max(noDataSleepMs, audioNoDataSleepMs, ioCtrlPollMs, txPollMs) + ioJoinMarginMs (320 ms default); a tx stuck in avSendIOCtrl is aborted by releaseOnce and defers the registry"
metrics:
  duration: 25min
  completed: 2026-10-23
  tasks: 2
  files: 4
---

# Phase 3 Plan 04: TUTK audio, IOCTRL reader and in-session resolution Summary

TutkCameraLink gains a single IOCTRL writer (`tutk-<n>-tx`: 768 when an audio sink is set, LE 4881 from `requestResolution`), an `avRecvIOCtrl` reader (`tutk-<n>-rxc`) and an `avRecvAudioData` reader (`tutk-<n>-rxa`, cleanAudioBuf first, LE FrameInfo, unencrypted units); teardown joins all four io threads before the AV channel is released. The Phase 2 start sequence is byte-identical.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Failing tests for TUTK audio, IOCTRL reader, resolution and teardown (RED) | 402df87 |
| 2 | Implement tx / rxc / rxa threads and the extended teardown (GREEN) | dcbdf5a |

## Verification

- RED: 38 TutkCameraLinkTest tests, 10 new ones failed against the old link (compile green).
- GREEN: `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.p2p.*" :app:assembleDebug` BUILD SUCCESSFUL, exit 0; then two more `--rerun-tasks` p2p runs, all green: 3/3 runs, 9 suites, 133 tests, 0 failures (TutkCameraLinkTest 38).
- Greps: `IoCtrlMessages.tutkStart(START_USE_COUNT)` = 1; `bigEndian = false` = 3; START_ / END_ for TX_LOOP / IOCTRL_LOOP / AUDIO_LOOP / SET_RESOLUTION = 4 / 4; no `/*`.

## Deviations from Plan

- [Rule 3 - Blocking] The `-20012` pacing test uses the default `audioNoDataSleepMs` (120 ms, asserts >= 100 ms gaps) instead of an injected 5 ms, because `Timeouts.audioNoDataSleepMs` did not exist at RED time and the RED tests had to compile.
- [Rule 3 - Blocking] FakeTutkNative also got `ioCtrlEndCode` (needed to script a `-20015` from recvIoCtrl), `sendInFlight` / `sendInFlightAtStop` (the "no send in flight at stop" assertion) and `recvAudioAtNs` (poll pacing). The plan's `callCount("sendIoCtrl 768")` form does not match the fake's `sendIoCtrl av=.. type=..` record, so the tests assert on `sentIoCtrl` types instead.
- [Rule 2 - GRACE] `docs/verification-plan.xml` V-M-TUTK-LINK: scenarios 16-18 and markers 9-12 for the new blocks (AGENTS.md rule 4).
- Extra test `phase 3 blocks log value-free markers` (the redaction extension) also checks `BLOCK_IOCTRL_LOOP`.

## Known Stubs

None. Device proof (H-L2 audio, H-L4 HD/SD) belongs to the phase device verification.

## Self-Check: PASSED
