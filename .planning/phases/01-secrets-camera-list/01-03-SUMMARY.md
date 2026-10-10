---
phase: 01-secrets-camera-list
plan: 03
subsystem: repo-hygiene, tooling
tags: [sec-03, git-hooks, gitignore, secrets, grace]
requires:
  - 01-00 (phase base commit, planned M-REPO-HYGIENE contract)
provides:
  - tools/git-hooks/pre-commit (path, decompiled-code and secret-value rules; --range mode)
  - tools/install-hooks.ps1 (copies the hook into .git/hooks, LFS hooks preserved)
  - tools/git-hooks/test-pre-commit.sh (8-case throwaway-repo self-test)
  - .gitignore SEC-03 block (vendor binaries, RE output, captures, local agent state)
  - docs/reference/yi-cloud-endpoints.md + tools/apk_endpoints.py now tracked
affects: [01-06 and 01-11 (range-mode scan over 01-PHASE-BASE.txt..HEAD), every later commit in this repo]
tech-stack:
  added: []
  patterns:
    - "Versioned hook copied into .git/hooks instead of core.hooksPath (keeps Git LFS hooks)"
    - "Fixture secrets carry SYNTH; test payloads that must be blocked are assembled from fragments at runtime"
    - "tools/git-hooks/* pinned to eol=lf in .gitattributes (core.autocrlf=true on this machine)"
key-files:
  created:
    - tools/git-hooks/pre-commit
    - tools/git-hooks/test-pre-commit.sh
    - tools/install-hooks.ps1
    - docs/reference/yi-cloud-endpoints.md
    - tools/apk_endpoints.py
  modified:
    - .gitignore
    - .gitattributes
    - HANDOFF.md
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
  removed:
    - docs/logs/classes8.dex.jadx
decisions:
  - "Hook is installed by copy, never via core.hooksPath (would disable the LFS post-checkout/post-commit/post-merge/pre-push hooks); the planned contract text said the opposite and was corrected"
  - "Content scan only reads added diff lines and never echoes the matched value; output is rule/code/file/line plus the [RepoGuard][preCommit][BLOCK_SCAN_STAGED_*] marker"
  - "Decompiler markers get their own error code HYGIENE_DECOMPILED_CODE (contract listed only PATH and SECRET)"
metrics:
  duration: "~35 min"
  completed: 2026-10-10
  tasks: 2
  files: 11
---

# Phase 01 Plan 03: SEC-03 repo guard Summary

Versioned POSIX-sh pre-commit hook that blocks vendor binaries/RE output/capture paths, decompiler markers in .java/.kt and real-looking token/License/InitString/password/hmac/Bearer values (SYNTH lines allowed), with a `--range` mode for commits made without the hook, a copy-based installer that keeps the LFS hooks, and an 8-case self-test.

## What was built

- `.gitignore`: SEC-03 block (`*.apk/apks/apkm/xapk/aab`, `*.so`, `*.dex`, `*.smali`, `*.jadx`, `*.flows`, `*.har`, `_tools/`, `_work/`, `_jadx/`, `.venv-mitm/`, `.mitmproxy/`, `diag_log.txt(.1)`, `.claude/`). `gradle/wrapper/gradle-wrapper.jar` stays tracked (no `*.jar` rule).
- `tools/git-hooks/pre-commit`: rules `vendor-or-capture-path` (HYGIENE_FORBIDDEN_PATH), `decompiled-code` (HYGIENE_DECOMPILED_CODE), `secret-value` (HYGIENE_SECRET_VALUE). Exit 0 clean, 1 blocked, 2 usage/git error. Range mode: `sh tools/git-hooks/pre-commit --range <base>..<head>`.
- `tools/install-hooks.ps1`: CRLF to LF, UTF-8 no BOM, writes `.git/hooks/pre-commit`; prints `installed pre-commit; existing LFS hooks untouched (...)`.
- `tools/git-hooks/test-pre-commit.sh`: temp repo, real `git commit` per case, each blocked case must also show the RepoGuard marker.
- `HANDOFF.md`: step 4.7 (install guard + self-test) and a SEC-03 bullet in section 6.
- `docs/logs/classes8.dex.jadx` untracked and deleted locally: it was a jadx *project* file (JSON pointing at an extracted `classes8.dex` in another user's temp dir), not decompiled source; removed because the extension is now forbidden.

## Verification evidence

- `sh tools/git-hooks/test-pre-commit.sh` (final run, after the marker fix):
  ```
  PASS allow-plain-readme
  PASS allow-synth-fixture
  PASS block-native-lib
  PASS block-capture-flows
  PASS block-initstring-value
  PASS block-hmac-query
  PASS block-decompiled-java
  PASS block-range-mode
  RESULT: all 8 cases passed
  ```
  exit=0.
- Task 1 verify command printed `OK`; `git config --get core.hooksPath` exit 1; `.git/hooks/pre-push` present; no `no-verify` in the hook; `git ls-files` has no vendor/capture extension.
- Pre-enable check against other plans' work: hook run over a temporary index containing the whole working tree (incl. 01-01's in-progress LogRedactor files) exited 0. Full history range from the root commit blocked only `docs/logs/classes8.dex.jadx` (since removed); `01-PHASE-BASE.txt..HEAD` exits 0.
- A parallel 01-01 commit (911d9da) passed through the installed hook.
- Acceptance grep for literal payloads in the test script returns nothing.

## Commits

| Task | Commit | Message |
|---|---|---|
| 1 | 1fe1ecb | chore(M-REPO-HYGIENE): SEC-03 pre-commit guard and ignore vendor/RE/capture artifacts |
| 2 | ee958d5 | chore(M-REPO-HYGIENE): SEC-03 hook self-test; add endpoint inventory |
| fix | 09dbb13 | fix(M-REPO-HYGIENE): emit V-M-REPO-HYGIENE log markers; correct installHooks contract |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] CRLF checkout would break the sh scripts**
- **Found during:** Task 1 commit (`core.autocrlf=true`, git warned LF will be replaced by CRLF)
- **Fix:** `.gitattributes` gets `tools/git-hooks/* text eol=lf`; the self-test copies the hook with `tr -d '\r'`. The installer already normalises.
- **Files:** .gitattributes, tools/git-hooks/test-pre-commit.sh. **Commit:** ee958d5

**2. [Rule 2 - GRACE] Hook output did not carry the V-M-REPO-HYGIENE required log markers, and the planned contract said installHooks sets core.hooksPath**
- **Fix:** blocks renamed to `BLOCK_SCAN_STAGED_PATHS` / `BLOCK_SCAN_STAGED_SECRETS`, BLOCKED lines carry `code=HYGIENE_*` and `[RepoGuard][preCommit][BLOCK_SCAN_STAGED_*]`; self-test asserts the marker; development-plan/knowledge-graph: STATUS implemented, installHooks purpose corrected, HYGIENE_DECOMPILED_CODE and the range export added. verification-plan.xml unchanged (markers, file and check already matched).
- **Files:** tools/git-hooks/pre-commit, tools/git-hooks/test-pre-commit.sh, docs/development-plan.xml, docs/knowledge-graph.xml. **Commit:** 09dbb13

**3. Commit split** — the plan described one combined commit; per-task protocol produced two task commits plus the fix commit. Task 2's commit body holds the required "Incorporates pre-existing uncommitted RE inventory from 2026-10-06; contains endpoint paths only" line.

## Notes / follow-ups

- HANDOFF.md section 3 still says the Yi Home APK is in the repo under LFS (`_apk/`); `_apk/` was already ignored and nothing under it is tracked. Left as-is (out of scope).
- The hook is a copy: after pulling a new `tools/git-hooks/pre-commit`, re-run the installer.
- Binary files are not content-scanned (git shows no added lines); forbidden binary types are covered by the path rule.

## Known Stubs

None.

## Self-Check: PASSED
