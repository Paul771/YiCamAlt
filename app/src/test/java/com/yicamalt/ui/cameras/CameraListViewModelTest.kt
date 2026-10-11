// FILE: CameraListViewModelTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify the camera-list view-model: cache-first start, Loading/Content/Empty/Error, stale banner,
//     single-flight refresh, retry transitions and cancellation safety, against a scripted fake port.
//   SCOPE: JVM-only, synthetic camera data (no real names, ids or serials).
//   DEPENDS: M-UI-SHELL (CameraListViewModel), M-CAMERA-LIST (CameraListPort, CameraError), TestInfrastructure
//   LINKS: V-M-UI-SHELL, M-UI-SHELL
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   ScriptedPort - fake CameraListPort: cached list, queue of results/throwables, optional gate, call counters.
//   cam - builds a synthetic CameraInfo.
//   CameraListViewModelTest - one test per behaviour of plan 01-10 Task 1.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - plan 01-10: ScriptedPort, cache-first / Empty / typed Error / stale / single-flight /
//     retry / cancellation / isRefreshing tests (replaces the v0.1.0 three-test smoke set).
// END_CHANGE_SUMMARY
package com.yicamalt.ui.cameras

import com.yicamalt.camera.CameraError
import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private fun cam(n: Int, online: Boolean = true) =
    CameraInfo("synthetic-$n", "Камера $n", "MODEL_$n", online = online, streamUrl = null)

/** Each getCameraList() call takes the next scripted outcome (a List or a Throwable); waits on [gate] first if set. */
private class ScriptedPort(
    private val cached: List<CameraInfo> = emptyList(),
    vararg outcomes: Any,
) : CameraListPort {
    val queue = ArrayDeque(outcomes.toList())
    var gate: CompletableDeferred<Unit>? = null
    var networkCalls = 0
    var cacheCalls = 0

    override suspend fun getCameraList(): List<CameraInfo> {
        networkCalls++
        gate?.await()
        val next = queue.removeFirst()
        if (next is Throwable) throw next
        @Suppress("UNCHECKED_CAST")
        return next as List<CameraInfo>
    }

    override suspend fun cachedCameras(): List<CameraInfo> {
        cacheCalls++
        return cached
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val cached2 = listOf(cam(1), cam(2, online = false))
    private val fresh3 = listOf(cam(1), cam(2), cam(3))

    // START_BLOCK_CACHE_FIRST
    @Test
    fun `cached list is shown at once with refreshing, then replaced by the network list`() = runTest(dispatcher) {
        val port = ScriptedPort(cached2, fresh3).apply { gate = CompletableDeferred() }
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(cached2, refreshing = true), vm.state.value)
        assertEquals(1, port.cacheCalls)
        port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(fresh3, refreshing = false, staleError = null), vm.state.value)
        assertEquals(1, port.networkCalls)
    }

    @Test
    fun `empty cache shows Loading until the network answers`() = runTest(dispatcher) {
        val port = ScriptedPort(emptyList(), cached2).apply { gate = CompletableDeferred() }
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Loading, vm.state.value)
        port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(cached2), vm.state.value)
    }
    // END_BLOCK_CACHE_FIRST

    // START_BLOCK_EMPTY_STATE
    @Test
    fun `empty cache and empty network list give Empty, not an error`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(emptyList(), emptyList<CameraInfo>()))
        advanceUntilIdle()
        assertEquals(CameraListUiState.Empty(refreshing = false), vm.state.value)
    }

    @Test
    fun `cached cameras but server says no cameras gives Empty`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(cached2, emptyList<CameraInfo>()))
        advanceUntilIdle()
        assertEquals(CameraListUiState.Empty(refreshing = false), vm.state.value)
    }
    // END_BLOCK_EMPTY_STATE

    // START_BLOCK_TYPED_ERRORS
    @Test
    fun `network failure with empty cache gives NETWORK error`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(emptyList(), CameraError.FetchFailed))
        advanceUntilIdle()
        assertEquals(
            CameraListUiState.Error(ErrorKind.NETWORK, "Нет связи с сервером Yi. Проверьте интернет."),
            vm.state.value,
        )
    }

    @Test
    fun `server rejection with empty cache gives SERVER error`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(emptyList(), CameraError.ServerRejected))
        advanceUntilIdle()
        assertEquals(
            CameraListUiState.Error(ErrorKind.SERVER, "Сервер Yi вернул ошибку. Попробуйте позже."),
            vm.state.value,
        )
    }

    @Test
    fun `unauthorized with empty cache gives UNAUTHORIZED error`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(emptyList(), CameraError.Unauthorized))
        advanceUntilIdle()
        assertEquals(
            CameraListUiState.Error(ErrorKind.UNAUTHORIZED, "Сессия истекла. Войдите снова."),
            vm.state.value,
        )
    }

    @Test
    fun `any other throwable maps to NETWORK error`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(emptyList(), IllegalStateException("synthetic")))
        advanceUntilIdle()
        val s = vm.state.value as CameraListUiState.Error
        assertEquals(ErrorKind.NETWORK, s.kind)
        assertEquals(ErrorKind.NETWORK.userMessage(), s.message)
    }

    @Test
    fun `failed refresh over a cached list keeps the list with a stale SERVER banner`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(cached2, CameraError.ServerRejected))
        advanceUntilIdle()
        assertEquals(
            CameraListUiState.Content(cached2, refreshing = false, staleError = ErrorKind.SERVER),
            vm.state.value,
        )
    }

    @Test
    fun `pull over a stale list clears the banner while refreshing`() = runTest(dispatcher) {
        val port = ScriptedPort(cached2, CameraError.FetchFailed, fresh3)
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, (vm.state.value as CameraListUiState.Content).staleError)
        vm.refresh()
        assertEquals(CameraListUiState.Content(cached2, refreshing = true, staleError = null), vm.state.value)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(fresh3), vm.state.value)
    }
    // END_BLOCK_TYPED_ERRORS

    // START_BLOCK_SINGLE_FLIGHT
    @Test
    fun `refresh twice while one is in flight calls the network once`() = runTest(dispatcher) {
        val port = ScriptedPort(cached2, fresh3, fresh3, fresh3).apply { gate = CompletableDeferred() }
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(1, port.networkCalls) // init refresh is waiting on the gate
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, port.networkCalls)
        port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.networkCalls)
        // After the first finished, a user refresh runs exactly once more.
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, port.networkCalls)
    }
    // END_BLOCK_SINGLE_FLIGHT

    // START_BLOCK_RETRY
    @Test
    fun `retry from Error shows Loading and then Content`() = runTest(dispatcher) {
        val port = ScriptedPort(emptyList(), CameraError.FetchFailed, cached2)
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertTrue(vm.state.value is CameraListUiState.Error)
        vm.refresh()
        assertEquals(CameraListUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(cached2), vm.state.value)
        assertEquals(2, port.networkCalls)
    }

    @Test
    fun `retry from Empty shows Empty refreshing and then Content`() = runTest(dispatcher) {
        val port = ScriptedPort(emptyList(), emptyList<CameraInfo>(), fresh3)
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Empty(), vm.state.value)
        vm.refresh()
        assertEquals(CameraListUiState.Empty(refreshing = true), vm.state.value)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(fresh3), vm.state.value)
    }

    @Test
    fun `load delegates to refresh`() = runTest(dispatcher) {
        val port = ScriptedPort(emptyList(), CameraError.FetchFailed, cached2)
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        vm.load()
        assertEquals(CameraListUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(cached2), vm.state.value)
    }
    // END_BLOCK_RETRY

    // START_BLOCK_CANCELLATION
    @Test
    fun `CancellationException from the port is not turned into Error`() = runTest(dispatcher) {
        val port = ScriptedPort(emptyList(), CancellationException("synthetic"), cached2)
        val vm = CameraListViewModel(port)
        advanceUntilIdle()
        assertEquals(CameraListUiState.Loading, vm.state.value)
        // The cancelled refresh is no longer in flight: a user refresh runs again.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(CameraListUiState.Content(cached2), vm.state.value)
        assertEquals(2, port.networkCalls)
    }
    // END_BLOCK_CANCELLATION

    // START_BLOCK_IS_REFRESHING
    @Test
    fun `isRefreshing per state`() {
        assertTrue(CameraListUiState.Loading.isRefreshing())
        assertTrue(CameraListUiState.Content(cached2, refreshing = true).isRefreshing())
        assertFalse(CameraListUiState.Content(cached2, refreshing = false).isRefreshing())
        assertFalse(CameraListUiState.Content(cached2, staleError = ErrorKind.SERVER).isRefreshing())
        assertTrue(CameraListUiState.Empty(refreshing = true).isRefreshing())
        assertFalse(CameraListUiState.Empty(refreshing = false).isRefreshing())
        assertFalse(CameraListUiState.Error(ErrorKind.NETWORK, "x").isRefreshing())
    }

    @Test
    fun `refresh sets a refreshing state synchronously before it returns`() = runTest(dispatcher) {
        val vm = CameraListViewModel(ScriptedPort(cached2, fresh3, fresh3))
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing())
        vm.refresh()
        assertTrue(vm.state.value.isRefreshing())
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing())
    }
    // END_BLOCK_IS_REFRESHING
}
