// FILE: TraceRecorder.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Capture Timber log output into an ordered list of [Module][fn][BLOCK] markers for trajectory assertions.
//   SCOPE: plant/unplant a Timber Tree; record markers; assert presence, absence, ordered sequences, retry bounds.
//   DEPENDS: none (test-only)
//   LINKS: RedactionScanner
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

// START_MODULE_MAP
//   TraceRecorder - records ordered BLOCK markers + structured fields from Timber.
//   Marker - single captured log entry: tag, message, fields, timestamp.
// END_MODULE_MAP

class TraceRecorder {

    data class Marker(
        val tag: String,
        val message: String,
        val fields: Map<String, String>,
        val index: Int,
    )

    private val captured = CopyOnWriteArrayList<Marker>()

    fun plant() {
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += Marker(tag ?: "?", message ?: "", parseFields(message), captured.size)
            }
        })
    }

    fun unplant() { Timber.forest().forEach { Timber.uproot(it) } }
    fun reset() { captured.clear() }
    fun all(): List<Marker> = captured.toList()

    /** Extract `[Module][fn][BLOCK_NAME]` prefix plus key=value fields from a log line. */
    private fun parseFields(message: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val bracketed = Regex("""\[([^\]]+)]""").findAll(message).map { it.groupValues[1] }.toList()
        out["markers"] = bracketed.joinToString("->")
        Regex("""(\w+)=("?[^\s,]+"?)""").findAll(message).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    // START_BLOCK_ASSERT_API
    fun assertMarkerAppeared(block: String) {
        check(captured.any { it.message.contains("[$block]") || it.fields["markers"]?.contains(block) == true }) {
            "Expected BLOCK $block to appear in trace. Captured:\n${captured.joinToString("\n")}"
        }
    }

    fun assertMarkerAbsent(block: String) {
        check(captured.none { it.message.contains("[$block]") || it.fields["markers"]?.contains(block) == true }) {
            "Forbidden BLOCK $block appeared in trace."
        }
    }

    fun assertSequence(vararg blocks: String) {
        val markers = captured.mapNotNull { m ->
            Regex("""\[([^\]]+)]""").findAll(m.message).map { it.groupValues[1] }.toList()
        }.flatten()
        val joined = markers.joinToString("->")
        val expected = blocks.joinToString("->")
        check(joined.contains(expected)) {
            "Expected sequence $expected not found in trace. Got: $joined"
        }
    }

    fun assertMaxRetries(retryBlock: String, max: Int) {
        val count = captured.count { it.message.contains("[$retryBlock]") }
        check(count <= max) { "Block $retryBlock appeared $count times, expected <= $max" }
    }
    // END_BLOCK_ASSERT_API

    fun bufferText(): String = captured.joinToString("\n") { "${it.tag} | ${it.message}" }
}