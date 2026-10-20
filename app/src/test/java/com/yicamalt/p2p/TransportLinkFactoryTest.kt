// FILE: TransportLinkFactoryTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for TransportLinkFactory: TNP -> TnpLinkFactory, TUTK -> TutkLinkFactory,
//     LANGTAO / UNKNOWN -> Failed(UNSUPPORTED_TRANSPORT) with zero native calls.
//   SCOPE: JVM only; real delegate factories over FakePpppNative and FakeTutkNative; synthetic credentials.
//   DEPENDS: M-TNP-LINK, M-TUTK-LINK, FakePpppNative, FakeTutkNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-TUTK-LINK, V-M-TNP-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.tnp.TnpLinkFactory
import com.yicamalt.p2p.tutk.FakeTutkNative
import com.yicamalt.p2p.tutk.TutkLinkFactory
import com.yicamalt.p2p.tutk.TutkRuntime
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

// START_MODULE_MAP
//   Fixture - FakePpppNative + FakeTutkNative behind real TnpLinkFactory / TutkLinkFactory and TransportLinkFactory.
//   creds - synthetic TNP or TUTK credentials.
//   dispatch tests - TNP reaches only PPPP, TUTK reaches only TUTK, LANGTAO / UNKNOWN reach neither.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-11: created.
// END_CHANGE_SUMMARY

class TransportLinkFactoryTest {

    private val recorder = TraceRecorder()
    private val opened = CopyOnWriteArrayList<CameraLink>()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach
    fun tearDown() {
        opened.forEach { it.close() }
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = SECRETS)
        recorder.unplant()
    }

    private class Fixture {
        val loaded = CopyOnWriteArrayList<String>()
        val pppp = FakePpppNative()
        val tutk = FakeTutkNative()
        private val libs = NativeLibs { loaded += it }
        val factory = TransportLinkFactory(
            TnpLinkFactory(pppp, PpppRuntime(pppp, libs)),
            TutkLinkFactory(tutk, TutkRuntime(tutk, libs)),
        )
    }

    private fun creds(transport: TransportType) = P2pCredentials(
        deviceId = RedactionFixtures.CLOUD_UID,
        p2pId = if (transport == TransportType.TNP) RedactionFixtures.DID else RedactionFixtures.TUTK_UID,
        transport = transport,
        password = PASSWORD,
        initString = if (transport == TransportType.TNP) RedactionFixtures.INIT_STRING else null,
        licenseKey = if (transport == TransportType.TNP) LICENSE_KEY else null,
        p2pEncrypt = true,
        wakeup = false,
        needsPincode = false,
        transportVerified = true,
    )

    private fun Fixture.openTracked(t: TransportType) = factory.open(creds(t), LinkOptions()).also { opened += it }

    private fun awaitTrue(ms: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    // START_BLOCK_DISPATCH_TESTS
    @Test
    fun `TNP credentials go to the TNP factory only`() {
        val fx = Fixture()
        val link = fx.openTracked(TransportType.TNP)
        assertEquals(LinkState.Idle, link.state.value)
        assertTrue(link.javaClass.simpleName == "TnpCameraLink", link.javaClass.name)
        link.start { }
        awaitTrue { fx.pppp.calls.any { it.startsWith("connect ") } }
        assertTrue(fx.tutk.calls.isEmpty(), fx.tutk.calls.toString())
    }

    @Test
    fun `TUTK credentials go to the TUTK factory only`() {
        val fx = Fixture()
        val link = fx.openTracked(TransportType.TUTK)
        assertEquals(LinkState.Idle, link.state.value)
        assertTrue(link.javaClass.simpleName == "TutkCameraLink", link.javaClass.name)
        link.start { }
        awaitTrue { fx.tutk.callCount("connectByUid") == 1 }
        assertTrue(fx.pppp.calls.isEmpty(), fx.pppp.calls.toString())
        assertEquals(listOf("IOTCAPIs", "sCHL", "AVAPIs"), fx.loaded.toList())
    }

    @Test
    fun `LANGTAO and UNKNOWN are refused without loading or calling anything`() {
        val fx = Fixture()
        listOf(TransportType.LANGTAO, TransportType.UNKNOWN).forEach {
            val link = fx.openTracked(it)
            assertEquals(LinkState.Failed(LinkError(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0)), link.state.value)
            link.start { }
        }
        Thread.sleep(30)
        assertTrue(fx.pppp.calls.isEmpty())
        assertTrue(fx.tutk.calls.isEmpty())
        assertTrue(fx.loaded.isEmpty())
        assertTrue(recorder.bufferText().contains("[P2P][factory][BLOCK_DISPATCH_LINK] transport=LANGTAO result=UNSUPPORTED_TRANSPORT"))
    }
    // END_BLOCK_DISPATCH_TESTS

    companion object {
        private const val PASSWORD = "SYNTH1234567890"
        private const val LICENSE_KEY = "SYNTHLIC01"
        private val SECRETS = RedactionFixtures.ALL_SECRETS + listOf(PASSWORD, LICENSE_KEY)
    }
}
