---
phase: 02-p2p
plan: 05
subsystem: stream-core
tags: [p2p, media, aes, nal, h264, h265, tdd]
requires: ["02-00"]
provides:
  - "IFrameDecryptor: vendor-exact I-frame decrypt (AES/ECB/NoPadding on [4,20) and [20,36), key = password + '0', fail-open)"
  - "NalUtils: framing detection, toAnnexB, splitAnnexB, nalType(s), parameterSets, isKeyStart"
  - "FrameAssembler: keyframe gate, u16 P reorder (MAX_PENDING 100), useCount generation filter"
  - "FramePipeline + PipelineStats: VideoUnitSink -> DecodableFrameSink"
affects: [02-07, 02-08, 02-09, 02-11]
tech-stack:
  added: []
  patterns: ["known-answer JVM tests (FIPS-197) through the production cipher path", "mutation check as RED evidence"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/stream/IFrameDecryptor.kt
    - app/src/main/java/com/yicamalt/stream/NalUtils.kt
    - app/src/main/java/com/yicamalt/stream/FrameAssembler.kt
    - app/src/main/java/com/yicamalt/stream/FramePipeline.kt
    - app/src/test/java/com/yicamalt/stream/IFrameDecryptorTest.kt
    - app/src/test/java/com/yicamalt/stream/NalUtilsTest.kt
    - app/src/test/java/com/yicamalt/stream/FrameAssemblerTest.kt
    - app/src/test/java/com/yicamalt/stream/FramePipelineTest.kt
  modified:
    - docs/development-plan.xml
decisions:
  - "FramePipeline decrypts a copy of the I-frame payload; the transport buffer is never modified"
  - "Pending overflow drops the earliest-buffered P (insertion order); an I-frame clears pending (counted as dropped)"
  - "onUnsupportedCodec fires once per distinct unknown codec id; every such unit counts as dropped"
  - "ptsUs clock starts at the first emitted frame (pts 0) and reset() keeps pts monotonic"
metrics:
  duration: 20min
  completed: 2026-10-14
  tasks: 2
  files: 9
---

# Phase 2 Plan 05: Media core (I-frame decrypt, NAL utils, assembler, pipeline) Summary

Pure-Kotlin media half of the P2P core. It decrypts I-frames exactly like the vendor client, normalises H.264/H.265 access units to Annex-B with codec-specific data, orders frames behind a keyframe gate, and delivers `DecodableFrame`s to a sink. Every unknown codec is signalled explicitly. 35 JVM tests cover it, including FIPS-197 C.1 through our cipher path.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | IFrameDecryptor + NalUtils with tests | 4f4341e |
| 2 | FrameAssembler + FramePipeline with tests (+ dev-plan export purpose) | 84459de |

Verification: `tools/gradle.ps1 :app:testDebugUnitTest --tests ...IFrameDecryptorTest --tests ...NalUtilsTest --tests ...FrameAssemblerTest --tests ...FramePipelineTest` gave BUILD SUCCESSFUL and `--- gradle exit=0`. TEST XML results: IFrameDecryptorTest 6 tests, 0 failures. NalUtilsTest 10/0. FrameAssemblerTest 9/0. FramePipelineTest 10/0.

Acceptance greps:
- `AES/ECB/NoPadding` matches 1 line in IFrameDecryptor.kt.
- `69c4e0d86a7b0430d8cdb78070b4c55a` matches 1 line in IFrameDecryptorTest.kt.
- `MAX_PENDING = 100` matches 1 line in FrameAssembler.kt.
- `Timber` does not appear in IFrameDecryptor, NalUtils or FrameAssembler.
- `/*` does not appear in the 8 files, and FramePipeline has no `android.` import.

## API for plans 02-07 / 02-08 / 02-09

```kotlin
object IFrameDecryptor { const val MIN_PAYLOAD = 36; fun decryptInPlace(payload: ByteArray, password: String): Boolean; internal fun aesEcbDecryptBlock(key, block) }
object NalUtils {
    enum class Framing { ANNEX_B, LENGTH_PREFIXED, UNKNOWN }; val START_CODE: ByteArray
    fun detectFraming(bytes): Framing; fun toAnnexB(bytes): ByteArray   // same instance unless length-prefixed
    fun splitAnnexB(bytes): List<IntRange>; fun nalType(firstByte, codec): Int; fun nalTypes(annexB, codec): List<Int>
    fun parameterSets(annexB, codec): List<ByteArray>                     // AVC [SPS, PPS]; HEVC [VPS+SPS+PPS]; else empty
    fun isKeyStart(types, codec): Boolean
}
class FrameAssembler { const val MAX_PENDING = 100; fun offer(unit): List<VideoUnit>; fun reset(); val emitted; val dropped; val pending }
data class PipelineStats(framesIn: Long, keyFrames: Long, emitted: Long, dropped: Long,
                         decryptOk: Boolean?, framing: String, unsupportedCodec: Int?)
// decryptOk null until the first key frame; framing "none" | "annexb" | "length" | "unknown"
class FramePipeline(password: String?, p2pEncrypt: Boolean, sink: DecodableFrameSink,
                    nanoTime: () -> Long = System::nanoTime) : VideoUnitSink {
    @Synchronized override fun onVideoUnit(unit: VideoUnit)
    fun stats(): PipelineStats          // volatile snapshot, safe from any thread
    @Synchronized fun reset()           // re-arm gate, drop cached csd, re-log first key frame
}
```

Behaviour notes for downstream plans:
- **Fail-open.** A key frame whose first NAL is not a key NAL is still forwarded. Check `stats().decryptOk == false` to spot a wrong password, and surface it in the UI or probe.
- **csd caching.** The pipeline keeps the last non-empty csd and reuses it for P-frames and for key frames that carry no parameter sets. `reset()` clears it.
- **Log marker.** `[Stream][pipeline][BLOCK_FIRST_KEYFRAME] codec=H264 framing=annexb nals=7,8,5 decryptOk=true size=1280x720` is printed once per arm. Unknown codec ids produce `[Stream][pipeline][BLOCK_MAP_CODEC] unsupported codecId=N`, also once per id.

## Deviations from Plan

**1. [Rule 3 - Blocking] The RED commit with `TODO()` stubs was replaced by a mutation check (same as 02-04)**
- **Issue:** a local write hook rejects files that contain only `TODO()` stubs.
- **Fix:** I wrote the implementation and tests together. As RED evidence I mutated all four sources at once: key suffix "1" instead of "0", H.264 mask 0x0F, H.265 type without the shift, reorder threshold `distance > 2`, pending cap `>=`, pts `max(last, …)`, and unsupported signalled on every unit. The run then failed 13 of 35 tests across all four classes (gradle exit=1). After restoring the sources the run was green again.
- **Commits:** the plan named `test(...)` and `feat(...)` commits. I made two atomic feat commits instead, split by module group. Each one compiles and is green.

**2. [Test data] Synthetic SPS bytes adjusted**
- With the first SPS header choice, the ciphertext's first byte happened to decode as H.264 type 5/7. That made the "encrypted bytes in plain mode → decryptOk false" assertion fail by coincidence. I changed the synthetic SPS profile bytes. The vectors are deterministic, so this cannot flake.

**3. [Docs] `export-FrameAssembler` purpose in development-plan.xml**
- It said "Reassemble units into access units", which did not match what the module does. I corrected it to describe the keyframe gate, reorder and generation filter. The knowledge graph and verification plan already list the files and marker from 02-00, so neither needed a change.

Extra tests beyond the plan: ECB block independence, duplicate future P, reorder across the u16 wrap, an I-frame discarding stale pending units, the transport buffer staying unmodified, and pts following an advancing clock.

Requirements LIVE-01 and LIVE-03 are deliberately NOT marked complete. They close only after device verification.

## Known Stubs

None.

## Self-Check: PASSED

- All 8 source and test files exist on disk. Commits 4f4341e and 84459de exist and carry the Co-Authored-By trailer.
- All four TEST XML files show failures="0".
