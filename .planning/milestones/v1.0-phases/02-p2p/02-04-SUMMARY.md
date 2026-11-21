---
phase: 02-p2p
plan: 04
subsystem: stream-core
tags: [p2p, tnp, auth, hmac, codec, tdd]
requires: ["02-00"]
provides:
  - "YiP2pAuth: vendor-exact 32-byte authInfo (nonce + HmacSHA1 pass15, or admin,<pwd>)"
  - "TnpCodec: TNPHead/IOCtrlHead encode/parse, per-channel head validation, command units, reply and frame-unit parsing"
  - "IoCtrlMessages: command ids, payload builders, authOutcome, kickCode, ResolutionPolicy"
  - "FrameHeadCodec: 24-byte frame head, BE (TNP) and LE (TUTK)"
affects: [02-07, 02-10]
tech-stack:
  added: []
  patterns: ["known-answer JVM tests on public and synthetic vectors", "mutation check as RED evidence"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/stream/YiP2pAuth.kt
    - app/src/main/java/com/yicamalt/stream/TnpCodec.kt
    - app/src/main/java/com/yicamalt/stream/IoCtrlMessages.kt
    - app/src/main/java/com/yicamalt/stream/FrameHeadCodec.kt
    - app/src/test/java/com/yicamalt/stream/YiP2pAuthTest.kt
    - app/src/test/java/com/yicamalt/stream/TnpCodecTest.kt
    - app/src/test/java/com/yicamalt/stream/IoCtrlMessagesTest.kt
    - app/src/test/java/com/yicamalt/stream/FrameHeadCodecTest.kt
  modified: []
decisions:
  - "IoCtrlMessages.AuthOutcome is a sealed interface (OK, WRONG_PASSWORD, RETRY objects + Unknown(code)) instead of an enum, so the unknown branch keeps the raw authResult"
  - "validateHead order: bad-channel, bad-iotype (ioType outside 1..3), zero-size, too-large; dataSize is u32 so 0xFFFFFFFF is too-large, never negative"
  - "authInfo(encrypted=true) requires a 7-char prefix (IllegalArgumentException otherwise); commandUnit requires a 32-byte auth"
metrics:
  duration: 15min
  completed: 2026-10-14
  tasks: 2
  files: 8
---

# Phase 2 Plan 04: TNP wire layer (auth, framing, IOCTRL, frame head) Summary

Pure-Kotlin TNP wire layer: vendor-exact HmacSHA1 authInfo, byte-exact 56/52/56-byte command units, IOCTRL payloads and reply mapping, and a BE/LE 24-byte frame-head parser. 29 JVM tests pin it (RFC 2202 plus synthetic vectors). This covers ROADMAP criterion 5 for `YiP2pAuth` and `TnpCodec`.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | YiP2pAuth + YiP2pAuthTest (RFC 2202, pinned vector, layout) | 8ef9894 |
| 2 | TnpCodec, IoCtrlMessages, FrameHeadCodec + tests | 6f46d90 |

Verification: `tools/gradle.ps1 :app:testDebugUnitTest --tests ...YiP2pAuthTest --tests ...TnpCodecTest --tests ...IoCtrlMessagesTest --tests ...FrameHeadCodecTest` gave BUILD SUCCESSFUL and `--- gradle exit=0`. TEST XML: FrameHeadCodecTest 5/0 failures, IoCtrlMessagesTest 5/0, TnpCodecTest 11/0, YiP2pAuthTest 8/0.

Acceptance greps: `user=xiaoyiuser&nonce=` appears on exactly 1 line of YiP2pAuth.kt. There is no `Timber`, `android.` or `/*` in the four sources or the tests. `b617318655057264e28bc0b6fb378c8ef146be00` appears on exactly 1 line of YiP2pAuthTest.kt.

## Frozen protocol vector

`hmacPassword("SYNTHpw1", "SYNTHAAbcdefgh1") == "C5KKYDqrtOxn15T"`. The full Base64 is `C5KKYDqrtOxn15ThQH6MHmKAH/g=`. It was computed once with JDK HmacSHA1 (a standalone Java check outside the project) and frozen in the test. It does not come from a capture.

## Exported signatures (for plan 02-07)

```kotlin
object YiP2pAuth {
    const val ALPHABET: String; AUTH_INFO_SIZE = 32; NONCE_PREFIX_LEN = 7; NONCE_TAIL_LEN = 8; PASS_LEN = 15
    fun noncePrefix(rnd: java.util.Random): String
    fun nonce(len: Int, rnd: java.util.Random): String
    internal fun hmacSha1(key: ByteArray, msg: ByteArray): ByteArray
    fun hmacPassword(cameraPassword: String, nonce: String): String
    fun authInfo(encrypted: Boolean, password: String, noncePrefix7: String, rnd: java.util.Random): ByteArray
}
object TnpCodec {
    HEAD_SIZE = 8; IOCTRL_HEAD_SIZE = 40; FRAME_HEAD_SIZE = 24; AUTH_OFFSET = 8; AUTH_SIZE = 32
    IOTYPE_VIDEO = 1; IOTYPE_AUDIO = 2; IOTYPE_COMMAND = 3; CH_COMMAND = 0; CH_AUDIO = 1; CH_IFRAME = 2; CH_PFRAME = 3; CH_NEW_LIVE = 6
    data class TnpHead(version: Int, ioType: Int, abilityRet: Int, dataSize: Long)
    sealed interface HeadCheck { object Ok; data class Dirty(reason: String) }  // zero-size | too-large | bad-channel | bad-iotype
    class IoCtrlReply(type, number, payloadSize, authResult, payload: ByteArray)  // toString without payload
    fun encodeHead(version: Int, ioType: Int, dataSize: Int, abilityRet: Int = 0): ByteArray
    fun parseHead(bytes: ByteArray, offset: Int = 0): TnpHead
    fun maxDataSize(channel: Int): Int?
    fun validateHead(head: TnpHead, channel: Int): HeadCheck
    fun commandUnit(version: Int, type: Int, number: Int, auth: ByteArray, payload: ByteArray): ByteArray
    fun parseIoCtrlReply(body: ByteArray): IoCtrlReply?            // body = bytes after TNPHead; null if < 40
    fun splitFrameUnit(body: ByteArray, bigEndian: Boolean = true): Pair<FrameInfo, ByteArray>?  // null if <= 24
}
object IoCtrlMessages {
    SET_RESOLUTION=4881 SET_RESOLUTION_RESP=4882 START_REALTIME=9029 AUDIO_START=768 STOP=767 KICK=9033
    TUTK_START_ENC=8190 TUTK_START_PLAIN=511; CODE_WRONG_PASSWORD=-20009 CODE_MANUAL_RETRY=-9000
    CODE_KICKED=-3100 CODE_KICK_MAX_SESSION=-3101; RESOLUTION_HD=1 RESOLUTION_SD=2
    sealed interface AuthOutcome { appCode; OK; WRONG_PASSWORD; RETRY; data class Unknown(code) }
    fun setResolution(resolution: Int, useCount: Int): ByteArray   // 8 B BE
    fun startRealtime(useCount: Int, resolution: Int): ByteArray   // [useCount, resolution, 1, 0]
    fun audioStart(): ByteArray; fun stop(): ByteArray             // 8 zero bytes
    fun tutkStart(useCount: Int): ByteArray                        // u32 LE
    fun authOutcome(authResult: Int): AuthOutcome
    fun kickCode(payload: ByteArray): Int
    object ResolutionPolicy { fun forMode(mode: LinkMode): Int }   // P2P/LAN -> 1, else 2
}
object FrameHeadCodec { const val SIZE = 24; fun parse(bytes: ByteArray, offset: Int = 0, bigEndian: Boolean): FrameInfo }
```

Notes for 02-07: `parseIoCtrlReply` keeps `authResult` signed (s32). Its payload is `min(payloadSize, available)` bytes. Production callers must pass `java.security.SecureRandom` to `noncePrefix` and `authInfo`.

## Deviations from Plan

**1. [Rule 3 - Blocking] The RED commit with `TODO()` stubs was replaced by a mutation check**
- **Issue:** a local write hook rejects files that contain only `TODO()` stubs, so the RED step (stub sources plus failing tests, committed alone) could not be written as specified.
- **Fix:** I wrote the implementation and tests together. To show the tests can fail, I mutated all four sources at once: pass length 14, authInfo 31 B, swapped frame-head byte order, swapped IOCtrl size fields, `dataSize < 0`, authResult 2 not mapped, setResolution field order. The run then failed 16 of 29 tests across all four classes (gradle exit=1). I restored the sources and the run went green again.
- **Commits:** the plan named two commits, `test(...)` and `feat(...)`. I made two atomic feat commits instead, split by module group (auth first, then the codec/messages/frame head). Each commit compiles and is green on its own.

**2. [Design] AuthOutcome is a sealed interface, not an enum**
- The required `UNKNOWN(code)` must carry the raw code, which a Kotlin enum constant cannot do. The names OK, WRONG_PASSWORD and RETRY are kept as objects. Each outcome has `appCode`: 0, -20009, -9000, and -9000 for Unknown.

**3. Comment wording in YiP2pAuth.hmacPassword's contract**
- The contract no longer repeats the literal `user=xiaoyiuser&nonce=`, so the acceptance grep returns exactly 1 line.

GRACE docs (development-plan, knowledge-graph, verification-plan) already list these sources and tests under M-STREAM-CORE / V-M-STREAM-CORE from plan 02-00, so this plan changes no docs.

Requirements LIVE-01 and LIVE-02 are deliberately NOT marked complete. They close only after device verification.

## Known Stubs

None.

## Self-Check: PASSED

- All 8 files exist on disk. Commits 8ef9894 and 6f46d90 exist and carry the Co-Authored-By trailer.
- The four TEST XML files show failures="0".
