---
phase: 03-live-view
plan: 08
subsystem: live-view
tags: [network, connectivity, hilt, mediacodec, aspect-ratio, grace]
requires: ["03-00"]
provides:
  - "NetStateTracker (pure NetState rules) + NetStateTrackerTest (6)"
  - "AndroidNetworkMonitor : NetworkMonitor (@Singleton, registerDefaultNetworkCallback, no VALIDATED gate)"
  - "LiveModule: @Binds NetworkMonitor -> AndroidNetworkMonitor"
  - "VideoDecoder.videoSize: StateFlow<VideoSize?>; top-level VideoSize + visibleSize() + VideoSizeTest (4)"
affects: [03-09, 03-10, 03-11]
tech-stack:
  added: []
  patterns: ["Android adapter delegates to a JVM-tested pure tracker; pure helpers top-level so tests never load MediaCodec"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/live/NetStateTracker.kt
    - app/src/main/java/com/yicamalt/live/AndroidNetworkMonitor.kt
    - app/src/main/java/com/yicamalt/live/LiveModule.kt
    - app/src/test/java/com/yicamalt/live/NetStateTrackerTest.kt
    - app/src/test/java/com/yicamalt/media/VideoSizeTest.kt
  modified:
    - app/src/main/java/com/yicamalt/media/VideoDecoder.kt
    - docs/knowledge-graph.xml
    - docs/development-plan.xml
    - docs/verification-plan.xml
decisions:
  - "NetStateTracker keeps the last network key after onLost, so the same network coming back keeps the generation; keys compared with == (android.net.Network equality is by net id)"
  - "videoSize is seeded with the key-frame size at codec creation and replaced by the crop-aware output format size; published only on change"
metrics:
  duration: 15min
  completed: 2026-10-23
  tasks: 2
  files: 10
---

# Phase 3 Plan 08: Default-network monitor and decoded video size Summary

ConnectivityManager default-network callback feeds a pure `NetStateTracker` (value-free `NetState(available, generation)`, Hilt-bound as `NetworkMonitor`), and `VideoDecoder` publishes a crop-aware `videoSize` StateFlow from `onOutputFormatChanged` for the live screen aspect ratio.

## Tasks

| Task | Name | Commits |
| ---- | ---- | ------- |
| 1 | NetStateTracker (TDD) + AndroidNetworkMonitor + LiveModule | ab1a478 (RED), 22555d5 (GREEN) |
| 2 | VideoDecoder publishes the visible video size (TDD) | e4dafd7 (RED), 0108c04 (GREEN) |

## Verification

- RED runs: NetStateTrackerTest 6/6 failed, VideoSizeTest 4/4 failed (stubs `error("RED stub")`).
- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.live.*" :app:assembleDebug`: BUILD SUCCESSFUL, exit 0.
- `tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, exit 0; 47 suites, 593 tests, 1 skipped (RealLogScanTest without a log file), 0 failures (DecoderPolicy, LiveProbe and ui.live suites included).
- Acceptance greps: `@Test` NetStateTrackerTest 6, VideoSizeTest 4; `registerDefaultNetworkCallback` 1; no `NET_CAPABILITY_VALIDATED`; `abstract fun bindNetworkMonitor` 1; `val videoSize: StateFlow<VideoSize?>` 1; no `onOutputFormatChanged(...) = Unit`; no `/*` in live/ or VideoDecoder.kt.
- GRACE XML (knowledge-graph, development-plan, verification-plan) parses (minidom).

## Deviations from Plan

- [Rule 2 - Robustness] AndroidNetworkMonitor also catches RuntimeException on registration (some OEM builds throw TooManyRequestsException) in addition to SecurityException; logs only the exception class and keeps the seeded state. `getSystemService` null is tolerated (seed false, no callback).
- [Rule 2 - Robustness] onOutputFormatChanged ignores callbacks from an old codec (identity check, like the other callbacks) and catches a mistyped format key so decoding is never broken by size publishing.
- The `BLOCK_NETWORK_EVENT` log is emitted only when the state actually changes (repeat onAvailable of the same network is silent).
- GRACE docs: NetStateTrackerTest / VideoSizeTest added to test lists; `BLOCK_FORMAT_CHANGED` added as marker-7 of V-M-MEDIA-RENDER; NetStateTracker / AndroidNetworkMonitor / visibleSize annotations added to the knowledge graph.

## Known Stubs

None.

## Self-Check: PASSED
