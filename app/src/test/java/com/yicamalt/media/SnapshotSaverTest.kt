// FILE: SnapshotSaverTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the gallery snapshot storage rules (LIVE-08).
//   SCOPE: file name format, per-API target (scoped path + IS_PENDING vs legacy permission), JPEG constants.
//   DEPENDS: M-MEDIA-SNAPSHOT (SnapshotSpec)
//   LINKS: V-M-MEDIA-SNAPSHOT
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.media

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   SnapshotSaverTest - one test per behaviour bullet of plan 03-09 Task 1.
//   scoped / legacy - expected SnapshotSpec.Target values.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-09: snapshot spec vectors.
// END_CHANGE_SUMMARY

class SnapshotSaverTest {

    private val scoped = SnapshotSpec.Target(relativePath = "Pictures/YiCamAlt", usePending = true, needsLegacyPermission = false)
    private val legacy = SnapshotSpec.Target(relativePath = null, usePending = false, needsLegacyPermission = true)

    @Test
    fun `file name is local time with zero padding and no camera data`() {
        assertEquals("YiCamAlt_20261022_090507.jpg", SnapshotSpec.fileName(LocalDateTime.of(2026, 10, 22, 9, 5, 7)))
    }

    @Test
    fun `api 29 and later use the scoped relative path with IS_PENDING`() {
        assertEquals(scoped, SnapshotSpec.target(29))
        assertEquals(scoped, SnapshotSpec.target(34))
    }

    @Test
    fun `api 26 to 28 need the legacy write permission and no relative path`() {
        assertEquals(legacy, SnapshotSpec.target(28))
        assertEquals(legacy, SnapshotSpec.target(26))
    }

    @Test
    fun `jpeg quality and mime are fixed`() {
        assertEquals(90, SnapshotSpec.JPEG_QUALITY)
        assertEquals("image/jpeg", SnapshotSpec.MIME)
        assertEquals("Pictures/YiCamAlt", SnapshotSpec.RELATIVE_PATH)
    }
}
