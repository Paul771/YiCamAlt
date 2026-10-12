---
phase: 01-secrets-camera-list
verified: 2026-10-12T19:05:00Z
status: passed
score: 5/5 success criteria verified, 9/9 requirements satisfied
re_verification: false
---

# Phase 1: Secrets and real camera list - Verification Report

**Phase Goal:** after login the user sees their real cameras, and the app knows the P2P credentials and the transport of each one, with no secret leaking into logs or Git.
**Verified:** 2026-10-12
**Status:** passed
**Re-verification:** No (initial)

Inputs: ROADMAP.md Phase 1, REQUIREMENTS.md, 13 plans, `01-PLAN-ADDENDUM.md` (BRANCH A, overrides earlier plans), `01-DEVICE-VERIFICATION.md` (on-device PASS, Mi 9, commit 298b335). Code checked at HEAD 1cce476. No network calls made, no secret printed.

## Independent spot checks (run by the verifier)

| Check | Command | Result |
|-------|---------|--------|
| JVM unit tests | `powershell -File tools/gradle.ps1 :app:testDebugUnitTest` | BUILD SUCCESSFUL, test task executed (not up-to-date); XML results: 278 tests, 0 failures, 0 errors, 1 skipped (`RealLogScanTest`, needs a pulled device log; it was run on the device log during 01-11) |
| Hook self-test | `sh tools/git-hooks/test-pre-commit.sh` | 8/8 cases PASS, exit 0 |
| Range scan of the whole phase | `sh tools/git-hooks/pre-commit --range 38b4010..HEAD` (60 commits) | exit 0, no findings |
| Installed hook | `diff .git/hooks/pre-commit tools/git-hooks/pre-commit` | identical (installed via copy; `core.hooksPath` not set) |
| Vendor binaries in Git | `git ls-files` filtered for apk/aab/so/dex/smali/flow/mitm/pcap/har/_apk/_re/_jadx/jniLibs | none; only `gradle/wrapper/gradle-wrapper.jar` (standard wrapper, not vendor) |
| `--no-verify` evidence | commit messages scanned; range scan re-applies the hook to every phase commit | no mention; every commit in range passes the hook, so even a bypassed commit would carry nothing the hook blocks |
| `/*` inside KDoc | grep over `app/src` | none |

## Goal Achievement - Success Criteria

| # | Criterion | Verdict | Evidence |
|---|-----------|---------|----------|
| 1 | User logs in and sees own + shared cameras with name, model, online/offline, no manual gw-us/eu/sg choice | PASS | `config/YiRegion.kt` (US/EU/SG real gateways, placeholder host gone); `auth/RegionDiscovery.kt` `HttpDeviceListCheck` bound in `auth/AuthModule.kt:54`; `AuthRepository` login hop + discovery (`BLOCK_REGION_DISCOVERY/SELECTED`); `ui/cameras/CameraListScreen.kt` row shows name, model, online/offline dot+text, "общая" for shared; screen wired in `ui/shell/AppShellScreen.kt:92`. Device: `region=US reason=devices`, 2 cameras = official app (user-confirmed) |
| 2 | Clear empty / server error / retry states; pull-to-refresh; cached list shown at once on restart | PASS | `CameraListViewModel` states Loading/Content(+staleError)/Empty/Error with `ErrorKind` NETWORK/SERVER/UNAUTHORIZED; cache emitted first (`source=cache`), then network; screen: "Камеры не найдены", error text, "Повторить", "Войти снова", stale banner, `PullToRefreshContainer`. 20200 = empty, not error (addendum). Device: user confirmed banner, cache-first start, pull-to-refresh |
| 3 | Transport (TNP/TUTK) and P2P credentials known per camera (p2pid, password, InitString, License via `/v4/tnp/device_info`); stored only in memory / EncryptedSharedPreferences, not Room | PASS | `CameraRegistry` `BLOCK_RESOLVE_P2P_CREDENTIALS`: `CameraPasswordCipher.decrypt` (AES/ECB, uid key), `tnpDeviceInfo` per TNP camera, `credentialStore.replaceAll`; `CameraModule.kt:40` binds `EncryptedP2pCredentialStore` (AES256_SIV/GCM, MasterKey). `CameraEntity` (Room v2) columns: device_id, name, model, online, stream_url, last_seen, cached_at, transport, shared - no secret columns. `P2pCredentials.toString()` flag-only. Device: `tnp=1 tutk=1 stored=2 withLicense=1 withPassword=2` |
| 4 | Extended `RedactionScanner` on realistic fixtures finds no hmac, tokens, passwords, License, InitString, DID/p2pid, nonce, serials; diag log in internal storage | PASS | `test/.../RedactionScanner.kt` patterns cover Bearer, email, 10+ digit runs, key=value for password/hmac/license/initstring/nonce/p2pid/did/serial, TNP DID shape, base64 HMAC, long opaque tokens; `RedactionFixtures` + tests green. Code: `HttpLoggingInterceptor` at BASIC with `maskQuery`, body logger shape-only (`LogRedactor.maskJsonBody`: every value masked except `code`/booleans), all interpolated log lines carry counts/codes/flags only. `YiCamAltApp`: `FileLogTree(filesDir/diag_log.txt)` + legacy external purge. Device sweep clean |
| 5 | `.gitignore` and pre-commit block APK, .so, decompiled code, capture flows | PASS | `.gitignore` covers *.apk/apks/xapk/aab/so/dex/smali/jadx/flow/flows/mitm/pcap/har, `_apk/ _re/ _jadx/ jniLibs/`, diag logs; hook installed, self-test 8/8, range scan of the phase clean |

**Score:** 5/5

## Requirements Coverage

| Req | Status | Evidence |
|-----|--------|----------|
| SEC-01 | PASS | RedactionScanner + fixtures (tests green), `LogRedactor` shape-only, value-free log markers (grep of all `Timber`/`Log` interpolations), `CameraDao` no longer logs device_id; device log sweep clean |
| SEC-02 | PASS | `YiCamAltApp.kt:51-52` internal `filesDir/diag_log.txt`, `purgeLegacy` removes external copies; device `diagLog=internal` |
| SEC-03 | PASS | `.gitignore`, `tools/git-hooks/pre-commit` (installed, identical), self-test 8/8, `--range` over 60 phase commits exit 0, no vendor binaries tracked |
| SEC-04 | PASS | `EncryptedP2pCredentialStore` is the bound implementation; Room `cameras` has no secret columns; `clearAll` wipes store on logout (fail-closed, JVM tests) |
| CAM-01 | PASS | List UI with name/model/online/shared; device: 2 = official 2 (user). Note: REQUIREMENTS.md still shows CAM-01 unchecked / Pending - doc drift only |
| CAM-02 | PASS | Region hop (20253) + discovery by `/v5/devices/list` in login, bounded 3+3, skipped for manual server; `AuthRegionDiscoveryTest`; device `region=US reason=devices` |
| CAM-03 | PASS | `DeviceRecordParser` (uid/did/type/password/ipcParam object-or-string), `TransportType`, password decrypt, TNP device_info; device both transports resolved |
| CAM-04 | PASS | Empty/Error/stale states with retry; 20200 -> Empty; device airplane-mode banner confirmed |
| CAM-05 | PASS | `PullToRefreshContainer` -> `refresh()`; Room cache emitted first; device confirmed |

All 9 IDs from ROADMAP are covered by plan frontmatter and by evidence; no orphaned requirement.

## Anti-Patterns

No blocker. No TODO/stub in the phase's runtime paths found by the checks above. `EndpointProbe` logs pre-built probe lines (`$line`, `$summary`); they are value-free by construction in `ProbeMatrix` and covered by `ProbeMatrixTest`.

## Non-blocking observations

1. One camera is TUTK, one TNP. LIVE-01..03 in Phase 2 need both transports; TUTK was only a v2 fallback (P2P-01). Decide at Phase 2 planning.
2. TNP camera reports `mismatch=true` (list `type` vs p2pid prefix disagree). Phase 2 must determine which is right before connecting.
3. Doc drift: REQUIREMENTS.md has CAM-01 `[ ]` / Pending; ROADMAP has Phase 1 and 01-11 unchecked; `docs/PROJECT_STATUS.md` still says "ждёт устройства" / V-M-CAMERA-LIST `in-progress`. 01-11 has no SUMMARY yet. Update with the 01-11 close-out.
4. Room `cameras.device_id` is the cloud uid; `DeviceRecordParser`/`CameraInfo` fall back to `did` when uid is absent, which would put a DID into plain Room for such records. Not seen on the wire (uid always present), but worth closing (store a hash or skip the record) before P2P work.
5. The cloud uid is also the AES key source for the camera password; it is in plain Room. The encrypted password itself is not stored in Room, so this is not a leak, only a note.
6. `RedactionScanner` has no explicit pattern for a 20-char TUTK UID shape; current log lines never print it, but a TUTK UID regex would strengthen SEC-01 for Phase 2 logs.
7. The pre-commit hook is installed by copy into `.git/hooks`, not via `core.hooksPath`; a fresh clone needs the install step from HANDOFF.md.
8. 1 skipped test (`RealLogScanTest`) is by design: it needs a pulled device log.

## Gaps Summary

None. All success criteria and requirements are met in code, by the JVM suite (278 tests, 0 failures, re-run by the verifier), by the repo guard, and by the on-device run recorded in `01-DEVICE-VERIFICATION.md` (user-confirmed UI behavior).

---

_Verified: 2026-10-12_
_Verifier: Claude (gsd-verifier)_
