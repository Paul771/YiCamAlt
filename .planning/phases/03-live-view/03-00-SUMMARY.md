---
phase: 03-live-view
plan: 00
subsystem: live-view
tags: [contracts, port-types, grace, manifest]
requires: []
provides:
  - "CameraLink.setAudioSink / requestResolution / capabilities (default bodies), LinkCapabilities"
  - "AudioCodec, AudioUnit, AudioUnitSink"
  - "IoCtrlMessages.AUDIO_STOP, setResolution(resolution, useCount, bigEndian)"
  - "live/LiveSessionTypes.kt: SupervisorState, ErrorClass, Quality, QualityState, NetState, NetworkMonitor, KeyFrameObservation, MediaSession, MediaSessionFactory"
  - "MainActivity configChanges; WRITE_EXTERNAL_STORAGE maxSdkVersion 28"
  - "GRACE records M-LIVE-SESSION, M-MEDIA-AUDIO, M-MEDIA-SNAPSHOT (planned), rewritten M-UI-LIVEVIEW"
affects: [03-01, 03-02, 03-03, 03-04, 03-05, 03-06, 03-07, 03-08, 03-09, 03-10, 03-11, 03-12, 03-13]
tech-stack:
  added: []
  patterns: ["interface default members keep existing links and fakes compiling"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt
  modified:
    - app/src/main/java/com/yicamalt/stream/CameraLink.kt
    - app/src/main/java/com/yicamalt/stream/StreamTypes.kt
    - app/src/main/java/com/yicamalt/stream/IoCtrlMessages.kt
    - app/src/test/java/com/yicamalt/stream/IoCtrlMessagesTest.kt
    - app/src/main/AndroidManifest.xml
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Phase 3 CameraLink members have default bodies (no-op sink, null useCount, UNKNOWN capabilities) so TNP/TUTK links and test fakes compile unchanged"
  - "M-UI-LIVEVIEW drops PTZ and camera selector (v2: CTRL-01, LIVE-13); depends on M-LIVE-SESSION instead of M-TNP-LINK/M-CAMERA-CMD/M-CAMERA-LIST"
metrics:
  duration: 20min
  completed: 2026-10-23
  tasks: 2
  files: 9
---

# Phase 3 Plan 00: Phase 3 port types, manifest prerequisites and GRACE records Summary

Contract-first Phase 3 ports: CameraLink gains default `setAudioSink` / `requestResolution` / `capabilities`, audio unit types and a byte-order-aware 4881 builder exist, `live/LiveSessionTypes.kt` fixes the supervisor state machine and media-session ports; MainActivity survives rotation via `configChanges`; GRACE docs list the planned Phase 3 modules.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Port types: CameraLink extensions, audio unit types, 769 and LE 4881, live session types | a8c46e0 |
| 2 | Manifest prerequisites and GRACE records for the new modules | 76bdcc5 |

## Verification

- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.stream.*" --tests "com.yicamalt.p2p.*" --tests "com.yicamalt.ui.live.*"`: BUILD SUCCESSFUL, exit 0 (212 tests, 0 failures; new `setResolution honours the transport byte order` passed). No fake needed changes.
- `tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, exit 0; 41 suites, 527 tests, 1 skipped (RealLogScanTest without a log file), 0 failures.
- XML parse (minidom) of development-plan, knowledge-graph, verification-plan and the manifest: OK.
- Acceptance greps: configChanges = 1, maxSdkVersion 28 = 1, no PtzJoystick/CameraSelector in M-UI-LIVEVIEW, no connectedDebugAndroidTest in V-M-UI-LIVEVIEW, opening + closing tags for all new modules; no `/*` in the touched stream/live files.

## Deviations from Plan

- The `SupervisorState` sealed-interface objects carry their comments on the line above (instead of trailing comments) to keep a `toString()` override body per object; content unchanged.
- The second `setResolution` overload got its own START_CONTRACT anchor named `setResolutionOrdered` (unique anchor name, AGENTS.md rule that block names be unique).
- In knowledge-graph.xml the old M-UI-LIVEVIEW CrossLinks to M-TNP-LINK, M-CAMERA-CMD and M-CAMERA-LIST were replaced by the new ones (they contradicted the rewritten dependencies).

Otherwise the plan was executed as written.

## Known Stubs

None. The default CameraLink members are intentional no-op defaults; TNP/TUTK implementations follow in later Phase 3 plans.

## Self-Check: PASSED
