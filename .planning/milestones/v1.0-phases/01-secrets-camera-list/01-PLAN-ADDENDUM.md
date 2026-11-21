# Phase 1 plan addendum (after the on-device probe, 2026-10-11)

Source of truth: `01-PROBE-RESULTS.md` (BRANCH A, DATA_REGION US) and `01-STATIC-FINDINGS.md`.
Where this addendum contradicts a PLAN.md written before the probe, the addendum wins.

## Measured facts

1. The vendor app uses only `GET <regionBase>/v5/devices/list` (seq=1, userid, hmac). `/v4/devices/list` returns the same payload but is not used by the vendor: keep it out of the default path.
2. `code=20200` on the device list means "this account has no devices on this server". It is an EMPTY LIST, not an error.
3. The same credentials log in successfully on gw-us, gw-eu and gw-sg. Only one region holds the cameras (US for the owner). Login success therefore does NOT identify the region; the 20253 fallback alone is not enough.
4. Tokens are per region (a US session gets 20202 / -10003 elsewhere).
5. `/v5/deviceshare/shareddetail` is a PUT that changes access rights. It is NOT a list source: remove it from every candidate list. Shared cameras arrive inside `/v5/devices/list` (fields `category`, `share`, `nickname`).
6. Device item fields: activeTime, appParam, category, count, did, flag, hasPincode, interVersion, ipcParam, isNew, message, model, name, nickname, online, onlineTime, password, relayFlag, sdState, share, state, type, uid, webPairStatus. `ipcParam` is a JSON object here (ip, mac, p2p_encrypt, signal_quality, ssid) and may be a JSON string on other firmware: accept both.
7. `GET /v4/tnp/device_info` (seq, userid, uid) returns DID, InitString, License.
8. The app's default base URL is a placeholder host (`api.yicamalt.local`), so the first login fails.

## Changes per plan

### 01-07 (parsers, DevicePaths)
- Default and only owner path: `/v5/devices/list`. No shared-list path.
- A response with code 20200 parses to an empty list (success), not to an error.
- `ipcParam`: object or string-encoded JSON.
- Key mapping: uid=uid, p2pid=did (fall back to uid when did is blank), type, password, name, model, online, hasPincode; ownership from `category` (0 = own) / `share`.

### 01-08 (YiRegion, login)
- Replace the placeholder default base URL with a real gateway. Default order of regions: US, EU, SG (the fourth server `https://api.xiaoyi.com` is selectable only through the manual-server setting).
- Keep the 20253 hop rule as planned.
- ADD region discovery by device list, inside the same explicit login call (the password is only available there):
  after a login succeeds on region R, make ONE signed `GET /v5/devices/list` on R. If it returns code 20000 with a non-empty list, keep R. If it returns 20200 or an empty list, log in on the next region that has not been tried and repeat. Keep the first region with a non-empty list. If no region has devices, keep the FIRST region where login succeeded.
- Bounds per one press of "login": at most 3 `GET /v4/users/login` and at most 3 `GET /v5/devices/list` in total, no retries, no timers, never on token refresh or app start. A wrong-password code stops the loop at once. A network error stops the loop and keeps the last good session.
- The discovery check only needs the response code and the item count; implement it in this plan against M-HTTP (`YiRequestSigner`, `probeClient`-style client without the auth interceptor is NOT required here; use the normal client), without depending on the parsers of plan 01-07.
- When the user has set a manual server, no discovery and no hop happens at all.
- Log lines stay value-free: region name, code, count only.
- Tests (JVM, fake API): US empty -> EU has devices => ends on EU with 2 logins + 2 list calls; all three empty => stays on the first, exactly 3 + 3 calls; first region non-empty => 1 + 1; wrong password on the second region => stop, first session kept; manual server => 1 login, 0 discovery calls.

### 01-09 (CameraRegistry)
- DeviceSourcePlan: owner path `/v5/devices/list`, no shared path, region = the one stored by login discovery. 20200 => empty state ("no cameras"), not an error state.

### 01-12 (P2P credentials)
- TNP credentials come from `/v4/tnp/device_info` (DID, InitString, License). Camera password field `password` from the device list (hex, AES/ECB/PKCS7, key = first 16 chars of uid; empty => default "888888") — decrypt logic may be implemented and unit-tested with synthetic vectors only.
