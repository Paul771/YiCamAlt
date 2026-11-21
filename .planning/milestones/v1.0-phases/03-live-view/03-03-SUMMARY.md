---
phase: 03-live-view
plan: 03
subsystem: p2p-tutk
tags: [tutk, jni, shim, audio, ioctrl, grace]
requires: ["03-00"]
provides:
  - "AVAPIs natives avClientCleanAudioBuf, avRecvAudioData, avRecvIOCtrl (vendor signatures, pinned)"
  - "TutkNative.cleanAudioBuf / recvAudio / recvIoCtrl, IoCtrlResult"
  - "FakeTutkNative audioQueue / audioEndCode / ioCtrlQueue / IoCtrlReply / cleanAudioBufResult"
affects: [03-04]
tech-stack:
  added: []
  patterns: ["fake in-flight counter (recvInFlight) shared by all reader calls"]
key-files:
  created: []
  modified:
    - app/src/main/java/com/tutk/IOTC/AVAPIs.java
    - app/src/test/java/com/yicamalt/p2p/tutk/TutkShimContractTest.kt
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkNative.kt
    - app/src/main/java/com/yicamalt/p2p/tutk/AndroidTutkNative.kt
    - app/src/test/java/com/yicamalt/p2p/tutk/FakeTutkNative.kt
decisions:
  - "TUTK audio and IOCTRL readers use avRecvAudioData / avRecvIOCtrl on the same AV channel; exports verified on arm64-v8a and armeabi-v7a"
  - "FakeTutkNative recvInFlight counts recvFrame, recvAudio and recvIoCtrl, so recvInFlightAtStop proves all readers left before stop"
metrics:
  duration: 15min
  completed: 2026-10-23
  tasks: 2
  files: 5
---

# Phase 3 Plan 03: TUTK JNI shim for audio and IOCTRL replies Summary

The TUTK shim gains the three vendor natives `avClientCleanAudioBuf`, `avRecvAudioData`, `avRecvIOCtrl` (exact signatures, pinned by `TutkShimContractTest`); `TutkNative` exposes `cleanAudioBuf` / `recvAudio` / `recvIoCtrl` + `IoCtrlResult`, bound 1:1 in `AndroidTutkNative` and scriptable in `FakeTutkNative`.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Verify vendor exports on both ABIs, add shim natives and pin them | 26b1ae3 |
| 2 | TutkNative seam, Android binding and fake | d020e2c |

## Export check (local APK splits, in-memory, nothing written)

Exact symbol + NUL terminator searched in `lib/<abi>/libAVAPIs.so`:

| ABI | avRecvAudioData | avRecvIOCtrl | avClientCleanAudioBuf |
| --- | --- | --- | --- |
| arm64-v8a | yes | yes | yes |
| armeabi-v7a | yes | yes | yes |

No blocker.

## Verification

- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.p2p.tutk.TutkShimContractTest"`: BUILD SUCCESSFUL, exit 0.
- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.p2p.*" :app:assembleDebug`: BUILD SUCCESSFUL, exit 0; 9 suites, 123 tests, 0 failures, 0 skipped.
- `public static native` count in AVAPIs.java: 8 -> 11; `avRecvIOCtrl(int av, int[] ioType, byte[] buf, int bufSize, int timeoutMs)` = 1 line.
- Greps: 3 seam funs, 3 AVAPIs calls in AndroidTutkNative, 8 `audioQueue|ioCtrlQueue` lines in the fake; no `/*` in touched p2p Kotlin files; `git status` clean (nothing from `_apk/`, `_re/`, scratchpad).

## Deviations from Plan

- FakeTutkNative also exposes `cleanAudioBufResult` (scripted return code of cleanAudioBuf) and applies `recvDelayMs` to recvAudio / recvIoCtrl too (documented in MODULE_MAP), so 03-04 can simulate blocking readers at teardown. `IoCtrlReply.payload` defaults to an empty array. recvIoCtrl returns the copied payload length as the code.

Otherwise executed as written.

## Known Stubs

None. TutkCameraLink does not call the new methods yet (plan 03-04).

## Self-Check: PASSED
