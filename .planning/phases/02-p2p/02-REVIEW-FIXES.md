# Phase 2: fixes from the independent code review

Date: 2026-10-16. Branch: `gsd/phase-2-p2p`. The user approved every finding, plus exactly one automatic retry on WRONG_PASSWORD.
LIVE-01..03 are NOT marked complete; STATE.md and ROADMAP.md are untouched (device proof is still pending).

Method: tests were written together with each fix, because the write hook rejects stub files that contain only TODO().
To show that each new test can fail, it was run against the old source: either `git stash` of the main file, or, where
the old source would not compile with the new tests, the old source plus the minimal constants the tests need, or
compile-compatible mutations of the new code (VM). Every new test listed below failed in that run unless noted.

## Status per finding

| ID | Status | Fix | Commit |
|----|--------|-----|--------|
| HIGH-1 | fixed | `FrameAssembler` v0.2.0: an I-frame no longer clears pending. It drops only stale pending (seq <= I in u16 order, or another useCount generation), counts those as dropped, emits the I and drains I+1, I+2, ... (vendor `TnpCamera.handleVideoFrame`). A P before the first I, or one from another generation, waits in pending (bounded by MAX_PENDING) instead of being dropped immediately. | 2c17e8c |
| HIGH-2 | fixed | `TnpCameraLink` v0.2.0: if the FIRST ch0 head carries a version in 1..3 that differs from ours, the link adopts it, queues the whole start sequence (4881, 9029, 768) again with the same resolution and useCounts and the next command numbers, and skips that reply without parsing it. Later version changes are ignored. | dcbcb0f |
| MED-3 | fixed | authResult is checked on every ch0 reply: 1 or 2 means WRONG_PASSWORD (-20009) and 4 means PROTOCOL (-9000). Any other non-zero value drops the reply and logs `outcome=UNKNOWN action=ignored` once per link. authOk is still set only by a 4882 with result 0. `IoCtrlMessages.AuthOutcome.Unknown.appCode` is now 0 (not fatal). | dcbcb0f, cbb9757 |
| MED-4 | fixed | `LiveProbeViewModel` v0.2.0 does exactly one automatic retry per press, only after Failed(WRONG_PASSWORD) of the first session. It waits 2 s (coroutine `delay`, so tests use virtual time) while the screen shows "Повторная попытка…", calls `P2pCredentialsSource.refreshCredentials(deviceId)` off Main, then starts a new session (new decoder, pipeline and link). A second WRONG_PASSWORD is final. Stop, close or leaving the screen cancels a pending retry, and no other error triggers one. New port method `refreshCredentials` (default = `credentialsFor`). `CameraRegistry.refreshCredentials` does one `getCameraList()`, catches errors and logs only the class name, then returns the stored value. | 7b53c2f |
| MED-5 | fixed | `TutkCameraLink` v0.2.0: the read loop ends only on FATAL_READ_CODES {-20015, -20016, -20010, -20000, -20018, -20019, -20025, -20026}. Any other negative code is logged once per code (`skip=<NAME>`), followed by the 30 ms pause, and reading continues. maxNoDataPolls (300) such codes in a row end the session, as a safety bound. -20001 grows the buffer once to 1_000_000 and drops frames until the next key frame. `TutkCodes.AV_BUFPARA_MAXSIZE_INSUFF` was added. | 43d037c |
| MED-6 | fixed | `avReleased` / `sidReleased` with `releaseOnce()` serialised under a lock: `native.stop(if (doAv) av else -1, if (doSid) s else -1)`. If avClientStart2 returns after close(), its channel still gets avClientStop. | 43d037c |
| MED-7 | fixed | Every link thread body (connect, watch, tx, rx readers, TUTK rx) runs under a Throwable guard. The guard logs `BLOCK_THREAD_GUARD` with the class name only, then `Failed(INTERNAL, 0)` and full teardown. The teardown always publishes the final state and calls onClosed. Both factories reject `p2pEncrypt && password.isNullOrEmpty()` as NO_CREDENTIALS. | dcbcb0f, 43d037c |
| LOW-8 | fixed | Both links call onClosed (which frees the registry slot) only after every link thread has exited. If a thread outlives the bounded join, a `-reap` thread waits for it first; the log shows `registry=deferred`. onClosed now runs before the final state is published. The VM keeps Connect disabled until the closed link reports Closed or Failed (`watchDrain`). | dcbcb0f, 43d037c, 7b53c2f |
| LOW-9 | fixed | TNP readers and the writer keep a local handle copy and re-check `stopping` / `handleClosed` before every native call. Teardown runs STOP, then Connect_Break, then joins the readers and writer (readTimeoutMs + 200 ms), then ForceClose, then the rest of the bounded join. The TUTK reader keeps a local av and checks `avReleased`. Teardown runs connectStop if a connect is still pending, joins the rx thread (noDataSleepMs + 200 ms), then the vendor stop (767, SendIOCtrlExit, ClientStop, ClientExit, Session_Close), then the bounded join. Close stays bounded by joinMs. | dcbcb0f, 43d037c |
| LOW-10 | fixed | Status `auth=` shows `-` before streaming, `pending` while Streaming(authOk=false), and `ok` / `fail` only once the result is known. Error codes are named from the table of the camera's transport (`codeName`: TutkCodes for TUTK, PpppCodes otherwise), in both the status line and the INTERNAL message. | 7b53c2f |
| LOW-11 | fixed | `kickCode` reads the reason from `payload[0]`, so reason 1 gives -3101. An empty payload gives -3100. | cbb9757 |

No finding was left unfixed.

## Changed invariants

- **TNP resend.** Before: "only 4881 is re-sent; 9029 and 768 are never re-sent". Now the whole start sequence is re-sent at most once per connection, so 9029 goes out at most twice, and only after a header-version mismatch on the first ch0 reply. That reply is skipped.
- **No retry.** Before: "no reconnect, no retry" at both the link and the VM level. Now links still never retry or reconnect. The live screen may open one new link per press, only after WRONG_PASSWORD, after 2 s, with refreshed credentials. A press never makes more than 2 `factory.open` calls.
- **TUTK read loop.** Before: any unexpected code ended the session. Now only the vendor fatal list ends it.
- **Registry release.** Before: released after a 2 s join, even if native code was still running. Now released only after every link thread has exited.
- **Unknown authResult.** Before: fatal, PROTOCOL(-9000). Now the reply is ignored and the unknown value is logged once.

## Tests added or changed

- `FrameAssemblerTest` (9 -> 13): P frames that overtake the first I are emitted after it; overtaking P frames before a later I are kept while stale ones are dropped; overtaking across the u16 wrap; an overtaking P of a new generation. Three existing tests changed to the new "wait, then drop as stale" rule.
- `IoCtrlMessagesTest` (5): kick reason taken from payload[0]; Unknown has appCode 0.
- `TnpCameraLinkTest` (19 -> 27): version 3 and version 1 resend of the whole sequence (the mismatched reply carries authResult 1 and does not fail); same version resends nothing; WRONG_PASSWORD on a reply that is not 4882; unknown authResult logged once; a sink throwing an Error gives INTERNAL; an empty encrypted password gives INTERNAL; registry release deferred while connect is stuck; no PPPP_Read in flight or after ForceClose.
- `TnpLinkFactoryTest` (5): an empty encrypted password is refused.
- `TutkCameraLinkTest` (20 -> 28): non-fatal codes skipped and logged once; every vendor fatal code ends the session (a positive test that the old code also passed); -20001 buffer growth; late av after close is stopped exactly once; a sink throwing an Error; empty password; deferred registry release; vendor stop only after the reader left recvFrame. The factory test now also refuses an empty password.
- `LiveProbeViewModelTest` (17 -> 25): retry once then success (refresh, new password, 2 s boundary); second WRONG_PASSWORD final (exactly 2 opens); other errors never retry; Stop during the pause cancels the retry; a user Stop never retries; Connect disabled while draining; auth pending; TUTK code names. The existing WRONG_PASSWORD test now goes through the retry.
- `CameraRegistryCredentialsTest` (+1, creds_13): refreshCredentials refreshes once and falls back to the stored value.
- Fakes: `FakePpppNative` v0.3.0 (connectHold, in-flight reads at ForceClose), `FakeTutkNative` v0.3.0 (abortedClientStartResult, connectIgnoresStop, recvDelayMs and in-flight tracking, bufSizes).

## Gate

- `tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`: BUILD SUCCESSFUL, 41 suites, 519 tests (baseline 490), 1 skipped (RealLogScanTest without a log file), 0 failures.
- The p2p, stream and ui.live suites (210 tests) passed 3 times in a row with `--rerun-tasks`.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (not installed).

## Notes for the device check

- The retry produces a second `[P2P][factory][BLOCK_OPEN_LINK]` within about 2 s of a WRONG_PASSWORD. Expect `[LiveProbe][vm][BLOCK_RETRY]` and `[Camera][refreshCredentials][BLOCK_REFRESH_CREDENTIALS]` lines.
- If `registry=deferred` shows up on the device, a native connect outlived Connect_Break / connectStop. Connect stays blocked for that camera until the native call returns, which is correct but worth recording.
