// FILE: FrameAssembler.kt
// VERSION: 0.1.0
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
//   FrameAssembler - class: offer(unit) -> List<VideoUnit> emitted in order, reset(), emitted, dropped, pending,
//     MAX_PENDING (100 units).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05 from 02-RESEARCH.md Pattern 6.
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
    private val pendingBySeq = LinkedHashMap<Int, VideoUnit>()

    val pending: Int get() = pendingBySeq.size

    // START_CONTRACT: offer
    //   PURPOSE: Feed one transport unit; get back the units that may go to the decoder now, in decode order.
    //   INPUTS: { unit: VideoUnit }
    //   OUTPUTS: { List<VideoUnit> - possibly empty }
    //   SIDE_EFFECTS: updates decode point, pending buffer, emitted/dropped counters
    //   LINKS: M-STREAM-CORE, FramePipeline.onVideoUnit
    // END_CONTRACT: offer
    fun offer(unit: VideoUnit): List<VideoUnit> {
        // START_BLOCK_ASSEMBLE_FRAMES
        val info = unit.info
        val seq = info.seq and SEQ_MASK
        if (info.isKeyFrame) {
            dropped += pendingBySeq.size
            pendingBySeq.clear()
            armed = true
            lastSeq = seq
            generation = info.useCount
            emitted++
            return listOf(unit)
        }
        if (!armed || info.useCount != generation) {
            dropped++
            return emptyList()
        }
        val distance = (seq - lastSeq) and SEQ_MASK
        if (distance == 0 || distance >= HALF_RANGE) {
            dropped++
            return emptyList()
        }
        if (distance > 1) {
            if (pendingBySeq.containsKey(seq)) {
                dropped++
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
        val out = ArrayList<VideoUnit>(1)
        out += unit
        lastSeq = seq
        while (true) {
            val next = (lastSeq + 1) and SEQ_MASK
            val queued = pendingBySeq.remove(next) ?: break
            out += queued
            lastSeq = next
        }
        emitted += out.size
        return out
        // END_BLOCK_ASSEMBLE_FRAMES
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
