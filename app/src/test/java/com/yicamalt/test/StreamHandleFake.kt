// FILE: StreamHandleFake.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Fake StreamHandle that emits scripted StreamState transitions + fps/latency for UI tests.
//   SCOPE: queue + emit state transitions; expose measured fps/latency without a real video pipeline.
//   DEPENDS: none
//   LINKS: M-STREAM-LIVE, M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

// START_MODULE_MAP
//   StreamHandleFake - scripted stream state machine for UI tests.
//   State - connecting | playing | error | stopped.
// END_MODULE_MAP

class StreamHandleFake {

    enum class State { CONNECTING, PLAYING, ERROR, STOPPED }

    private val transitions = ArrayDeque<State>()
    private var current = State.STOPPED
    var fps: Int = 0
        private set
    var latencyMs: Int = 0
        private set

    fun enqueue(vararg states: State) { states.forEach { transitions += it } }

    fun tick(): State {
        current = transitions.removeFirstOrNull() ?: current
        if (current == State.PLAYING) { fps = 18; latencyMs = 900 }
        return current
    }

    fun current(): State = current
    fun isPlaying(): Boolean = current == State.PLAYING
}