// FILE: FakeYiApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: In-memory Yi Cloud API mock with scripted responses and failure injection.
//   SCOPE: account/login, user/camera/list, camera/event/list, camera/control, camera/live/key endpoints.
//   DEPENDS: none
//   LINKS: TraceRecorder
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import kotlinx.coroutines.delay

// START_MODULE_MAP
//   FakeYiApi - scripted Yi Cloud API mock.
//   Response - queued response for an endpoint.
//   ApiError - simulated failure (http code or network exception).
// END_MODULE_MAP

class FakeYiApi {

    sealed class Outcome {
        data class Body(val json: String, val code: Int = 200) : Outcome()
        data class Http(val code: Int) : Outcome()
        data class Failure(val error: Throwable) : Outcome()
        data class Delay(val ms: Long, val then: Outcome) : Outcome()
    }

    private val scripted = mutableMapOf<String, ArrayDeque<Outcome>>()
    private val recordedCalls = mutableListOf<Pair<String, String?>>()

    /** Register an ordered sequence of outcomes for an endpoint path. */
    fun stub(path: String, vararg outcomes: Outcome) {
        scripted[path] = ArrayDeque(outcomes.toList())
    }

    fun calls(): List<Pair<String, String?>> = recordedCalls.toList()

    /** Resolve the next outcome for a path. Defaults to 404 if none scripted. */
    suspend fun respond(path: String, body: String? = null): Outcome {
        recordedCalls += path to body
        val queue = scripted[path]
        val outcome = queue?.removeFirstOrNull() ?: Outcome.Http(404)
        if (outcome is Outcome.Delay) {
            delay(outcome.ms)
            return outcome.then
        }
        return outcome
    }

    // START_BLOCK_FIXTURES
    fun loginSuccessBody(token: String, refresh: String) =
        """{"code":200,"data":{"access_token":"$token","refresh_token":"$refresh","expires_in":3600,"user_id":"u-1"}}"""

    fun loginInvalidBody() = """{"code":401,"message":"invalid credentials"}"""

    fun cameraListBody() =
        """{"code":200,"data":[{"device_id":"cam-1","name":"Living Room","model":"YI_HOME","online":true,"stream_url":"https://stream/cam-1"}]}"""
    // END_BLOCK_FIXTURES
}