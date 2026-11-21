---
phase: 04-sd-archive
plan: 01
subsystem: sd-archive
tags: [archive, ioctrl, codec, stimeday, java-time, tdd]
requires:
  - phase: 04-sd-archive
    provides: CameraLink archive port (04-00)
provides:
  - SdArchiveCodec (IOTYPE constants, 816/9031/792/9030/12718/794 builders, 9032/793/817 parsers)
  - STimeDay, IndexEntry, TutkIndexPage, DeviceInfo value types
  - SdTime (WIRE_IS_UTC seam, toWire/fromWire, DST-safe dayBounds, localDate)
  - M-SD-ARCHIVE stub node in docs/knowledge-graph.xml
affects: [04-02, 04-03, 04-04, 04-05, 04-06, 04-08]
tech-stack:
  added: []
  patterns:
    - "Byte fixtures built independently of the codec (SdFixtures) so parser tests do not round-trip through the builder"
    - "Day bounds as half-open LongRange via atStartOfDay (no fixed 86 400 000)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/archive/SdArchiveCodec.kt
    - app/src/main/java/com/yicamalt/archive/SdTime.kt
    - app/src/test/java/com/yicamalt/archive/SdArchiveCodecTest.kt
    - app/src/test/java/com/yicamalt/archive/SdTimeTest.kt
    - app/src/test/java/com/yicamalt/archive/SdFixtures.kt
  modified:
    - docs/knowledge-graph.xml
decisions:
  - "dayBounds returns start until nextStart (LongRange.last = nextStart - 1); callers measure length as last + 1 - first"
  - "parseTutkIndexPage returns null for payloads under 12 B as well as count mismatches; event/status bytes are skipped"
  - "STimeDay wday is carried through parsers as-is; fromWire ignores it"
metrics:
  duration: 15min
  completed: 2026-10-30
  tasks: 3
  files: 6
---

# Phase 4 Plan 01: SD archive codec and wire time Summary

The pure byte layer of M-SD-ARCHIVE. Every archive IOCTRL payload (816, 9031/792, 9030, 12718/794 cmd 16 and cmd 1) is built in the transport byte order. The 9032, 793 and 817 replies are parsed with the vendor tolerance rules. Wire time is the UTC broken-down time, converted with java.time, and the single `WIRE_IS_UTC` constant is the seam if the device turns out to differ.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | RED: SdFixtures, SdArchiveCodecTest (21), SdTimeTest (10), signature-only main files | 8de9904 |
| 2 | GREEN: SdArchiveCodec + SdTime implementation | 964a00f |
| 3 | M-SD-ARCHIVE stub node + CrossLink in knowledge-graph.xml | 4454a71 |

## Verification (actually run)

- Task 1 RED: `:app:testDebugUnitTest --tests "com.yicamalt.archive.*"`. 31 tests completed, 30 failed with IllegalStateException (RED stub), BUILD FAILED as expected. The one test that passed is `iotype constants`, because the constants are real in the stub. `@Test` counts: codec 21 (needed >= 14), time 10 (needed >= 7).
- Task 2 GREEN: same command gave BUILD SUCCESSFUL, exit=0. JUnit XML: SdArchiveCodecTest 21/0/0, SdTimeTest 10/0/0. Greps: `error("RED stub")` = 0 in both files; `86_400_000|86400000` in SdTime.kt = 0 lines; `const val WIRE_IS_UTC = true` = 1 line.
- Task 3: `xml.dom.minidom.parse('docs/knowledge-graph.xml')` OK; `<M-SD-ARCHIVE` count = 1.
- Full JVM suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, exit=0, 726 tests, 0 failures, 0 errors, counted from the JUnit XML reports (695 before this plan + 31 new).

## Deviations from Plan

**1. [Extra coverage] Tests added beyond the listed cases**
- Codec tests: `deviceInfoRequest is 4 zero bytes`, `iotype constants`, `readTimeDay reads unsigned fields in both orders`.
- Time tests: `toWire drops sub-second part`, `default wire zone is UTC fields`, `fromWire in a non-UTC wire zone`.
- All listed cases are present. Test names use `12-34-56Z` instead of `12:34:56Z`, because colons are not valid in JVM method names on this platform.

**2. [Note] `verification-ref V-M-SD-ARCHIVE` in the graph node**
- The node references V-M-SD-ARCHIVE. That record does not exist yet in docs/verification-plan.xml, and this plan does not touch that file. A later Phase 4 GRACE sync plan should add it.

Otherwise the plan was executed as written.

## Known Stubs

None. The graph node is intentionally a stub; plan 04-08 completes it.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/archive/SdArchiveCodec.kt, SdTime.kt; app/src/test/java/com/yicamalt/archive/SdArchiveCodecTest.kt, SdTimeTest.kt, SdFixtures.kt
- FOUND commits: 8de9904, 964a00f, 4454a71
