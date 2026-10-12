# Yi Cloud HTTP endpoints — static inventory from the official APK

> Extracted 2026-10-06 from `_apk/extracted/base.apk` (Yi Home 6.9.8, package
> `com.ants360.yicamera.international`). Method: DEX string-pool scan, no code decompilation.
> Reproduce with `tools/apk_endpoints.py`.

**This is static evidence, not a live capture.** A path appearing here proves the client can
call it; it does not prove which call carries which parameters. Parameter names, signing and
response envelopes still need a real capture (blocked on the emulator, see PROJECT_STATUS 0.2).

Total path-shaped string constants recovered: **210**.

## Versioning scheme

The camera / device / event families are versioned `/v2` `/v3` `/v4` `/v5` `/v8`, with service
namespaces `/vas/`, `/event/`, `/touch/`, `/bs/`, `/orderpay/`. Census:

```
v5=43  vas=38  v8=36  v4=33  api=15  orderpay=14  v2=13  event=8  bs=5  touch=2  v1=1  v3=1  oauth2=1
```

`/v1` is **not** the scheme, but it is not absent either — it appears exactly once in the main
app as `/v1/equipment/cloud/isGive`. A separate `/api/v1/app_sdk/*` set (cache, config, ddl,
monitor) belongs to a bundled third-party SDK in `classes7.dex` and carries its own `/api/v1`
prefix, unrelated to Yi Cloud versioning.

The decisive point for M-EVENT: **the guessed shape `/v1/camera/event/list` does not exist
anywhere in the APK.** Neither `/v1/camera/...` nor any `*/camera/event/*` path is present, so the
current `EventPaths` constant cannot be right.

## Camera list (home) — CONFIRMED

| Path | Note |
|---|---|
| `/v5/devices/list` | home device list; the HMAC scheme of M-CAMERA-LIST is confirmed on-device for this path |
| `/v5/devices/deviceinfo` | single-device detail |
| `/v5/devices/owners` `/v5/devices/relations` | owner / sharing graph |
| `/v5/devices/password` `/v4/devices/pincode` | credentials |
| `/vas/v8/cove/deviceinfo` | cove-variant device info |
| `/vas/v8/all/cloud/deviceList` | batched cloud device list |
| `/v8/monitor/camera` `/v8/monitor/deviceModels` | catalogue / model info |

## Event / alert timeline — CANDIDATES (replaces the failed `/v1` guess)

| Path | Note |
|---|---|
| `/v5/alert/events` | best candidate; same `v5` family as `/v5/devices/list` |
| `/v5/alert/events/top` | pinned/pinned-top events |
| `/v4/alert/events` | previous-generation list |
| `/v2/alert/list` | oldest generation list |
| `/v5/alert/del_list` | delete marker |
| `/v5/alert/push_prop` `/v4/alert/push_prop` | push subscription properties |
| `/v8/monitor/alarm/trigger` `/v8/monitor/alarm/action` | alarm trigger / reaction |
| `/event/v8/face/list` `/event/v8/face/alert` | per-device face events |

**Inference worth testing:** `/v5/alert/events` sits in the same `v5` family as the confirmed
signed `/v5/devices/list`. If the family shares one request shape, the M-EVENT signer
(`Base64(HMAC-SHA1)` over `seq` + `userid`) applies unchanged. That would resolve the
signing uncertainty at the same time as the path uncertainty.

## Playback / recording

No literal playback-URL endpoint was found — the path is assembled at runtime, so it cannot be
recovered by string scanning. Candidates by namespace:

| Path | Note |
|---|---|
| `/bs/v8/recording/list` `/bs/v8/recording/{id}` | recording list / detail (note: `/bs/` also serves the babycam SDK) |
| `/bs/v8/videoSummary/list` `/bs/v8/videoSummary/{id}` | video summaries |
| `/vas/hourglass/listVideoInfo` | time-lapse ("hourglass") video info |
| `/vas/v8/cloud/videos` `/vas/v8/cloud/videos/quickview` | cloud video list / quick view |
| `/v5/deviceshare/record` | shared recording |

## Auth (already verified on-device in Phase-1)

`/v4/users/login` `/v4/users/auth_token` `/v4/users/resend_activation_code`
`/v8/users/mobile/verificationCode`

## Hosts seen

`https://test-us-touch.xiaoyi.com` `https://test-lb.xiaoyi.com/state`
(`test-*` are staging; production hosts are resolved at runtime and are not literals here.)

## Method note

DEX string constants are length-prefixed; the low ASCII byte of the prefix is part of the match
window, so extracted constants can carry a leading junk character (commonly `!`). Values were
stripped of that prefix and validated against the endpoint shape before being recorded.
