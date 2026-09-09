// FILE: CameraRegistry.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Fetch and cache the user's Yi cameras from the cloud; resolve live stream URLs.
//   SCOPE: getCameraList (fetch + Room cache), getCameraById, cacheCameraList, updateCameraStatus,
//     getLiveStreamUrl; emits BLOCK_FETCH_CAMERA_LIST / BLOCK_RESOLVE_STREAM_URL trace markers.
//   DEPENDS: M-HTTP (YiCloudDeviceApi), M-LOCAL-DB (CameraDao)
//   LINKS: M-CAMERA-LIST, M-UI-SHELL
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.database.CameraDao
import com.yicamalt.database.CameraEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   CameraRegistry - facade over cloud device API + Room cache.
//   CameraError - typed errors (FetchFailed, Unauthorized, NotFound, NoStreamUrl).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-2 M-CAMERA-LIST. Device-list field mapping is
//     provisional (CameraListParser candidate keys); stop if a live capture contradicts it.
// END_CHANGE_SUMMARY

sealed class CameraError(message: String) : Error(message) {
    object FetchFailed : CameraError("CAMERA_FETCH_FAILED: cloud device list unavailable")
    object Unauthorized : CameraError("CAMERA_UNAUTHORIZED: session rejected by device API")
    object NotFound : CameraError("CAMERA_NOT_FOUND: no such device id")
    object NoStreamUrl : CameraError("CAMERA_NO_STREAM_URL: stream url not resolved for this device")
}

/** Thin contract consumed by the M-UI camera list so the view-model needs no network/DAO. */
interface CameraListPort {
    suspend fun getCameraList(): List<CameraInfo>
}

@Singleton
class CameraRegistry @Inject constructor(
    private val api: YiCloudDeviceApi,
    private val cameraDao: CameraDao,
) : CameraListPort {
    /** Fetch the device list from the cloud and refresh the Room cache. */
    override suspend fun getCameraList(): List<CameraInfo> = withContext(Dispatchers.IO) {
        // START_BLOCK_FETCH_CAMERA_LIST
        Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] fetching device list")
        val envelope = try {
            api.deviceList()
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401) throw CameraError.Unauthorized
            throw CameraError.FetchFailed
        } catch (e: IOException) {
            throw CameraError.FetchFailed
        }
        if (envelope.code in AUTH_ERROR_CODES) throw CameraError.Unauthorized
        val cameras = CameraListParser.extract(envelope.data)
        Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=${cameras.size} code=${envelope.code}")
        cacheCameraList(cameras)
        cameras
        // END_BLOCK_FETCH_CAMERA_LIST
    }

    private companion object {
        val AUTH_ERROR_CODES = setOf("20201", "20203", "20205", "20253", "40110")
    }

    /** Look up a single camera from the cache. */
    suspend fun getCameraById(deviceId: String): CameraInfo? = withContext(Dispatchers.IO) {
        cameraDao.getById(deviceId)?.toInfo()
    }

    /** Overwrite the Room cache with the given list. */
    suspend fun cacheCameraList(cameras: List<CameraInfo>) {
        if (cameras.isEmpty()) return
        cameraDao.deleteAll()
        cameras.forEach { cameraDao.insertCamera(it.toEntity(System.currentTimeMillis())) }
    }

    /** Refresh the persisted online/offline flag (e.g. from an FCM status push). */
    suspend fun updateCameraStatus(deviceId: String, online: Boolean): CameraInfo? = withContext(Dispatchers.IO) {
        val row = cameraDao.getById(deviceId) ?: return@withContext null
        val updated = row.copy(online = online, last_seen = System.currentTimeMillis())
        cameraDao.insertCamera(updated)
        updated.toInfo()
    }

    /** Resolve the live stream URL for a camera. Uses the server-provided url when present. */
    suspend fun getLiveStreamUrl(deviceId: String): String = withContext(Dispatchers.IO) {
        // START_BLOCK_RESOLVE_STREAM_URL
        val cam = cameraDao.getById(deviceId)?.toInfo() ?: throw CameraError.NotFound
        val url = cam.streamUrl?.takeIf { it.isNotBlank() }
            ?: throw CameraError.NoStreamUrl
        Timber.d("[Camera][resolveUrl][BLOCK_RESOLVE_STREAM_URL] device=$deviceId url=***")
        url
        // END_BLOCK_RESOLVE_STREAM_URL
    }

    private fun CameraEntity.toInfo(): CameraInfo =
        CameraInfo(device_id, name, model, online, stream_url)

    private fun CameraInfo.toEntity(now: Long): CameraEntity =
        CameraEntity(deviceId, name, model, online, streamUrl, now, now)
}