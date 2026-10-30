// FILE: FrameAssembler.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Order transport video units for the decoder: keyframe gate, P-frame sequence reorder, generation filter.
//   SCOPE: u16 sequence arithmetic, bounded pending buffer (MAX_PENDING), useCount generation, counters.
//   DEPENDS: StreamTypes (VideoUnit, FrameInfo)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

// START_MODULE_MAP
//   FrameAssembler - class: offer(unit) -> List<VideoUnit> emitted in order, expectGeneration(useCount) gate,
//     reset(), emitted, dropped, pending, MAX_PENDING (100 units).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-05: useCount equality gate for archive seeks / go-live (Pitfall 2).
//     expectGeneration(uc): units whose useCount low byte differs are dropped before ordering (robust to the
//     255 -> 0 wrap); setting it drops pending of other generations and disarms when the armed generation differs.
//     null = no gate (Phase 3 behaviour). reset() keeps the gate: decoder resets do not change the generation.
//   PREVIOUS: v0.2.0 - Phase 2 review HIGH-1: I-frames (ch2) and P-frames (ch3) come from different reader
//     threads, so P(I+1), P(I+2) can arrive before their I-frame. An I-frame no longer clears the pending buffer:
//     it drops only stale pending units (seq <= I in u16 order, or another useCount generation; counted as
//     dropped), is emitted, then drains I+1, I+2, ... from pending (vendor TnpCamera.handleVideoFrame). A P that
//     arrives before the first I, or with a useCount other than the current generation, now waits in pending
//     instead of being dropped at once; it is dropped as stale when the next I does not continue it.
//   PREVIOUS: v0.1.0 - GSD plan 02-05 from 02-RESEARCH.md Pattern 6.
//     Rules: an I-frame resets the decode point (seq, generation = useCount) and is emitted; a P-frame is emitted
//     only when seq == last + 1 (u16 wrap); a future P (distance 2..0x7FFF) waits in pending; a P with seq <= last,
//     a P before the first I, and a P of another useCount generation are dropped. Pending overflow drops the
//     earliest-buffered unit. NOT thread-safe: FramePipeline serialises calls.
// END_CHANGE_SUMMARY

class FrameAssembler {

    companion object {
        const val MAX_PENDING = 100
        private const val SEQ_MASK = 0xFFFF
        private const val HALF_RANGE = 0x8000
    }

    var emitted: Long = 0
        private set
    var dropped: Long = 0
        private set

    private var armed = false
    private var lastSeq = 0
    private var generation = 0
    private var gate: Int? = null
    private val pendingBySeq = LinkedHashMap<Int, VideoUnit>()

    val pending: Int get() = pendingBySeq.size

    // START_CONTRACT: offer
    //   PURPOSE: Feed one transport unit; get back the units that may go to the decoder now, in decode order.
    //   INPUTS: { unit: VideoUnit }
    //   OUTPUTS: { List<VideoUnit> - possibly empty }
    //   SIDE_EFFECTS: updates decode point, pending buffer, emitted/dropped counters. Key frame: drop stale pending
//     (seq <= I modulo u16, or another generation), emit I, drain I+1, I+2, ... P before the first I or of another
//     generation: buffered (bounded by MAX_PENDING) until an I continues it or proves it stale.
    //   LINKS: M-STREAM-CORE, FramePipeline.onVideoUnit
    // END_CONTRACT: offer
    fun offer(unit: VideoUnit): List<VideoUnit> {
        // START_BLOCK_ASSEMBLE_FRAMES
        val info = unit.info
        val g = gate
        if (g != null && (info.useCount and 0xFF) != (g and 0xFF)) {
            dropped++
            return emptyList()
        }
        val seq = info.seq and SEQ_MASK
        if (info.isKeyFrame) {
            val it = pendingBySeq.values.iterator()
            while (it.hasNext()) {
                val queued = it.next()
                val d = (queued.info.seq - seq) and SEQ_MASK
                if (queued.info.useCount != info.useCount || d == 0 || d >= HALF_RANGE) {
                    it.remove()
                    dropped++
                }
            }
            armed = true
            lastSeq = seq
            generation = info.useCount
            return drainFrom(unit)
        }
        if (!armed || info.useCount != generation) {
            // May belong to an I-frame still in flight on the other reader thread: wait for it.
            return enqueue(seq, unit)
        }
        val distance = (seq - lastSeq) and SEQ_MASK
        if (distance == 0 || distance >= HALF_RANGE) {
            dropped++
            return emptyList()
        }
        if (distance > 1) return enqueue(seq, unit)
        return drainFrom(unit)
        // END_BLOCK_ASSEMBLE_FRAMES
    }

    // Emits [unit] (the new decode point) and every consecutive pending unit of the same generation after it.
    private fun drainFrom(unit: VideoUnit): List<VideoUnit> {
        val out = ArrayList<VideoUnit>(1)
        out += unit
        lastSeq = unit.info.seq and SEQ_MASK
        while (true) {
            val next = (lastSeq + 1) and SEQ_MASK
            val queued = pendingBySeq[next] ?: break
            if (queued.info.useCount != generation) break
            pendingBySeq.remove(next)
            out += queued
            lastSeq = next
        }
        emitted += out.size
        return out
    }

    private fun enqueue(seq: Int, unit: VideoUnit): List<VideoUnit> {
        val existing = pendingBySeq[seq]
        if (existing != null) {
            dropped++
            // A unit of the current generation wins the slot over a leftover of another generation.
            val replace = armed && unit.info.useCount == generation && existing.info.useCount != generation
            if (replace) pendingBySeq[seq] = unit
            return emptyList()
        }
        pendingBySeq[seq] = unit
        if (pendingBySeq.size > MAX_PENDING) {
            val oldest = pendingBySeq.keys.first()
            pendingBySeq.remove(oldest)
            dropped++
        }
        return emptyList()
    }

    // START_CONTRACT: expectGeneration
    //   PURPOSE: Vendor useCount equality gate (AntsCamera handler): accept only units of [useCount] (low byte); null = no gate.
    //   INPUTS: { useCount: Int? - generation of the last seek / go-live command; null removes the gate }
    //   OUTPUTS: none
    //   SIDE_EFFECTS: drops pending units of other generations (counted); disarms when the armed generation differs
    //   LINKS: M-STREAM-CORE, FramePipeline.expectGeneration, PlaybackController.startAt
    // END_CONTRACT: expectGeneration
    fun expectGeneration(useCount: Int?) {
        // START_BLOCK_GENERATION_GATE
        gate = useCount
        if (useCount == null) return
        val want = useCount and 0xFF
        val it = pendingBySeq.values.iterator()
        while (it.hasNext()) {
            if ((it.next().info.useCount and 0xFF) != want) {
                it.remove()
                dropped++
            }
        }
        if (armed && (generation and 0xFF) != want) armed = false
        // END_BLOCK_GENERATION_GATE
    }

    // START_CONTRACT: reset
    //   PURPOSE: Re-arm the keyframe gate (decoder recreated or link restarted).
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: clears pending (counted as dropped); counters keep accumulating
    //   LINKS: FramePipeline.reset
    // END_CONTRACT: reset
    fun reset() {
        dropped += pendingBySeq.size
        pendingBySeq.clear()
        armed = false
    }
}
