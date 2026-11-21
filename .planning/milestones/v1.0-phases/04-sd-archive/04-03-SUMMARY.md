---
phase: 04-sd-archive
plan: 03
subsystem: sd-archive
tags: [archive, timeline, seek, ioctrl, repository, coroutines, tdd]
requires:
  - phase: 04-sd-archive
    provides: LinkIoCtrlReply / StreamMode / FrameObservation (04-00), SdArchiveCodec + SdTime (04-01)
provides:
  - ArchiveLinkPort contract, Segment, SdStatus, IndexResult, SeekTarget, ArchiveSupport, sdStatusOf
  - SdTimeline (fromEntries, daysWithRecordings, segmentsForDay, nextSegmentStart, resolveSeek)
  - PlaybackCommands + PlaybackCmd (seek / pause / live per transport and p2pEncrypt)
  - SdIndexRepository (fetchStatus, fetchIndex with 10 s per-reply timeout)
  - FakeArchivePort test double for later archive plans
affects: [04-04, 04-05, 04-06, 04-08]
tech-stack:
  added: []
  patterns:
    - "Subscribe-before-send: UNDISPATCHED collector into an UNLIMITED channel, then sendIoCtrl, then withTimeoutOrNull receive"
    - "Fake port scripts replies synchronously from onSend (tryEmit into a buffered SharedFlow)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/archive/SdTypes.kt
    - app/src/main/java/com/yicamalt/archive/SdTimeline.kt
    - app/src/main/java/com/yicamalt/archive/PlaybackCommands.kt
    - app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt
    - app/src/test/java/com/yicamalt/archive/SdTimelineTest.kt
    - app/src/test/java/com/yicamalt/archive/SdStatusTest.kt
    - app/src/test/java/com/yicamalt/archive/PlaybackCommandsTest.kt
    - app/src/test/java/com/yicamalt/archive/SdIndexRepositoryTest.kt
    - app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt
  modified: []
decisions:
  - "Segments merge when the gap is <= MERGE_GAP_MS (2 s, inclusive); overlaps keep the larger end"
  - "resolveSeek returns Live for any target within 10 s of now or in the future"
  - "TUTK paging is capped at 1000 replies (accepted + rejected) so a stream of malformed pages cannot loop forever"
  - "fetchStatus logs tfstat=null when there is no / an unparsable 817 reply"
metrics:
  duration: 20min
  completed: 2026-10-30
  tasks: 3
  files: 9
---

# Phase 4 Plan 03: Archive core (types, timeline, commands, index repository) Summary

The pure archive layer above the byte codec. Index entries become merged segments and per-day clipped timelines (DST-safe, zone injected). A seek target resolves to Archive / Live / NoRecording. Seek, pause and live command lists are byte-exact per transport and per p2pEncrypt. SdIndexRepository turns 816/817 and 9031/9032 or 792/793 exchanges into typed results (Ok, Timeout, Malformed, NotReady; status Unknown) over the new `ArchiveLinkPort`. All of it is JVM-only and tested against a fake port.

## Tasks

| # | Task | RED commit | GREEN commit |
|---|------|------------|--------------|
| 1 | Archive types, port contract, SdTimeline | 42b7fa7 | 1cfdaac |
| 2 | PlaybackCommands per transport | 6b41e47 | 58f0164 |
| 3 | SdIndexRepository over ArchiveLinkPort | a1873f6 | a6ceab6 |

## Verification (actually run)

- Task 1 RED: `--tests "com.yicamalt.archive.*"` gave 56 tests, 24 failed (18 timeline + 6 status on `RED stub`). The ArchiveSupport test passed, because ArchiveSupport is part of the contract and was real in the stub. BUILD FAILED as expected. GREEN: BUILD SUCCESSFUL, exit=0. JUnit XML: SdTimelineTest 18/0, SdStatusTest 7/0. `systemDefault` in SdTimeline.kt/SdTypes.kt: 0 lines. `interface ArchiveLinkPort`: 1 line.
- Task 2 RED: 66 tests, 8 failed (the toString and byte-order tests pass in RED because they do not call the stubs). GREEN: BUILD SUCCESSFUL, exit=0, PlaybackCommandsTest 10/0. The reuse grep (`IoCtrlMessages.startRealtime|tutkStart|stop()`) matches 4 lines.
- Task 3 RED: 83 tests, all 17 SdIndexRepositoryTest tests failed. GREEN: BUILD SUCCESSFUL. Then `--tests "com.yicamalt.archive.*" --rerun` ran 3 times in a row, each BUILD SUCCESSFUL with exit=0. The XML timestamps are fresh and show 83 archive tests with 0 failures/errors on every run. Greps: `CoroutineStart.UNDISPATCHED` 1 line; START_BLOCK_FETCH_INDEX|SD_STATUS 2; END_ 2; `@Test` count 17.
- Full JVM suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, exit=0. 786 tests, 1 skipped, 0 failures, 0 errors, counted from the JUnit XML. That is 734 before this plan plus 52 new. The skipped test is pre-existing, not from this plan.

## Deviations from Plan

**1. [Extra coverage] Tests beyond the listed cases**
- Timeline: overlap merge, a 3 s gap stays split, a segment ending exactly at midnight marks one day, the first day is clipped at midnight, an empty index has no days, the end clamp never goes before the last start, a future target gives Live.
- Status: an ArchiveSupport transports test.
- Commands: stop-record byte order spot-checked independently of the builder; TUTK seek layout spot-checked (cmd at offset 4, LE year at 12, useCount at 20).
- Repository: TUTK status reads capacity little-endian; TUTK stops at total without endflag; TUTK send refused gives NotReady.

**2. [Rule 2 - Robustness] TUTK page cap counts rejected pages too**
- The plan says "max 1000 pages". The loop bound is accepted + rejected < 1000, so a camera that only sends malformed pages cannot keep the fetch alive forever. With a per-reply timeout this is bounded anyway; the cap just makes it explicit.

Otherwise the plan was executed as written. docs/*.xml were not touched; the plan does not list them. M-SD-ARCHIVE graph/verification sync stays with plan 04-08.

## Known Stubs

None. `ArchiveLinkPort` has no production implementation yet by design: 04-04 (debug probe) and 04-06 (live supervisor) implement it.

## Self-Check: PASSED

- FOUND: all 9 files listed in key-files.created
- FOUND commits: 42b7fa7, 1cfdaac, 6b41e47, 58f0164, a1873f6, a6ceab6
