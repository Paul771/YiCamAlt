// FILE: VideoSizeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the crop-aware visible video size (LIVE-09).
//   SCOPE: crop keys present, no crop keys, partial crop keys, non-positive width / height.
//   DEPENDS: M-MEDIA-RENDER (visibleSize, VideoSize)
//   LINKS: V-M-MEDIA-RENDER
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   VideoSizeTest - one test per behaviour bullet of plan 03-08 Task 2.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: visible size vectors.
// END_CHANGE_SUMMARY

class VideoSizeTest {

    @Test
    fun `crop keys give the visible size of a 1088 aligned buffer`() {
        assertEquals(VideoSize(1920, 1080), visibleSize(1920, 1088, 0, 1919, 0, 1079))
    }

    @Test
    fun `no crop keys use width and height`() {
        assertEquals(VideoSize(1280, 720), visibleSize(1280, 720, null, null, null, null))
    }

    @Test
    fun `any crop key missing uses width and height`() {
        assertEquals(VideoSize(1920, 1088), visibleSize(1920, 1088, 0, 1919, 0, null))
        assertEquals(VideoSize(1920, 1088), visibleSize(1920, 1088, null, 1919, 0, 1079))
    }

    @Test
    fun `non positive width or height gives null`() {
        assertNull(visibleSize(0, 1080, null, null, null, null))
        assertNull(visibleSize(1920, -1, null, null, null, null))
        assertNull(visibleSize(0, 0, 0, 1919, 0, 1079))
    }
}
