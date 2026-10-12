// FILE: DiagnosticLogTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: JVM proof that the diagnostic log lives in internal storage only, rotates, and legacy auth_log.txt copies are purged (SEC-02).
//   SCOPE: DiagnosticLog.internalFile, DiagnosticLog.purgeLegacy, FileLogTree rotation.
//   DEPENDS: M-CONFIG (YiCamAltApp.kt: DiagnosticLog, FileLogTree)
//   LINKS: M-CONFIG, SEC-02
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.io.File

// START_MODULE_MAP
//   internalFile resolves to filesDir - path assertion
//   purgeLegacy deletes internal and external - both legacy copies removed, count 2
//   purgeLegacy with nothing present - count 0, null externalDir tolerated
//   FileLogTree rotates past maxBytes - current file shrinks, single .1 backup kept
// END_MODULE_MAP

class DiagnosticLogTest {

    // Own temp dir: JUnit's @TempDir fails the test when Windows briefly locks a just-written file on cleanup.
    private val tmp: File = java.nio.file.Files.createTempDirectory("diag").toFile()

    @AfterEach
    fun cleanup() {
        runCatching { tmp.deleteRecursively() }
    }

    @Test
    fun `internalFile resolves to filesDir diag_log txt`() {
        val filesDir = File(tmp, "files").apply { mkdirs() }
        assertEquals(File(filesDir, "diag_log.txt"), DiagnosticLog.internalFile(filesDir))
    }

    @Test
    fun `purgeLegacy deletes internal and external auth_log copies`() {
        val filesDir = File(tmp, "files").apply { mkdirs() }
        val extDir = File(tmp, "ext").apply { mkdirs() }
        File(filesDir, "auth_log.txt").writeText("old")
        File(extDir, "auth_log.txt").writeText("old")

        assertEquals(2, DiagnosticLog.purgeLegacy(filesDir, extDir))
        assertFalse(File(filesDir, "auth_log.txt").exists())
        assertFalse(File(extDir, "auth_log.txt").exists())
    }

    @Test
    fun `purgeLegacy returns zero when nothing present and tolerates null externalDir`() {
        val filesDir = File(tmp, "files").apply { mkdirs() }
        assertEquals(0, DiagnosticLog.purgeLegacy(filesDir, null))
        File(filesDir, "auth_log.txt").writeText("old")
        assertEquals(1, DiagnosticLog.purgeLegacy(filesDir, null))
    }

    @Test
    fun `FileLogTree rotates past maxBytes and keeps one backup`() {
        val file = File(tmp, "diag_log.txt")
        val tree = FileLogTree(file, maxBytes = 200)
        repeat(20) { i -> tree.d("line number %d with some padding text", i) }

        assertTrue(file.length() < 200, "current file must be below maxBytes, was ${file.length()}")
        assertTrue(File(file.path + ".1").exists(), "rotated backup must exist")
        assertFalse(File(file.path + ".2").exists(), "at most one rotated file")
        val backups = tmp.listFiles()!!.filter { it.name.startsWith("diag_log.txt.") }
        assertEquals(1, backups.size)
    }
}
