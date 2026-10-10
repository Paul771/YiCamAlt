// FILE: YiCamAltApp.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Application entry point that initializes Hilt DI graph and Timber logging.
//   SCOPE: Hilt bootstrapping, Timber plant (debug only), internal-only rotating diagnostic log,
//     purge of legacy auth_log.txt copies, global config init.
//   DEPENDS: M-CONFIG
//   LINKS: M-CONFIG
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// START_MODULE_MAP
//   YiCamAltApp - Hilt-annotated Application; plants Timber debug tree + internal file logger (debug only).
//   DiagnosticLog - internal diag file resolution (filesDir/diag_log.txt) + legacy auth_log.txt purge.
//   FileLogTree - Timber tree appending log lines to a file, rotating to <file>.1 past maxBytes.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - SEC-02: diagnostic log is internal-only (filesDir/diag_log.txt, 1 MiB
//     rotation); external auth_log.txt planting removed and legacy copies purged on start.
//     Pull with: adb exec-out run-as com.yicamalt cat files/diag_log.txt
//   PREV_CHANGE: v0.1.1 - FileLogTree wrote to BOTH internal filesDir and external files dir
//     (MTP-readable without root); reverted by v0.2.0 because it leaked credentials once.
// END_CHANGE_SUMMARY

@HiltAndroidApp
class YiCamAltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // START_BLOCK_INIT_APP
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String =
                    "YiCamAlt/${super.createStackElementTag(element)}"
            })
            // Internal-only diagnostic log (SEC-02). Never plant a tree on external storage:
            // it is MTP-readable without root and leaked a password once (PROJECT_STATUS 14).
            Timber.plant(FileLogTree(DiagnosticLog.internalFile(filesDir)))
            val purged = DiagnosticLog.purgeLegacy(filesDir, getExternalFilesDir(null))
            Timber.d("[App][init][BLOCK_INIT_APP] diagLog=internal legacyPurged=$purged")
        }
        // END_BLOCK_INIT_APP
    }
}

/** Internal-only diagnostic log location and cleanup of the legacy leaked log files. */
internal object DiagnosticLog {
    const val FILE_NAME = "diag_log.txt"
    const val LEGACY_FILE_NAME = "auth_log.txt"
    const val MAX_BYTES = 1_048_576L

    fun internalFile(filesDir: File): File = File(filesDir, FILE_NAME)

    // START_CONTRACT: purgeLegacy
    //   PURPOSE: Delete legacy auth_log.txt copies from internal and external app dirs.
    //   INPUTS: { filesDir: File - internal app dir, externalDir: File? - external files dir or null }
    //   OUTPUTS: { Int - number of files actually deleted }
    //   SIDE_EFFECTS: deletes files on disk
    //   LINKS: SEC-02
    // END_CONTRACT: purgeLegacy
    fun purgeLegacy(filesDir: File, externalDir: File?): Int =
        listOfNotNull(filesDir, externalDir)
            .map { File(it, LEGACY_FILE_NAME) }
            .count { it.exists() && it.delete() }
}

/**
 * Timber tree that appends every log line to a file in internal storage.
 * When the next line would push the file past maxBytes, the file is moved to
 * file.1 (replacing any older backup), so at most two files exist.
 */
class FileLogTree(
    private val file: File,
    private val maxBytes: Long = DiagnosticLog.MAX_BYTES,
) : Timber.Tree() {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(" ")
            append(level)
            append("/")
            append(tag ?: "YiCamAlt")
            append(": ")
            append(message)
            t?.let { append("\n").append(Log.getStackTraceString(it)) }
            append("\n")
        }
        // START_BLOCK_WRITE_DIAG_LOG
        try {
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (file.length() + bytes.size > maxBytes) {
                val backup = File(file.path + ".1")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            FileOutputStream(file, true).use { it.write(bytes) }
        } catch (e: IOException) {
            // Never crash the app because logging failed.
        }
        // END_BLOCK_WRITE_DIAG_LOG
    }
}