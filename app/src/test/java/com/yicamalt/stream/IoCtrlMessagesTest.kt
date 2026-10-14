// FILE: IoCtrlMessagesTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for IOCTRL constants, payload builders and reply interpretation.
//   SCOPE: 4881/9029/768/767/8190/511 payload bytes, authOutcome, kickCode, ResolutionPolicy.
//   DEPENDS: M-STREAM-CORE (IoCtrlMessages, LinkMode)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import com.yicamalt.stream.IoCtrlMessages.AuthOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   bytes(vararg) - int list to byte array.
//   IoCtrlMessagesTest - payload bytes, constants, auth/kick mapping, resolution policy.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04: wire-layer vectors.
// END_CHANGE_SUMMARY

class IoCtrlMessagesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `payload builders are byte exact`() {
        assertArrayEquals(bytes(0, 0, 0, 1, 0, 0, 0, 3), IoCtrlMessages.setResolution(resolution = 1, useCount = 3))
        assertArrayEquals(bytes(4, 1, 1, 0), IoCtrlMessages.startRealtime(useCount = 4, resolution = 1))
        assertArrayEquals(ByteArray(8), IoCtrlMessages.audioStart())
        assertArrayEquals(ByteArray(8), IoCtrlMessages.stop())
        assertArrayEquals(bytes(2, 0, 0, 0), IoCtrlMessages.tutkStart(useCount = 2))
    }

    @Test
    fun `command constants`() {
        assertEquals(4881, IoCtrlMessages.SET_RESOLUTION)
        assertEquals(4882, IoCtrlMessages.SET_RESOLUTION_RESP)
        assertEquals(9029, IoCtrlMessages.START_REALTIME)
        assertEquals(768, IoCtrlMessages.AUDIO_START)
        assertEquals(767, IoCtrlMessages.STOP)
        assertEquals(9033, IoCtrlMessages.KICK)
        assertEquals(8190, IoCtrlMessages.TUTK_START_ENC)
        assertEquals(511, IoCtrlMessages.TUTK_START_PLAIN)
    }

    @Test
    fun `authOutcome maps vendor authResult`() {
        assertEquals(AuthOutcome.OK, IoCtrlMessages.authOutcome(0))
        assertEquals(AuthOutcome.WRONG_PASSWORD, IoCtrlMessages.authOutcome(1))
        assertEquals(AuthOutcome.WRONG_PASSWORD, IoCtrlMessages.authOutcome(2))
        assertEquals(-20009, IoCtrlMessages.authOutcome(2).appCode)
        assertEquals(AuthOutcome.RETRY, IoCtrlMessages.authOutcome(4))
        assertEquals(-9000, IoCtrlMessages.authOutcome(4).appCode)
        assertEquals(AuthOutcome.Unknown(7), IoCtrlMessages.authOutcome(7))
        assertEquals(0, IoCtrlMessages.authOutcome(0).appCode)
    }

    @Test
    fun `kickCode maps reason`() {
        assertEquals(-3101, IoCtrlMessages.kickCode(bytes(0, 0, 0, 1)))
        assertEquals(-3100, IoCtrlMessages.kickCode(bytes(0, 0, 0, 0)))
        assertEquals(-3100, IoCtrlMessages.kickCode(bytes(0, 0, 0, 9)))
        assertEquals(-3100, IoCtrlMessages.kickCode(bytes(0, 1)))
    }

    @Test
    fun `resolution policy is HD on direct paths and SD on relay`() {
        val p = IoCtrlMessages.ResolutionPolicy
        assertEquals(1, p.forMode(LinkMode.P2P))
        assertEquals(1, p.forMode(LinkMode.LAN))
        assertEquals(2, p.forMode(LinkMode.RELAY))
        assertEquals(2, p.forMode(LinkMode.TCP_RELAY))
        assertEquals(2, p.forMode(LinkMode.SDEV_RELAY))
        assertEquals(2, p.forMode(LinkMode.UNKNOWN))
    }
}
