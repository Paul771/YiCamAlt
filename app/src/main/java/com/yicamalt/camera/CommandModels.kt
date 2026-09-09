// FILE: CommandModels.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Camera command model + pure PTZ normalization for M-CAMERA-CMD.
//   SCOPE: CameraCommandType enum, CameraCommand payload, PtzNormalizer (joystick -> angles).
//   DEPENDS: none
//   LINKS: M-CAMERA-CMD
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import kotlinx.serialization.Serializable

// START_MODULE_MAP
//   CameraCommandType - PTZ / IR / SOUND / CAPTURE / RECORDING.
//   CameraCommand - typed command value sent to a device.
//   PtzNormalizer - maps joystick delta [-1..1] to pan/tilt degrees.
// END_MODULE_MAP

enum class CameraCommandType { PTZ, IR, SOUND, CAPTURE, RECORDING_MODE }

/**
 * A normalized camera command.
 * @param ptzPan / ptzTilt only meaningful for PTZ (degrees).
 * @param on only meaningful for toggles (IR / SOUND / RECORDING_MODE).
 */
data class CameraCommand(
    val type: CameraCommandType,
    val ptzPan: Int = 0,
    val ptzTilt: Int = 0,
    val on: Boolean = false,
)

/** Pure PTZ mapping: joystick delta in [-1, 1] to pan/tilt degrees in [-90, 90]. */
object PtzNormalizer {
    private const val MAX_DEGREES = 90
    fun panTilt(joystickDx: Float, joystickDy: Float): Pair<Int, Int> {
        val pan = (joystickDx.coerceIn(-1f, 1f) * MAX_DEGREES).toInt()
        val tilt = (joystickDy.coerceIn(-1f, 1f) * MAX_DEGREES).toInt()
        return pan to tilt
    }
}

/** Wire payload for the (provisional) cloud command transport. */
@Serializable
data class CommandPayload(
    val device_id: String,
    val command: String,
    val params: Map<String, String> = emptyMap(),
)