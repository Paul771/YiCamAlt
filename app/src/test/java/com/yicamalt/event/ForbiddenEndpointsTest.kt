// FILE: ForbiddenEndpointsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Account-safety guard (EVT-06): no forbidden alert / push / payment endpoint appears as a string
//     literal anywhere in the main sources, and the only alert path literal is v2/alert/list.
//   SCOPE: walks app/src/main/java (.kt / .java), extracts double-quoted literals per line, matches the deny
//     list; proves the matcher on a planted literal; refuses a hollow pass (missing dir or < 100 files).
//   DEPENDS: none (file system of the module under test)
//   LINKS: V-M-EVENT, EVT-06, M-EVENT
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// START_MODULE_MAP
//   ForbiddenEndpointsTest - deny-list scan, alert-path allow-list scan, planted-literal self check.
//   FORBIDDEN - deny-list regexes applied to literal contents.
//   mainSources - resolves app/src/main/java from the Gradle working dir and lists .kt / .java files.
//   literals - (line number, content) of every double-quoted literal of a text.
//   forbiddenHits - "file:line pattern" for every deny-list match in a text.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-01: deny list from 05-RESEARCH §3 (v4/alert/events is a DELETE).
// END_CHANGE_SUMMARY

class ForbiddenEndpointsTest {

    private val forbidden = listOf(
        Regex("v4/alert/events"),
        Regex("alert/get_push"),
        Regex("alert/push_prop"),
        Regex("alert/del_list"),
        Regex("monitor/alarm"),
        Regex("ai/alert"),
        Regex("orderpay"),
        Regex("(^|/)vas/"),
        Regex("cloud/isGive"),
    )

    private val literal = Regex("\"[^\"\n]*\"")

    private fun mainSources(): List<File> {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        requireNotNull(dir) { "app/src/main/java not found from ${File(".").absoluteFile}" }
        return dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    private fun literals(text: String): List<Pair<Int, String>> =
        text.lines().flatMapIndexed { i, line ->
            literal.findAll(line).map { (i + 1) to it.value.removeSurrounding("\"") }.toList()
        }

    private fun forbiddenHits(name: String, text: String): List<String> =
        literals(text).flatMap { (line, content) ->
            forbidden.filter { it.containsMatchIn(content) }.map { "$name:$line ${it.pattern}" }
        }

    @Test
    fun `main sources contain no forbidden endpoint literal`() {
        val files = mainSources()
        assertTrue(files.size >= 100, "only ${files.size} main source files read; refusing a hollow pass")
        val hits = files.flatMap { forbiddenHits(it.name, it.readText()) }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the only alert path literal is v2 alert list`() {
        val files = mainSources()
        assertTrue(files.size >= 100, "only ${files.size} main source files read; refusing a hollow pass")
        val allowed = setOf("v2/alert/list", "/v2/alert/list")
        val offenders = files.flatMap { f ->
            literals(f.readText())
                .filter { (_, content) -> content.contains("alert/") && content !in allowed }
                .map { (line, _) -> "${f.name}:$line" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `forbidden guard catches a planted literal`() {
        val snippet = "const val X = \"/v4/alert/events\"\nval ok = \"v2/alert/list\"\nval p = \"/vas/x\""
        val hits = forbiddenHits("Planted.kt", snippet)
        assertEquals(2, hits.size, hits.toString())
        assertTrue(hits.any { it.startsWith("Planted.kt:1 ") })
        assertTrue(hits.any { it.startsWith("Planted.kt:3 ") })
    }
}
