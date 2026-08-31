// FILE: DbLog.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Tiny logging indirection for the database layer so TraceRecorder can capture BLOCK_DB_WRITE markers.
//   SCOPE: forward d() to Timber.
//   DEPENDS: none
//   LINKS: TraceRecorder
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

internal object DbLog {
    fun d(message: String) = timber.log.Timber.d(message)
}