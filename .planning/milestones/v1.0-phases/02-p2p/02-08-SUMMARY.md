---
phase: 02-p2p
plan: 08
subsystem: media-render
tags: [mediacodec, h264, h265, surface, decoder, tdd]
requires: ["02-00", "02-03", "02-05"]
provides:
  - "VideoDecoder: DecodableFrameSink -> MediaCodec (async) -> Surface, DecoderStatus StateFlow, resetEvents SharedFlow"
  - "DecoderPolicy: FrameQueue, ResetCounter, CsdBuilder, PtsGuard, DecoderStatus (pure, JVM-tested)"
affects: [02-09, 02-12]
tech-stack:
  added: []
  patterns: ["all MediaCodec work on one HandlerThread; callers only offer to a queue and post a pump", "stale codec callbacks ignored by identity"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/media/DecoderPolicy.kt
    - app/src/main/java/com/yicamalt/media/VideoDecoder.kt
    - app/src/test/java/com/yicamalt/media/DecoderPolicyTest.kt
  modified:
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Codec work runs on the media-decoder HandlerThread only; detachSurface/release block (max 1 s) until the codec stopped using the surface"
  - "Key frame with a different mime, size or csd releases and recreates the codec (reset reason=format-change), for SD/HD switches"
  - "Access unit larger than the input buffer is dropped, queue cleared, resetEvents emitted (reason=oversize-frame); KEY_MAX_INPUT_SIZE = max(w*h, 2*keyframe)"
  - "NoDecoder is sticky per mime for the lifetime of the VideoDecoder; Failed (3 codec errors in a row) is terminal, the owner recreates the decoder"
metrics:
  duration: 25min
  completed: 2026-10-15
  tasks: 2
  files: 5
---

# Phase 2 Plan 08: VideoDecoder (MediaCodec async to Surface) and DecoderPolicy Summary

The render sink for the P2P stream. `VideoDecoder` takes `DecodableFrame`s, creates a MediaCodec for H.264 or H.265 on the first key frame using that frame's csd, and renders to a Surface in async mode. If the phone has no decoder for the codec, it reports `NoDecoder(mime)` instead of showing a black screen. The decision logic is pure Kotlin in `DecoderPolicy` and is pinned by 9 JVM tests. On-device proof is plan 02-12.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | DecoderPolicy (pure rules) with JVM tests | 652ebbb |
| 2 | VideoDecoder over MediaCodec (async) to Surface (+ GRACE docs) | b30947d |

## Verification

- `tools/gradle.ps1 :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.yicamalt.media.*"` gave BUILD SUCCESSFUL and `--- gradle exit=0`. `TEST-com.yicamalt.media.DecoderPolicyTest.xml` shows `tests="9" failures="0"`.
- RED evidence came from mutation, because the write hook rejects `TODO()` stubs. I flipped the pts comparison to `>=`, kept the gate open on overflow, and changed the no-decoder text. 3 of 9 tests failed (pts, overflow gate, status text), as expected. The source was then restored.
- Acceptance greps:
  - `нет декодера` matches 1 line in DecoderPolicy.kt.
  - `android\.` does not appear in DecoderPolicy.kt.
  - In VideoDecoder.kt, `setCallback` is on line 255 and `.configure(` on line 256.
  - The 4 required START_BLOCK markers are present.
  - `releaseOutputBuffer(index, true)` is present.
  - `queueInputBuffer` and `DecoderCatalog.find` are present (key_links).
  - No `/*` in the new files.

## API for plan 02-09 (LiveProbeViewModel / screen)

```kotlin
class VideoDecoder(catalog: (String, Int, Int) -> DecoderChoice = DecoderCatalog::find) : DecodableFrameSink {
    val status: StateFlow<DecoderStatus>      // Idle | Ready | Rendering(ready, framesRendered, fps) | NoDecoder(mime) | Unsupported(codecId) | Failed(resets)
    val resetEvents: SharedFlow<Unit>         // owner: collect -> FramePipeline.reset()
    fun attachSurface(surface: Surface)       // SurfaceHolder.Callback.surfaceCreated / surfaceChanged
    fun detachSurface()                       // surfaceDestroyed; blocks until the codec released (<= 1 s); session untouched
    override fun onFrame(frame: DecodableFrame)
    override fun onUnsupportedCodec(codecId: Int)
    fun release()                             // idempotent; quits the HandlerThread
}
sealed interface DecoderStatus { fun userText(): String }   // Russian, value-free
// DecoderStatus.codecLabel(mime) -> "H.264" | "H.265" | mime
```

Wiring: `FramePipeline(password, p2pEncrypt, sink = videoDecoder)`. Collect `resetEvents` and call `pipeline.reset()` on each event. Show `status.value.userText()` and `Ready.name` / `Ready.hw` in the debug status line, which is criterion 4 evidence. Frames that arrive while no surface is attached are dropped.

Log markers:
- `[Media][decoder][BLOCK_CREATE_DECODER] mime= name= hw= lowLatency= size=WxH` (on failure: `... result=failed error=<Class>`)
- `[Media][decoder][BLOCK_FIRST_RENDER] ms=<since create>`
- `[Media][decoder][BLOCK_DECODER_RESET] reason=<codec-error|surface-lost|queue-overflow|format-change|oversize-frame> resets=<total>` (when exhausted: `result=failed streak=<n>`)
- `[Media][decoder][BLOCK_NO_DECODER] mime=`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RED with `TODO()` stubs was not possible**
- **Issue:** The local write hook rejects files whose bodies are `TODO()` stubs.
- **Fix:** I wrote the implementation and the tests together. I proved the tests can fail with three targeted mutations (3/9 failed), then restored the source. Task 1 has one commit.
- **Commit:** 652ebbb

**2. [Rule 2 - Correctness] Extra reset reasons format-change and oversize-frame**
- **format-change:** a key frame whose mime, size or csd differs from the running codec now recreates the codec. This covers SD/HD switches and verification scenario-4, "csd change resets the decoder".
- **oversize-frame:** the plan asked to drop frames larger than the input buffer and reset the gate; this reason gives that a named log reason.
- **Commit:** b30947d

**3. [Rule 2 - GRACE] Docs updated**
- knowledge-graph.xml: M-MEDIA-RENDER gets the DecoderPolicy exports, the tests and a change-summary.
- verification-plan.xml: V-M-MEDIA-RENDER gets scenarios 5-7 and the full marker shapes.
- **Commit:** b30947d

## Notes

- I did not mark requirements LIVE-01 and LIVE-03 complete. They close after the device run in plan 02-12.
- Device check 1 lists the phone's decoders as OMX.qcom.video.decoder.avc and .hevc. HEVC is therefore expected to reach `Ready`, not `NoDecoder`, on the Mi 9.

## Known Stubs

None.

## Self-Check: PASSED
