// FILE: CameraCommandModuleTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify M-CAMERA-CMD: PTZ normalization, offline/unsupported gating, dispatch payloads.
//   SCOPE: success + failure scenarios for V-M-CAMERA-CMD; trace marker assertions.
//   DEPENDS: M-CAMERA-CMD, TestInfrastructure
//   LINKS: V-M-CAMERA-CMD
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.database.CameraDao
import com.yicamalt.database.CameraEntity
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** In-memory CameraDao fake mirroring Room REPLACE-on-PK. */
private class CmdDaoFake : CameraDao() {
    val stored = mutableListOf<CameraEntity>()
    override fun getAll(): List<CameraEntity> = stored.toList()
    override fun getById(deviceId: String): CameraEntity? = stored.find { it.device_id == deviceId }
    override fun deleteAll() { stored.clear() }
    override fun count(): Int = stored.size
    override fun insertCameraRaw(entity: CameraEntity) {
        stored.removeAll { it.device_id == entity.device_id }
        stored += entity
    }
}

/** Captures payloads instead of hitting the network. */
private class FakeTransport : CommandTransport {
    val sent = mutableListOf<CommandPayload>()
    var ack = true
    override suspend fun send(payload: CommandPayload): Boolean {
        sent += payload
        return ack
    }
}

class CameraCommandModuleTest {

    private val recorder = TraceRecorder()
    private val dao = CmdDaoFake()
    private val transport = FakeTransport()
    private lateinit var service: CameraCommandService

    @BeforeEach fun setUp() {
        recorder.plant()
        service = CameraCommandService(dao, transport)
        dao.insertCamera(CameraEntity("dome-1", "Living Room", "YI_DOME", true, "https://s/dome-1", 0, 0))
        dao.insertCamera(CameraEntity("bulb-1", "Hall", "YI_BULB", true, null, 0, 0))
        dao.insertCamera(CameraEntity("off-1", "Yard", "YI_DOME", false, null, 0, 0))
    }

    @AfterEach fun tearDown() { recorder.unplant() }

    @Test
    fun `scenario_1 PTZ command normalized and delivered`() {
        runBlocking { service.sendPTZ("dome-1", 1f, -0.5f) }
        assertEquals(1, transport.sent.size)
        val payload = transport.sent.first()
        assertEquals("dome-1", payload.device_id)
        assertEquals("ptz", payload.command)
        assertEquals("90", payload.params["pan"])
        assertEquals("-45", payload.params["tilt"])
        recorder.assertMarkerAppeared("BLOCK_VALIDATE_COMMAND")
        recorder.assertMarkerAppeared("BLOCK_SEND_COMMAND")
    }

    @Test
    fun `scenario_1b zero joystick yields zero angles`() {
        runBlocking { service.sendPTZ("dome-1", 0f, 0f) }
        assertEquals("0", transport.sent.first().params["pan"])
        assertEquals("0", transport.sent.first().params["tilt"])
    }

    @Test
    fun `scenario_2 IR toggle delivered with enabled flag`() {
        runBlocking { service.toggleIR("dome-1", true) }
        assertEquals("ir", transport.sent.first().command)
        assertEquals("true", transport.sent.first().params["enabled"])
    }

    @Test
    fun `scenario_3 PTZ on non-ptz model is rejected before send`() {
        assertThrows(CameraCommandError.UnsupportedModel::class.java) {
            runBlocking { service.sendPTZ("bulb-1", 0.5f, 0.5f) }
        }
        assertTrue(transport.sent.isEmpty(), "no transport call must happen for unsupported model")
    }

    @Test
    fun `scenario_4 command to offline camera is rejected`() {
        assertThrows(CameraCommandError.CameraOffline::class.java) {
            runBlocking { service.toggleIR("off-1", true) }
        }
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `scenario_5 transport rejection maps to SendFailed`() {
        transport.ack = false
        assertThrows(CameraCommandError.SendFailed::class.java) {
            runBlocking { service.capturePhoto("dome-1") }
        }
    }

    @Test
    fun `scenario_6 unknown device maps to UnsupportedModel`() {
        assertThrows(CameraCommandError.UnsupportedModel::class.java) {
            runBlocking { service.setRecordingMode("ghost-1", true) }
        }
    }

    @Test
    fun `pure normalizer clamps to 90 degrees`() {
        val (pan, tilt) = PtzNormalizer.panTilt(3f, -3f)
        assertEquals(90, pan)
        assertEquals(-90, tilt)
    }

    @Test
    fun `redaction no device names leak into command logs`() {
        val red = com.yicamalt.test.RedactionScanner()
        runBlocking { service.sendPTZ("dome-1", 1f, 1f) }
        red.assertClean(recorder.bufferText(), extraLiterals = listOf("Living Room"))
    }
}