# Pitfalls Research

**Domain:** Reverse-engineering a vendor cloud + proprietary P2P video protocol (Yi Home / Kami, TUTK Kalay) and building a non-rooted-capture Android client
**Researched:** 2026-10-10
**Confidence:** MIXED — binary facts from the on-disk APK are HIGH; protocol interpretation is MEDIUM; third-party web claims are MEDIUM/LOW (marked per item)

> Evidence base: the APK is on disk (`_apk/extracted/`). I parsed the ELF dynsym/dynamic tables and string pools of the arm64 native libs and the `base.apk` dex string pools directly (not from memory). Where a claim is "from the binary" it is HIGH confidence. Protocol semantics (what a nonce/authkey *means*) are inferred and marked MEDIUM. Repo visibility checked live via `gh` (PUBLIC).

---

## What the binary actually contains (ground truth for the pitfalls below)

Confirmed by direct inspection of `_apk/extracted/split_config.arm64_v8a.apk` and `base.apk`:

- **TWO P2P stacks ship side by side.** Classic TUTK (`com.tutk.IOTC.*`: `TutkCamera`, `IOTCAPIs`, `AVAPIs`) **and** a newer Yi-proprietary "TNP" stack (`com.tnp.TnpCamera`, `com.xiaoyi.camera.sdk.AntsCameraTnp`, `com.tnp.model.TNPFrameHead/TNPHead/TNPIOCtrlHead/TNP_Proto`). The TNP path is layered over `libPPPP_API.so` + `libkami_core.so`. **Deciding which stack a given camera model uses is itself a research task** (see Pitfall 7).
- **Native libs & their JNI classes (HIGH):**
  - `libIOTCAPIs.so` → `Java_com_tutk_IOTC_IOTCAPIs_*` (66 JNI). Exports both `IOTC_Initialize` and `IOTC_Initialize2`, plus `IOTC_Device_Update_Authkey`, `IOTC_Get_Version`, `IOTC_Lan_Search2`, `IOTC_Connect_ByUID_Parallel`. Built with **clang 3.8** (old). NEEDED: only libc/m/dl/stdc++/log — no license-server dependency inside it.
  - `libAVAPIs.so` → `Java_com_tutk_IOTC_AVAPIs_*` (41 JNI): `avClientStart2`, `avClientStartEx`, `avRecvFrameData2`, `avRecvAudioData`, `avSendIOCtrl`, `avClientRequestTokenWithIdentity`, `avClientRequestServerAbility`. **NEEDED: `libIOTCAPIs.so`, `libsCHL.so`** (DTLS layer), libc/m/dl/stdc++. So the load order and the DTLS lib matter.
  - `libsCHL.so` → DTLS/TLS helper (`IOTC_sCHL_connect`, `IOTC_sCHL_CTX_*`, PSK callbacks, `set_dtls_connect_timeout`). NEEDED: `libIOTCAPIs.so`.
  - `libPPPP_API.so` → `Java_com_p2p_pppp_1api_PPPP_1APIs_*` (26 JNI). Strings: `"7. Listen result: Invalid Nonce"`, `"8. Connect result: Invalid nonce"`, `PPPP_MakeNonce`, `ERROR_PPPP_INVALID_APILICENSE`, `%s.xiaoyi.com`, `tnpmastercn.xiaoyi.com`, `Send st_PPPP_DevLgn_EX ... NONCE:%.32s ... Signature:%.32s`. clang 3.8.
  - `libkami_core.so` → `Java_com_kami_mobileipc_KamiMobileIPC_*` (`nativeInit/nativeStart/nativeListen/nativeFeedVideo/nativeFeedAudio/nativeSetCallbacks`). **NEEDED: `libPPPP_API.so`, `libssl.so`, `libcrypto.so`, `libz.so`.** Bundles curl 8.4.0. Strings include the control-plane URLs `%s/v4/tnp/on_line?hmac=%s&seq=9&uid=%s&version=%s&timestamp=%u` and `%s/v6/ipc/on_line?hmac=%s&seq=9&uid=%s&password=%s&version=%s&model=0&...`, plus `"License"`, `"tsNonce"`, `"Bad password"`. clang 12.0.x.
  - `libyimedia.so` → only `Java_com_xiaoyi_media_Hls2Mp4_*` (HLS→MP4 conversion for **cloud** recordings; not needed for live P2P).
- **Device-list endpoint family in `classes2.dex` (HIGH):** `/v5/devices/list`, `/v5/devices/owners`, `/v5/devices/relations`, `/v5/devices/deviceinfo`, `/v5/devices/getSensorsState`, `/v5/devices/password`, `/v4/tnp/device_info`, and a **separate shared-device family**: `/v5/deviceshare/friends`, `/v5/deviceshare/invite`, `/v5/deviceshare/shareddetail`, `/v5/deviceshare/record`, `/v4/deviceshare/inviteedevice`, `/v4/deviceshare/inviterdevice`, `/v4/deviceshare/accept`.
- **The camera P2P credentials live in a SQLite table (HIGH), `device_info`:** columns `UID, DID, P2PID, showDid, UUID, viewAccount, viewPassword, nickName, online, ..., tnpServerString, tnpLicenseKey, latitude, longitude, model, sName, localIp, mac, ssid`. Prefs keys: `PREF_KEY_TNP_SERVER_STRING`, `TNP_DID_PREFIX_`, `TNP_KEY_PREFIX_`, `TNP_SEV_PREFIX_`. Methods: `getTnpLicenseDeviceKey`, `getTnpLicenseGroupKey`, `getP2PID`, `updateTnpConnectInfo`. **These fields (UID/DID/P2PID + viewAccount/viewPassword + tnpLicenseKey + tnpServerString) are exactly what `avClientStart2`/`PPPP_Connect` consume — the device-list response must yield them.**
- **TLS pinning in dex:** real `okhttp3.CertificatePinner` + `CertificatePinner.kt` references AND an ad-SDK copy (`com.applovin.shadow.okhttp3.CertificatePinner`). `sha256/` literals present. `de.robv.android.xposed` anti-hook string present (classes3). No PlayIntegrity/SafetyNet/RootBeer string found in the app's own classes (only Facebook's unrelated `IntegrityManager`).
- **Flutter is a secondary module (HIGH):** `libflutter.so` + `libapp.so` exist; `libapp.so` embeds Dio + `shared_preferences`, `sqflite`, `table_calendar`, `graphic` (charts), `device_info_plus`. The **core login + camera + P2P is native Kotlin/Java** (`com.ants360.yicamera.*`), not Flutter. Implication below.

---

## Critical Pitfalls

### Pitfall 1: The empty `20200` device list is almost certainly owned-vs-shared / region, not a signing bug

**What goes wrong:** `/v5/devices/list` signs correctly (`20200`) but returns a 16-byte empty body, and the team keeps re-probing the *signature*, which is already solved.

**Why it happens:** `20200` means "authenticated and accepted" — the signature question is closed. An empty list has three realistic causes, in order of likelihood: (a) the user's cameras are **shared to** the account, not **owned** by it, so they surface through the `/v5/deviceshare/*` family (`deviceshare/friends`, `deviceshare/inviteedevice`, `deviceshare/shareddetail`) rather than `devices/list`; (b) **region mismatch** — the account lives on `gw-eu`/`gw-sg` and `gw-us` legitimately has zero devices for it (the official app has `LoginAreaSelectActivity` + `ServerInfo$ServerLocation` precisely for this); (c) a **required query param is missing** (`relations`/`owners` returned `-10003` = "missing params" during probing, which proves these endpoints want more than `seq`+`userid`).

**How to avoid:** Stop guessing — capture the real `devices/list` (and `deviceshare/friends`) request/response from the official app on the physical phone. In parallel, cheaply test hypothesis (a) by calling `/v5/deviceshare/friends` and `/v5/deviceshare/shareddetail` with the existing HMAC signer, and hypothesis (b) by repeating `devices/list` against `gw-eu`/`gw-sg` (the login that returned `20261` on the real account tells you the home region).

**Warning signs:** `20200` + empty body on `devices/list` while `deviceshare/*` is never tried; probing continues on `gw-us` only.

**Phase to address:** Traffic-capture phase (first), with a fast "try deviceshare + other regions with the signer we already have" spike *before* the capture even runs.

**Confidence:** HIGH that the `deviceshare` + region split exists (endpoints in dex, region selector in dex); MEDIUM on which specific cause applies to user 167315.

---

### Pitfall 2: Capturing the wrong layer — the device list is HTTPS, but live video is UDP P2P that never touches mitmproxy

**What goes wrong:** Team gets apk-mitm + mitmproxy working, captures login and REST, then expects the live-stream handshake and frames to appear in mitmproxy. They never will.

**Why it happens:** `libPPPP_API.so` uses **UDP 32100/32108 to the P2P masters and a random UDP port for direct device traffic** (per palant.info PPPP overview, MEDIUM), with a TCP/443 *obfuscated* fallback. mitmproxy is an HTTP(S) proxy; it sees none of this. The only thing on HTTP is the control plane: the `.../v4/tnp/on_line` / `.../v6/ipc/on_line` presence check and the device-list/credential fetch (both confirmed as URL strings in `libkami_core.so` / dex).

**How to avoid:** Split the capture into two tools. HTTP(S) → mitmproxy (login, device list, `tnp/on_line`, credential fetch). P2P UDP → a **full-packet pcap** (the phone's traffic mirrored, or `tcpdump`/PCAPdroid on the phone; the author of the Wireshark PPPP dissector at hacked.camera publishes a `.fdesc` for ~70 message types). Treat "mitmproxy shows no video" as expected, not a failure.

**Warning signs:** A capture plan that mentions only mitmproxy; an expectation that `avRecvFrameData2` payloads will show up as HTTP.

**Phase to address:** Traffic-capture phase — design both an HTTP capture and a UDP pcap capture up front (this belongs in `docs/verification-plan.xml` as two distinct evidence sources).

**Confidence:** HIGH (ports/behavior cross-checked against palant.info and the lib strings; the `on_line` URLs are literal in the binary).

---

### Pitfall 3: TUTK native init needs the exact library load order, ABI, and a license/authkey — any one missing = `ERROR_PPPP_INVALID_APILICENSE` / silent connect failure

**What goes wrong:** The JNI wrapper loads `libAVAPIs.so` first, or omits `libsCHL.so`, or calls `IOTC_Initialize` without the vendor license, and `avClientStart2` returns a negative error or connect hangs with no frames.

**Why it happens:**
- **Load order (HIGH):** `libAVAPIs.so` NEEDs `libIOTCAPIs.so` + `libsCHL.so`; `libkami_core.so` NEEDs `libPPPP_API.so` + `libssl`/`libcrypto`/`libz`. Android resolves `DT_NEEDED` automatically only if all libs are packaged in the same ABI dir and loadable; mixing a self-built OpenSSL with the vendor's `libssl.so` causes symbol clashes.
- **ABI (HIGH):** libs exist **only** for `arm64-v8a` and `armeabi-v7a`. On an x86/x86_64 device or emulator there is no live-view — matches the stated constraint. The app must set `ndk { abiFilters "arm64-v8a","armeabi-v7a" }` and ship `libc++_shared.so`.
- **License (HIGH that it exists, MEDIUM on mechanism):** `libPPPP_API.so` has `ERROR_PPPP_INVALID_APILICENSE`; dex has `PROPERTY_LICENSE_DURATION_REMAINING`, `getTnpLicenseDeviceKey`, `getTnpLicenseGroupKey`, and the DB stores `tnpLicenseKey` **per device**. So the P2P license is not a single compile-time constant like older TUTK SDKs — it is fetched from the cloud per camera (`/v4/tnp/device_info` is the likely source) and passed into init. `IOTC_Initialize2` (vs `IOTC_Initialize`) is the newer license-carrying initializer.

**How to avoid:** Mirror the official load sequence: `System.loadLibrary` in dependency order (IOTC → sCHL → AVAPIs; PPPP → kami_core). Keep the vendor `libssl.so`/`libcrypto.so` — do not substitute the NDK's. Extract the init arguments (license/authkey) from the captured `tnp/device_info` + `devices/list` response, not from a hard-coded string. Prove init with `IOTC_Get_Version` / `avGetAVApiVer` returning sane values before attempting a connect.

**Warning signs:** `IOTC_Initialize` returns ≥0 but `IOTC_Connect_ByUID*` times out; `avClientStart2` returns a negative auth error; `UnsatisfiedLinkError` at load; frames never arrive though "connected."

**Phase to address:** P2P bring-up phase — gate it behind a "native init returns version strings" checkpoint before any streaming work.

---

### Pitfall 4: avClientStart2 auth fields — password vs token vs TNP nonce/signature are three different schemes; picking the wrong one authenticates to nothing

**What goes wrong:** The client sends the camera "password" the way old TUTK samples do, but Yi's current firmware expects the TNP nonce+HMAC-SHA1 signature scheme (or a per-session token), so the AV channel never authorizes.

**Why it happens:** The binary carries **all three** auth callback types: `AVAPIs$avPasswordAuthFn`, `AVAPIs$avTokenAuthFn`/`avTokenRequestFn`, and the TNP path with `PPPP_MakeNonce` + `NONCE:%.32s` + `Signature:%.32s` in `st_PPPP_DevLgn_EX`. palant.info (MEDIUM) states Yi adds a V2 header with an **HMAC-SHA1 signature truncated to 8 bytes keyed by `tnp_license`**, and that camera "passwords" in several of these protocols aren't even checked by firmware. The DB stores both `viewPassword` **and** `tnpLicenseKey` — so which field feeds the handshake depends on whether the camera is a legacy TUTK or a TNP device.

**How to avoid:** Determine the per-model stack first (Pitfall 7). For TNP cameras, reproduce `PPPP_MakeNonce` + HMAC-SHA1(key = device license) and compare your signature byte-for-byte against a captured `DevLgn_EX` packet (the pcap from Pitfall 2) before writing the connect path. For legacy TUTK, use `avClientStart2` with the `viewAccount`/`viewPassword` from the device-list response.

**Warning signs:** P2P session reaches "ready" but IOCTRL responses are `Invalid Nonce` / `Bad password` (both are literal strings in the libs); video channel opens then closes.

**Phase to address:** P2P auth phase — write a byte-exact signature unit test pinned to a captured packet (GRACE `V-M-*` evidence) before the connect code.

**Confidence:** HIGH that three schemes exist; MEDIUM on which Yi firmware generation uses which.

---

### Pitfall 5: Patching a split bundle breaks installation (`INSTALL_FAILED_*`) or startup — and may not even intercept what you need

**What goes wrong:** apk-mitm runs on the `.xapk`/`.apkm`, produces patched splits, and either (a) `adb install` of one APK fails because splits must be installed together and signed with one key, or (b) the app crashes on launch from a signature self-check, or (c) it installs and runs but the traffic you care about still isn't visible.

**Why it happens:**
- **Split install (HIGH):** this is a multi-split bundle (`base.apk` + `split_config.arm64_v8a.apk` + density/lang splits). All installed splits must share one signature and be installed atomically via `adb install-multiple` (or `install-multi-package`). Re-signing only `base.apk`, or `adb install base.apk` alone, yields `INSTALL_FAILED_MISSING_SPLIT` / signature-mismatch. apk-mitm signs with uber-apk-signer; you must install the whole patched set together. An alternative is to merge splits first (AntiSplit-M), but merged APKs sometimes need split metadata stripped from the manifest to install.
- **Self-check (MEDIUM):** some apps verify their own signature and crash when re-signed. The anti-Xposed string (`de.robv.android.xposed`) in dex shows Yi has *some* tamper awareness; a signature check is plausible though not confirmed. No PlayIntegrity/SafetyNet string was found in the app's own code, which lowers the risk of a hard remote attestation block.
- **Coverage (HIGH):** apk-mitm rewrites the Network Security Config and disables **Java/Kotlin okhttp** pinning. The login + device-list traffic is native-Kotlin okhttp, so this should work. But any Flutter/Dio screen (`libapp.so` uses Dio) will **ignore the user CA and the system proxy entirely** — Dart/BoringSSL has its own trust store (OWASP MASTG, HIGH). Those calls won't appear.

**How to avoid:** Install with `adb install-multiple` over the full patched split set. If startup crashes, diff logcat for a signature/integrity abort and, if present, fall back to merging splits (AntiSplit-M) or to Frida gadget rather than static patching. Accept that the *core* flows (login, device list, `tnp/on_line`) are the native path apk-mitm covers — that is exactly what this project needs, so a Flutter-interception gap is acceptable for v1. Only if the device list turns out to be served through a Flutter screen would you need reFlutter / Frida `disable-flutter-tls`.

**Warning signs:** `INSTALL_FAILED_MISSING_SPLIT`, `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, `signatures do not match`, app exits immediately after splash, or mitmproxy shows login but a specific screen's calls are absent (that screen is Flutter).

**Phase to address:** Traffic-capture phase, step 1 (get a patched, installable, launching app) before any endpoint work.

---

### Pitfall 6: Secret leakage during capture and in logs — tokens, hmac, camera P2P password/license, serials

**What goes wrong:** The P2P credentials (`viewPassword`, `tnpLicenseKey`, UID/DID/P2PID), the account token/token_secret, and the replayable `hmac` query param end up in mitmproxy flow files, logcat, `auth_log.txt`, the UI, or the committed repo.

**Why it happens (cross-referenced with `CONCERNS.md`, CONFIRMED):**
- `hmac=` is a **replayable credential** (server accepts it in place of a Bearer) and is logged verbatim at `HEADERS` level; the masker only covers `password=`/`account=`.
- `auth_log.txt` is planted into `getExternalFilesDir()` — **MTP-readable over USB with no root** — and the response-body masker misses the JSON-encoded `data` envelope (token + token_secret leak). This already happened once (v0.3.2 plaintext password incident).
- Six live log sites emit raw `device_id`/serials; `CameraListScreen` renders the serial on-screen (screenshot exfiltration).
- Now the P2P layer adds **new** secrets the existing redaction doesn't know about: `tnpLicenseKey`, `viewPassword`, `P2PID`, `DID`, and the `tnp/on_line?...&password=...` URL — none are in the `RedactionScanner` key list.
- mitmproxy flow files contain **everything** in cleartext (that's the point of the capture) — `captures/` is gitignored, but a stray copy or a pasted snippet leaks the lot.

**How to avoid:** Before the capture phase, extend `RedactionScanner` + every masker to cover `hmac`, `password`, `tnplicensekey`, `p2pid`, `did`, `viewpassword`, `authkey`, `nonce`, `signature` and a realistic long-serial fixture (the current `cam-1` fixture never trips the `\d{10,}` rule — CONFIRMED gap). Keep `auth_log.txt` out of external storage. Never render serials/UIDs in UI. Treat flow files as toxic: single machine, gitignored, deleted after extraction; paste only redacted excerpts into docs/chat. User should rotate the Yi password after the capture session.

**Warning signs:** a Base64 `hmac` or a `tnpLicenseKey` visible in logcat/`auth_log.txt`; a serial in a screenshot; a `captures/*.flow` referenced from anywhere tracked.

**Phase to address:** A redaction-hardening task **at the start of** the capture phase (precondition), not after.

---

### Pitfall 7: Assuming one P2P stack — the app ships TUTK *and* TNP, and the live-view path differs per camera generation

**What goes wrong:** The team builds a clean `com.tutk.IOTC.TutkCamera`-style client, then the user's actual camera turns out to be a TNP device (`AntsCameraTnp` / `KamiMobileIPC`) with a different handshake, frame header (`TNPFrameHead` vs TUTK `AVFrame`), and auth — and nothing connects.

**Why it happens:** Both class trees are present (HIGH). `AntsCameraTnp` and `TnpCamera` wrap the PPPP/kami path; `TutkCamera` wraps classic TUTK. Yi migrated newer models to TNP. The frame structs differ (`com.tnp.model.TNPFrameHead/TNPIOCtrlHead` vs `com.tutk.IOTC.AVFrame`), so a decoder written for one won't parse the other.

**How to avoid:** Identify the user's specific model early (from `devices/list` `model`/`sName`, and from which code path the official app exercises in the capture). Scope v1 to that one stack. Pin the exact frame-header layout from a captured frame before writing the MediaCodec feed.

**Warning signs:** the device-list response carries `tnpServerString`/`tnpLicenseKey` (→ TNP) vs only `UID`/`viewPassword` (→ legacy TUTK); connect succeeds on one API but the other's `avRecvFrameData2` returns garbage.

**Phase to address:** End of capture phase (model identification) → start of P2P phase (stack selection).

**Confidence:** HIGH that both stacks ship; MEDIUM on which the user's camera uses (needs the model).

---

### Pitfall 8: Legal / ToS / account-ban exposure, amplified by a PUBLIC repo full of vendor binaries

**What goes wrong:** The project ships Yi's copyrighted APK, native libs, and a third party's signing certificate in a public GitHub repo, and/or the user's account gets flagged for anomalous API/P2P access.

> **Status update (2026-10-10, after research):** partly resolved. Commit `b804e33` untracked `_apk/`; `_apk/`, `_re/`, `app/src/main/jniLibs/` and captures are now gitignored and local-only. The repo stays PUBLIC by user decision. Old blobs remain in git/LFS history; a history purge is still an open decision.

**Why it happened (as observed before `b804e33`):** `Paul771/YiCamAlt` is **PUBLIC**. `git ls-files` shows ~570 MB of tracked vendor binaries via LFS: the full `.xapk` (270 MB), the `.apkm` (137 MB), `base.apk`, all `split_config.*`, every `.so`, **and `_apk/extracted/META-INF/APKMIRRO.RSA/.SF/MANIFEST.MF`** (APKMirror's signing certificate + JAR signatures). Redistributing a vendor's application binary and a third party's signing material from a public repo is a DMCA-takedown/ legal exposure, independent of the reverse-engineering itself. Ban risk: a second concurrent login can invalidate the official app's session (many vendor clouds single-session per device token); unusual request shapes or P2P from an unrecognized client can trip abuse heuristics.

**How to avoid:**
- `git rm -r --cached _apk` and add `/_apk/**` to `.gitignore`; purge the blobs from history (they live in LFS and in history) or **make the repo private** immediately. Remove the `META-INF` signing material unconditionally — nothing reads it.
- Keep only *derived, non-redistributable-in-bulk* reference notes (endpoint names, struct layouts) in docs — not the binaries.
- For the account: test against the user's own cameras only, keep request cadence human-plausible, prefer **LAN/direct P2P** over relay where possible to minimize cloud footprint, and warn the user that running a second client may log the official app out. Never store or transmit the user's credentials anywhere but the device.

**Warning signs:** repo is public with `_apk/` tracked; `APKMIRRO.RSA` in `git ls-files`; the user reports being logged out of the official app after using the alt client.

**Phase to address:** Immediate hygiene task (before any further public commits) + a standing constraint in every phase.

**Confidence:** HIGH on repo state (checked); MEDIUM on ban mechanics (vendor-specific, not documented).

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Hard-code a single P2P license/init string instead of fetching per-device | Faster bring-up | Breaks on any camera whose license differs / expires (`PROPERTY_LICENSE_DURATION_REMAINING` implies rotation) | Never — the DB schema proves it's per-device |
| Support only one P2P stack (TUTK) and ignore TNP | Less code | Dead on newer models | Only if the user's camera is confirmed TUTK-only |
| Keep probing endpoints blind instead of capturing | No phone setup needed | Burned 5 rounds already with no result | Never again — capture first |
| Commit vendor APK/libs "for convenience" | Easy agent handoff across machines | Legal exposure + 570 MB clones | Never in a public repo; private repo only, and even then prefer a documented download step |
| Relay-only P2P (skip LAN path) | Simpler | Higher latency, more cloud footprint, more ban signal | MVP only, with LAN as a fast follow |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| `/v5/devices/list` | Treating empty `20200` as "no cameras" | Add distinct `NoDevices` state; try `deviceshare/friends` + other regions (CONFIRMED gap in `CameraListViewModel`) |
| Shared cameras | Assuming all cameras come from `devices/list` | Owned → `devices/list`/`owners`; shared → `deviceshare/*` (separate endpoint family, HIGH) |
| TUTK native libs | Loading in wrong order / wrong ABI | IOTC→sCHL→AVAPIs, PPPP→kami_core; arm64-v8a & armeabi-v7a only; ship `libc++_shared.so` |
| OpenSSL | Substituting NDK libssl for vendor's | Keep vendor `libssl.so`/`libcrypto.so` — `libkami_core` links them (symbol-version clashes otherwise) |
| mitmproxy + Flutter screens | Expecting Dio/Dart calls to appear | They won't (own trust store); core native flows do appear — scope capture to those |
| Split-APK install | `adb install base.apk` | `adb install-multiple <all patched splits>`, one signing key |
| Frame decode | Reusing TUTK `AVFrame` layout for TNP | Pin `TNPFrameHead` from a captured frame; stacks differ |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| H.265 on devices without HW decode | Green/garbled frames, dropped FPS | Detect codec from stream, use `MediaCodec` HW path, fall back gracefully; libs ship `libh265decoder.so` (SW) as last resort | Older/low-end phones |
| Relay-path latency/bandwidth | Laggy remote view | Prefer direct/LAN (`IOTC_Lan_Search2`, `PPPP_ConnectOnlyLanSearch`); relay as fallback | Remote viewing over CGNAT |
| Packet loss on UDP P2P | Stutter, frame tearing | Use the SDK's resend path (`avClientCleanBuf`, resend IOCTRL) and I-frame request (`SMsgAVIoctrlIFrameReq`); request keyframe on loss | Mobile/weak Wi-Fi |
| `runBlocking` on OkHttp dispatcher (existing) | Thread starvation under refresh | Pre-resolve token into `AtomicReference` (CONFIRMED in CONCERNS) | Any 401 storm |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Logging `hmac` / `tnpLicenseKey` / `viewPassword` / `tnp/on_line?...password=` | Replayable credential + camera takeover secret in MTP-readable log | Add all to maskers + `RedactionScanner`; use realistic serial fixture |
| `auth_log.txt` in external storage | USB-readable token/secret leak (recurred once) | Internal storage only; delete after debugging; never in release |
| Rendering serial/UID/DID in UI | Screenshot exfiltration | Mask device identifiers in every UI + log site |
| Committing flow files / APK / signing cert | Public leak of secrets & vendor IP | `captures/` + `_apk/` gitignored; repo private; purge history |
| Trusting server-supplied stream URL blindly | Stale/forged target (existing `getLiveStreamUrl` gap) | Validate scheme + freshness, re-resolve |
| Weak DTLS/obfuscation assumption | palant: Yi AES only on A/V, rest unencrypted; PPPP "obfuscation" trivially reversible | Don't rely on P2P transport for confidentiality; treat creds as the only real secret |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Empty camera list shown as "no cameras" | User thinks app is broken when it's region/shared | Distinct diagnostic state with "try other region / shared devices" |
| Second login kills official-app session | User confused why official app logged out | Warn up front; document it |
| Spinner forever on P2P connect failure | No feedback on relay/auth failure | Surface connect state (connecting/relay/LAN/auth-failed) from SDK callbacks |
| Silent HW-decode failure | Black screen | Show codec + decode-path diagnostics in debug |

## "Looks Done But Isn't" Checklist

- [ ] **Device list:** returns owned cameras — verify shared cameras (`deviceshare/*`) and non-US regions also resolve, with real P2P credentials populated (UID/DID/P2PID/viewPassword/tnpLicenseKey).
- [ ] **P2P connect:** "connected" — verify actual frames arrive via `avRecvFrameData2` AND audio AND it works both LAN and remote (relay).
- [ ] **Auth:** handshake "ready" — verify no `Invalid Nonce`/`Bad password` in IOCTRL responses; signature matches a captured packet byte-for-byte.
- [ ] **Native libs:** app launches — verify on a real arm64 device (not x86), with `libc++_shared.so` present and version calls returning sane strings.
- [ ] **Capture:** mitmproxy shows login — verify the device-list + `tnp/on_line` + credential fetch are all captured (not hidden behind a Flutter screen).
- [ ] **Redaction:** scanner green — verify with a realistic 13-digit serial fixture and that `hmac`/`tnpLicenseKey`/`viewPassword` are masked.
- [ ] **Repo:** builds — verify `_apk/` and signing material are untracked and the repo is private before any public push.
- [ ] **Split install:** patched app installs — verify via `adb install-multiple` on the physical phone and it launches past splash.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Empty device list | LOW | Try `deviceshare/friends` + `gw-eu`/`gw-sg` with existing signer before anything else |
| Capture misses video | LOW | Add a UDP pcap source (PCAPdroid / mirrored tcpdump); mitmproxy was never going to show it |
| Native init fails | MEDIUM | Fix load order/ABI; extract license/authkey from captured `tnp/device_info`; verify with version calls |
| Wrong auth scheme | MEDIUM | Pin signature test to captured `DevLgn_EX`; switch password↔token↔nonce per stack |
| Split won't install / crashes | MEDIUM | `install-multiple`; if self-check, merge via AntiSplit-M or use Frida gadget instead of static patch |
| Secret leaked to public repo | HIGH | Make private, rotate Yi password, purge LFS history (`git lfs` objects + history rewrite), remove signing cert |
| Wrong P2P stack built | HIGH | Identify model first; rewrite frame/auth for the correct stack — scope v1 to one stack to avoid |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 1 Empty device list | Capture (pre-spike) | Non-empty list OR confirmed via `deviceshare`/region; `NoDevices` state exists |
| 2 Wrong capture layer | Capture (design) | Two evidence sources: HTTP flows + UDP pcap |
| 3 Native init/ABI/license | P2P bring-up | `IOTC_Get_Version`/`avGetAVApiVer` return valid; loads on arm64 device |
| 4 avClientStart2 auth | P2P auth | Byte-exact signature unit test vs captured packet; no `Invalid Nonce` |
| 5 Split patch/install | Capture (step 1) | Patched splits install via `install-multiple` and launch |
| 6 Secret leakage | Capture (precondition) | Redaction scanner with realistic fixtures covers all new P2P secrets |
| 7 Two P2P stacks | Capture→P2P boundary | User's model identified; v1 scoped to its stack |
| 8 Legal/repo/ban | Immediate + standing | Repo private; `_apk/` + signing cert untracked; user warned re: session |

## Sources

- Direct binary inspection (HIGH): `_apk/extracted/split_config.arm64_v8a.apk`, `base.apk` — ELF dynsym/dynamic tables + dex/`.so` string pools (parsed this session).
- Live repo state (HIGH): `gh repo view Paul771/YiCamAlt` → PUBLIC; `git ls-files _apk`, `git lfs ls-files`.
- Project docs (HIGH): `.planning/PROJECT.md`, `.planning/codebase/CONCERNS.md`, `docs/PROJECT_STATUS.md` (§0, §8, §10, §14, dated entries).
- apk-mitm README / docs (MEDIUM): https://github.com/shroudedcode/apk-mitm — bundle support, uber-apk-signer, NSC rewrite, native-pinning/Flutter limitation.
- AntiSplit-M (MEDIUM): https://github.com/AbdurazaaqMohammed/AntiSplit-M — split merge + manifest-split-metadata caveat.
- palant.info PPPP overview, 2025-11-05 (MEDIUM): https://palant.info/2025/11/05/an-overview-of-the-pppp-protocol-for-iot-cameras/ — ports, DID, init/license/CRC key, CS2 obfuscation, Yi V2 HMAC-SHA1 header keyed by `tnp_license`, Yi AES on A/V only, DRW reliability, TNP prefixes.
- PPPP Wireshark dissector (MEDIUM): https://hacked.camera/dissector/ — ~70 message types for UDP pcap analysis.
- OWASP MASTG Flutter (MEDIUM): https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0109/ — Dart not proxy-aware, own trust store; reFlutter / Frida `disable-flutter-tls`.
- Cisco Talos / arXiv 2306.00610 (LOW, context): Yi P2P UDP unencrypted control plane; hard-coded per-type keys in this camera family.

---
*Pitfalls research for: Yi/Kami cloud + TUTK/TNP P2P reverse-engineering Android client*
*Researched: 2026-10-10*
