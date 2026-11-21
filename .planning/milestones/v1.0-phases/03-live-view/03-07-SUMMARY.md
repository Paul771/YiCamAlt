---
phase: 03-live-view
plan: 07
subsystem: media-audio
tags: [audio, aac, mediacodec, audiotrack, latency]
requires:
  - phase: 03-live-view
    provides: "AacConfig / AacFrameSink (03-01, stream/AudioPipeline.kt)"
provides:
  - "AacDecoder : AacFrameSink with status StateFlow<AudioOutStatus>"
  - "AudioFrameQueue<T> drop-oldest bounded queue (capacity 6)"
affects: [03-live-view live screen (unmute = new AacDecoder, mute = release)]
tech-stack:
  added: []
  patterns: ["VideoDecoder thread model mirrored: lazy HandlerThread, async MediaCodec.Callback, identity-checked callbacks, bounded runOn wait"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/media/AudioFrameQueue.kt
    - app/src/main/java/com/yicamalt/media/AacDecoder.kt
    - app/src/test/java/com/yicamalt/media/AudioFrameQueueTest.kt
  modified:
    - docs/knowledge-graph.xml
    - docs/development-plan.xml
    - docs/verification-plan.xml
key-decisions:
  - "AacDecoder failure is terminal (Failed, frames dropped, no retry loop); createDecoderByType IOException/IllegalArgumentException -> Failed(no-decoder), others -> exception simple name, onError -> Failed(codec), >2 output channels -> Failed(channels)"
  - "AudioFrameQueue.offer returns true when the oldest item was dropped; clear keeps the dropped counter"
metrics:
  duration: 25min
  completed: 2026-10-23
  tasks: 2
  files: 6
---

# Phase 3 Plan 07: AacDecoder Summary

Live audio output for LIVE-06: raw AAC access units + csd-0 decoded by platform MediaCodec (`audio/mp4a-latm`, async on the `media-audio` HandlerThread) and played through an AudioTrack built from the decoder output format, behind a JVM-tested 6-frame drop-oldest queue.

## Tasks

| Task | Name | Commits |
|------|------|---------|
| 1 | AudioFrameQueue (TDD) | c66e358 (RED), e8b3d25 (GREEN) |
| 2 | AacDecoder over MediaCodec + AudioTrack (+ GRACE docs) | fde57fa |

## What was built

- `AudioFrameQueue<T>`: synchronized FIFO, `DEFAULT_CAPACITY = 6`, overflow drops oldest and counts it, `clear()` keeps the counter, capacity < 1 throws.
- `AacDecoder`: `onAacFrame` only offers + posts a pump (never blocks). Codec is created on the first frame and re-created when `AacConfig` changes (value equality incl. csd0). The callback is set before `configure`. `onOutputFormatChanged` creates the AudioTrack (USAGE_MEDIA / CONTENT_TYPE_SPEECH, PCM 16-bit, rate/channel mask from the output format, buffer = max(minBuffer, ~250 ms), MODE_STREAM) and sets status `Playing`. Output is written with `WRITE_NON_BLOCKING`; output that arrives before a track exists is released without writing. `release()` is idempotent, waits at most 1 s, frees the codec, track and thread, and logs `BLOCK_AUDIO_RELEASE dropped=N`.
- GRACE: M-MEDIA-AUDIO is now `implemented` in the knowledge graph and development plan (step-15). V-M-MEDIA-AUDIO is `in-progress` with the test file, check-2 and deterministic scenario-3.

## Verification

- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.media.*" :app:assembleDebug`: BUILD SUCCESSFUL, `--- gradle exit=0`. The 5 AudioFrameQueueTest cases pass, and so do the existing media tests (DecoderPolicy, VideoSize, SnapshotSaver).
- RED run before the implementation: 5/5 failed as expected.
- Acceptance greps: `"media-audio"` = 1, three START_BLOCK_AUDIO_* blocks, `audio/mp4a-latm` and `WRITE_NON_BLOCKING` present, `DEFAULT_CAPACITY = 6` present.
- On-device proof is still to come in H-L2 (TNP) and H-L3 (TUTK).

## Deviations from Plan

- [Rule 2 - Correctness] An output channel count other than 1 or 2 now ends in `Failed("channels")` instead of building a track with an invalid mask. A failed AudioTrack build ends in `Failed(<class>)`. A frame larger than the codec input buffer is dropped with a log line.
- The plan's `TRANSFER_MODE_STREAM` is `AudioTrack.MODE_STREAM` in the Android API.
- GRACE doc updates (graph, development plan, verification plan) went into the Task 2 commit.

## Known Stubs

None.

## Self-Check: PASSED
