// FILE: RedactionScanner.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Scan a captured trace buffer for forbidden substrings (tokens, passwords, AES keys, PII).
//   SCOPE: pure string scanning with regex + literal forbidden patterns. One match fails the gate.
//   DEPENDS: none
//   LINKS: TraceRecorder
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

// START_MODULE_MAP
//   RedactionScanner - asserts no secret pattern appears in a captured log buffer.
//   ForbiddenMatch - a single leak hit: pattern, matched substring, index.
// END_MODULE_MAP

class RedactionScanner {

    data class ForbiddenMatch(val pattern: String, val matched: String, val index: Int)

    /** Literal forbidden words. Case-insensitive substring match. */
    val forbiddenLiterals = listOf(
        "password", "authorization", "secret", "aeskey", "aes_key", "accesstoken", "refreshtoken",
    )

    /** Regex patterns for structured secrets / PII. */
    val forbiddenPatterns = listOf(
        Regex("Bearer\\s+[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), // email
        Regex("\\b\\d{10,}\\b"),                                    // long digit runs (tokens / serials)
    )

    /** Extra literals to add for a specific test (e.g. a known token value). */
    fun scan(
        buffer: String,
        extraLiterals: List<String> = emptyList(),
        extraPatterns: List<Regex> = emptyList(),
    ): List<ForbiddenMatch> {
        val hits = mutableListOf<ForbiddenMatch>()
        val lower = buffer.lowercase()
        (forbiddenLiterals + extraLiterals.map { it.lowercase() }).forEach { lit ->
            val idx = lower.indexOf(lit)
            if (idx >= 0) hits += ForbiddenMatch("literal:$lit", buffer.substring(idx, idx + lit.length), idx)
        }
        (forbiddenPatterns + extraPatterns).forEach { rx ->
            rx.find(buffer)?.let { m -> hits += ForbiddenMatch("regex:${rx.pattern}", m.value, m.range.first) }
        }
        return hits
    }

    /** Assert the buffer is clean. Throws with every hit if not. */
    fun assertClean(
        buffer: String,
        extraLiterals: List<String> = emptyList(),
        extraPatterns: List<Regex> = emptyList(),
    ) {
        val hits = scan(buffer, extraLiterals, extraPatterns)
        check(hits.isEmpty()) {
            "RedactionScanner found forbidden substrings in logs:\n" +
                hits.joinToString("\n") { "  - ${it.pattern}: '${it.matched}' @${it.index}" }
        }
    }
}