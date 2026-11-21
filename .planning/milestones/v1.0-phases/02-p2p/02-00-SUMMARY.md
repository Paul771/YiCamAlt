---
phase: 02-p2p
plan: 00
subsystem: stream-ports
tags: [p2p, pppp, contracts, redaction, grace]
requires: []
provides:
  - "CameraLink / CameraLinkFactory / P2pCredentialsSource ports + LinkState family (com.yicamalt.stream)"
  - "VideoCodec, FrameInfo, VideoUnit, DecodableFrame, VideoUnitSink, DecodableFrameSink (com.yicamalt.stream)"
  - "PpppNative seam, PpppCheck, PpppCodes name/classify (com.yicamalt.p2p)"
  - "FakePpppNative JVM test double"
  - "RedactionScanner IPv4 + authInfo shapes; fixtures AUTH_INFO / PRIVATE_IP / WAN_IP"
  - "GRACE planned nodes M-P2P-NATIVE, M-TNP-LINK, M-STREAM-CORE, M-MEDIA-RENDER, M-UI-LIVE-PROBE, M-TUTK-LINK"
affects: [02-01, 02-02, 02-03, 02-04, 02-05, 02-07, 02-08, 02-09, 02-10, 02-11, 02-12]
tech-stack:
  added: []
  patterns: ["interface-first port types before parallel waves", "scripted native fake on the JVM"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/stream/StreamTypes.kt
    - app/src/main/java/com/yicamalt/stream/CameraLink.kt
    - app/src/main/java/com/yicamalt/p2p/PpppNative.kt
    - app/src/test/java/com/yicamalt/p2p/FakePpppNative.kt
    - app/src/test/java/com/yicamalt/p2p/PpppContractTest.kt
    - .planning/phases/02-p2p/02-PHASE-BASE.txt
  modified:
    - app/src/test/java/com/yicamalt/test/RedactionFixtures.kt
    - app/src/test/java/com/yicamalt/test/RedactionScanner.kt
    - app/src/test/java/com/yicamalt/test/RedactionScannerTest.kt
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "RedactionScanner IPv4 pattern skips 127.x loopback: MockWebServer URLs in existing HTTP tests are not peer addresses"
  - "Phase 2 contract files use // doc comments only (no KDoc block comments) so the `/*` grep gate stays empty"
metrics:
  duration: 15min
  completed: 2026-10-13
  tasks: 3
  files: 12
---

# Phase 2 Plan 00: Wave-0 contracts, PPPP seam and redaction shapes Summary

Contract-first port types (CameraLink, StreamTypes, PpppNative/PpppCodes), a scripted FakePpppNative with 13 contract tests, IPv4/authInfo redaction shapes, and GRACE nodes for six planned P2P modules with the cloud-stream modules marked superseded.

## Baseline

- Phase base: `6d5e447bc368f2304fb7c41e20f1a54a844803e0` (`.planning/phases/02-p2p/02-PHASE-BASE.txt`)
- Baseline `:app:testDebugUnitTest` on the phase base: BUILD SUCCESSFUL, exit 0 — **22 suites, 278 tests, 1 skipped, 0 failures**
- After this plan: **23 suites, 300 tests, 1 skipped, 0 failures** (+9 RedactionScannerTest cases, +13 PpppContractTest)

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Baseline gate, phase base, P2P redaction shapes | 6ae83f5 |
| 2 | Interface-first contracts + FakePpppNative | 936fe52 |
| 3 | GRACE planned nodes; supersede M-STREAM-LIVE / M-STREAM-DECODE | 41cd897 |

## Contract signatures

All names, parameter orders and defaults are exactly as in the plan's `<interfaces>` block; nothing was renamed. The only change in form: doc comments are `//` line comments instead of `/** */`, so the plan's acceptance grep `grep -rn "/\*" app/src/main/java/com/yicamalt/stream app/src/main/java/com/yicamalt/p2p` returns nothing. Downstream plans should keep to `//` comments in these packages.

FakePpppNative surface (for 02-07 and later): `initResult`, `apiVersionValue`, `connectResult`, `connectBlocks`, `checkResult`, `writeResult`, `written`, `calls`, `enqueue(ch, bytes)`, `injectRemoteClose(ch)` (sticky: every later read on that channel returns -3012), `MAX_READ_WAIT_MS = 50`. Reads after `forceClose` return -3014; writes after it return -3011; a blocked `connect` returns -3019 on `connectBreak`.

## GRACE reference sweep

Before: 57 lines matched `STREAM-LIVE|STREAM-DECODE|FakeCameraStreamServer|StreamHandleFake` in docs/*.xml. After: 44 lines (the five removed CrossLinks, the M-UI-LIVEVIEW depends in both files, and the four rewritten data-flow steps are gone). Each remaining line is:
- development-plan.xml: inside the superseded nodes (l.361-444), the M-UI-LIVEVIEW purpose (l.559, annotated), the DF-LIVE-STREAM note (l.1132), Phase-3 steps 1-2 (l.1202-1203, `status="superseded"`).
- knowledge-graph.xml: only inside the superseded nodes (l.159-188); no `<depends>` of another node, no CrossLink from M-STREAM-LIVE.
- verification-plan.xml: l.6, 18, 63 (inline note), harness-4 / harness-7 (l.28, 32, `STATUS="superseded"`, files kept), matrix rows l.124-125, superseded V-M nodes l.554-624, V-M-UI-LIVEVIEW l.950 / 960 (StreamHandleFake note, node itself not superseded), autonomy rows l.1153-1154 (`rating="superseded"`), Gate-Phase-3 evidence l.1235/1236/1239 (also evidence-6, the reconnect cap), failure-packet example l.1270-1286 (`concrete-example STATUS="superseded"` + inline notes). A script check confirmed no matching line sits outside a superseded node without the word `superseded`.

Also added: harness-8 FakePpppNative, six V-M nodes (`STATUS="planned"`, planned test files from 02-03…02-11, `tools/gradle.ps1` checks, scenario stubs, value-free markers), matrix rows and autonomy rows for the new modules, Phase-3 steps 8-13, a rewritten DF-LIVE-STREAM, and risk-2.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] IPv4 pattern flagged MockWebServer loopback URLs**
- **Found during:** Task 1 (full-suite run after the scanner change)
- **Issue:** 7 existing HTTP tests (HttpClientModuleTest, CameraRegistryCredentialsTest, AuthRegionDiscoveryTest) failed; the only hit was `127.0.0.1` from MockWebServer request-log lines. That is test infrastructure, not one of our value-bearing log lines.
- **Fix:** narrowed the pattern with `(?!127\.)` and explained why in the RedactionScanner CHANGE_SUMMARY; added a `safe loopback mock server url is clean` test. PRIVATE_IP (192.168.x) and WAN_IP (TEST-NET-3) are still flagged.
- **Files modified:** app/src/test/java/com/yicamalt/test/RedactionScanner.kt, RedactionScannerTest.kt
- **Commit:** 6ae83f5

**2. [Rule 3 - Blocking] `/*` acceptance grep against the plan's KDoc**
- **Found during:** Task 2
- **Issue:** the `<interfaces>` block uses `/** ... */` KDoc, but the acceptance criterion requires no `/*` at all in the stream/p2p packages.
- **Fix:** turned every doc comment into a `//` line comment with the same text.
- **Commit:** 936fe52

### Notes

- TDD: the plan sets three fixed commit messages, so the RED test and the GREEN implementation of Task 2 went into one commit. The test file was written first.
- Requirements LIVE-01..03 (plan frontmatter) were deliberately NOT marked complete: this plan only creates contracts; live video is delivered by later Phase 2 plans and the device checkpoints.
- `V-M-P2P-NATIVE` lists `:app:verifyVendorNatives` as a module check. That task does not exist yet; plan 02-02 adds it.

## Known Stubs

None. These are type-only contracts and a test fake. Their implementations come in later plans by design: AndroidPpppNative in 02-03, TnpCameraLink in 02-07.

## Self-Check: PASSED

- Files exist: StreamTypes.kt, CameraLink.kt, PpppNative.kt, FakePpppNative.kt, PpppContractTest.kt, 02-PHASE-BASE.txt
- Commits exist: 6ae83f5, 936fe52, 41cd897 (each with the Co-Authored-By trailer)
- XML_OK for the three docs; PpppContractTest and RedactionScannerTest `failures="0"`
