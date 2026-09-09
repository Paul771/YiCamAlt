// FILE: YiCamAltApp.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Application entry point that initializes Hilt DI graph and Timber logging.
//   SCOPE: Hilt bootstrapping, Timber plant, global config init.
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
//   YiCamAltApp - Hilt-annotated Application; plants Timber debug tree + file logger.
//   FileLogTree - Timber tree that appends every log line to <filesDir>/auth_log.txt.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - FileLogTree now writes to BOTH internal filesDir and
//     getExternalFilesDir(/sdcard/Android/data/com.yicamalt/files) so auth_log.txt can be
//     pulled via USB MTP without root (user had no root access to /data/data).
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
            // Internal log: private dir, readable only with root/IDE.
            Timber.plant(FileLogTree(File(filesDir, "auth_log.txt")))
            // External log: /sdcard/Android/data/com.yicamalt/files/auth_log.txt —
            // readable via USB (MTP) from a PC WITHOUT root. Primary artifact for auth debugging.
            getExternalFilesDir(null)?.let { extDir ->
                Timber.plant(FileLogTree(File(extDir, "auth_log.txt")))
            }
        }
        // END_BLOCK_INIT_APP
    }
}

/**
 * Timber tree that appends every log line to a file so logs can be pulled
 * from the device without adb. Writes to <filesDir>/auth_log.txt.
 */
class FileLogTree(private val file: File) : Timber.Tree() {

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
        try {
            FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            // Never crash the app because logging failed.
        }
    }
}