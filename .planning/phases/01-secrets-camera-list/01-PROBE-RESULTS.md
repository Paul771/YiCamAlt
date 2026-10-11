# Phase 1 probe results

Date: 2026-10-11, device: Mi 9 (physical), login server: https://gw-eu.xiaoyi.com, app commit: 1f08b84
Sweep: RealLogScanTest on the pulled device log passed (executed, 0 failures). Raw log deleted.

## Probe lines (value-free)

```
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v4/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/deviceshare/friends http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v4/devices/list http=200 code=20200 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/devices/list http=200 code=20200 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/deviceshare/friends http=200 code=20000 len=26 count=0 shape=array fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v4/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/deviceshare/friends http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] tnp=skipped reason=no-uid-field
[Probe][run][BLOCK_PROBE_SUMMARY] requests=12 withData=0 authCodes=6
```

## Decision keys

BRANCH: D
OWNED_PATH: none
SHARED_PATH: none
DATA_REGION: none
LOGIN_REGION: EU
CROSS_REGION_SESSION: no
TNP_INFO: skipped
DEVICE_FIELDS: none
IPCPARAM_SHAPE: absent
KEY_MAPPING: ?
NOTES: see below

## Notes

- No row has devices, so by the classification rule BRANCH is D.
- The signer works: on EU the friends list answers code=20000 with an empty array (count=0), i.e. a valid signed call that succeeds. The owner and shared lists are the problem, not authentication.
- EU /v4/devices/list and /v5/devices/list answer HTTP 200 with code=20200 and a 16-byte body: a server-side error envelope. This is a request-content problem (missing or wrong parameters), not auth (auth failures are 20202).
- US and SG answer code=20202 on every signed row: the EU session is not valid there (CROSS_REGION_SESSION=no).
- /v5/deviceshare/shareddetail answers HTTP 405 on all regions: wrong HTTP method (probably POST) or wrong path. It was never reached as a valid call.
- tnp device_info was skipped (no uid field available), so the transport type is still unknown.
- Next: find the missing parameters of the list calls and the real method of shareddetail by static analysis of the vendor APK first (cheap, no user needed); apk-mitm capture on the phone is the fallback.
