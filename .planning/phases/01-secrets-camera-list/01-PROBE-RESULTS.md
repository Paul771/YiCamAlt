# Phase 1 probe results

Date: 2026-10-11, device: Mi 9 (physical, USB), app commit: 8f153f2
Four probe runs, each after a fresh login on one server: EU, SG, EU-again(skipped), US. The decisive run is the US login.
Sweep: RealLogScanTest on every pulled device log passed (executed with --rerun-tasks, 0 failures). Raw logs deleted.

## Probe lines of the decisive run (login on gw-us, value-free)

```
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v4/devices/list http=200 code=20000 len=1387 count=2 shape=array fields=activeTime,appParam,category,count,did,flag,hasPincode,interVersion,ipcParam,isNew,message,model,name,nickname,online,onlineTime,password,relayFlag,sdState,share,state,type,uid,webPairStatus ipcParam=object(ip,mac,p2p_encrypt,signal_quality,ssid) onlineTime=object(date,day,hours,minutes,month,nanos,seconds,time,timezoneOffset,year) devicelike=true
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/devices/list http=200 code=20000 len=1387 count=2 shape=array fields=activeTime,appParam,category,count,did,flag,hasPincode,interVersion,ipcParam,isNew,message,model,name,nickname,online,onlineTime,password,relayFlag,sdState,share,state,type,uid,webPairStatus ipcParam=object(ip,mac,p2p_encrypt,signal_quality,ssid) onlineTime=object(date,day,hours,minutes,month,nanos,seconds,time,timezoneOffset,year) devicelike=true
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/deviceshare/friends http=200 code=20000 len=26 count=0 shape=array fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v4/devices/list http=200 code=-10003 len=17 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/devices/list http=200 code=-10003 len=17 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/deviceshare/friends http=200 code=-10003 len=17 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v4/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/devices/list http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/deviceshare/friends http=200 code=20202 len=16 count=- shape=absent fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=SG path=/v5/deviceshare/shareddetail http=405 code=- len=745 count=- shape=non-json fields= devicelike=false
[Probe][run][BLOCK_PROBE_MATRIX] region=US path=/v4/tnp/device_info http=200 code=20000 len=172 count=1 shape=object fields=DID,InitString,License devicelike=true
[Probe][run][BLOCK_PROBE_SUMMARY] requests=13 withData=3 authCodes=3
```

## Decision keys

BRANCH: A
OWNED_PATH: /v5/devices/list
SHARED_PATH: none
DATA_REGION: US
LOGIN_REGION: US
CROSS_REGION_SESSION: no
TNP_INFO: ok(DID,InitString,License)
DEVICE_FIELDS: activeTime,appParam,category,count,did,flag,hasPincode,interVersion,ipcParam,isNew,message,model,name,nickname,online,onlineTime,password,relayFlag,sdState,share,state,type,uid,webPairStatus
IPCPARAM_SHAPE: object
KEY_MAPPING: uid=uid, p2pid=did, type=type, password=password, name=name, model=model, online=online, hasPincode=hasPincode
NOTES: see below

## Notes

- Two cameras, both in the owner list on the US server. /v4/devices/list and /v5/devices/list return the same payload (same length, same fields). OWNED_PATH is /v5/devices/list because that is the only path the vendor app 6.9.8 uses (01-STATIC-FINDINGS.md); /v4 is kept as a fallback candidate only.
- Transport: /v4/tnp/device_info answers with DID, InitString, License, so at least the probed camera is TNP (CS2 PPPP). This matches the TNP-first decision.
- ipcParam arrives as a JSON object here (ip, mac, p2p_encrypt, signal_quality, ssid); the static analysis saw it handled as a JSON string. The parser must accept both.
- Region finding (changes plan 01-08): the same credentials log in successfully on EU, SG and US. Only the US account has the cameras; EU and SG answer code 20200 (= empty list, not an error). So "login succeeded" does not identify the region. Region discovery must be: log in on a region, read the device list, and keep the first region whose list is non-empty; a 20253-only fallback is not enough. A user in Russia would be mapped to EU by the vendor country table, which is wrong for this account.
- Tokens are per region: a US session gets 20202 on SG and -10003 on EU.
- There is a fourth server, https://api.xiaoyi.com (China), not covered by the probe matrix.
- /v5/deviceshare/shareddetail is a PUT that changes access rights (static findings); the 405 is expected and the path must be removed from the list candidates.
- Earlier runs: EU login -> EU list code 20200, friends 20000 empty; SG login -> same on SG.
- Default base URL of the app is a placeholder host, so the first login fails out of the box.
