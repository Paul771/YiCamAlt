---
phase: 01-secrets-camera-list
plan: 00
subsystem: tooling, grace-docs, event
tags: [gradle, powershell, grace, baseline]
requires: []
provides:
  - tools/gradle.ps1 with reliable argument binding, per-PID logs, honest exit code, no caller-pipe leak
  - tools/test-gradle-args.ps1 argument-binding self-test
  - phase base commit recorded (01-PHASE-BASE.txt)
  - green baseline (91 unit tests)
  - planned GRACE contracts M-DEBUG-PROBE, M-REPO-HYGIENE, M-P2P-CREDS
affects: [all later 01-* plans (verify commands), docs/*.xml]
tech-stack:
  added: []
  patterns:
    - "CmdletBinding(PositionalBinding=$false) + ValueFromRemainingArguments for pass-through wrappers"
    - "Clear HANDLE_FLAG_INHERIT on all handles (except stdin) before spawning a daemon-starting child"
key-files:
  created:
    - tools/test-gradle-args.ps1
    - .planning/phases/01-secrets-camera-list/01-PHASE-BASE.txt
  modified:
    - tools/gradle.ps1
    - app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "gradle.ps1 clears inheritance on every handle except stdin: clearing only std handles was proven insufficient (stray inherited pipe handles)"
  - "Unknown Gradle exit code is reported as 1, never as success"
metrics:
  duration: "~50 min active (wall clock longer: session limit pause)"
  completed: 2026-10-10
  tasks: 3
  files: 8
---

# Phase 01 Plan 00: Baseline and tooling Summary

Исправлена привязка аргументов в `tools/gradle.ps1` (с самопроверкой), устранены две скрытые ошибки обёртки (зависание вызывающего процесса и ложный успех при неизвестном коде выхода), починена ошибка компиляции из-за вложенного комментария в KDoc `YiCloudEventApi.kt`, базовый набор тестов зелёный (91 тест, 0 падений), добавлены planned-контракты GRACE для трёх новых модулей фазы.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | gradle.ps1 binding + `-DryRun` + per-PID logs; test-gradle-args.ps1; 01-PHASE-BASE.txt | 36c6098 |
| 1a | (deviation) gradle.ps1 pipe leak + null exit code | 6643710 |
| 2 | KDoc nested-comment fix; EventPaths v0.1.1 committed; baseline green | 223fe37 |
| 3 | GRACE planned nodes in development-plan / knowledge-graph / verification-plan | dd213a3 |

Phase base: `38b4010ac96276b848741f01619857153e28c8f7`.

## Verification evidence

- `tools/test-gradle-args.ps1`: `PASS 1` .. `PASS 4`, exit 0.
- `:app:testDebugUnitTest --rerun-tasks` (fresh daemon): `BUILD SUCCESSFUL in 1m 1s`, `--- gradle exit=0 elapsed=62.3s`; test-results XML: **tests=91 failures=0 errors=0 skipped=0** (10 classes).
- Final run after Task 3: `BUILD SUCCESSFUL in 2s`, `--- gradle exit=0 elapsed=3.4s`.
- Negative check: `:app:noSuchTask` returns `--- gradle exit=1`, wrapper exit 1.
- XML: `XML_OK`; module-ID counts 9 / 13 / 9; `+STATUS="planned"` lines in verification-plan vs base = 3.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] gradle.ps1 reported success for every build (`exit=` empty)**
- **Found during:** Task 2 (first full suite run)
- **Issue:** `Start-Process -PassThru` returns a Process whose `ExitCode` is `$null` unless its handle is touched before exit; `exit $null` = 0, so failed builds looked green.
- **Fix:** `$null = $proc.Handle` right after start; unknown exit code -> 1.
- **Commit:** 6643710

**2. [Rule 3 - Blocking] gradle.ps1 blocked the caller after BUILD SUCCESSFUL when it spawned a new daemon**
- **Found during:** Task 2
- **Issue:** the new Gradle daemon inherited the harness pipe through `CreateProcess(bInheritHandles=TRUE)`; caller waited until `--stop`. Clearing only std handles was tested and did not help (stray inherited handle copies).
- **Fix:** before Start-Process, clear `HANDLE_FLAG_INHERIT` on every handle except stdin (`BLOCK_UNINHERIT_STD_HANDLES`). Proven with a fresh daemon: suite returned in 1m02s with `exit=0`.
- **Commit:** 6643710

No test expectations needed changing for EventPaths.

### Requirements not marked complete

The plan lists SEC-01 and SEC-03, but this Wave-0 plan only prepares tooling and contracts; neither requirement is satisfied yet (RedactionScanner extension and the pre-commit guard come in later 01-* plans). `requirements mark-complete` was run and then reverted, so REQUIREMENTS.md keeps them Pending.

## Known Stubs

V-M-REPO-HYGIENE / V-M-DEBUG-PROBE / V-M-P2P-CREDS scenarios are name-only stubs by design (STATUS=planned); later plans of phase 01 implement them.

## Self-Check: PASSED
- FOUND: tools/test-gradle-args.ps1, .planning/phases/01-secrets-camera-list/01-PHASE-BASE.txt
- FOUND commits: 36c6098, 6643710, 223fe37, dd213a3
