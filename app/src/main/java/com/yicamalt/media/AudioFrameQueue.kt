// FILE: AudioFrameQueue.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Bounded drop-oldest queue between the audio pipeline and the AAC codec (latency bound).
//   SCOPE: offer (drop oldest when full, counted), FIFO poll, clear (counter kept), size / dropped readouts.
//   DEPENDS: none (pure Kotlin, JVM-tested)
//   LINKS: M-MEDIA-AUDIO, V-M-MEDIA-AUDIO
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

// START_MODULE_MAP
//   AudioFrameQueue<T>(capacity) - thread-safe bounded FIFO; overflow drops the oldest item.
//   AudioFrameQueue.offer(item) - true when an older item was dropped to make room.
//   AudioFrameQueue.poll() - oldest item or null.
//   AudioFrameQueue.clear() - empties the queue, keeps the dropped counter.
//   AudioFrameQueue.size / dropped - current length / total dropped items.
//   AudioFrameQueue.DEFAULT_CAPACITY - 6 frames (~130 ms of AAC at 48 kHz, ~380 ms at 16 kHz).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-07: created. INVARIANT: never more than capacity items wait for the codec.
// END_CHANGE_SUMMARY

// START_CONTRACT: AudioFrameQueue
//   PURPOSE: Latency-bounded hand-off from the stream thread to the "media-audio" thread.
//   INPUTS: { capacity: Int - maximum queued items, >= 1 }
//   OUTPUTS: { AudioFrameQueue<T> }
//   SIDE_EFFECTS: none
//   LINKS: M-MEDIA-AUDIO, V-M-MEDIA-AUDIO
// END_CONTRACT: AudioFrameQueue
class AudioFrameQueue<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private val items = ArrayDeque<T>(capacity)
    private var droppedCount = 0L

    // START_BLOCK_AUDIO_QUEUE_OFFER
    @Synchronized
    fun offer(item: T): Boolean {
        var droppedOne = false
        while (items.size >= capacity) {
            items.removeFirst()
            droppedCount += 1
            droppedOne = true
        }
        items.addLast(item)
        return droppedOne
    }
    // END_BLOCK_AUDIO_QUEUE_OFFER

    @Synchronized
    fun poll(): T? = items.removeFirstOrNull()

    @Synchronized
    fun clear() {
        items.clear()
    }

    val size: Int
        @Synchronized get() = items.size

    val dropped: Long
        @Synchronized get() = droppedCount

    companion object {
        const val DEFAULT_CAPACITY = 6
    }
}
