// FILE: CameraCommandService.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Validate + dispatch camera commands (PTZ/IR/sound/capture/recording).
//   SCOPE: model-support and online checks against the camera cache, PTZ normalization,
//     then delivery through a CommandTransport seam. Emits BLOCK_VALIDATE_COMMAND /
//     BLOCK_SEND_COMMAND markers.
//   DEPENDS: M-CAMERA-CMD, M-CAMERA-LIST (camera status), M-HTTP (transport)
//   LINKS: M-CAMERA-CMD, M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.database.CameraDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   CameraCommandService - facade validating + dispatching camera commands.
//   CameraCommandError - typed errors (UnsupportedModel, CameraOffline, SendFailed, Timeout).
//   CommandTransport - delivery seam; CommandTransportRetrofit is the provisional impl.
// END_MODULE_MAP

sealed class CameraCommandError(message: String) : Error(message) {
    object UnsupportedModel : CameraCommandError("CMD_UNSUPPORTED_MODEL: command not supported by this camera")
    object CameraOffline : CameraCommandError("CMD_CAMERA_OFFLINE: camera is not online")
    object SendFailed : CameraCommandError("CMD_SEND_FAILED: command transport rejected the request")
    object Timeout : CameraCommandError("CMD_TIMEOUT: no acknowledgement within budget")
}

/** Delivery seam so command routing is testable without a live cloud control surface. */
interface CommandTransport {
    /** Returns true when the cloud acknowledged the command. */
    suspend fun send(payload: CommandPayload): Boolean
}

/** Provisional transport: POSTs to a reverse-engineered-style control endpoint.
 *  Path and acknowledgement semantics are unconfirmed; stop if a live capture contradicts it. */
class CommandTransportRetrofit(private val api: YiCloudCommandApi) : CommandTransport {
    override suspend fun send(payload: CommandPayload): Boolean =
        try {
            val envelope = api.control(payload)
            envelope.code == "20000"
        } catch (e: retrofit2.HttpException) {
            false
        } catch (e: IOException) {
            false
        }
}

@Singleton
class CameraCommandService @Inject constructor(
    private val cameraDao: CameraDao,
    private val transport: CommandTransport,
) {
    /** Send a PTZ rotation. joystickDx/Dy in [-1, 1]. */
    suspend fun sendPTZ(deviceId: String, joystickDx: Float, joystickDy: Float) {
        val (pan, tilt) = PtzNormalizer.panTilt(joystickDx, joystickDy)
        dispatch(deviceId, CameraCommand(CameraCommandType.PTZ, ptzPan = pan, ptzTilt = tilt))
    }

    suspend fun toggleIR(deviceId: String, on: Boolean) =
        dispatch(deviceId, CameraCommand(CameraCommandType.IR, on = on))

    suspend fun toggleSound(deviceId: String, on: Boolean) =
        dispatch(deviceId, CameraCommand(CameraCommandType.SOUND, on = on))

    suspend fun capturePhoto(deviceId: String) =
        dispatch(deviceId, CameraCommand(CameraCommandType.CAPTURE))

    suspend fun setRecordingMode(deviceId: String, on: Boolean) =
        dispatch(deviceId, CameraCommand(CameraCommandType.RECORDING_MODE, on = on))

    private suspend fun dispatch(deviceId: String, command: CameraCommand) = withContext(Dispatchers.IO) {
        // START_BLOCK_VALIDATE_COMMAND
        Timber.d("[CameraCmd][dispatch][BLOCK_VALIDATE_COMMAND] device=$deviceId cmd=${command.type}")
        val camera = cameraDao.getById(deviceId) ?: throw CameraCommandError.UnsupportedModel
        if (!camera.online) throw CameraCommandError.CameraOffline
        if (command.type == CameraCommandType.PTZ && !supportsPtz(camera.model)) {
            throw CameraCommandError.UnsupportedModel
        }
        // END_BLOCK_VALIDATE_COMMAND

        // START_BLOCK_SEND_COMMAND
        Timber.d("[CameraCmd][dispatch][BLOCK_SEND_COMMAND] device=$deviceId cmd=${command.type} pan=${command.ptzPan} tilt=${command.ptzTilt}")
        val payload = CommandPayload(
            device_id = deviceId,
            command = command.type.name.lowercase(),
            params = commandParams(command),
        )
        val acked = transport.send(payload)
        // END_BLOCK_SEND_COMMAND
        if (!acked) throw CameraCommandError.SendFailed
    }

    private fun commandParams(command: CameraCommand): Map<String, String> = when (command.type) {
        CameraCommandType.PTZ -> mapOf("pan" to command.ptzPan.toString(), "tilt" to command.ptzTilt.toString())
        CameraCommandType.IR, CameraCommandType.SOUND, CameraCommandType.RECORDING_MODE ->
            mapOf("enabled" to command.on.toString())
        CameraCommandType.CAPTURE -> emptyMap()
    }

    private fun supportsPtz(model: String): Boolean =
        model.contains("dome", ignoreCase = true) || model.contains("360", ignoreCase = true) || model.contains("ptz", ignoreCase = true)
}