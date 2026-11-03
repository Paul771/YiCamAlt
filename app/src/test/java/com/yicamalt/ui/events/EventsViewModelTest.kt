// FILE: EventsViewModelTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify EventsViewModel: refresh only on tab entry / pull (D-04), the 5-minute subtitle, cache-first
//     cards, camera / day filters, offline cache, session-expired and open-clip one-shot events, no ticker.
//   SCOPE: JVM-only with fakes (feed over a MutableStateFlow of rows, scripted sync results, fixed cameras); a
//     virtual clock; synthetic ids and names only.
//   DEPENDS: M-UI-EVENTS (EventsViewModel, EventFeedSource, EventSyncSource), M-EVENT (SyncResult),
//     M-CAMERA-LIST (CameraListPort)
//   LINKS: V-M-UI-EVENTS, M-UI-EVENTS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   FakeFeed - EventFeedSource over a MutableStateFlow of rows, filtered by the requested window.
//   FakeSync - EventSyncSource: queue of results (last one repeats), optional gate, call counter.
//   FakeCameras - CameraListPort with a fixed cached list; network calls are a test failure.
//   EventsViewModelTest - one test per behaviour of plan 05-04 Task 1 (view-model part).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-04: initial view-model tests.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.events

import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.EventEntity
import com.yicamalt.event.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
private val NOW0: Long = LocalDateTime.of(2026, 10, 28, 15, 0).atZone(ZONE).toInstant().toEpochMilli()
private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

private fun row(key: String, device: String, timeMs: Long) =
    EventEntity(key, device, timeMs, 1, 0, "mp4", true, 6, null, 0L)

private class FakeFeed(initial: List<EventEntity>) : EventFeedSource {
    val rows = MutableStateFlow(initial)
    val windows = mutableListOf<LongRange>()
    override fun observe(fromMs: Long, toMs: Long): Flow<List<EventEntity>> {
        windows += fromMs..toMs
        return rows.map { list -> list.filter { it.timeMs in fromMs..toMs }.sortedByDescending { it.timeMs } }
    }
}

private class FakeSync(vararg results: SyncResult) : EventSyncSource {
    private val queue = ArrayDeque(results.toList())
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun refresh(): SyncResult {
        calls++
        gate?.await()
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

private class FakeCameras(private val cached: List<CameraInfo>) : CameraListPort {
    override suspend fun getCameraList(): List<CameraInfo> = error("network camera list must not be called")
    override suspend fun cachedCameras(): List<CameraInfo> = cached
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var nowMs = NOW0

    @BeforeEach fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val cams = listOf(
        CameraInfo("dev-a", "Прихожая", "M", online = true, streamUrl = null),
        CameraInfo("dev-b", "Двор", "M", online = true, streamUrl = null),
    )
    private val baseRows = listOf(
        row("k1", "dev-a", NOW0 - HOUR),
        row("k2", "dev-b", NOW0 - 2 * HOUR),
        row("k3", "dev-a", NOW0 - DAY),
        row("k4", "dev-x", NOW0 - 2 * DAY),
    )

    private fun vm(feed: FakeFeed, sync: FakeSync) =
        EventsViewModel(feed, sync, FakeCameras(cams), { nowMs }, { ZONE })

    private fun EventsViewModel.keys(): List<String> = state.value.sections.flatMap { s -> s.cards.map { it.key } }

    private fun TestScope.collectEvents(vm: EventsViewModel): MutableList<EventsEvent> {
        val out = mutableListOf<EventsEvent>()
        backgroundScope.launch { vm.events.collect { out += it } }
        return out
    }

    @Test
    fun `tab entry calls refresh once and shows the result subtitle`() = runTest(dispatcher) {
        val sync = FakeSync(SyncResult.Fetched(2, 1, false))
        val vm = vm(FakeFeed(baseRows), sync)
        advanceUntilIdle()
        assertEquals(0, sync.calls)
        vm.onTabEntered()
        advanceUntilIdle()
        assertEquals(1, sync.calls)
        assertEquals("Обновлено только что", vm.state.value.subtitle)
        assertEquals(listOf("k1", "k2", "k3", "k4"), vm.keys())
        assertEquals(listOf("Прихожая", "Двор"), vm.state.value.cameras.map { it.name })
        assertTrue(vm.state.value.allSelected)
        assertEquals("Все дни ▾", vm.state.value.dayLabel)
    }

    @Test
    fun `pull refresh calls refresh once per gesture and clears the refreshing flag`() = runTest(dispatcher) {
        val sync = FakeSync(SyncResult.Fetched(0, 1, false))
        val vm = vm(FakeFeed(baseRows), sync)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        vm.onPullRefresh()
        assertTrue(vm.state.value.refreshing)
        advanceUntilIdle()
        assertEquals(1, sync.calls)
        assertTrue(vm.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        vm.onPullRefresh()
        advanceUntilIdle()
        assertEquals(2, sync.calls)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `second tab entry within 5 minutes shows TooEarly subtitle`() = runTest(dispatcher) {
        val sync = FakeSync(SyncResult.Fetched(1, 1, false), SyncResult.TooEarly(2))
        val vm = vm(FakeFeed(baseRows), sync)
        vm.onTabEntered()
        advanceUntilIdle()
        nowMs += 2 * 60_000L
        vm.onTabEntered()
        advanceUntilIdle()
        assertEquals(2, sync.calls)
        assertEquals("Обновлено 2 мин назад", vm.state.value.subtitle)
    }

    @Test
    fun `new db emission updates cards without a network call`() = runTest(dispatcher) {
        val feed = FakeFeed(baseRows)
        val sync = FakeSync(SyncResult.Fetched(0, 1, false))
        val vm = vm(feed, sync)
        vm.onTabEntered()
        advanceUntilIdle()
        nowMs += 7 * 60_000L
        feed.rows.value = baseRows + row("k0", "dev-b", NOW0 - 60_000L)
        advanceUntilIdle()
        assertEquals(1, sync.calls)
        assertEquals("k0", vm.keys().first())
        // The «N мин» label is recomputed on a DB emission (no ticker).
        assertEquals("Обновлено 7 мин назад", vm.state.value.subtitle)
    }

    @Test
    fun `toggle camera chip and select day filter the cards`() = runTest(dispatcher) {
        val vm = vm(FakeFeed(baseRows), FakeSync(SyncResult.Fetched(0, 1, false)))
        vm.onTabEntered()
        advanceUntilIdle()
        vm.toggleCamera("dev-a")
        advanceUntilIdle()
        assertEquals(listOf("k1", "k3"), vm.keys())
        assertFalse(vm.state.value.allSelected)
        assertTrue(vm.state.value.cameras.first { it.name == "Прихожая" }.selected)
        vm.openDays()
        assertTrue(vm.state.value.daysDialogOpen)
        assertTrue(LocalDate.of(2026, 10, 27) in vm.state.value.daysWithEvents)
        vm.selectDay(LocalDate.of(2026, 10, 27))
        advanceUntilIdle()
        assertFalse(vm.state.value.daysDialogOpen)
        assertEquals(listOf("k3"), vm.keys())
        assertEquals("Вчера ▾", vm.state.value.dayLabel)
        vm.selectDay(LocalDate.of(2026, 10, 26))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.keys())
        assertEquals("Нет событий для выбранного фильтра", vm.state.value.emptyText)
        vm.selectAllCameras()
        advanceUntilIdle()
        assertEquals(listOf("k4"), vm.keys())
        assertTrue(vm.state.value.allSelected)
        vm.toggleCamera("dev-a")
        vm.toggleCamera("dev-a")
        assertTrue(vm.state.value.allSelected)
    }

    @Test
    fun `clear day returns to the last days`() = runTest(dispatcher) {
        val vm = vm(FakeFeed(baseRows), FakeSync(SyncResult.Fetched(0, 1, false)))
        vm.onTabEntered()
        advanceUntilIdle()
        vm.selectDay(LocalDate.of(2026, 10, 28))
        advanceUntilIdle()
        assertEquals("Сегодня ▾", vm.state.value.dayLabel)
        assertEquals(listOf("k1", "k2"), vm.keys())
        vm.clearDay()
        advanceUntilIdle()
        assertNull(vm.state.value.selectedDay)
        assertEquals("Все дни ▾", vm.state.value.dayLabel)
        assertEquals(listOf("k1", "k2", "k3", "k4"), vm.keys())
    }

    @Test
    fun `offline keeps cached cards visible`() = runTest(dispatcher) {
        val vm = vm(FakeFeed(baseRows), FakeSync(SyncResult.Offline))
        vm.onTabEntered()
        advanceUntilIdle()
        assertEquals("Нет сети, показан кэш", vm.state.value.subtitle)
        assertEquals(4, vm.keys().size)
        assertNull(vm.state.value.emptyText)
        val empty = vm(FakeFeed(emptyList()), FakeSync(SyncResult.Offline))
        empty.onTabEntered()
        advanceUntilIdle()
        assertEquals("Событий пока нет", empty.state.value.emptyText)
    }

    @Test
    fun `unauthorized emits a session expired event once`() = runTest(dispatcher) {
        val vm = vm(FakeFeed(baseRows), FakeSync(SyncResult.Unauthorized))
        val events = collectEvents(vm)
        vm.onTabEntered()
        advanceUntilIdle()
        vm.onPullRefresh()
        advanceUntilIdle()
        assertEquals(listOf<EventsEvent>(EventsEvent.SessionExpired), events)
        assertEquals("Сессия истекла", vm.state.value.subtitle)
        assertEquals(4, vm.keys().size)
    }

    @Test
    fun `no ticker`() = runTest(dispatcher) {
        val feed = FakeFeed(baseRows)
        val sync = FakeSync(SyncResult.Fetched(0, 1, false))
        val vm = vm(feed, sync)
        vm.onTabEntered()
        advanceUntilIdle()
        val subtitle = vm.state.value.subtitle
        nowMs += 30 * 60_000L
        advanceTimeBy(30 * 60_000L)
        advanceUntilIdle()
        assertEquals(1, sync.calls)
        assertEquals(subtitle, vm.state.value.subtitle)
        vm.onPullRefresh()
        advanceUntilIdle()
        assertEquals(2, sync.calls)
    }

    @Test
    fun `card tap emits OpenClip with the key`() = runTest(dispatcher) {
        val vm = vm(FakeFeed(baseRows), FakeSync(SyncResult.Fetched(0, 1, false)))
        val events = collectEvents(vm)
        vm.onCardTap("k3")
        advanceUntilIdle()
        assertEquals(listOf<EventsEvent>(EventsEvent.OpenClip("k3")), events)
    }

    @Test
    fun `tab entry moves the window to the current day`() = runTest(dispatcher) {
        val feed = FakeFeed(baseRows)
        val vm = vm(feed, FakeSync(SyncResult.Fetched(0, 1, false)))
        vm.onTabEntered()
        advanceUntilIdle()
        val first = feed.windows.last()
        assertEquals(NOW0 - 30 * DAY, first.first)
        assertEquals(NOW0 + DAY, first.last)
        nowMs += 2 * DAY
        vm.onTabEntered()
        advanceUntilIdle()
        assertEquals(nowMs - 30 * DAY, feed.windows.last().first)
        assertEquals(LocalDate.of(2026, 10, 30), vm.state.value.today)
    }
}
