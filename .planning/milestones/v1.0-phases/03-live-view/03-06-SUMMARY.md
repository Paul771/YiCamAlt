---
phase: 03-live-view
plan: 06
subsystem: live-view
tags: [tdd, supervisor, reconnect, stall, network, resume, quality, grace]
requires: ["03-00", "03-05"]
provides:
  - "LiveSessionSupervisor (state, qualityState, currentLinkState; start, retry, stop, onForeground, setQuality; Timing defaults)"
  - "classify(LinkError) -> ErrorClass, classifyClosed(reason) -> ErrorClass?"
  - "Test fakes: FakeCameraLink, FakeLinkFactory, FakeNetworkMonitor, FakeMediaSession(Factory), FakeCredentialsSource (LiveFakes.kt)"
affects: [03-07, 03-10, 03-11]
tech-stack:
  added: []
  patterns:
    - "run token under a lock: a factory.open running on io after stop() sees the token mismatch, closes the link with the stop reason and never starts it"
    - "watch ends on terminal link state OR our own close request (combine(link.state, ownClose)); drain then waits for terminal up to 5 s"
    - "closes go through a private SupervisorJob scope on ioDispatcher so they survive cancellation of the screen scope"
key-files:
  created:
    - app/src/test/java/com/yicamalt/live/LiveFakes.kt
    - app/src/test/java/com/yicamalt/live/LiveSessionSupervisorTest.kt
  modified:
    - app/src/main/java/com/yicamalt/live/LiveSessionSupervisor.kt
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Wrong-password and kick retries show Waiting(attempt + 1, ...) and advance the attempt counter; only NETWORK class checks maxAttempts"
  - "Healthy reset (30 s Live) is applied when the link ends: attempt = 1 and both allowances restored"
  - "Network lost during Waiting -> NoNetwork; on return the run continues with attempt 1, no delay"
  - "stop() while Waiting closes the previous (already terminal) link with the stop reason; close is idempotent"
  - "Unknown Closed reasons classify as NETWORK; a Closed(user|screen-left|background) not issued by stop() ends the run as Stopped(reason)"
  - "factory.open throwing is treated as NETWORK with LinkError(INTERNAL, 0)"
metrics:
  duration: 30min
  completed: 2026-10-23
  tasks: 2
  files: 5
---

# Phase 3 Plan 06: LiveSessionSupervisor Summary

Pure-Kotlin session supervisor that owns one link at a time: backoff 2/5/10/20 s for at most 5 attempts then Exhausted, one wrong-password retry with refreshed credentials, one kick retry after 10 s, immediate Fatal, NoNetwork without consuming attempts, 1.5 s debounced reconnect on default-network change, 10 s stall watchdog, 5 s drain before every new open, stop/background-resume and HD/SD requests, all proven in virtual time.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | RED - fakes, stub, 38 behaviour tests | 4e5afdb |
| 2 | GREEN - supervisor implementation + graph/verification docs | 27024fa |

## Verification

- RED: `:app:compileDebugUnitTestKotlin` BUILD SUCCESSFUL; `--tests LiveSessionSupervisorTest` -> 38 tests, 38 failed.
- GREEN: `--tests "com.yicamalt.live.*"` green three consecutive runs (2 with `--rerun-tasks`): LiveSessionSupervisorTest 38/38 each time.
- `:app:testDebugUnitTest :app:assembleDebug` -> BUILD SUCCESSFUL, `--- gradle exit=0`; whole suite 651 tests, 1 skipped (RealLogScanTest without log), 0 failures.
- Greps: `@Test` = 38 (>= 28); `LinkErrorKind.entries` present; `listOf(2_000, 5_000, 10_000, 20_000)` = 1; START_BLOCK_ = END_BLOCK_ = 10; no `import android`, `Thread.sleep`, `System.currentTimeMillis`; LiveProbeViewModel.kt unchanged.
- RedactionScanner runs over every test's captured log with the synthetic id / DID / passwords / InitString / license literals: clean.

## Deviations from Plan

- RED stub uses `error("RED stub")` (and `kotlin.error` inside `classify`, whose parameter is named `error`) instead of `TODO()` — the write hook rejects TODO()-only stubs.
- [Rule 3] FakeCameraLink's recorded audio sink is `audioSinkSet` (a property named `audioSink` clashes with `setAudioSink` on the JVM).
- [Rule 2] `factory.open` exceptions are caught and treated as a NETWORK failure (the run loop would otherwise die silently).
- Network-change watcher code sits in `START_BLOCK_NETWORK_WATCH`; it logs the `BLOCK_NETWORK` marker like the no-network wait in `START_BLOCK_NETWORK` (one log anchor per spec, unique code-block names).
- docs: added 03-06 change-summary to M-LIVE-SESSION in knowledge-graph.xml and the six extra required markers to V-M-LIVE-SESSION.

## Known Stubs

None.

## Self-Check: PASSED
