---
phase: 06-release-build
reviewed: 2026-11-21T12:27:23Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - .gitignore
  - docs/development-plan.xml
  - docs/knowledge-graph.xml
  - docs/PROJECT_STATUS.md
  - docs/verification-plan.xml
  - HANDOFF.md
  - tools/git-hooks/test-pre-commit.sh
findings:
  critical: 0
  warning: 2
  info: 1
  total: 3
status: issues_found
---

# Phase 6: Code Review Report

**Reviewed:** 2026-11-21T12:27:23Z
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

Reviewed the phase-06 change set (base `7987ff8`): the release-signing ignore rules in `.gitignore`, the GRACE planning/verification artifacts (`development-plan.xml`, `knowledge-graph.xml`, `verification-plan.xml`, `PROJECT_STATUS.md`), the bootstrap doc `HANDOFF.md`, and the 13-case pre-commit hook self-test `tools/git-hooks/test-pre-commit.sh`.

No security vulnerabilities or runtime defects were found. The repo-guard self-test was executed under Git Bash and passes all 13 cases (`RESULT: all 13 cases passed`, exit 0); the signing-password path/content rules it exercises are implemented consistently in `tools/git-hooks/pre-commit`, and the new `.gitignore` patterns correctly cover keys, signing properties and local scratch state. All referenced tools/docs (`docs/RELEASE.md`, `tools/*.ps1`, phase-06/07 artifacts) exist.

The findings are limited to **stale/contradictory state in the documentation artifacts**. In this GRACE project the planning XML and status docs are load-bearing (AGENTS.md: "Knowledge Graph Is Always Current", "Verification Is a First-Class Artifact"), so contradictory version status and gap accounting are treated as real defects rather than style nits. Note that the reviewed docs mix phase-06 content (release 1.0.0 / 10000, open gaps) with later phase-07 content (release 1.0.2 / 10002, gaps closed); the contradictions below stem from that mix.

## Warnings

### WR-01: HANDOFF.md bootstrap TL;DR contradicts the canonical status doc it points to

**File:** `HANDOFF.md:8` (with `docs/PROJECT_STATUS.md:16`, `docs/PROJECT_STATUS.md:20-21`)

**Issue:** The TL;DR — the mandatory first-read bootstrap for an agent on a new machine, per AGENTS.md and PROJECT_STATUS.md §0 — states "GSD Фазы 1–6 реализованы; релизная сборка 1.0.0 (versionCode 10000) собрана, подписана и проверена … (H-R1..H-R14, 10 pass, 3 open gaps)". `HANDOFF.md:10` then directs the reader to `docs/PROJECT_STATUS.md` as the current state, but that file reports "GSD Phase 7 … release 1.0.2 ready", `versionCode 10002`, and lists H-R3, H-R9 and H-R10 as **closed** gaps. A new agent following HANDOFF.md gets a stale, internally contradictory picture of the shipped version and of which gaps remain open; this can lead to work against the wrong release or re-opening already-fixed defects.

**Fix:** Bring the HANDOFF TL;DR in line with the canonical status (or make it explicitly phase-scoped). For example:
```markdown
- **Статус:** GSD Фазы 1–7 реализованы; релиз 1.0.2 (versionCode 10002) собран из чистого клона, подписан и проверен на Xiaomi Mi 9. Гэпы v1.0.0 (H-R3/H-R9/H-R10) устранены в Фазе 7; статус — `docs/PROJECT_STATUS.md` §0.11.
```

### WR-02: Release-build contract and verification are pinned to 1.0.0/10000 while the shipped build is 1.0.2/10002

**File:** `docs/development-plan.xml:188`, `docs/verification-plan.xml:426`, `docs/verification-plan.xml:442`, `docs/verification-plan.xml:1865`, `docs/verification-plan.xml:1872`

**Issue:** The newly added `M-RELEASE-BUILD` contract hardcodes its output as "versionName 1.0.0, versionCode 10000" (development-plan.xml:188), and `V-M-RELEASE-BUILD` scenario-3 asserts "versionName 1.0.0 / versionCode 10000" with evidence records repeating those values (verification-plan.xml:426, 442, 1865, 1872). The actual build is `versionCode = 10002`, `versionName = "1.0.2"` (`app/build.gradle.kts`), and the **same verification-plan file** records 1.0.2/10002 as the passed release in `Gate-GSD-Phase-7` evidence-4 (verification-plan.xml:1905). Both artifacts claim `STATUS="passed"` while asserting different versions. A future re-run of V-M-RELEASE-BUILD will either mis-target version 1.0.0 or produce a false version-mismatch failure. (Related, out of scope: `tools/check-release-apk.ps1:51-61` reads the version from Gradle but falls back to a stale `1.0.1`/`10001` — worth aligning as well.)

**Fix:** Make the contract and scenario version source-of-truth-neutral, or update them to the current version with a historical note. For example:
```xml
<param name="app-release.apk" type="APK Signature Scheme v2/v3, versionName/versionCode read from app/build.gradle.kts (phase-06 baseline 1.0.0 / 10000), ..." />
<scenario-3 kind="success" evidence-type="deterministic">versionName / versionCode match app/build.gradle.kts, not debuggable, no minifyReleaseWithR8 task</scenario-3>
```

## Info

### IN-01: Phase-6 gate lists H-R10 as an open gap while the same gate marks it passed

**File:** `docs/verification-plan.xml:1889` (vs `docs/verification-plan.xml:1881`)

**Issue:** `note-2` of `Gate-GSD-Phase-6` says the gate stays in-progress partly because of "H-R10 (slideshow playback in archive from events)", but the gate's own `device-checks` marks `<H-R10 … status="passed">` (with only an inline "gap: slideshow playback"), and evidence-3 likewise records H-R10 as passed. The gap status for H-R10 is represented two contradictory ways in one artifact, which will confuse a milestone audit deciding whether v1.0 can close.

**Fix:** Pick one representation: either change the H-R10 device-check to `status="partial"` and keep it in `note-2`, or drop H-R10 from `note-2` and keep `status="passed"` with the cosmetic gap noted inline.

---

_Reviewed: 2026-11-21T12:27:23Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
