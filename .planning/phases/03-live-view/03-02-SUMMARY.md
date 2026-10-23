---
phase: 03-live-view
plan: 02
subsystem: p2p
tags: [tnp, pppp, audio, resolution, capabilities]
requires:
  - phase: 03-00
    provides: CameraLink.setAudioSink / requestResolution / capabilities defaults, AudioUnit, LinkCapabilities
provides:
  - TnpCameraLink ch1 audio -> AudioUnitSink (encrypted = header version >= 2)
  - TnpCameraLink.requestResolution (4881 via the single writer, useCount 3..)
  - TnpCameraLink.capabilities (4882 abilityRet 1 -> resolutionSwitch=false)
affects: [03-live-session supervisor, live screen HD/SD toggle, audio pipeline wiring]
tech-stack:
  added: []
  patterns: [enqueue-only command from foreign thread onto the link writer, optional sink with once-logged failures]
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/p2p/tnp/TnpCameraLink.kt
    - app/src/test/java/com/yicamalt/p2p/tnp/TnpCameraLinkTest.kt
    - docs/verification-plan.xml
    - docs/knowledge-graph.xml
key-decisions:
  - "requestResolution returns null until the start burst is queued (burstQueued flag), so 4881 useCount 1 always leads"
  - "Audio sink Exception drops that unit and is logged once (class name); Errors still go through the thread guard"
  - "Reused TnpCodec.IOTYPE_AUDIO / FRAME_HEAD_SIZE instead of new private constants"
requirements-completed: []
duration: 25min
completed: 2026-10-23
---

# Phase 3 Plan 02: TNP audio units and in-session resolution switch Summary

**TnpCameraLink v0.3.0 routes ch1 audio to an AudioUnitSink (encrypted = header version >= 2), queues in-session 4881 resolution requests through the existing single writer (useCount 3, 4, ...), and exposes 4882 abilityRet 1 as `resolutionSwitch=false`, with the start burst unchanged.**

## Performance

- Duration: ~25 min
- Tasks: 2/2
- Files modified: 4

## Accomplishments

- `setAudioSink`: ch1 units with ioType 2 and body > 24 bytes are split with `TnpCodec.splitFrameUnit(bigEndian = true)` and delivered as `AudioUnit(1, info, payload, encrypted = headerVersion >= 2)`. With no sink they are discarded as before, and they never reach the video sink.
- `requestResolution(res)`: only enqueues `buildCommand(SET_RESOLUTION, setResolution(res, ++useCount))` on `txQueue`. It returns null while not Connected/Streaming, before the burst is queued, or once closing.
- `capabilities`: a 4882 reply whose TNPHead has abilityRet 1 sets `LinkCapabilities(resolutionSwitch = false)`. `BLOCK_AUTH_REPLY` now logs `abilityRet=%d`.
- GRACE: VERSION 0.3.0, CHANGE_SUMMARY, MODULE_MAP, START_CONTRACT for both overrides, paired `BLOCK_AUDIO_UNIT` / `BLOCK_SET_RESOLUTION`. Added verification-plan scenarios 18-20, markers 12-13 and device markers 9-10, plus a knowledge-graph change-summary.

## Task Commits

1. **Task 1: RED tests**: `222e0bf` (test). 6 new or extended tests failed against v0.2.0. The null-before-connect test passed already because of the 03-00 default.
2. **Task 2: GREEN implementation**: `d5ac88b` (grace)

## Verification

- `:app:testDebugUnitTest --tests "com.yicamalt.p2p.*" --rerun-tasks` passed 3 runs in a row: BUILD SUCCESSFUL, `--- gradle exit=0` (56.7 s, 54.1 s, 55.4 s).
- TnpCameraLinkTest: 33 tests, 0 failures.
- `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, exit 0. 43 suites, 558 tests, 1 skipped (RealLogScanTest), 0 failures.
- Acceptance greps: `USE_COUNT_SET_RES = 1` = 1, `USE_COUNT_START_RT = 2` = 1, `override fun requestResolution` = 1, START/END_BLOCK_AUDIO_UNIT = 1/1, and no removed `startSequence` lines.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Correctness] requestResolution gated on the start burst being queued**
- **Found during:** Task 2
- **Issue:** Between `Connected` being published and `txQueue.addAll(burst)`, a request would have taken an earlier command number and been sent before 4881 useCount 1.
- **Fix:** Added `@Volatile burstQueued`, set right after `addAll(burst)`. Until then `requestResolution` returns null.
- **Files modified:** TnpCameraLink.kt
- **Commit:** d5ac88b

**2. [Rule 2 - Robustness] Throwing-audio-sink test added**
- Added `a throwing audio sink does not stop video`, which checks that `sinkError` is logged exactly once and the link stays Streaming. The plan required this behaviour but had no test for it.

**3. Reused codec constants**
- Used `TnpCodec.IOTYPE_AUDIO` and `TnpCodec.FRAME_HEAD_SIZE` instead of new private `AUDIO_IO_TYPE` / `FRAME_HEAD_SIZE` duplicates. Values are identical.

## Known Stubs

None.

## Next Phase Readiness

- The supervisor and live screen can call `setAudioSink` before `start`, `requestResolution` while streaming, and observe `capabilities`.
- No device proof yet for TNP audio or the resolution switch. That is deferred to H-L3/H-L4 (after Phase 2 H1).
- LIVE-06 / LIVE-07 are left unchecked for the orchestrator.

## Self-Check: PASSED
