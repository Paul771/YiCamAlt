# Phase 1 device verification

Date: 2026-10-12, device: Mi 9 (physical), commit: 298b335, APK installed by hand (adb install is blocked by MIUI).
User reply: `approved cameras=2 official=2` (the tnp/tutk numbers in the reply were misread; the log is authoritative: tnp=1 tutk=1).
Sweep: RealLogScanTest on the pulled device log, run with --rerun-tasks (executed, 0 failures). Raw log deleted afterwards.

## Evidence (value-free log markers, clean install: pm clear, login without the gear / manual server)

```
18:56:46.587 [App][init][BLOCK_INIT_APP] diagLog=internal legacyPurged=0
18:57:30.223 [Auth][login][BLOCK_REGION_FALLBACK] region=US attempt=1 code=20000
18:57:30.535 [Auth][login][BLOCK_REGION_DISCOVERY] region=US code=20000 count=2
18:57:30.536 [Auth][login][BLOCK_REGION_SELECTED] region=US reason=devices logins=1 lists=1
18:57:30.542 [Login][vm][BLOCK_LOGIN_SUCCESS] login succeeded
18:57:30.704 [Cameras][load][BLOCK_LOAD_CAMERAS] source=cache count=0
18:57:30.970 [Camera][getCameraList][BLOCK_FETCH_SOURCE] source=v5list code=20000 count=2
18:57:31.194 [Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=TNP mismatch=true hasPassword=true tnpInfo=ok needsPincode=false
18:57:31.195 [Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=TUTK mismatch=false hasPassword=true tnpInfo=n/a needsPincode=false
18:57:31.196 [Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] stored=2 withLicense=1 withPassword=2
18:57:31.207 [P2pCreds][replaceAll][BLOCK_STORE_P2P_CREDENTIALS] count=2
18:57:31.216 [Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=2 shared=0 tnp=1 tutk=1
18:57:31.221 [Cameras][load][BLOCK_LOAD_CAMERAS] source=network result=content count=2 kind=-
```

Requests of the fresh login: 1 login, 2 device-list calls (1 region discovery + 1 registry refresh), 1 tnp device_info.

## Criteria

| ID | Result | Evidence |
|----|--------|----------|
| SEC-01 | PASS | RealLogScanTest sweep of the real device log: clean |
| SEC-02 | PASS | log lives in internal storage (`diagLog=internal`, run-as readable); external app dir listing has no log file |
| SEC-03 | PASS | repo-guard range scan over the whole phase exits 0; hook self-test 8/8 |
| SEC-04 | PASS | credentials only via the encrypted store (stored=2); Room rows carry transport/shared only; logout wipe is fail-closed (JVM tests); the Keystore-backed store ran on the device without error |
| CAM-01 | PASS | user: 2 cameras on screen = 2 in official Yi Home; log devices=2 |
| CAM-02 | PASS | region found automatically: BLOCK_REGION_SELECTED region=US reason=devices logins=1 lists=1, no manual server |
| CAM-03 | PASS | transport per camera resolved (TNP + TUTK); stored=2 withLicense=1 (the TNP camera) withPassword=2 |
| CAM-04 | PASS | JVM state tests (17); airplane-mode banner confirmed by the user |
| CAM-05 | PASS | cache-first start with banner confirmed by the user; pull-to-refresh confirmed by the user |

## Observations for Phase 2 (not Phase 1 failures)

- One camera is TUTK, one is TNP. The v1 requirement LIVE-01..03 therefore needs BOTH transports for full coverage; TUTK was planned only as a fallback (P2P-01 in v2). Decide in Phase 2 planning whether to start with the TNP camera and add TUTK in the same phase.
- The TNP camera has `mismatch=true`: the list `type` and the p2pid prefix disagree for it. Phase 2 must resolve which one is right before connecting (check by trying both).
- `/v4/tnp/device_info` works for the TNP camera (DID, InitString, License).

GAPS: none for Phase 1

RESULT: PASS
