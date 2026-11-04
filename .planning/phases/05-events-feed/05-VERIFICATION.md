---
phase: 05-events-feed
verified: 2026-11-04
status: human_needed
score: 5/5 success criteria implemented and wired in code (JVM-tested); device-verified 5/5, SC-3 only on the happy path (with SD and a recording); no failed device check
re_verification: false
open_blockers: 0
human_verification:
  - test: "H-EV3 label decision (EVT-02, D-03): look at the day button with no day selected on the «События» tab"
    expected: "The user confirms «Все дни ▾» (deviation from the letter of D-03, which says «Сегодня ▾») or asks to change it; a change is a small UI follow-up, the filter itself passed"
    why_human: "Product wording decision only the user can make"
  - test: "H-EV7 jump without SD or without a recording (EVT-04, Phase 4 D-07): «Открыть запись на SD» for an event of a camera without a microSD card, or of an event at a moment without a recording"
    expected: "The camera screen opens with live video and the Phase 4 text («Нет SD-карты» / «Нет записи в это время» / «Архив SD на этой камере недоступен»); nothing hangs; back returns to «События»"
    why_human: "Not exercised on 2026-11-04: the only camera with events has a card and no event at a moment without a recording was found; logic covered by ArchiveCoordinatorTest only"
  - test: "Clip picture edge (EVT-03, cosmetic): play a clip in the dialog and look at the bottom edge"
    expected: "No green strip; currently a thin green strip is visible (probably decoder padding). Decide whether to fix or accept"
    why_human: "Visual on the device"
---

# Phase 5: Лента событий — Verification Report

**Phase goal:** «Пользователь видит события движения по всем камерам с превью, смотрит бесплатные клипы и переходит к полной записи на SD, а аккаунт не рискует из-за частых запросов»
**Verified:** 2026-11-04
**Status:** human_needed
**Re-verification:** No, this is the initial verification.

## Summary

EVT-01..EVT-06 are implemented and wired (plans 05-00..05-05, GRACE sync in 05-06). The device session on the Mi 9 (plan 05-07, 2026-11-04) passed 9 of 10 checks, H-EV7 was not exercised, nothing failed. Two passes (H-EV4 clip dialog, H-EV10 log sweep) needed fixes that were made and re-checked in the same session; a third fix removed a stuck pull-to-refresh indicator.

Why `human_needed` and not `passed`: one device check (H-EV7) is not exercised and one UI decision (H-EV3 label vs D-03) is open with the user. Neither is a known defect, so this is not `gaps_found`. There are no open blockers. If the user accepts «Все дни ▾» and accepts H-EV7 as JVM-covered, the phase can close with caveats, as Phase 4 did. Requirements are NOT marked complete here; that is the orchestrator's decision.

## Test evidence

- Full JVM gate on the build installed for the session (run by the orchestrator, 2026-11-04): 1102 tests, 0 failures. `RealLogScanTest` over the pulled device log (738 lines): tests=1 skipped=0 failures=0.
- Device evidence: `05-DEVICE-VERIFICATION.md`, per-check results in `05-HUMAN-UAT.md`, probe facts in `05-PROBE.md`.
- This report was written from those documents; no new Gradle or device run was made for it.

## Observable Truths (ROADMAP success criteria)

| # | Truth | Code | Device | Evidence |
|---|-------|------|--------|----------|
| 1 | Feed of motion events of all cameras with preview and time, filtered by camera and day | ✓ | ✓ PASS (H-EV1, H-EV2, H-EV3) | 23 events grouped «Сегодня» / «Вчера», chips incl. the empty-filter text, calendar dots and day filter; label question open (D-03) |
| 2 | The 6-second free clip opens and plays | ✓ | ✓ PASS after 2 fixes (H-EV4, H-EV5) | MediaPlayer on TextureView plays mp4 / H.264; link refresh once per open (`reason=missing`), then `status=200 magic=mp4`; cosmetic green strip |
| 3 | One action from an event opens the SD recording of that camera at the event moment | ✓ | ✓ PASS on the happy path (H-EV6 x2); no-SD path not exercised (H-EV7) | `BLOCK_ARCHIVE_OPEN_AT result=seek`, `BLOCK_SEEK result=Archive`, archive 8-12 s after the event time with the same scene |
| 4 | Without network, cached events and previews are visible | ✓ | ✓ PASS (H-EV9) | Wi-Fi off + force-stop: all cards with previews from the cache |
| 5 | Foreground polling at most once per 5 minutes; payment / subscription endpoints and `push_prop` never called | ✓ | ✓ PASS (H-EV8, H-EV10) | gate too-early on re-entry, pull and after force-stop; one page after 5 min; forbidden-path grep 0; no media links in the log |

**Score:** 5/5 implemented; 5/5 device-confirmed on the main path, SC-3 without the no-SD branch.

## Requirements Coverage

| Req | Code | Device | Caveat |
|-----|------|--------|--------|
| EVT-01 | SATISFIED-in-code | PASS (H-EV1) | — |
| EVT-02 | SATISFIED-in-code | PASS (H-EV2, H-EV3) | label «Все дни ▾» vs D-03, user decision |
| EVT-03 | SATISFIED-in-code | PASS (H-EV4, H-EV5) | green strip (cosmetic); HLS clips never seen |
| EVT-04 | SATISFIED-in-code | PASS with SD (H-EV6); H-EV7 not exercised | no-SD / no-recording path JVM-only |
| EVT-05 | SATISFIED-in-code | PASS (H-EV9) | retention beyond 2 days unknown |
| EVT-06 | SATISFIED-in-code | PASS (H-EV8, H-EV10) | — |

No orphaned requirements: REQUIREMENTS.md maps EVT-01..EVT-06 to Phase 5 and every one is covered by the plans.

## Defects found during device verification (all fixed)

| Defect | Fix | Check |
|---|---|---|
| Clip dialog read Room on the main thread (IllegalStateException) | a43316d (test), f8ff4af | H-EV4 |
| Dead pooled CDN connection, clip status -1 (`retryOnConnectionFailure(false)`) | 4f97df7 | H-EV4, H-EV5 |
| Idle pull-to-refresh indicator over the filter row | 2658424 | screenshots |
| `HTTP FAILED` log lines carried server and phone IPv4 (Phase 1 hole) | 5b47900 (test), de20d23, c6163c4 | H-EV10 |

## Open risks (not blockers)

- D-03 day button label: «Все дни ▾» vs «Сегодня ▾» (user decision).
- Thin green strip under the clip (cosmetic).
- H-EV7 not exercised on the device.
- Event retention beyond 2 days unknown; the feed held 2 days of events.
- Events of shared cameras: not observable on this account (no shared camera).
- HLS clips never seen (all mp4); they stay «Клип недоступен».
- `EventMediaFiles.wipeAll` ignores the delete result (deferred-items.md).
- Environment, not code: unstable phone-to-cloud connectivity at night on 2-4 November; the login session lives 24 h, after which a new login is needed.

## Anti-patterns

Not re-scanned in this docs-only plan. The plan summaries 05-00..05-06 report no stubs in the feed path; the device session showed real data on every screen it touched.

> **Closed 2026-11-04 with a caveat (decision taken by the orchestrator on `/gsd:next`; the user can reopen either point):** EVT-01..EVT-06 are marked complete. The day button keeps the label «Все дни ▾» with no day selected (a deviation from the letter of D-03; a one-line change if the user wants «Сегодня ▾»). H-EV7 (jump for a camera without a card or for a moment without a recording) is covered by JVM tests only. Also open: a thin green stripe at the bottom of the playing clip, event retention beyond two days, events of shared cameras (none on this account), HLS clips (none seen). See 05-HUMAN-UAT.md and deferred-items.md.
