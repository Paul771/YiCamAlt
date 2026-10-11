// FILE: AuthRegionFakes.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Scripted fakes for the login region tests (CAM-02): auth API, region selector, device-list check.
//   SCOPE: synthetic values only; every fake counts its calls so request bounds can be asserted exactly.
//   DEPENDS: M-AUTH, M-CONFIG
//   LINKS: V-M-AUTH
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import com.yicamalt.config.RegionSelector
import com.yicamalt.config.YiRegion
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

// START_MODULE_MAP
//   Synth - synthetic credentials and ids used by the region tests.
//   RegionTestStore / RegionTestClock - in-memory AuthStore (counts writes) and fixed clock.
//   FakeRegionSelector - mutable region, records selections and restores.
//   ScriptedAuthApi - login answers per region or from a queue; counts login and refresh calls.
//   ScriptedDeviceCheck - device-list answers per region; counts calls and records the session tokens seen.
//   ok / rejected - LoginResponse builders.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial fakes for plan 01-08 (20253 hop + region discovery by device list).
// END_CHANGE_SUMMARY

internal object Synth {
    const val EMAIL = "synth.user@example.com"
    const val PASSWORD = "synth-pass-SYNTH"
    const val USER_ID = "424242"
    const val CUSTOM_URL = "http://127.0.0.1:9/"
    fun token(region: String) = "tok-$region-synth"
    fun secret(region: String) = "sec-$region-synth"
}

internal class RegionTestStore(initial: AuthSession? = null) : AuthStore {
    private var held: AuthSession? = initial
    var writes = 0
    override fun putSession(session: AuthSession) { held = session; writes++ }
    override fun getSession(): AuthSession? = held
    override fun clear() { held = null }
}

internal class RegionTestClock(start: Long = 1_000_000L) : Clock {
    private val t = AtomicLong(start)
    fun advance(ms: Long) { t.addAndGet(ms) }
    override fun nowMillis(): Long = t.get()
}

/** Region selector double. [region] == null models a custom/manual server. */
internal class FakeRegionSelector(var region: YiRegion? = YiRegion.US) : RegionSelector {
    val selections = mutableListOf<YiRegion>()
    val restores = mutableListOf<String>()
    private var customUrl: String = Synth.CUSTOM_URL

    override fun currentRegion(): YiRegion? = region
    override fun selectRegion(region: YiRegion) { selections += region; this.region = region }
    override fun currentBaseUrl(): String = region?.baseUrl ?: customUrl
    override fun restoreBaseUrl(url: String) {
        restores += url
        region = YiRegion.fromBaseUrl(url)
        if (region == null) customUrl = url
    }
}

internal fun ok(tag: String, code: String = "20000"): LoginResponse = LoginResponse(
    code = code,
    data = Json.parseToJsonElement(
        """{"userid":${Synth.USER_ID},"token":"${Synth.token(tag)}","token_secret":"${Synth.secret(tag)}"}""",
    ),
)

internal fun rejected(code: String): LoginResponse = LoginResponse(code = code)

/**
 * Login answers come from [byRegion] (keyed by the selector's region at call time, "custom" for none)
 * or, when [queue] is non-empty, from the queue in order. A Throwable entry is thrown.
 */
internal class ScriptedAuthApi(
    private val selector: RegionSelector?,
    private val byRegion: Map<String, Any> = emptyMap(),
    queue: List<Any> = emptyList(),
) : YiCloudAuthApi {
    private val pending = ArrayDeque(queue)
    val loginRegions = mutableListOf<String>()
    var refreshCalls = 0
    val loginCalls: Int get() = loginRegions.size

    override suspend fun login(
        seq: String, account: String, password: String, devName: String, devType: String, devOsVersion: String,
    ): LoginResponse {
        val region = selector?.currentRegion()?.name ?: "custom"
        loginRegions += region
        val step = if (pending.isNotEmpty()) pending.removeFirst()
        else byRegion[region] ?: error("unscripted login call #$loginCalls on $region")
        if (step is Throwable) throw step
        return step as LoginResponse
    }

    override suspend fun refresh(request: RefreshRequest): LoginResponse {
        refreshCalls++
        return rejected("20201")
    }
}

/** Device-list answers keyed by the selector's region at call time. A Throwable entry is thrown. */
internal class ScriptedDeviceCheck(
    private val selector: RegionSelector,
    private val byRegion: Map<String, Any>,
    private val onCheck: suspend () -> Unit = {},
) : DeviceListCheckPort {
    val regions = mutableListOf<String>()
    val tokens = mutableListOf<String>()
    val calls: Int get() = regions.size

    override suspend fun check(session: AuthSession): DeviceListCheck {
        val region = selector.currentRegion()?.name ?: "custom"
        regions += region
        tokens += session.accessToken
        onCheck()
        val step = byRegion[region] ?: error("unscripted device check on $region")
        if (step is Throwable) throw step
        return step as DeviceListCheck
    }

    companion object {
        val EMPTY = DeviceListCheck(DEVICE_LIST_EMPTY_CODE, 0)
        val TWO = DeviceListCheck(DEVICE_LIST_OK_CODE, 2)
    }
}
