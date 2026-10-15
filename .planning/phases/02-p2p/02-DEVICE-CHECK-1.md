# Phase 2 — Device Check 1 (native load, API version, transports)

- Date: 2026-10-15 (APK installed 19:05 local)
- Commit under test: c4f3dd0 (branch gsd/phase-2-p2p)
- Device: user's arm64 phone (Mi 9, MIUI), USB attached; APK installed by hand (adb install blocked by MIUI)
- Account safety: no P2P connection made; one user pull-to-refresh (camera list + device_info per camera)

## User reply

The user replied only "готово" (done) — no `p2p:` / `cams:` lines were sent. The on-screen
camera labels are therefore NOT available; all evidence below comes from the value-free log
markers pulled over USB (`run-as` copy of `files/diag_log.txt[.1]`), and the cross-check is
log-internal only (BLOCK_NATIVE_CHECK vs BLOCK_INIT_PPPP; BLOCK_RESOLVE_P2P_CREDENTIALS vs
BLOCK_FETCH_CAMERA_LIST).

## Redaction sweep (SEC-01)

- `RealLogScanTest` with `-PredactionLogFile=… --rerun-tasks`: BUILD SUCCESSFUL, gradle exit=0
- `TEST-com.yicamalt.test.RealLogScanTest.xml`: tests="1" skipped="0" failures="0" errors="0"
- Pulled log: 70 lines; deleted after the sweep.

## Marker lines (value-free, latest session)

```
[P2P][native][BLOCK_LOAD_NATIVE] abi=arm64-v8a pppp=ok tutk=skipped
[P2P][native][BLOCK_LOAD_NATIVE] abi=arm64-v8a pppp=ok tutk=ok
[P2P][runtime][BLOCK_INIT_PPPP] init=0 api=0xA2050401 debug=off
[Settings][nativeCheck][BLOCK_NATIVE_CHECK] libs=OK abi=arm64-v8a ppppApi=0xA2050401 init=0 tutkLibs=OK avc=OMX.qcom.video.decoder.avc(hw) hevc=OMX.qcom.video.decoder.hevc(hw)
[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=TNP via=deviceInfo listType=2 tnpInfo=ok hasPassword=true needsPincode=false
[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=TUTK via=uid listType=0 tnpInfo=notTnp hasPassword=true needsPincode=false
[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] stored=2 withLicense=1 withPassword=2 verified=2
[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=2 shared=0 tnp=1 tutk=1 langtao=0 unknown=0
```

The log also holds an earlier refresh from the previous build (pre-02-01 marker format without
`via=`/`verified=`); it agrees on the counts (tnp=1 tutk=1) and is not used for the gate.

Observation: `[Media][catalog][BLOCK_QUERY_DECODERS]` is absent — Settings calls
`DecoderCatalog.find()` directly, not `summary()`, so that marker is never emitted. The same
decoder facts are carried by `BLOCK_NATIVE_CHECK` (avc/hevc name + hw flag). Not a defect for
this gate; worth wiring when the decoder is chosen in the session layer.

## Task 1 results (build gate)

- Full unit suite: 397 tests green; `:app:assembleDebug` BUILD SUCCESSFUL
- APK check: `lib/arm64-v8a/` and `lib/armeabi-v7a/` each hold exactly libPPPP_API.so,
  libIOTCAPIs.so, libAVAPIs.so, libsCHL.so; no x86 dirs; arm64 libPPPP_API.so hash matches
  `app/vendor-natives.sha256`; dex contains `com/p2p/pppp_api/PPPP_APIs` — OK
- R8: `:app:minifyReleaseWithR8` green after the dontwarn fix (c4f3dd0); mapping.txt keeps
  `com.p2p.pppp_api.PPPP_APIs` and `com.tnp.model.st_PPPP_Session` (2 lines) — OK

## Evidence lines

CRITERION_1_RUNTIME: PASS (pppp=ok, init=0, api=0xA2050401 — equals the expected 0xA2050401)
HEVC_DECODER: OMX.qcom.video.decoder.hevc(hw)
AVC_DECODER: OMX.qcom.video.decoder.avc(hw)
TRANSPORTS: tnp=1 tutk=1 langtao=0 unknown=0
TRANSPORT_CAM_A: transport=TNP via=deviceInfo listType=2 tnpInfo=ok
TRANSPORT_CAM_B: transport=TUTK via=uid listType=0 tnpInfo=notTnp
VERIFIED: devices=2 verified=2
GATE_2B: needed
RESULT: PASS

Gate rule (priority unknown -> unverified -> tutk -> langtao -> skip): unknown=0, verified=2 of
devices=2, tutk=1 -> `needed`. Plan 02-10 (TUTK) is required for the second camera.

Self-check (`python -I`, recomputes GATE_2B from TRANSPORTS/VERIFIED and asserts the rule on
the five synthetic tuples): GATE_RULE_OK
