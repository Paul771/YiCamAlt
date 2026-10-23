// FILE: SnapshotSaver.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Gallery snapshot storage rules (RED stub).
//   SCOPE: SnapshotSpec only.
//   DEPENDS: none
//   LINKS: M-MEDIA-SNAPSHOT, V-M-MEDIA-SNAPSHOT
// END_MODULE_CONTRACT
package com.yicamalt.media

import java.time.LocalDateTime

// START_MODULE_MAP
//   SnapshotSpec - RED stub.
// END_MODULE_MAP

object SnapshotSpec {
    const val RELATIVE_PATH = "Pictures/YiCamAlt"
    const val MIME = "image/jpeg"
    const val JPEG_QUALITY = 0

    data class Target(val relativePath: String?, val usePending: Boolean, val needsLegacyPermission: Boolean)

    fun fileName(time: LocalDateTime): String = error("RED stub")

    fun target(sdkInt: Int): Target = error("RED stub")
}
