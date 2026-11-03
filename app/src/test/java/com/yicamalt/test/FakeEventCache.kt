// FILE: FakeEventCache.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Plain-JVM fakes of the alert cache DAOs so non-Robolectric tests can build an EventCacheCleaner.
//   SCOPE: FakeEventDao (in-memory rows, optional failure), FakeAppMetaDao (in-memory map), FakeEventCache.cleaner.
//   DEPENDS: M-LOCAL-DB (EventDao, AppMetaDao), M-EVENT (EventCacheCleaner)
//   LINKS: V-M-CAMERA-LIST, V-M-EVENT
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import com.yicamalt.database.AppMetaDao
import com.yicamalt.database.AppMetaEntity
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import com.yicamalt.event.EventCacheCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// START_MODULE_MAP
//   FakeEventDao - in-memory EventDao; failWrites makes deleteAll throw (Room failure during a wipe).
//   FakeAppMetaDao - in-memory AppMetaDao.
//   FakeEventCache - holder of one dao pair plus the cleaner built over them.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created for the CameraRegistry logout wipe of the alert cache.
// END_CHANGE_SUMMARY

class FakeEventDao : EventDao() {
    private val rows = MutableStateFlow<Map<String, EventEntity>>(emptyMap())
    @Volatile var failWrites = false

    @Synchronized override fun insertAllRaw(rows: List<EventEntity>) {
        this.rows.value = this.rows.value + rows.associateBy { it.eventKey }
    }

    override fun thumbsFor(keys: List<String>): List<KeyThumb> =
        keys.mapNotNull { k -> rows.value[k]?.thumbPath?.let { KeyThumb(k, it) } }

    override fun observeFeed(fromMs: Long, toMs: Long): Flow<List<EventEntity>> =
        rows.map { m -> m.values.filter { it.timeMs in fromMs..toMs }.sortedByDescending { it.timeMs } }

    override fun feed(fromMs: Long, toMs: Long): List<EventEntity> =
        rows.value.values.filter { it.timeMs in fromMs..toMs }.sortedByDescending { it.timeMs }

    override fun timesSince(fromMs: Long): List<Long> =
        rows.value.values.map { it.timeMs }.filter { it >= fromMs }.sortedDescending()

    override fun newestTime(): Long? = rows.value.values.maxOfOrNull { it.timeMs }

    override fun getByKey(key: String): EventEntity? = rows.value[key]

    @Synchronized override fun setThumb(key: String, path: String?) {
        val row = rows.value[key] ?: return
        rows.value = rows.value + (key to row.copy(thumbPath = path))
    }

    override fun missingThumbsSince(fromMs: Long, limit: Int): List<EventEntity> =
        feed(fromMs, Long.MAX_VALUE).filter { it.thumbPath == null }.take(limit)

    override fun thumbPathsOlderThan(cutoff: Long): List<String> =
        rows.value.values.filter { it.timeMs < cutoff }.mapNotNull { it.thumbPath }

    @Synchronized override fun clearOlderThan(cutoff: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterValues { it.timeMs >= cutoff }
        return before - rows.value.size
    }

    override fun count(): Int = rows.value.size

    @Synchronized override fun deleteAll() {
        if (failWrites) throw IllegalStateException("SYNTH room failure")
        rows.value = emptyMap()
    }
}

class FakeAppMetaDao : AppMetaDao {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun put(entity: AppMetaEntity) { map[entity.key] = entity.value }
    override fun get(key: String): String? = map[key]
    override fun delete(key: String) { map.remove(key) }
    override fun all(): List<AppMetaEntity> = map.map { (k, v) -> AppMetaEntity(k, v) }
}

class FakeEventCache {
    val eventDao = FakeEventDao()
    val appMetaDao = FakeAppMetaDao()
    val cleaner = EventCacheCleaner(eventDao, appMetaDao)
}
