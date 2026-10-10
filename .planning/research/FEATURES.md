# Feature Research

**Domain:** Alternative Android client for Yi Home / Yi Dome / Kami cameras on stock firmware (TUTK P2P + Yi Cloud HTTP), no paid subscription
**Researched:** 2026-10-10
**Confidence:** MEDIUM overall. The official app's free-vs-paid boundaries are HIGH: they come from the Yi Home 6.9.8 APK string resources, dumped with `aapt2 dump resources`. Exact P2P/HTTP wire behaviour is LOW until it is captured on the device.

> **Main evidence source.** I dumped the APK strings from `_apk/extracted/base.apk` with `aapt2 dump resources`, which gave 8,340 string key/value pairs. I also scanned the DEX string pool across `classes*.dex`. Resource keys are quoted as `string/<key>` and TUTK command names as `IOTYPE_*` so they can be re-grepped. Web sources were thin for Yi: most are vendor-adjacent blogs or forum posts. They serve only as cross-checks.

## What the official app actually gates (baseline for "no subscription")

| Capability | Free in official Yi Home 6.9.8? | Evidence (APK string) | Confidence |
|---|---|---|---|
| Motion alerts with a **6-second** cloud clip + image | **Free**, with a cooldown of up to 10 min between alerts | `alert_page_may_more_text_1`: "free cloud service ... 6-second activity clips for up to 7 days"; `alert_page_may_more_text_2`: "cool down period for free service can be up to 10 minutes"; `cameraSetting_alert_frequency_may_cloud_text_pop_up_text`: "unlimited amount of 6-second clips ... in the Alert section" | HIGH (strings); MEDIUM (current retention) |
| Free alert retention | **24 h to 7 days**. Strings from different generations conflict. | `app_thumbnail_bar4`: "Messages are stored for only 24 hours. Subscribe ... up to 30 days" vs `alert_page_may_more_text_1` (7 days) | MEDIUM: verify by capture |
| Full-length event video | **Paid** (cloud) | `camera_alert_video_play_purchase_cloud`, `cameraActivity_freeClipEnded_*` | HIGH |
| "Continue on SD card" after the free clip ends | **Free**. The official app jumps from the alert to SD playback at the same timestamp. | `camera_alert_video_play_full_video_SDcard` = "View SD card playback"; `cameraActivity_noSDCardVideo_body` = "No SD Card Video for this Activity". Matches a forum report. | HIGH |
| SD card timeline playback | **Free** (tab "SD Card", date picker, "End of SD Card footage") | `cameraActivity_sdCardTab_label`, `cameraActivity_noSDCardVideoOnDay_body`, `cameraActivity_datePickerEndSDCard_body` | HIGH |
| Multi-speed fast-forward in playback | **Paid** (cloud) in the official app | `CameraPlayContainer_1540363391_49`: "You can experience the Multi speed fast forward after you buy the cloud." | HIGH (string); scope (cloud-only vs SD) unclear |
| Live view duration | **Time-limited for free users** in the new tier | `app_live_streaming_90sExplanation`: "The free version will feature limited-time Live Previews"; `app_live_streaming_limit2`: "Your free viewing time has ended"; `app_livetime_reminduser` | HIGH that the limit exists. LOW whether the client or the server enforces it, and on which product lines. |
| HD streaming, 4x zoom, two-way talk | Listed as premium perks in some product lines ("phoneMagic" and VIP guide) | `app_phoneMagic_welcomegift_renewal3..6`, `app_vip_guide1..3` | LOW: possibly only the phone-as-camera product |
| AI detection (person/animal/vehicle/face) | **Paid** (free trials exist) | `cameraActivity_freeClipEndedUpgradeAI_title`, `cameraPopups_newAIFeaturesFreeUserPopup_body` | HIGH |
| Ads in the app | Present (Mintegral `mbridge_*`, `sdm_myoffer_*` gamified offers) | resource keys | HIGH |

**Product implication.** The official app has degraded its free tier. It limits live preview time, adds ads and upsells, and shows only 6-second clips. Restoring the free baseline is enough for the core value: unlimited live view, SD playback, and the alert feed with "jump to SD" on tap. None of it needs to touch paid endpoints.

### Can events come from the SD card instead of the cloud?

**Yes, very likely, over the same P2P channel.** The DEX contains TUTK IOCTRL names for SD-side indexes:

- `IOTYPE_USER_IPCAM_LISTEVENT_REQ/RESP`: the classic TUTK AVAPI "list recorded events in time range" command.
- `IOTYPE_USER_IPCAM_GET_DAY_EVENT_LIST_REQ/RESP`, `IOTYPE_USER_IPCAM_GET_ALARM_EVENT_LIST_REQ/RESP`, `IOTYPE_USER_IPCAM_TNP_EVENT_LIST_REQ`: Yi-specific per-day and alarm event lists.
- `IOTYPE_GET_SD_INDEX_REQ/RESP`, `IOTYPE_GET_SD_FILE_REQ/RESP`: SD index and file fetch.
- `IOTYPE_USER_IPCAM_RECORD_PLAYCONTROL` / `PLAYCONTROL2`: start, pause, seek, and stop of SD playback.
- `IOTYPE_USER_IPCAM_GET_TIME_VIDEO(_NEW)`: likely the list of recorded time segments for the timeline.

The names are confirmed in the DEX (HIGH). Payload layouts and which ones the Yi firmware honours are not confirmed (LOW). Settings strings `storage_SDcard_record_event_tip` ("Only record when an alarm event is detected") and `storage_SDcard_record_timed_tip` show that the SD card records continuously or only on events. In event mode the SD index **is** an event list.

**Recommendation.** Build the events feed as a merge of two sources:
1. The cloud alert list (`/v5/alert/events` candidate). It is free, has thumbnails and 6-second clips, and works while the camera is offline or the P2P session is closed.
2. The SD event/day index over P2P. It gives full-length footage and covers events beyond the 24 h to 7 day cloud window.

Cloud is the primary feed for v1 because it needs no P2P session per camera to render a list. SD is the "play full video" path.

## Feature Landscape

### Table Stakes (Users Expect These)

#### Camera list

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| List of the account's cameras with name, model, and online/offline state | Entry point of every camera app | LOW | `/v5/devices/list` signing is already confirmed. The body still needs to be decoded (16-byte reply = empty or encrypted). Hard dependency for everything else. |
| Last-frame thumbnail per camera | Official app, tinyCam, and wyze-bridge (`SNAPSHOT=API`) all show a still | MEDIUM | Cheapest source is the latest alert image from the cloud (no P2P). Second choice is a frame grabbed from the last live session and cached locally. |
| Shared cameras (owner granted access) | Official app supports sharing (`/v5/devices/owners`, `relations`, `cameraActivity_onlyLiveViewPermission_body`) | LOW | List and view only. Respect the "live-view only" permission and do not offer SD playback for those cameras. |
| Pull-to-refresh, offline badge | Basic UX | LOW | — |

#### Live view

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Live video **on LAN and remotely** (P2P, falling back to relay) | Core value. wyze-bridge on the same TUTK stack does LAN → P2P → relay automatically. | **HIGH** | Needs P2P credentials from the device list, JNI to `libIOTCAPIs.so`/`libAVAPIs.so`, and a MediaCodec decoder. TUTK picks the mode itself; expose the current mode (LAN/P2P/Relay) in the UI for debugging. |
| H.264 **and H.265** decode | Newer Yi/Kami cameras use H.265 (`MEDIA_CODEC_VIDEO_H265`, `video/hevc` in DEX) | MEDIUM | MediaCodec handles both. Check hardware HEVC support on the user's phone. |
| Audio playback + **mute toggle** (muted by default) | Universal | MEDIUM | DEX references `audio/g711-alaw`, `g711-mlaw`, `mp4a-latm`, plus PCM/ADPCM/G726/Speex enums. Expect G.711 or AAC. Start audio with `IOTYPE_USER_IPCAM_AUDIOSTART`. |
| **HD / SD / Auto** quality switch | Official app has `cameraActivity_hdBitrate_option`, `sdBitrate_option`, `autoBitrate_option`; wyze-bridge has substreams with HD/SD quality | MEDIUM | IOCTRL candidates: `IOTYPE_USER_IPCAM_SET_RESOLUTION`, `SET_HD_RESOLUTION`. Default to SD when on relay. |
| **Snapshot** to gallery | Official app ("Screenshot saved.") and every competitor | LOW | Grab the last decoded frame (TextureView/SurfaceView `PixelCopy`) and save to MediaStore. Needs no camera command. |
| **Fullscreen / landscape** + pinch-zoom | Official app has 4x digital zoom; universal expectation | LOW–MEDIUM | Compose `graphicsLayer` transform. Zoom is free in our app. |
| **Auto-reconnect** + clear connection states (connecting / LAN / relay / camera offline / camera turned off) | Relay sessions drop; official app has `liveView_deviceDisconnected_desc` and `live_video_off` | MEDIUM | Exponential backoff with a hard cap (see anti-features: no reconnect storms). Pause the stream in background, resume on foreground. |
| Stop the stream when leaving the screen or backgrounding | Battery, data, and P2P slot hygiene | LOW | Cameras limit concurrent viewers (`IOTYPE_WATCH_COUNT`, `IOTYPE_USER_TNP_IPCAM_KICK`). A leaked session blocks the official app. |

#### SD-card archive playback

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| **Day picker** (calendar, days with footage marked) | Official "SD Card" tab with date picker | MEDIUM | Needs the per-day index over P2P (`GET_TIME_VIDEO*` / `LISTEVENT` / `GET_DAY_EVENT_LIST`). |
| **24 h timeline scrubber** with recorded segments drawn; event markers on it | Official app: "Drag the timeline to select video clips"; universal in NVR apps | HIGH | Custom Compose canvas: zoomable hour/minute scale, segments from the index, event ticks from the cloud or SD event list. |
| **Seek** by dragging, play/pause, "Back to live" | Official `camera_live_botton` "Back to live", `camera_live_status1/2` Live/Playback | HIGH | `RECORD_PLAYCONTROL` seek semantics need a capture. Expect seek to snap to the nearest file or I-frame boundary. |
| Empty states: "No video recorded to SD Card on %s", no SD card, SD full, card error | Official app has all of them | LOW | Map IOCTRL status codes. |

#### Motion events / alerts feed

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Chronological feed with **thumbnail**, camera name, time, type (motion / sound / person if present) | Official "Alerts" section; free | MEDIUM | `/v5/alert/events` candidate. Fields seen in DEX: `img_url`, `video_url`, `pic_pwd`, `video_pwd`, `subtype`. Media are probably **encrypted per event** and must be decrypted locally (see PITFALLS). |
| **Filter by camera and by day** | Official app filters the activity list by camera and date | LOW | Client-side over the Room cache plus a server time-range query. |
| Tap event → play the **6-s cloud clip** | Free in the official app | MEDIUM | Decrypt and play with ExoPlayer/Media3, or MediaCodec if the format is raw. |
| Tap event → **"Play full video from SD card"** at the event timestamp | Official free path (`camera_alert_video_play_full_video_SDcard`) | MEDIUM (once SD playback exists) | Reuses the SD playback screen with an initial seek. This is the key free substitute for paid full clips. |
| Local cache of events and thumbnails | Free cloud retention is short (24 h to 7 days) | LOW | Room already exists. Caching thumbnails on the user's device extends history without anything paid. |
| New-event indicator / notification | Users expect alerts | MEDIUM | **We cannot receive Yi's FCM pushes**: the push token belongs to Yi's Firebase project. Use WorkManager periodic polling (minimum 15 min) or foreground polling while the app is open. Set the expectation as "near-real-time", not instant. |

### Differentiators (Competitive Advantage)

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| **Unlimited live view** (no 90-second / "free viewing time has ended" cutoff) | The single most visible regression of the official free tier | Depends | If the limit is client-side (timer plus `app_livetime_*` dialog), we get it for free by not implementing it. If the relay enforces it server-side, remote sessions may still be cut, so reconnect transparently. **Verify in capture.** |
| **No ads, no upsell screens** | Official app ships the Mintegral ad SDK and gamified offers | LOW | Comes free with a clean app. |
| Pinch zoom and multi-speed SD playback without a subscription | Official gates 4x zoom and fast-forward in some tiers | LOW / MEDIUM | Zoom is a pure client feature. SD fast-forward depends on the firmware honouring a speed parameter in `RECORD_PLAYCONTROL`. Fallback is client-side frame skipping. |
| **Multi-camera grid** (2x2 live tiles) | tinyCam's core feature; the official app shows one camera at a time | HIGH | Each tile is a P2P session with its own decoder and bandwidth. Do it after single-view is stable, defaulting to SD substreams. v1.x. |
| Event history longer than the cloud window | Local Room cache of alert metadata and thumbnails, plus the SD index | LOW–MEDIUM | Natural by-product of the cache. |
| Merged timeline (cloud events + SD segments on one scrubber) | Better than the official split Cloud/SD tabs | MEDIUM | After both sources exist. |
| Connection-mode transparency (LAN / P2P / Relay badge, bitrate) | Power-user trust; wyze-bridge exposes `NET_MODE` | LOW | Read session info from IOTC. |
| Picture-in-picture for live view | tinyCam-class feature; Android native API | LOW–MEDIUM | v1.x. |
| Save SD clip to phone (download a segment) | Official supports download (`cameraActivity_maxTimeLimit_caption` "Maximum 10 minutes per download") | MEDIUM | Remux the received H.264/H.265 + G.711/AAC into MP4 with MediaMuxer. G.711 needs transcoding or dropping, because MediaMuxer cannot mux G.711 into MP4. v1.x. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| Anything under `/orderpay/*`, `/vas/v8/cloud/*`, cloud-video endpoints, "free trial" activation, `/v1/equipment/cloud/isGive` | "Unlock cloud for free" | Touches billing and entitlements. It is ToS abuse, the most likely trigger for an account ban, and plausibly illegal. It also breaks the project's stated scope. | Never call payment or entitlement endpoints. Treat the cloud as the 6-second free tier only. Full video comes from the SD card. |
| Playing paid full-length cloud recordings by bypassing the client-side check | Same | Entitlement circumvention, with the same ban and legal risk | "Play full video from SD card" |
| Aggressive polling / reconnect loops (login every launch, list every few seconds, retry forever) | "Real-time" feel | Analogous Wyze case: an account was banned after 500+ logins/day from a Homebridge plugin, and failed-login lockouts followed. The Yi backend has similar anti-abuse (`app_live_streaming_90sExplanation` mentions "reallocating free tier resources"). | Reuse refresh tokens and never re-login on a timer. Poll alerts at ≥ 5 min in the foreground and 15 min in the background. Use backoff with a cap on P2P reconnect. Use a single in-flight request per endpoint. |
| Spoofing a different app identity or region to get perks | Workaround for regional limits | Detectable and ban-worthy | Use honest region selection (gw-us/eu/sg already exists). |
| Changing camera settings (`SET_TF_FORMAT`, `SET_MOTION_DETECT`, `RESET`, `SET_VER_RECOVER`, OTA) in v1 | "While we're at it" | Destructive and irreversible (format SD, factory reset, firmware) with unverified payloads. One malformed IOCTRL can wipe a card or brick a camera. Out of scope per PROJECT.md. | Read-only P2P in v1 (live, index, playback). Settings arrive later behind explicit confirmation. |
| Two-way talk in v1 | Official feature | Needs an audio encoder, `SPEAKERSTART` channel, and echo handling. It is not in the user's v1 scope and adds a second AV channel to debug. | v1.x after live audio is solid |
| Real-time push identical to the official app | Users expect instant alerts | Impossible without Yi's FCM sender. Re-registering push properties (`/v5/alert/push_prop`) could hijack or break the official app's notifications. | WorkManager polling; never call `push_prop` |
| Running in parallel with the official app on the same camera all the time | Convenience | Concurrent-viewer limits (`IOTYPE_WATCH_COUNT`, `TNP_IPCAM_KICK`) mean one client kicks the other | Close sessions promptly; show "camera busy / viewer limit" clearly |
| Local recording (NVR-style continuous recording to phone) | tinyCam does it | Persistent P2P session drains battery and data, keeps a viewer slot occupied, and adds a large storage and UX surface | Snapshot plus clip download only |
| yi-hack / RTSP / ONVIF paths | Every HA integration and the old HA `yi` platform need custom firmware | Explicitly out of scope; the user chose stock firmware | — |

## Feature Dependencies

```
Login + token refresh (done)
    └──requires──> Device list decode (/v5/devices/list body + P2P creds: TUTK UID, password/key)
                       ├──requires──> TUTK JNI session (IOTC connect → AV client start)
                       │                  ├──> Live view (video decode H.264/H.265)
                       │                  │       ├──> Mute/audio (G.711/AAC decode)
                       │                  │       ├──> HD/SD switch (SET_RESOLUTION IOCTRL)
                       │                  │       ├──> Snapshot (last frame, no IOCTRL)
                       │                  │       ├──> Fullscreen/zoom (pure UI)
                       │                  │       └──> Reconnect/state machine
                       │                  └──> SD index (GET_TIME_VIDEO / LISTEVENT / DAY_EVENT_LIST)
                       │                          └──> SD playback (RECORD_PLAYCONTROL start/seek/stop)
                       │                                  └──> Timeline scrubber UI
                       └──> Cloud alert list (/v5/alert/events, signed like devices/list)
                                ├──> Thumbnail/clip decrypt (pic_pwd / video_pwd)
                                ├──> Filters (camera/day) + Room cache
                                └──> "Play full video from SD" ──requires──> SD playback

Camera list thumbnail ──enhanced by──> latest alert image (cloud) OR last live frame
Multi-camera grid ──requires──> stable single live view + SD substream
Clip download ──requires──> SD playback + MediaMuxer remux
Background new-event notification ──requires──> alert list + WorkManager
```

### Dependency Notes

- **Everything video needs the P2P credentials from the device list.** Decoding the `/v5/devices/list` body (and confirming that it carries the TUTK UID and AV password/key) is the critical-path blocker. Live view, SD, and grid are all impossible without it.
- **Live view before SD playback.** Both share the IOTC/AV session, the decoder, and the audio pipeline. SD playback adds only the index and playcontrol IOCTRLs plus the timeline UI. Do not build them in parallel.
- **The events feed is partly independent of P2P.** Its cloud half only needs HTTP signing (already solved) and can ship before or alongside live view. Its "full video" action depends on SD playback.
- **Thumbnail/clip decryption is a hidden dependency of the feed.** If `pic_pwd`/`video_pwd` mean per-event encryption, the feed shows nothing visual until the decryption scheme is reversed from the APK.
- **Two-way talk conflicts with the v1 stability goal.** It adds an outbound AV channel and stays deferred.

## MVP Definition

### Launch With (v1)

- [ ] Real camera list with online state and P2P credentials parsed. Gates everything.
- [ ] Live view LAN + remote (auto LAN → P2P → relay), H.264/H.265, auto-reconnect, clear states. This is the core value.
- [ ] Mute toggle with audio decode. Expected by every user.
- [ ] HD/SD switch (Auto optional). Remote and relay need SD.
- [ ] Snapshot to gallery, fullscreen, and pinch-zoom. Cheap and expected.
- [ ] SD archive: day picker, timeline with segments, seek, play/pause, back to live.
- [ ] Alerts feed from the cloud: thumbnail, camera/day filter, 6-s clip, and "full video from SD" jump. Room cache.
- [ ] No live-time limit, no ads. These differentiators come free with a clean implementation.

### Add After Validation (v1.x)

- [ ] SD event list as a second feed source and event ticks on the timeline, once SD index IOCTRLs are understood.
- [ ] Background polling notification (WorkManager), once the alert endpoint has proven rate-safe.
- [ ] Clip download (MP4 remux), once SD playback is stable.
- [ ] Multi-camera grid and PiP, once single live view is stable on relay.
- [ ] Two-way talk.
- [ ] Playback speed (if the firmware supports it).

### Future Consideration (v2+)

- [ ] PTZ (`IOTYPE_USER_PTZ_*`) and camera settings: destructive commands only behind confirmation.
- [ ] Merged cloud+SD timeline.
- [ ] Android TV / widgets.

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---|---|---|---|
| Device list + P2P creds | HIGH | MEDIUM | P1 |
| Live view LAN+remote | HIGH | HIGH | P1 |
| Mute/audio | HIGH | MEDIUM | P1 |
| HD/SD switch | MEDIUM | MEDIUM | P1 |
| Snapshot / fullscreen / zoom | MEDIUM | LOW | P1 |
| Reconnect state machine | HIGH | MEDIUM | P1 |
| Cloud alerts feed + thumbnails | HIGH | MEDIUM (HIGH if encrypted) | P1 |
| SD day index + timeline + seek | HIGH | HIGH | P1 |
| Event → SD jump | HIGH | LOW (after SD) | P1 |
| SD event list source | MEDIUM | MEDIUM | P2 |
| Background notifications | MEDIUM | MEDIUM | P2 |
| Clip download | MEDIUM | MEDIUM | P2 |
| Multi-camera grid / PiP | MEDIUM | HIGH | P2 |
| Two-way talk | MEDIUM | HIGH | P2 |
| PTZ / settings | MEDIUM | MEDIUM, risky | P3 |

## Competitor Feature Analysis

| Feature | Official Yi Home 6.9.8 (free tier) | tinyCam Monitor | docker-wyze-bridge (Wyze, same TUTK stack) | HA / yi-hack | Our Approach |
|---|---|---|---|---|---|
| Stock-firmware Yi support | Yes | Unclear for Yi P2P (forum: auth errors). Its P2P list names Wyze, not Yi. | N/A (Wyze) | **No**: needs yi-hack or custom firmware (RTSP/ONVIF/FTP/MQTT) | Yes, via Yi Cloud + TUTK |
| Live LAN + remote | Yes, **time-limited for free** | RTSP mostly | Yes: LAN, falling back to P2P and relay (`NET_MODE`) | LAN RTSP | Yes, unlimited |
| HD/SD | HD/SD/Auto | Per stream | Main stream + `-sub` substream (SD30/SD60) | Two RTSP streams | HD/SD toggle |
| Snapshot | Yes | Yes | Interval snapshots, API thumbnail | Snapshot JPG endpoint | Yes (client-side) |
| SD playback | Yes, timeline | No (stock Yi) | No (BOA HTTP for some models) | Web UI file list | Yes, timeline |
| Motion events | 6-s free clips, cooldown up to 10 min | Own on-device MD | `MOTION_API` polling of Wyze cloud | MQTT events, clips in HA Media | Cloud alerts + SD jump |
| Ads/upsell | Yes | Free tier with ads | No | No | No |

## Sources

Primary (HIGH, local, reproducible):
- `E:/Dev/YiCamAlt/_apk/extracted/base.apk`: `aapt2 dump resources` (Android SDK build-tools 34.0.0), string keys quoted above
- Same APK, DEX string pool (`classes*.dex`): `IOTYPE_*`, `MEDIA_CODEC_*`, `video_pwd`/`pic_pwd`/`img_url`/`video_url`
- `E:/Dev/YiCamAlt/docs/reference/yi-cloud-endpoints.md`: endpoint inventory

Web (fetched/searched 2026-10-10):
- https://github.com/mrlt8/docker-wyze-bridge (README: LAN-first with internet fallback, snapshots, recording, firmware P2P caveat): MEDIUM
- https://github.com/mrlt8/docker-wyze-bridge/wiki/Network-Connection-Modes (LAN / P2P / ANY-relay): MEDIUM
- https://github.com/mrlt8/docker-wyze-bridge/wiki/Camera-Substreams (substream, SD30/SD60): MEDIUM
- https://github.com/mrlt8/docker-wyze-bridge/wiki/Camera-Audio (audio passthrough/transcode): MEDIUM
- https://github.com/roleoroleo/yi-hack-Allwinner-v2 (custom-firmware feature set: events web page, snapshot, MQTT, cloud disable): MEDIUM
- https://github.com/roleoroleo/yi-hack_ha_integration and https://www.home-assistant.io/integrations/yi (HA requires custom firmware): MEDIUM
- https://forums.wyze.com/t/faield-to-update-device-list-error-code-1011/196517 (ban after 500+ logins/day; analogous ecosystem): LOW–MEDIUM
- https://forums.wyzecam.com/t/event-playback-from-sd-card-after-12-second-cloud-event-has-completed/198136 (Yi user: 6-s clip then "View on SD card"): LOW, corroborated by APK strings
- https://www.techhive.com/article/583966/kami-wire-free-camera-kit-review.html (6-s free clips): LOW
- https://scos.co.uk/troubleshooting/yi/yi-no-local-storage (free = alerts without full clips; vendor-biased): LOW
- https://dashcamtalk.com/forum/threads/anyone-here-running-the-xiaomi-xiaoyi-yi-smart-wifi-cam.12177/ (tinyCam Yi P2P auth failure): LOW

---
*Feature research for: Yi/Kami stock-firmware alternative Android client*
*Researched: 2026-10-10*
