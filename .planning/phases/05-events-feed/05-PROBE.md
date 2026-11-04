# Phase 5 — alert feed device probe (plans 05-00 / 05-01)

**Date:** 2026-11-03
**Device:** Mi 9 / MIUI (USB, adb), debug APK from plan 05-00 (probe v2, commit 91e83e6)
**Branch:** gsd/phase-5-events
**Run by:** the user (button «Проба событий (debug)» pressed once per run; two runs: v1, then v2 after the probe extension)
**Source of truth:** `05-PROBE-RESULTS.txt` (value-free v2 lines, recorded in commit b32ac08) and the user's report. The diag log was not re-pulled in 05-01: the recorded lines are already the swept, value-free `[EventProbe][run][BLOCK_ALERT_PROBE]` output.

Values (uid, DID, userid, URLs, keys, absolute times) are deliberately not recorded. Every number below is a count, a code, a digit count, a bucket or a minute / day delta.

## Raw probe lines (v2)

```
list http=200
list code=20000 count=18 keys=[ai_alert_level,category,event_desc,event_source,expire_time,file_type,id,incorrect_desc,media_type,message,name,no_risk,pic_pwd,pic_urls,push_type,sub_type,time,type,uid,verification_tags,video02_pwd,video02_urls,video_pwd,video_urls] oddKeys=0 timeDigits=13 expireDigits=13 expireInMin=5..1436 uidMatch=18/18 idBlank=0 oldestAgeDays=0 picExt=jpg:18,tar:0,yitar:0,other:0 fileType=mp4:18,ts:0,empty:0,other:0 picPwdLen=16 videoPwdLen=16 cameras=c0(shared=false):0,c1(shared=false):18,unknown:0
urlshape field=pic_urls types=string:18 elems=1 urls=18 schemes=http:18,https:0,other:0 hostLabels=4 ipHost=0 query=18 apiBase=0 ext=jpg:18
urlshape field=video_urls types=string:18 elems=1 urls=18 schemes=http:18,https:0,other:0 hostLabels=4 ipHost=0 query=18 apiBase=0 ext=mp4:18
urlshape field=video02_urls types=string:18 elems=0 urls=0 schemes=http:0,https:0,other:0 hostLabels=- ipHost=0 query=0 apiBase=0 ext=-
uid n=18 lens=20 class=other:18 dev[have=2/2 lens=20 eq=18 ci=18 pre=0 suf=0 sub=0] p2p[have=2/2 lens=20 eq=18 ci=18 pre=0 suf=0 sub=0] user[have=1/1 lens=6 eq=0 ci=0 pre=0 suf=0 sub=0] any=18/18
fields n=18 kinds=[ai_alert_level:str,category:num,event_desc:str,event_source:str,expire_time:num,file_type:str,id:str,incorrect_desc:str,media_type:num,message:str,name:str,no_risk:str,pic_pwd:str,pic_urls:str,push_type:num,sub_type:num,time:num,type:num,uid:str,verification_tags:str,video02_pwd:str,video02_urls:str,video_pwd:str,video_urls:str] video02Keys=[video02_pwd,video02_urls] video02Present=0 video02PwdLen=- sameKey=picVideo:0,video02Video:0 hosts=1
media cleartextDefault=false
preview attempt=orig scheme=http cleartext=false http=-1 sizeBucket=- decrypt=raw magic=- codec=- tarEntry=- error=UnknownServiceException
preview attempt=https-retry scheme=https http=200 sizeBucket=<16KB decrypt=ok magic=jpeg codec=- tarEntry=-
clip attempt=orig scheme=http cleartext=false http=-1 sizeBucket=- decrypt=raw magic=- codec=- tarEntry=- error=UnknownServiceException
clip attempt=https-retry scheme=https http=200 sizeBucket=64-256KB decrypt=ok magic=mp4 codec=avc tarEntry=-
requests=5
```

Sweep: the lines are produced by `AlertProbeShape` formatters whose output is asserted against RedactionScanner in `AlertProbeShapeTest` / `AlertProbeShapeFormsTest`; this file was checked for URL and 10+ digit runs (none).

Alerts seen by the user in Yi Home: the exact number was not reported; the probe returned 18 events, so the "empty list while Yi Home shows alerts" stop condition does not apply.

## Decisions

- **D-PROBE-1 envelope — confirmed.** `code=20000`; `data` is an array of objects (18). Keys contain `uid`, `time`, `pic_urls`, `pic_pwd`, `video_urls`, `video_pwd`, `expire_time`, plus `id`, `type`, `sub_type`, `file_type`. Names are PLURAL (`pic_urls`, `video_urls`, as in research §1), but each value is ONE string (one element), not a JSON array; the mapper still splits on commas tolerantly. **Missing vs research:** `play_time` (absent on all 18 -> `playTimeS = 0`, UI must not depend on it). **Extra keys:** `media_type` (num), `name`, `verification_tags` (str); `video02_urls` / `video02_pwd` present but empty (ignored in v1). Field kinds: `time`, `expire_time`, `type`, `sub_type`, `category`, `media_type`, `push_type` are numbers; `id`, `uid` and all URL / pwd fields are strings.
- **D-PROBE-2 time units — ms confirmed.** `timeDigits=13`, `expireDigits=13`; the mapper keeps both as is (no widening).
- **D-PROBE-3 camera id — uid == CameraInfo.deviceId.** 18/18 equal (case-sensitive), also equal to the p2p id of the same camera; never equal to the user id. `idBlank=0`.
- **D-PROBE-4 shared cameras — no shared camera on the account.** Both known cameras are `shared=false`; all 18 events belong to one camera (c1), the other camera has 0 events in the window. Whether shared cameras appear in the owner list stays open (no data).
- **D-PROBE-5 preview.** Extension jpg 18/18 (no tar / yitar); `pic_pwd` 16 chars; decrypt ok; magic JPEG; size <16KB. **Transport:** the URL scheme is http (18/18, with query, one host, host-name not IP); the app's cleartext policy is off (`cleartextDefault=false`), the http GET fails with `UnknownServiceException`; the https variant of the same URL works (http=200). Decision: media URLs are rewritten http -> https before any request (MediaRef holds the https form); the app network security config is NOT changed and cleartext is NOT enabled globally or per host.
- **D-PROBE-6 clip.** `file_type` mp4 18/18, ts 0 -> no HLS seen (HLS stays "клип недоступен"); `video_pwd` 16 chars; https GET http=200, decrypt ok, magic MP4, codec AVC (H.264), size 64-256KB. Same http -> https rule as previews.
- **D-PROBE-7 URL lifetime.** `expireInMin=5..1436`: `expires=10` does NOT mean ten minutes; links live up to about a day after the event (the youngest link had about 5 minutes left, the oldest about 24 h). `MediaRef.isStale(now) = now > expire_time + 10 s` stays; stale or 403 -> one ±5 s refresh query.
- **D-PROBE-8 retention and volume.** `count=18` in the 30-day window (limit 20, below the cap -> one page); `oldestAgeDays=0`: all events are from the last day, so server retention can NOT be determined from this run. Open: retention measured in device UAT 05-07 (or later), the feed must not assume more than one day of history.

## Gate

**CONTINUE.** code is 20000; count 18 > 0; D-PROBE-3 matches 18/18; D-PROBE-2 shows 13 digits; decrypt succeeded on both preview and clip (over https). Traffic capture fallback is not needed.

Inputs for 05-01 Task 2 and later plans:
- field names `pic_urls` / `video_urls` (plural), values single strings (comma split kept for tolerance);
- `play_time` absent -> `playTimeS` defaults to 0;
- http media URLs are rewritten to https in the mapper (media host only; cleartext stays off);
- times are ms; uid is the camera deviceId.
