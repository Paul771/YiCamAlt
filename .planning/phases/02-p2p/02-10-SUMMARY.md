---
phase: 02-p2p
plan: 10
status: implemented
subsystem: p2p-tutk-native
tags: [p2p, tutk, iotc, jni, shim, runtime, hilt, grace]
requires:
  - "02-03: NativeLibs.loadTutk (IOTCAPIs, sCHL, AVAPIs), PpppRuntime pattern, AndroidPpppNative.isPrivateIpv4"
  - "02-06: 02-DEVICE-CHECK-1.md GATE_2B: needed (tnp=1 tutk=1)"
provides:
  - "JNI shim com.tutk.IOTC: IOTCAPIs (7 natives), AVAPIs (8 natives + 7 av*Fn interfaces), St_SInfoEx, Region, st_LanSearchInfo, st_LanSearchInfo2, st_SearchDeviceInfo, St_AvIdentity"
  - "TutkNative seam + TutkCheck / ClientStart / RecvResult + TutkCodes (names, classify, iotcInitOk/avInitOk)"
  - "AndroidTutkNative (@Singleton) over the shim, vendor stop order in stop(av, sid)"
  - "TutkRuntime.ensureInitialized() + TutkRuntimeState (NotStarted/Unavailable/InitFailed/Ready(iotcVersion, avVersion))"
  - "TutkRuntimeModule: @Binds TutkNative -> AndroidTutkNative"
  - "FakeTutkNative (scripted results, call + thread trace, init gate, recv queue) for plan 02-11"
affects: [02-11, 02-12]
tech-stack:
  added: []
  patterns: ["native load as a state", "process-once init on a dedicated named thread with a bounded wait (tutk-runtime)"]
key-files:
  created:
    - app/src/main/java/com/tutk/IOTC/IOTCAPIs.java
    - app/src/main/java/com/tutk/IOTC/AVAPIs.java
    - app/src/main/java/com/tutk/IOTC/St_SInfoEx.java
    - app/src/main/java/com/tutk/IOTC/Region.java
    - app/src/main/java/com/tutk/IOTC/st_LanSearchInfo.java
    - app/src/main/java/com/tutk/IOTC/st_LanSearchInfo2.java
    - app/src/main/java/com/tutk/IOTC/st_SearchDeviceInfo.java
    - app/src/main/java/com/tutk/IOTC/St_AvIdentity.java
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkNative.kt
    - app/src/main/java/com/yicamalt/p2p/tutk/AndroidTutkNative.kt
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkRuntime.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkShimContractTest.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkRuntimeTest.kt
  modified:
    - docs/verification-plan.xml
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
decisions:
  - "TutkRuntime has its own TutkRuntimeState (two versions) instead of reusing RuntimeState (single PPPP apiVersion)"
  - "Init timeout (10 s) yields InitFailed(IOTC_ER_TIMEOUT -13); the late native result is adopted on the next call, never re-initialised"
  - "IOTC_Initialize2 >= 0 or -3 and avInitialize >= 0 or -20031 count as initialised (avInitialize returns a positive channel count)"
  - "TutkCodes.classify beyond the plan minimum: -20040 also WRONG_PASSWORD; IOTC remote-close codes REMOTE_CLOSED; offline/network/busy/credentials groups; AV_ER_TIMEOUT TIMEOUT; read-loop codes -20012/-20013/-20014 stay INTERNAL (link handles them first)"
  - "IOTC_DeInitialize / avDeInitialize are not declared in the shim (never called; only bound by name if declared)"
metrics:
  duration: 35min
  completed: 2026-10-15
  tasks: 3
  files: 17
---

# Phase 2 Plan 10: TUTK JNI shim, TutkNative seam and TutkRuntime Summary

Device check 1 recorded `GATE_2B: needed` (`TRANSPORTS: tnp=1 tutk=1 langtao=0 unknown=0`; camera B is `transport=TUTK via=uid`), so the full plan ran. The TUTK libraries now find every class, field and callback interface they look up, through our own declarations at the vendor names. A `TutkNative` seam with a JVM fake sits on top of them, and `TutkRuntime` initialises IOTC and AV once per process on the `tutk-runtime` thread. A missing library is the `Unavailable` state and makes zero native calls. No live camera was contacted. Everything was tested on the JVM only.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Gate: `GATE_2B: needed` -> proceed | (no files) |
| 2a | TUTK Java shim + reflection contract test | cdceffa |
| 2b | TutkNative seam, AndroidTutkNative, TutkRuntime, FakeTutkNative, GRACE docs | bf5d92f |

## .so string scan (Task 2a step 1)

`python -I` regex scan of `app/build/generated/vendorJniLibs/arm64-v8a/`. Only names were read; nothing from `_re/` or the libs was copied or committed.

- libIOTCAPIs.so: `com/tutk/IOTC/Region` (plus `getValue` `()I`), `com/tutk/IOTC/st_LanSearchInfo` (plus `<init>` `()V`), `com/tutk/IOTC/st_LanSearchInfo2`, `com/tutk/IOTC/st_SearchDeviceInfo`. It has 66 `Java_com_tutk_IOTC_IOTCAPIs_*` exports.
- libAVAPIs.so: `com/tutk/IOTC/St_AvIdentity` and the seven nested interfaces `AVAPIs$avAbilityRequestFn`, `$avChangePasswordRequestFn`, `$avIdentityArrayRequestFn`, `$avPasswordAuthFn`, `$avTokenAuthFn`, `$avTokenDeleteFn`, `$avTokenRequestFn`, each with its method name and JNI descriptor. It has 41 `Java_com_tutk_IOTC_AVAPIs_*` exports.
- libsCHL.so: no `com/tutk` strings and no JNI exports.
- `St_SInfoEx` is not FindClass-ed by name; the lib reads it from the argument object. Its field names and array sizes (21/17/17) come from the decompile.

`TutkShimContractTest` pins all 12 names, the 15 native signatures, the callback JNI descriptors, the struct fields and the array sizes.

## Verification

- Wave-4 gate: `:app:testDebugUnitTest` BUILD SUCCESSFUL, exit=0 (before any change).
- `:app:testDebugUnitTest --tests "com.yicamalt.p2p.tutk.*" :app:compileDebugKotlin`: BUILD SUCCESSFUL, `--- gradle exit=0`. `hiltJavaCompileDebug` ran, so the Hilt graph resolves `TutkNative`. Results: TutkShimContractTest 6/0 failures, TutkRuntimeTest 14/0 failures.
- Full suite after the plan: 466 tests, 0 failures/errors, exit=0.
- Mutation evidence (RED substitute):
  - Shim: RemoteIP size 16 and a wrong `password_auth` signature. 2/6 tests failed, exit=1.
  - Runtime/codes: AV channels 16, the libs check skipped, -3 not accepted, -20010 and -20017 dropped from REMOTE_CLOSED. 9/20 tests failed, exit=1.
  - Sources were restored both times and the runs are green again.
- Acceptance greps:
  - `loadLibrary` and `/*` in `app/src/main/java/com/tutk`: none.
  - `/*` in the new p2p/tutk sources and tests: none.
  - `interface TutkNative`: 1 line.
  - No `_re/` or `_apk/` path was staged.
- Every TutkRuntimeTest buffer passes `RedactionScanner().assertClean(..., ALL_SECRETS)` in `@AfterEach`.

## Log marker (value-free)

```
[P2P][tutk][BLOCK_INIT_TUTK] iotc=<code> av=<code> iotcVer=0x<HEX> avVer=0x<HEX> result=ready
[P2P][tutk][BLOCK_INIT_TUTK] iotc=<code> [av=<code>] name=<NAME> result=failed
[P2P][tutk][BLOCK_INIT_TUTK] result=unavailable reason=libs | result=timeout boundMs=<n>
```

## Notes for plan 02-11

- `TutkRuntime.ensureInitialized()` may block up to 10 s on first use, so call it off the main thread.
- `TutkNative.stop(av, sid)` runs the whole vendor teardown (767 STOP with 8 zero bytes, `avSendIOCtrlExit`, `avClientStop`, `avClientExit(sid, 0)`, `IOTC_Session_Close`). A negative av or sid skips its half.
- `RecvResult.size` is the return value when it is > 0, and otherwise `actualFrameSize[0]`.
- In `FakeTutkNative`, an empty `recvQueue` returns `AV_ER_DATA_NOREADY` (-20012). The uid and password are never recorded in `calls`.
- The Settings native check (`tutkLibs=`) still reports only the load state. Showing TUTK versions is left to the 02-11/02-12 checkpoint.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RED commit with `TODO()` stubs replaced by a mutation check**
- **Issue:** A local write hook rejects files that contain only `TODO()` stubs (as in plans 02-03, 02-04 and 02-07), so the `test(02-10): stubs + failing TUTK seam tests` commit could not be produced.
- **Fix:** I wrote the implementation and tests together, then proved the tests can fail by mutating the sources (see Verification) and restored them.
- **Commits:** bf5d92f. The plan's test+grace pair became one grace commit.

**2. [Rule 2 - Correctness] Shim natives that return `void`**
- The decompile declares `IOTC_Get_Version`, `IOTC_Session_Close`, `avClientStop` and `avClientExit` as `void`, so the shim matches that (the JNI return type is part of the binding). `iotcVersion()` reads `int[1]`.

**3. [Rule 2 - GRACE] Docs**
- `development-plan.xml` M-TUTK-LINK target lists the 8 shim files and FakeTutkNative.
- `verification-plan.xml` V-M-TUTK-LINK has scenarios 5-7, marker-6 `BLOCK_INIT_TUTK` and note-2.
- `knowledge-graph.xml` M-TUTK-LINK has the new exports and a change-summary.
- Module STATUS stays `planned` until the device checkpoint.

### Notes

- Requirements LIVE-01..03 are NOT marked complete. They close only after device verification.

## Known Stubs

None. `AndroidTutkNative` is Android-only and gets verified on the device in the 02-11/02-12 checkpoint.

## Self-Check: PASSED
