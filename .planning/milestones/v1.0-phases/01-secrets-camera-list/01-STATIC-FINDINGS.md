# Phase 1 static findings: camera-list request in vendor Yi Home 6.9.8

Date: 2026-10-11. Source: `_apk/extracted/base.apk` -> `classes2.dex` (the only dex holding the
`/v5/devices/list` and `deviceshare` strings), decompiled with jadx 1.5.6 into git-ignored
`_re/jadx2/` (`--no-res`). All class refs below are obfuscated jadx names under
`_re/jadx2/sources/`. No network calls were made and no secrets were read or printed.

## TL;DR

1. **Our `/v5/devices/list` request is already identical to the vendor's** (method, path, params,
   signature, headers). There is no Retrofit interface; it is a hand-built OkHttp GET.
2. **`code=20200` is NOT a request error. The vendor app treats it as "list is empty".** The
   device-list ViewModel catches 20200, clears the local list and shows an empty list. So the EU
   probe means: the gateway accepted the call and the account has **no devices on the EU server**.
3. `/v5/deviceshare/shareddetail` is **not a list endpoint**. It is a PUT that changes a share's
   access right. Shared cameras arrive in the **same `/v5/devices/list` response** and are told
   apart by item fields (`category`, `share`, `sharedBy`).
4. So the next step is about **account/region/data**, not request format. Candidates: the cameras
   are bound to another region server, or to another account, or the account really has none.
   See "What is still unknown".

## 1. Device list (owned + shared): GET /v5/devices/list   (confidence: HIGH)

Call chain (who triggers it in the app):
`viewmodel/a0.java` (DeviceListViewModel) `g0()` -> `fa/u1.java` `T1()` ->
`wa/d.java` (RequestCenter) `s0(String userid)` -> `y(url, "GET", params)` -> inner class
`wa.d$h` -> `za/a.java` (CommonRequest) `d()` builds the GET -> `wa/a.java` (CommonOkHttpClient) `c()`.

Request, exactly:

| Item | Value |
|---|---|
| Method | `GET` (`wa.d.b = "GET"`, built by `za.a.d()`) |
| URL | `<regionBase>/v5/devices/list` (`wa.c.b(path)`) |
| Query, signed, in this order | `seq=1`, `userid=<USER_NAME pref = login response "userid">` |
| Query, unsigned | `hmac=<signature>` |
| Body | none |
| No other params | no type/page/version/language/timestamp |

- Signature (`wa.d.m0` -> `mc/c1.java` `b()/c()`): Base64(HmacSHA1(key = `TOKEN + "&" + TOKEN_SECRET`,
  msg = `"seq=1&userid=<id>"`)). Key comes from `s9/e0.java` `A()` = pref `TOKEN`, `B()` = pref `TOKEN_SECRET`.
  This is the same as `YiRequestSigner.kt`.
- The query string is put on the URL with OkHttp `HttpUrl.Builder.query(raw)` (`za.a.k`), with values
  not pre-encoded. OkHttp turns `+` in the Base64 into `%2B`; `/` and `=` stay as they are. Param order
  in the URL comes from a `ConcurrentHashMap`, so it is effectively random. The server does not care about
  URL order; only the signed string order (`seq`, then `userid`) matters.
- Region base (`wa/c.java` `b()` + `y9/d.java` `i(false)`): CHN -> `https://api.xiaoyi.com`, USA ->
  `https://gw-us.xiaoyi.com`, EU -> `https://gw-eu.xiaoyi.com`, everything else (SEA) -> `https://gw-sg.xiaoyi.com`.
  The region is `w9.d.j` = `LocaleInfo.server` of the country picked at login (`assets/locale.json`:
  245 countries, server in {EU, SEA, USA}; **RU, UA, BY -> EU; KZ -> SEA**).

Response handling (`wa.d$h.subscribe`):
- HTTP 200 and `code == 20000` -> success, `data` is a JSON **array** of device objects.
- Any other `code` -> `OkHttpException(code, msg)`; 20202 also triggers the "session expired" event.
- **20200 handling** (`viewmodel/a0.java`, inner class `f.onError`, about line 139): `if code == 20200 ->
  u1.A2() (clear list); onNext(emptyList)`. Same pattern in `fragment/DeviceListFragment.java`
  (lines ~509 and ~1163), `ab/f.java:470` (20200 -> default/empty result) and
  `DeviceShare2YiAccountActivity` (20200 -> "share_userNotFound"). **20200 means "no data / not found".**

## 2. Shared devices   (confidence: HIGH)

There is no separate "list of devices shared with me" call in the device-list flow. Shared cameras
come in the same `/v5/devices/list` array:
- `category` -> `DeviceInfo.shareType` (0 = own; non-zero = shared; the app checks `state == 5 && shareType == 0`).
- `share` (bool) -> `isShare`, `sharedBy` (int), `sharedTime`, `nickname` -> `fromUser` (owner's name),
  `accessRight`, `accessCount`, `count` -> `shareCount`.

The "deviceshare" endpoints (`va/i.java` = HttpClientV5, `va/h.java` = HttpClientV4). Method mapping
from `va/b.java`: `f` = GET, `k` = POST (form), `m` = PUT (form body), `l`/`j`/`b` = PUT/POST/DELETE with
the params in the URL query (URL-encoded via `za.b.b()`) and an empty body.

| Path | Method | Signed params (in order) | Meaning |
|---|---|---|---|
| `/v5/deviceshare/shareddetail` | **PUT**, params in the query, empty form body | seq, userid, inviteeid, uid, access_right | change a friend's access right on one camera (`va.i.b0`). **That's why GET -> 405.** |
| `/v4/deviceshare/shareddetails` | GET | seq, userid, uid | who one camera (`uid`) is shared with (`va.h.s`) |
| `/v5/deviceshare/friends` | GET | seq, userid | friends list (`va.i.g0`); PUT with name/friendid = rename |
| `/v5/deviceshare/invitee/invitations` | GET | seq, userid [, history=true only for CHN] | invitations received |
| `/v5/deviceshare/invitor/invitations` | GET | seq, userid, uid | invitations sent for a camera |
| `/v5/deviceshare/accept` | PUT (query) | seq, userid, share_token, accept | accept an invitation |
| `/v8/usershare/invitee/list` | GET | seq, timestamp, userid | new (v8) share flow: pending invitations for the invitee (`wa.d.y0`, used by `ac/o.java`) |

If a camera was shared with this account but never accepted, it shows up in
`/v5/deviceshare/invitee/invitations` or `/v8/usershare/invitee/list`, not in `/v5/devices/list`.

## 3. Headers / signing differences vs ours   (confidence: HIGH)

Every request from `za.a` adds: `x-kamihome-appType: ANDROID`, `x-kamihome-packageType: RELEASE`,
`User-Agent: yihome/<versionName> (<Build.MODEL>; Android <release>[(MIUI ...)]; <lang-COUNTRY>)`,
`x-xiaoyi-appCountryCode: <login country code, e.g. RU>`, `x-xiaoyi-appVersion: android;<versionCode>;<versionName>`
(set in `ab/d.java` `i()`). The only OkHttp interceptor (`sn.b`, in classes8) only watches for HTTP 555;
it adds no headers and does no signing. `YiAppHeaders.kt` already sends the same five headers; the only
difference is the UA (ours has no `Build.MODEL` or locale). That is unlikely to matter, since `/friends` returns 20000.

## 4. Device item fields (`fa/u1.java` `r2(JSONArray)`)   (confidence: HIGH)

| JSON key | Meaning / mapping |
|---|---|
| `uid` | camera UID; used as UID, DID and P2PID |
| `did` | display DID (`showDid`), also used to work out the model |
| `name` | nickname (`DeviceInfo.KEY_RES_NAME = "name"`) |
| `model` | server model string (default "1") -> `getDeviceModelFromServer(model, did)` |
| `type` | int `type` |
| `category` | shareType (0 = own) |
| `share`, `sharedBy`, `sharedTime`, `nickname`, `count`, `accessRight`, `accessCount`, `lastAccessTime` | sharing fields |
| `online` (bool), `state` (int), `message` (description), `flag` (bool -> isPublic) | status |
| `hasPincode` (bool) | if true, viewPassword = "" |
| `password` | P2P view password, **encrypted**: hex string -> AES/ECB/PKCS7 decrypt, key = first 16 chars of `uid` as bytes (`mc.k.a` -> `mc.a`). Empty -> default `888888` (`mc.k.d`). View account is `admin`. |
| `interVersion` | firmware version |
| `groupBindable`, `webPairStatus`, `relayFlag` (1 -> forceRelay) | misc |
| `ipcParam` | **JSON string** (parse a second time): `p2p_encrypt` (default true), `ssid`, `mac`, `ip`, `rssi`, `smartservice`, `signal_quality`/`signalQuality`, `battery`/`batteryLevel`, `battery_chg`, `wakeup` |

The `tnpServerString` and `tnpLicenseKey` fields are blank here. They come later from
`GET /v4/tnp/device_info` (`wa.d.B0`, signed params: seq, userid, uid; same key).

## What is still unknown / next step

- **Why the account has no devices on the EU server.** The request format is now ruled out. Options:
  (a) the cameras are bound under a different region server (for example, an account first registered with a
  SEA/USA country). We cannot check this with the EU token: US/SG return 20202 because tokens are per-region,
  so it needs a login with that region's country; (b) the cameras belong to a different Yi account;
  (c) they are shared but not accepted (check `/v5/deviceshare/invitee/invitations` and `/v8/usershare/invitee/list`).
  A cheap check: in the vendor app on the phone, note which country/region the account logs in with
  (Settings -> region) and whether the cameras show there.
- Our probe used `/v4/devices/list`. That path is **not in the 6.9.8 dex at all** (only `/v5/devices/list`);
  drop it.
- `CameraRegistry` already accepts 20200 as success (see its CHANGE_SUMMARY), which matches the vendor
  app. Its meaning should be written down as "empty list" (no `data`), not "ok with data".
- Not checked: the Kotlin/Retrofit modules in other dexes (classes6-8) for any newer "v8 device list".
  The string sweep found no other `devices/list` path in any of the 10 dex files, so this is unlikely.
