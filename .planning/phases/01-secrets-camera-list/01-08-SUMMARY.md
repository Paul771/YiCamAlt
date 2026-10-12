---
phase: 01-secrets-camera-list
plan: 08
subsystem: auth
tags: [region, gateway, login, discovery, hilt, okhttp, cam-02]

requires:
  - phase: 01-05
    provides: per-request base URL resolution in HttpClientModule, refresh-once-per-expiry memo
  - phase: 01-06
    provides: probe results (BRANCH A, DATA_REGION US, 20200 = empty list)
provides:
  - YiRegion (US, EU, SG) + RegionSelector port (ConfigModule, Hilt bindRegionSelector)
  - real default gateway https://gw-us.xiaoyi.com/ (placeholder api.yicamalt.local removed)
  - manual-server flag (SettingsStore api_base_url_manual) that disables every automatic region change
  - bounded login: 20253 hop + region discovery by device list (<= 3 logins + <= 3 list checks per login call)
  - DeviceListCheckPort / HttpDeviceListCheck (GET /v5/devices/list -> code + count)
affects: [01-09 CameraRegistry (region kept by login), M-UI-SETTINGS, M-HTTP]

tech-stack:
  added: []
  patterns:
    - "Port + Hilt binding for region selection (RegionSelector) and the discovery check (DeviceListCheckPort)"
    - "Per-call request caps enforced by visiting each region at most once plus explicit counters"

key-files:
  created:
    - app/src/main/java/com/yicamalt/config/YiRegion.kt
    - app/src/main/java/com/yicamalt/auth/RegionDiscovery.kt
    - app/src/test/java/com/yicamalt/auth/AuthRegionDiscoveryTest.kt
    - app/src/test/java/com/yicamalt/auth/AuthRegionFakes.kt
  modified:
    - app/src/main/java/com/yicamalt/config/ConfigModule.kt
    - app/src/main/java/com/yicamalt/config/SettingsStore.kt
    - app/src/main/java/com/yicamalt/auth/AuthRepository.kt
    - app/src/main/java/com/yicamalt/auth/AuthModule.kt
    - app/src/main/java/com/yicamalt/debug/ProbeMatrix.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt
    - app/build.gradle.kts
    - app/src/test/java/com/yicamalt/config/ConfigModuleTest.kt
    - app/src/test/java/com/yicamalt/auth/AuthRepositoryTest.kt
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
    - docs/development-plan.xml
    - docs/PROJECT_STATUS.md

key-decisions:
  - "Default server = US gateway; region order US, EU, SG; api.xiaoyi.com only as a manual server"
  - "Login region = first region whose /v5/devices/list has code 20000 and count > 0; none => first successful login"
  - "Network error during discovery keeps the LAST good session (literal reading of the addendum); no good session => error propagates and the original server is restored"
  - "Settings field shows only a manual server (empty in automatic mode) so Save cannot pin an auto-selected gateway by accident"
  - "Token refresh is suppressed while the discovery check runs (no POST auth_token inside a login call beyond the pre-existing once-per-expiry case)"

patterns-established:
  - "Region logs are value-free: region name, code, count only"

requirements-completed: [CAM-02]

duration: 25min
completed: 2026-10-11
---

# Phase 1 Plan 08: Region Selection and Bounded Login Region Discovery Summary

**The app now starts on the real US gateway. An explicit login finds the account's gateway by itself: it hops on code 20253, then checks the device list on each region (US, EU, SG) and keeps the first one that has cameras. One login press makes at most 3 login and 3 device-list requests. A server the user types in Settings turns all of this off.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-10-11T16:27Z
- **Completed:** 2026-10-11T16:52Z
- **Tasks:** 2 planned tasks plus the addendum's discovery (merged into Task 2), and a docs commit
- **Files modified:** 18

## Accomplishments
- `YiRegion` + `RegionSelector` are typed and come from one source (ProbeMatrix.REGIONS is derived from them). The build default is `https://gw-us.xiaoyi.com/` in both debug and release.
- Manual-server flag: while it is set, `currentRegion()` returns null and `selectRegion`/`restoreBaseUrl` do nothing. Clearing the field switches back to automatic.
- `AuthRepository.login` handles the 20253 hop and region discovery by device list in one bounded loop. Only the kept session is persisted.
- `HttpDeviceListCheck` sends one signed `GET /v5/devices/list` on the shared client and reduces the response to code + count. It does not depend on the 01-07 parsers.

## How the request bounds are enforced
- **Order list:** `YiRegion.orderStartingWith(start).take(MAX_LOGIN_ATTEMPTS)` holds each region exactly once. The loop is a `for` over that list (no `while`, no retry, no timer).
- **Counters:** each login has `logins >= MAX_LOGIN_ATTEMPTS (3)` checked before it, and each list check has `lists >= MAX_DISCOVERY_CALLS (3)` checked before it. A list check only runs right after a successful login on the same region.
- **Stop rules:** any rejection other than 20253 (for example 20261) breaks the loop at once. An I/O error breaks the loop: the last good session is kept, or the error propagates with the original server restored if there is none.
- **Where the loop runs:** `currentRegion() == null` (manual or unknown server) means one login and no discovery. The loop exists only inside `login()`. `getAccessToken`/`refresh`/`refreshSession` and construction never call login or the check. `discoveryInFlight` suppresses a token refresh during the check.
- **Concurrent presses:** `loginMutex` serialises login calls, so the bounds also hold when the button is pressed twice.

## How the bounds are tested (JVM, synthetic fixtures)
- AuthRegionDiscoveryTest (18 tests). Addendum scenarios:
  - discovery_1: US empty, EU has devices. Ends on EU after 2 logins + 2 list calls.
  - discovery_2: all three empty. Stays on US after exactly 3 + 3.
  - discovery_3: first region non-empty. 1 + 1.
  - discovery_4: wrong password on EU. Stops, US session kept.
  - discovery_5 and _6: manual server (real ConfigModule, typed gw-eu / gw-sg). 1 login, 0 list calls, server unchanged.
- Network errors: discovery_9/10/11.
- Repeated presses: discovery_12 does 4 worst-case presses at exactly 3+3 each, then moves to SG and stays at 1+1.
- Never on app start or refresh: discovery_13.
- Refresh suppressed during the check: discovery_14.
- `assertMaxRetries("BLOCK_REGION_FALLBACK", 3)` and `("BLOCK_REGION_DISCOVERY", 3)` run on every press. RedactionScanner checks the logs against email, password, user id and tokens, and region lines may not contain a URL.
- counter_1 tests DeviceListCounter on these bodies: array, `[]`, 20200, null, object container, string-JSON, numeric code, non-JSON.
- http_check_1/2 use MockWebServer with the production HttpClientModule. They check exactly one GET `/v5/devices/list` with exactly `seq`, `userid`, `hmac` (equal to the YiRequestSigner value), code + count, and that an IOException propagates without a retry.
- AuthRepositoryTest region_1..region_9 cover the plan behaviours: 20253 hops, 3x20253 restoring the original server, 20253 then 20261 keeping EU, 20261 first giving 1 call, IOException giving 1 call, a custom server giving 1 call, and the 3-arg constructor giving 1 call.

## Task Commits

1. **Task 1: YiRegion + RegionSelector + real default gateway** - `b249563` (grace(M-CONFIG))
2. **Task 2 + addendum: bounded login region fallback and discovery** - `458f28d` (grace(M-AUTH))
3. **Docs: knowledge-graph, verification-plan, development-plan, PROJECT_STATUS** - `71da0d5` (grace(docs))

## Verification evidence
- Scoped run (auth, config, debug): `BUILD SUCCESSFUL in 3s`, `--- gradle exit=0`. Results: AuthRepositoryTest 23/0 failures, AuthRegionDiscoveryTest 18/0, ConfigModuleTest 13/0, ProbeMatrixTest 12/0. network.* was run once as well: HttpClientModuleTest 15/0, YiRequestSignerTest 6/0, LogRedactorTest 16/0.
- Full wave gate `:app:testDebugUnitTest`: `BUILD SUCCESSFUL in 22s`, `--- gradle exit=0 elapsed=23.8s`. 21 suites (= 21 test classes), 241 tests, 1 skipped, 0 failures, 0 errors.
- Acceptance greps:
  - `api.yicamalt.local` in build.gradle.kts: 0. `xiaoyi.com` in build.gradle.kts: 2. `gw-us.xiaoyi.com` in ProbeMatrix.kt: 0.
  - `bindRegionSelector` and `START_BLOCK_SELECT_REGION`: 1 line each.
  - `isManualBaseUrl` in SettingsStore.kt: 3 or more. `setManualBaseUrl` in SettingsViewModel.kt: 1 line.
  - `BLOCK_REGION_FALLBACK` in AuthRepository.kt: 4. `ACCOUNT_NOT_FOUND_CODE = "20253"`: 1 line.
  - `user=${…userId}`: none. `while (true)`: none.

## Decisions Made
See key-decisions in the frontmatter. CROSS_REGION_SESSION is `no` with LOGIN_REGION = DATA_REGION = US, so there is no mismatch gap. The default region is US as planned.

## Deviations from Plan

### Auto-fixed Issues

**1. [Addendum - scope] Region discovery by device list added to the login loop**
- **Found during:** Task 2
- **Issue:** The probe showed that login succeeds on all three gateways, so the 20253 hop alone cannot find the region.
- **Fix:** Added DeviceListCheckPort/HttpDeviceListCheck (new file RegionDiscovery.kt) and an AuthModule binding. AuthRepository takes them as constructor parameters 4 and 5, with defaults, so the old 3- and 4-argument calls still work. Added a new test file and a fakes file.
- **Commit:** 458f28d

**2. [Rule 2 - Missing critical] No token refresh during the discovery check; login serialised**
- **Issue:** A 401 on the list call would go through the shared client's authenticator and send POST auth_token, which is a request outside the bound. Two quick presses could also double the bound.
- **Fix:** `discoveryInFlight` guard in refreshOncePerExpiry, and a `loginMutex`. Tested by discovery_14.
- **Commit:** 458f28d

**3. [Rule 1 - Bug prevention] Settings field no longer pre-fills the effective URL**
- **Issue:** With the manual flag added, pressing Save on a pre-filled auto-selected gateway would pin it as manual by accident.
- **Fix:** The field shows only a manual server. The helper text now reads «Пусто = автоматический выбор сервера.» (SettingsScreen.kt was touched, which the plan allowed).
- **Commit:** b249563

**4. [Intentional marker change] Existing login trace assertions updated**
- **Issue:** scenario_1 and scenario_6 asserted that BLOCK_VALIDATE_CREDENTIALS is directly followed by BLOCK_STORE_TOKEN. Login now logs BLOCK_REGION_FALLBACK and BLOCK_REGION_SELECTED between them.
- **Fix:** The assertions now check the full new sequence. This is stricter than before, not weaker.
- **Commit:** 458f28d

**5. [Process] No separate RED/GREEN commits**
- **Issue:** Other executors shared the working tree at the time, and a commit with tests that do not compile would have broken their builds.
- **Fix:** Tests and implementation went into one commit per task. The plan's success criteria name exactly these two commits.

**6. [Interpretation] Network error keeps the last good session**
- The addendum says both "keep the FIRST region where login succeeded" (when no region has devices) and "keep the last good session" (on a network error). The code follows both literally. Tested by discovery_9 (list error on EU keeps EU) and discovery_10 (login error on EU keeps US).

---

**Total deviations:** 6 (1 addendum scope, 1 missing critical, 1 bug prevention, 1 intentional marker update, 1 process, 1 interpretation)
**Impact on plan:** All within the plan and addendum, with no architectural change.

## Issues Encountered
- Normalising docs/PROJECT_STATUS.md to CRLF also changed two trailing blank lines that were LF-only. The only effect is whitespace.

## User Setup Required
None.

## Next Phase Readiness
- Plan 01-09 can read the region kept by login through `RegionSelector.currentRegion()` / `ConfigModule`. 20200 means an empty state.
- Real-device check pending: a fresh install (or one with no manual server) should log in on US with 1 login + 1 list. That shows in logcat as `[Auth][login][BLOCK_REGION_SELECTED] region=US reason=devices logins=1 lists=1`.

## Self-Check: PASSED
- Files exist: YiRegion.kt, RegionDiscovery.kt, AuthRegionDiscoveryTest.kt, AuthRegionFakes.kt.
- Commits exist: b249563, 458f28d, 71da0d5.

---
*Phase: 01-secrets-camera-list*
*Completed: 2026-10-11*
