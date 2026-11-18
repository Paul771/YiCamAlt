---
phase: 07-regression-fixes
plan: 04
subsystem: release
tags: [gradle, apk-signing, release-checker, adaptive-icon, grace-docs, uat]

# Dependency graph
requires:
  - phase: 07-01
    provides: TNP live audio routing fix used by the release build
  - phase: 07-02
    provides: TUTK SD archive index fix used by the release build
  - phase: 07-03
    provides: FrameAssembler gap recovery and smooth event-to-SD playback used by the release build
provides:
  - versionCode/versionName bump to 1.0.1 (10001), later superseded by 1.0.2 (10002) in 77dde98
  - version auto-detection in tools/check-release-apk.ps1 from app/build.gradle.kts
  - adaptive launcher icon set (Yi Home palette) wired through AndroidManifest.xml
  - GRACE docs synced with the Phase 7 fixes
  - 07-UAT.md device regression script and 07-DEVICE-VERIFICATION.md evidence template
affects: [release, verification, UAT, phase-08]

# Actuals — chars/4 over the realized text diff; 19 binary icon assets are not countable
actuals:
  tokens: 150
  tasks: 2
  commits: 3

# Tech tracking
tech-stack:
  added: [adaptive icon drawables, check-release-apk version auto-detection]
  patterns: [version derived from build.gradle.kts instead of hardcoded in the checker]

key-files:
  created:
    - app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
    - app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
    - .planning/phases/07-regression-fixes/07-UAT.md
    - .planning/phases/07-regression-fixes/07-DEVICE-VERIFICATION.md
  modified:
    - app/build.gradle.kts
    - tools/check-release-apk.ps1
    - app/src/main/AndroidManifest.xml
    - docs/PROJECT_STATUS.md
    - docs/development-plan.xml
    - docs/verification-plan.xml
    - docs/knowledge-graph.xml

key-decisions:
  - "Version is read from build.gradle.kts by the release checker so future bumps cannot desync the gate"
  - "Device regression is a blocking human checkpoint (plan autonomous: false); it is not claimed complete from the harness"

patterns-established:
  - "Release tooling derives expected version from the build file rather than duplicating the constant"

requirements-completed: [REL-01, REL-02]

# Coverage metadata
coverage:
  - id: D1
    description: "Version bump and GRACE documentation synced with Phase 7 fixes"
    requirement: REL-01
    verification:
      - kind: unit
        ref: "tools/gradle.ps1 :app:testDebugUnitTest — 1111 tests, 0 failures"
        status: pass
    human_judgment: false
  - id: D2
    description: "Release checker auto-detects the expected version; clean-clone release APK validates"
    requirement: REL-02
    verification:
      - kind: manual_procedural
        ref: "07-DEVICE-VERIFICATION.md — RESULT=PASS commit=f77223c; versionName=1.0.1 versionCode=10001, v2/v3 ok, 8 libs ok"
        status: pass
    human_judgment: false
  - id: D3
    description: "Adaptive launcher icon set matching the Yi Home colour palette"
    verification:
      - kind: unit
        ref: "tools/gradle.ps1 :app:testDebugUnitTest — resource merge and compile pass"
        status: pass
    human_judgment: true
    rationale: "Visual result on a real launcher cannot be judged by unit tests"
  - id: D4
    description: "Device regression UAT script and evidence template prepared"
    verification:
      - kind: other
        ref: "07-UAT.md, 07-DEVICE-VERIFICATION.md exist with H-R1..H-R14 table"
        status: pass
    human_judgment: false
  - id: D5
    description: "Physical device regression H-R3, H-R9, H-R10, H-R6, calendar, logcat sweep on Xiaomi Mi 9"
    requirement: REL-02
    verification: []
    human_judgment: true
    rationale: "Requires the physical Xiaomi Mi 9, the user's Yi cameras and manual observation; 6 criteria are still [pending] in 07-DEVICE-VERIFICATION.md"

# Metrics
duration: 0min
completed: 2026-11-18
status: halted
---

# Phase 07: regression-fixes — Plan 04 Summary

**Release tooling and v1.0.1 bump shipped with a version-aware APK checker and adaptive launcher icon; physical device regression remains at a blocking human checkpoint**

## Performance

- **Duration:** not recorded in the originating sessions; close-out on 2026-11-18
- **Started:** 2026-11-14 (Phase 7 planning)
- **Completed:** 2026-11-18 (close-out, halted at human gate)
- **Tasks:** 2 of 3 completed (Task 3 is the blocking human checkpoint)
- **Files modified:** 27 across 3 task commits

## Accomplishments

- Bumped `app/build.gradle.kts` to versionName 1.0.1 / versionCode 10001 and synced all four GRACE XML docs plus PROJECT_STATUS with the Phase 7 fixes
- Release checker now derives the expected version from `build.gradle.kts` instead of a hardcoded constant
- Added the adaptive launcher icon set (5 densities, foreground/round variants) and wired it in `AndroidManifest.xml`
- Prepared `07-UAT.md` and `07-DEVICE-VERIFICATION.md` with the H-R1..H-R14 regression matrix
- Clean-clone build and APK validation recorded as PASS in `07-DEVICE-VERIFICATION.md` (`RESULT=PASS commit=f77223c`, v2/v3 signatures valid, 8/8 vendor libs)

## Task Commits

1. **Task 1: Version bump to 1.0.1 and sync GRACE documentation** — `7d7ed4e` (chore)
2. **Task 2: Release APK validation tooling and UAT template** — `f77223c` (chore), `39d9748` (feat)
3. **Task 3: Device regression on Xiaomi Mi 9** — not committed; blocking human checkpoint, 6 criteria still `[pending]`

**Plan metadata:** `85d9344` (docs: defer incomplete Phase 2 and 7 items to backlog)

## Files Created/Modified

- `app/build.gradle.kts` — version 1.0.1 (10001) at the time of this plan; now 1.0.2 (10002) after `77dde98`
- `tools/check-release-apk.ps1` — reads expected version from the build file
- `app/src/main/AndroidManifest.xml` — adaptive icon references
- `app/src/main/res/mipmap-*/ic_launcher*.png`, `mipmap-anydpi-v26/*.xml` — launcher icon assets
- `docs/PROJECT_STATUS.md`, `docs/development-plan.xml`, `docs/knowledge-graph.xml`, `docs/verification-plan.xml` — GRACE sync
- `07-UAT.md`, `07-DEVICE-VERIFICATION.md` — physical regression procedure and evidence log

## Decisions Made

- Release checker reads the version from `build.gradle.kts` so a future bump cannot silently desync the gate.
- Task 3 was left at the designed `blocking-human` checkpoint. Automation cannot observe audio on the media loudspeaker, SD archive index loading, or playback smoothness.

## Deviations from Plan

- **Version superseded:** the plan targets 1.0.1 (10001); a later commit `77dde98` bumped the app to 1.0.2 (10002). The release checker auto-detection makes this harmless, but `must_haves` truth #1 is no longer literally true at HEAD.
- **Task 3 not executed:** recorded as `status: halted`, not `complete`. This is the plan's own `gate="blocking-human"` checkpoint.

**Total deviations:** 2
**Impact on plan:** No code correctness impact. Phase 7 cannot be marked verified until the 6 pending device criteria are observed by the user.

## Issues Encountered

- `safe_resume_gate` found three production commits for this plan with no SUMMARY.md and no async-job deferral manifest. Close-out was chosen by the user over re-execution to avoid duplicating work.
- The working tree carries 5 uncommitted files (frame queue 48→128, H-R10) belonging to the user. They were excluded from all close-out commits.

## User Setup Required

None - no external service configuration. Physical device access is required for Task 3.

## Next Phase Readiness

- Release automation and documentation are in place; `:app:testDebugUnitTest` passes **1111 tests / 0 failures** at HEAD (including the uncommitted queue change).
- **Blocker:** 6 device criteria remain `[pending]` in `07-DEVICE-VERIFICATION.md` (H-R3 TNP audio, H-R9 TUTK SD index, H-R10 smooth event→SD playback, H-R6 relay SD default, calendar day picker, logcat redaction sweep). Resolve with `/gsd-verify-work 07` on the Xiaomi Mi 9.

---
*Phase: 07-regression-fixes*
*Completed: 2026-11-18 (halted at human checkpoint)*
