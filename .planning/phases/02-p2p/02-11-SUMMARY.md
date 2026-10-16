---
phase: 02-p2p
plan: 11
status: implemented
subsystem: p2p-tutk-link
tags: [p2p, tutk, iotc, av, camera-link, hilt, grace]
requires:
  - "02-06: 02-DEVICE-CHECK-1.md GATE_2B: needed"
  - "02-10: TutkNative seam, TutkCodes, TutkRuntime, FakeTutkNative (status: implemented)"
  - "02-07: TnpLinkFactory, TnpCameraLink state machine, FailedLink"
provides:
  - "TutkCameraLink: CameraLink over TutkNative (vendor order, LE FrameInfo, nonce + HMAC auth, 8190/511 start)"
  - "TutkLinkFactory (@Singleton): typed refusals, TutkRuntime init, one session per camera"
  - "TransportLinkFactory (@Singleton): TNP -> TnpLinkFactory, TUTK -> TutkLinkFactory, LANGTAO/UNKNOWN -> Failed(UNSUPPORTED_TRANSPORT)"
  - "P2pLinkModule binds CameraLinkFactory -> TransportLinkFactory (debug live screen unchanged)"
affects: [02-12]
tech-stack:
  added: []
  patterns: ["transport dispatch behind one port", "bounded no-data polling instead of vendor auto-reconnect"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
    - app/src/main/java/com/yicamalt/p2p/TransportLinkFactory.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkCameraLinkTest.kt
    - app/src/test/java/com/yicamalt/p2p/TransportLinkFactoryTest.kt
  modified:
    - app/src/main/java/com/yicamalt/p2p/P2pLinkModule.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
    - docs/verification-plan.xml
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
decisions:
  - "The connect timer (30 s) covers connect + check + clientStart; the first-frame timer (20 s) starts at the 8190/511 send"
  - "Teardown order: connectStop(sid0) while connectByUid blocks; stop(av, sid) once when a session exists (its avClientExit aborts a blocking clientStart); stop(-1, sid0) once after a failed or aborted connect, to free the pre-allocated session id"
  - "lan = mode LAN, or P2P with a private remote (same rule as TNP)"
  - "TUTK VideoUnit.channel = 0 (the AV channel); FramePipeline ignores the channel"
  - "LinkOptions.forceRelay is ignored for TUTK because IOTC_Connect_ByUID_Parallel has no relay flag"
  - "TutkLinkFactory keeps its own cam counter, so a TNP and a TUTK link can both log cam=1 (the threads are p2p-1-* and tutk-1-*)"
metrics:
  duration: 40min
  completed: 2026-10-16
  tasks: 2
  files: 9
---

# Phase 2 Plan 11: TutkCameraLink and transport-dispatching factory Summary

The gate read `GATE_2B: needed` and 02-10 has `status: implemented`, so the full plan ran. A TUTK camera now opens through the same `CameraLink` port as a TNP camera. The steps are: connect by cloud uid, report the mode (P2P/Relay/LAN), nonce + HMAC auth (or `admin`), send the 8190/511 start, then read little-endian frame heads into the shared FramePipeline. `TransportLinkFactory` picks the TNP or TUTK factory from the camera's transport, so the debug live screen needs no UI change. Everything was tested on the JVM only. No camera was contacted. Device proof is plan 02-12.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Gate: `GATE_2B: needed`, 02-10 `status: implemented` -> proceed | (no files) |
| 2 | TutkCameraLink + TutkLinkFactory + TransportLinkFactory + P2pLinkModule + tests + GRACE docs | 6d832f5 |

## Behaviour (as tested)

- **Call order:** `sessionId`, `connectByUid sid0=0`, `check`, `clientStart timeoutS=15`, `sendIoCtrl type=8190 size=4` (payload `01 00 00 00`), then `recvFrame` polling on `tutk-<n>-rx`. Every native call runs on a `tutk-<n>-*` thread. The link never calls `initialize` or `avInitialize`.
- **Auth:** the user is a 15-char nonce from the YiP2pAuth alphabet and the pass is `hmacPassword(pwd, nonce)` (15 chars). Plain cameras use `admin` + the password and send 511.
- **Modes:** 0 -> P2P, 1 -> RELAY, 2 -> LAN (`lan` = true), anything else -> UNKNOWN.
- **Failures:**
  - clientStart -20009 -> `Failed(WRONG_PASSWORD, -20009)` with no start command.
  - connect -90 -> `OFFLINE`. sessionId -18 -> `BUSY`. Check -22 and sendIoCtrl -20015 -> `REMOTE_CLOSED`.
  - -20015 mid-stream -> `REMOTE_CLOSED`, with one connect and no reconnect.
  - 300 consecutive -20012 -> `Failed(TIMEOUT, -20012)`, exactly 300 polls. A frame resets the counter.
- **Drop rule:** after -20013 or -20014, P-frames are dropped until the next key frame. The state stays `Streaming`.
- **Timers:** connect 30 s -> `TIMEOUT(-13)` plus `connectStop`. No frame 20 s after the start -> `TIMEOUT(0)`.
- **Close:**
  - During connect: `connectStop sid0=0` + `stop av=-1 sid=0`.
  - During clientStart: `stop av=-1 sid=0`, which aborts it. No 8190 is sent.
  - While streaming: `stop av=0 sid=0` exactly once.
  - Close is idempotent, `start` after close is ignored, and the threads are gone within 2 s.
- **Factory:** TNP/LANGTAO/UNKNOWN credentials and missing credentials are refused with zero native calls. Runtime Unavailable -> `NATIVE_UNAVAILABLE(0)`, InitFailed(-6) -> `NATIVE_UNAVAILABLE(-6)` (init runs once). A second open of the same camera -> `ALREADY_OPEN`. The camera can be opened again after close.
- **Dispatch:** TNP touches only PPPP, TUTK touches only TUTK (loads IOTCAPIs, sCHL, AVAPIs). LANGTAO/UNKNOWN touch neither and log `[P2P][factory][BLOCK_DISPATCH_LINK]`.

## Log markers (value-free)

```
[P2P][tutk][BLOCK_CONNECT] cam=<n> step=sessionId|connect result=ok|<NAME> [ms=<n>]
[P2P][tutk][BLOCK_CHECK_SESSION] cam=<n> mode=<MODE> lan=<b> connectMs=<n>
[P2P][tutk][BLOCK_CLIENT_START] cam=<n> encrypted=<b> result=ok|<NAME>
[P2P][tutk][BLOCK_CLIENT_START] cam=<n> cmd=8190|511 result=ok|<NAME>
[P2P][tutk][BLOCK_READ_LOOP] cam=<n> first=video codecId=<n> key=<b> ms=<n>
[P2P][tutk][BLOCK_READ_LOOP] cam=<n> exit=closed|error|no-data code=<NAME> units=<n> dropped=<n>
[P2P][tutk][BLOCK_CLOSE] cam=<n> reason=<r> units=<n> joinMs=<n>
```

Every test buffer passes `RedactionScanner().assertClean` with `ALL_SECRETS`, the password, and the generated nonce and HMAC from each run as extra literals.

## Verification

- `:app:testDebugUnitTest --tests "com.yicamalt.p2p.*"`:
  - First run: BUILD SUCCESSFUL, `--- gradle exit=0`.
  - Three consecutive `--rerun` runs: all BUILD SUCCESSFUL, exit=0.
  - Counts: TutkCameraLinkTest 20/0, TransportLinkFactoryTest 3/0, plus every other p2p suite green (100 p2p tests).
- `:app:assembleDebug`: BUILD SUCCESSFUL, exit=0.
- Full `:app:testDebugUnitTest`: 489 tests, 0 failures/errors, exit=0.
- Mutation evidence (RED substitute):
  - Six source mutations: `bigEndian = true`, an off-by-one in the no-data bound, a drop rule that never ends, connectStop removed, a 14-char nonce, and TUTK routed to the TNP factory.
  - Result: 7 tests failed, exit=1. The sources were restored and the runs are green again.
- Acceptance greps:
  - `TransportType.TUTK` in TransportLinkFactory.kt: line 55.
  - `TransportLinkFactory` in P2pLinkModule.kt: line 33.
  - `bigEndian = false` in TutkCameraLink.kt: 1 line.
  - `/*` in the touched files: none.
- The three GRACE XML docs parse.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RED commit with `TODO()` stubs replaced by a mutation check**
- **Issue:** A local write hook rejects files that contain only `TODO()` stubs (as in 02-03/02-04/02-07/02-10).
- **Fix:** I wrote the implementation and tests together, then proved the tests can fail by mutating the sources (see Verification).
- **Commit:** 6d832f5. The plan's `test(02-11)` + `grace` pair became one grace commit.

**2. [Rule 3 - Blocking] FakeTutkNative extended**
- **Issue:** The cancellation and auth tests could not be written with the existing fake.
- **Fix:** I added blocking `connectGate` and `clientStartGate`. `connectStop` makes the connect return `IOTC_ER_ABORTED`, and `stop(sid>=0)` makes clientStart return `AV_ER_CLIENT_EXIT`. I also added in-memory `lastUid`, `lastUser` and `lastPass` for the assertions. They are never put in `calls`.
- **Files:** FakeTutkNative.kt (v0.2.0).

**3. [Rule 2 - Correctness] Session id released after a failed or aborted connect**
- **Fix:** Teardown calls `stop(-1, sid0)` once, so the IOTC session slot taken by `IOTC_Get_SessionID` is freed.

**4. [Rule 2 - GRACE] Docs**
- V-M-TUTK-LINK has scenarios 8-10 and note-3.
- The development-plan M-TUTK-LINK has note-3.
- The knowledge graph has the `export-TutkLinkFactory` export, a change-summary, and CrossLinks to M-STREAM-CORE and M-TNP-LINK and from M-UI-LIVE-PROBE.
- Module STATUS stays `planned` until device check 02-12.

### Notes

- The write hook flagged `PASSWORD = "SYNTH1234567890"` in the tests. That is the synthetic fixture already used by the TNP tests, not a real secret.
- Requirements LIVE-01..03 are NOT marked complete. They close only after device verification (02-12).
- No vendor binaries, `_re/` or `_apk/` paths were staged.

## Known Stubs

None. `AndroidTutkNative` and the real IOTC/AV behaviour are verified on the device in 02-12.

## Self-Check: PASSED
