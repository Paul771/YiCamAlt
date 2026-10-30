// FILE: AudioFrameQueueTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the bounded drop-oldest audio frame queue (latency bound of AacDecoder).
//   SCOPE: FIFO within capacity, drop-oldest on overflow with counter, clear keeps counter, empty poll, bad capacity.
//   DEPENDS: M-MEDIA-AUDIO (AudioFrameQueue)
//   LINKS: V-M-MEDIA-AUDIO
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// START_MODULE_MAP
//   AudioFrameQueueTest - one scenario per AudioFrameQueue behaviour (capacity 6 default).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-07: created with AudioFrameQueue.
// END_CHANGE_SUMMARY

class AudioFrameQueueTest {

    @Test
    fun `default capacity keeps six items in FIFO order`() {
        val q = AudioFrameQueue<Int>()
        assertEquals(6, AudioFrameQueue.DEFAULT_CAPACITY)
        for (i in 1..6) assertFalse(q.offer(i))
        assertEquals(6, q.size)
        assertEquals(0L, q.dropped)
        for (i in 1..6) assertEquals(i, q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `seventh offer drops the oldest`() {
        val q = AudioFrameQueue<Int>()
        for (i in 1..6) q.offer(i)
        assertTrue(q.offer(7))
        assertEquals(1L, q.dropped)
        assertEquals(6, q.size)
        assertEquals(2, q.poll())
    }

    @Test
    fun `clear empties and keeps the dropped counter`() {
        val q = AudioFrameQueue<Int>(capacity = 2)
        q.offer(1); q.offer(2); q.offer(3)
        assertEquals(1L, q.dropped)
        q.clear()
        assertEquals(0, q.size)
        assertEquals(1L, q.dropped)
        assertNull(q.poll())
    }

    @Test
    fun `poll on empty returns null`() {
        assertNull(AudioFrameQueue<String>().poll())
    }

    @Test
    fun `capacity below one is rejected`() {
        assertThrows<IllegalArgumentException> { AudioFrameQueue<Int>(capacity = 0) }
        assertThrows<IllegalArgumentException> { AudioFrameQueue<Int>(capacity = -1) }
    }
}
