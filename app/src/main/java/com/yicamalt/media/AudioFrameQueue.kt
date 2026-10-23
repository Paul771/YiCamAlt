// FILE: AudioFrameQueue.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Bounded drop-oldest queue between the audio pipeline and the AAC codec (latency bound).
//   SCOPE: RED stub.
//   DEPENDS: none
//   LINKS: M-MEDIA-AUDIO, V-M-MEDIA-AUDIO
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

// START_MODULE_MAP
//   AudioFrameQueue<T> - RED stub.
// END_MODULE_MAP

class AudioFrameQueue<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {
    fun offer(item: T): Boolean = error("RED stub")
    fun poll(): T? = error("RED stub")
    val size: Int get() = error("RED stub")
    val dropped: Long get() = error("RED stub")
    fun clear(): Unit = error("RED stub")

    companion object {
        const val DEFAULT_CAPACITY = 6
    }
}
