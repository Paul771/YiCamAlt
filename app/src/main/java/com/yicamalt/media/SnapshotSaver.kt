// FILE: SnapshotSaver.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Save a live-view frame as JPEG into the gallery (Pictures/YiCamAlt on API 29+, Pictures on 26-28).
//   SCOPE: named outcomes (SnapshotResult), pure storage rules (SnapshotSpec), the SnapshotStore port,
//     its MediaStore implementation and the Hilt binding. No EXIF / metadata is written (Bitmap.compress).
//   DEPENDS: Android MediaStore / ContentResolver, androidx.core ContextCompat, kotlinx.coroutines, Timber
//   LINKS: M-MEDIA-SNAPSHOT, V-M-MEDIA-SNAPSHOT
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// START_MODULE_MAP
//   SnapshotResult - Saved | NotReady | PermissionDenied | WriteFailed(errorClass)
//   SnapshotSpec - RELATIVE_PATH, MIME, JPEG_QUALITY, Target, fileName(time), target(sdkInt)
//   SnapshotStore - port: suspend save(bitmap): SnapshotResult (takes ownership of the bitmap)
//   MediaStoreSnapshotStore - MediaStore implementation; deletes the gallery row on any failed write
//   SnapshotModule - Hilt @Binds SnapshotStore -> MediaStoreSnapshotStore
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-09: created (LIVE-08). Log never carries URI, path, file or camera name.
// END_CHANGE_SUMMARY

sealed interface SnapshotResult {
    object Saved : SnapshotResult
    object NotReady : SnapshotResult
    object PermissionDenied : SnapshotResult
    data class WriteFailed(val errorClass: String) : SnapshotResult
}

object SnapshotSpec {
    const val RELATIVE_PATH = "Pictures/YiCamAlt"
    const val MIME = "image/jpeg"
    const val JPEG_QUALITY = 90
    private const val SCOPED_STORAGE_SDK = 29

    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    data class Target(val relativePath: String?, val usePending: Boolean, val needsLegacyPermission: Boolean)

    // START_CONTRACT: fileName
    //   PURPOSE: Gallery display name for a snapshot taken at local time [time].
    //   INPUTS: { time: LocalDateTime - local wall-clock time }
    //   OUTPUTS: { String - "YiCamAlt_yyyyMMdd_HHmmss.jpg", never camera name or id }
    //   SIDE_EFFECTS: none
    //   LINKS: M-MEDIA-SNAPSHOT
    // END_CONTRACT: fileName
    fun fileName(time: LocalDateTime): String = "YiCamAlt_" + NAME_FORMAT.format(time) + ".jpg"

    // START_CONTRACT: target
    //   PURPOSE: Per-API storage target: scoped path + IS_PENDING on 29+, legacy permission on 26-28.
    //   INPUTS: { sdkInt: Int - Build.VERSION.SDK_INT }
    //   OUTPUTS: { Target }
    //   SIDE_EFFECTS: none
    //   LINKS: M-MEDIA-SNAPSHOT
    // END_CONTRACT: target
    fun target(sdkInt: Int): Target =
        if (sdkInt >= SCOPED_STORAGE_SDK) {
            Target(relativePath = RELATIVE_PATH, usePending = true, needsLegacyPermission = false)
        } else {
            Target(relativePath = null, usePending = false, needsLegacyPermission = true)
        }
}

interface SnapshotStore {
    suspend fun save(bitmap: Bitmap): SnapshotResult
}

class MediaStoreSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SnapshotStore {

    // START_CONTRACT: save
    //   PURPOSE: Write [bitmap] as JPEG into the gallery; a failed write leaves no gallery row.
    //   INPUTS: { bitmap: Bitmap - full frame; ownership passes to the store (always recycled) }
    //   OUTPUTS: { SnapshotResult - Saved | PermissionDenied | WriteFailed(class) }
    //   SIDE_EFFECTS: MediaStore insert/update/delete on Dispatchers.IO; one Timber line
    //   LINKS: M-MEDIA-SNAPSHOT, V-M-MEDIA-SNAPSHOT
    // END_CONTRACT: save
    override suspend fun save(bitmap: Bitmap): SnapshotResult = withContext(Dispatchers.IO) {
        val sdk = Build.VERSION.SDK_INT
        var bytes = 0L
        val result = try {
            // START_BLOCK_SAVE_SNAPSHOT
            val t = SnapshotSpec.target(sdk)
            if (t.needsLegacyPermission &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                SnapshotResult.PermissionDenied
            } else {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, SnapshotSpec.fileName(LocalDateTime.now()))
                    put(MediaStore.Images.Media.MIME_TYPE, SnapshotSpec.MIME)
                    if (t.relativePath != null) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, t.relativePath)
                        if (t.usePending) put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                var insertError: String? = null
                val uri: Uri? = try {
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    insertError = e.javaClass.simpleName
                    null
                }
                if (uri == null) {
                    SnapshotResult.WriteFailed(insertError ?: "insert")
                } else {
                    try {
                        val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("no output stream")
                        val counting = CountingOutputStream(out)
                        val ok = counting.use { bitmap.compress(Bitmap.CompressFormat.JPEG, SnapshotSpec.JPEG_QUALITY, it) }
                        bytes = counting.count
                        if (!ok) {
                            runCatching { resolver.delete(uri, null, null) }
                            SnapshotResult.WriteFailed("compress")
                        } else {
                            if (t.usePending) {
                                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                            }
                            SnapshotResult.Saved
                        }
                    } catch (e: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        SnapshotResult.WriteFailed(e.javaClass.simpleName)
                    }
                }
            }
            // END_BLOCK_SAVE_SNAPSHOT
        } finally {
            bitmap.recycle()
        }
        Timber.i("[Media][snapshot][BLOCK_SAVE_SNAPSHOT] result=%s sdk=%d bytes=%d", resultName(result), sdk, bytes)
        result
    }

    private fun resultName(r: SnapshotResult): String = when (r) {
        SnapshotResult.Saved -> "Saved"
        SnapshotResult.NotReady -> "NotReady"
        SnapshotResult.PermissionDenied -> "PermissionDenied"
        is SnapshotResult.WriteFailed -> "WriteFailed(" + r.errorClass + ")"
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SnapshotModule {

    @Binds
    @Singleton
    abstract fun bindSnapshotStore(impl: MediaStoreSnapshotStore): SnapshotStore
}
