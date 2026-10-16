// FILE: TnpLinkFactoryTest.kt
// VERSION: 0.1.1
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for TnpLinkFactory: typed refusals, runtime init once, one session per camera.
//   SCOPE: JVM only; PpppRuntime over FakePpppNative and a fake library loader; synthetic credentials.
//   DEPENDS: M-TNP-LINK, M-P2P-NATIVE, FakePpppNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-TNP-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tnp

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.FakePpppNative
import com.yicamalt.p2p.NativeLibs
import com.yicamalt.p2p.PpppCodes
import com.yicamalt.p2p.PpppRuntime
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

// START_MODULE_MAP
//   CountingLoader - fake System.loadLibrary; optional failure.
//   creds / factory / awaitTrue - builders and a bounded poll.
//   refusal tests - transport, missing credentials, PIN, encrypted without password, runtime unavailable / failed.
//   registry tests - ALREADY_OPEN with one connect, reopen after close, independent cam counters.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - Phase 2 review MED-7: an empty password with p2pEncrypt is refused as NO_CREDENTIALS.
//   PREVIOUS: v0.1.0 - GSD plan 02-07: created.
// END_CHANGE_SUMMARY

class TnpLinkFactoryTest {

    private class CountingLoader(private val fail: Boolean = false) : (String) -> Unit {
        val loaded = CopyOnWriteArrayList<String>()
        override fun invoke(name: String) {
            loaded += name
            if (fail) throw UnsatisfiedLinkError("missing")
        }
    }

    private val recorder = TraceRecorder()
    private val opened = CopyOnWriteArrayList<CameraLink>()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach
    fun tearDown() {
        opened.forEach { it.close() }
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = SECRETS)
        recorder.unplant()
    }

    private fun creds(
        deviceId: String = RedactionFixtures.CLOUD_UID,
        transport: TransportType = TransportType.TNP,
        p2pId: String = RedactionFixtures.DID,
        password: String? = PASSWORD,
        initString: String? = RedactionFixtures.INIT_STRING,
        licenseKey: String? = LICENSE_KEY,
        p2pEncrypt: Boolean = true,
        needsPincode: Boolean = false,
    ) = P2pCredentials(
        deviceId, p2pId, transport, password, initString, licenseKey, p2pEncrypt,
        wakeup = false, needsPincode = needsPincode, transportVerified = true,
    )

    private fun factory(fake: FakePpppNative, loader: CountingLoader = CountingLoader()) =
        TnpLinkFactory(fake, PpppRuntime(fake, NativeLibs(loader)))

    private fun TnpLinkFactory.openTracked(c: P2pCredentials) = open(c, LinkOptions()).also { opened += it }

    private fun awaitTrue(ms: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    private fun failed(kind: LinkErrorKind, code: Int = 0) = LinkState.Failed(LinkError(kind, code))

    // START_BLOCK_REFUSAL_TESTS
    @Test
    fun `non-TNP transport is refused with zero native calls`() {
        val fake = FakePpppNative()
        val loader = CountingLoader()
        val f = factory(fake, loader)
        listOf(TransportType.TUTK, TransportType.LANGTAO, TransportType.UNKNOWN).forEach {
            val link = f.openTracked(creds(transport = it, p2pId = RedactionFixtures.TUTK_UID))
            assertEquals(failed(LinkErrorKind.UNSUPPORTED_TRANSPORT), link.state.value)
            link.start { }
        }
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
        assertTrue(loader.loaded.isEmpty())
    }

    @Test
    fun `missing TNP credentials are refused with NO_CREDENTIALS`() {
        val fake = FakePpppNative()
        val f = factory(fake)
        listOf(
            creds(p2pId = " "),
            creds(initString = null),
            creds(licenseKey = null),
            creds(needsPincode = true),
            creds(p2pEncrypt = true, password = null),
            creds(p2pEncrypt = true, password = ""),
        ).forEach { c ->
            assertEquals(failed(LinkErrorKind.NO_CREDENTIALS), f.openTracked(c).state.value, c.toString())
        }
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
        val plain = f.openTracked(creds(p2pEncrypt = false, password = null))
        assertEquals(LinkState.Idle, plain.state.value)
    }

    @Test
    fun `runtime unavailable and init failure map to NATIVE_UNAVAILABLE`() {
        val missing = FakePpppNative()
        val link = factory(missing, CountingLoader(fail = true)).openTracked(creds())
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE), link.state.value)
        assertTrue(missing.calls.isEmpty())

        val broken = FakePpppNative().apply { initResult = PpppCodes.NOT_INITIALIZED }
        val f = factory(broken)
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE, -3001), f.openTracked(creds()).state.value)
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE, -3001), f.openTracked(creds(deviceId = "cam-b")).state.value)
        assertEquals(1, broken.calls.count { it.startsWith("initialize") })
    }
    // END_BLOCK_REFUSAL_TESTS

    // START_BLOCK_REGISTRY_TESTS
    @Test
    fun `second open of the same camera is ALREADY_OPEN and only one connect happens`() {
        val fake = FakePpppNative()
        val loader = CountingLoader()
        val f = factory(fake, loader)
        val first = f.openTracked(creds())
        first.start { }
        awaitTrue { fake.written.size >= 3 }

        val second = f.openTracked(creds())
        assertEquals(failed(LinkErrorKind.ALREADY_OPEN), second.state.value)
        second.start { }
        Thread.sleep(50)
        assertEquals(1, fake.calls.count { it.startsWith("connect ") })

        first.close()
        assertEquals(LinkState.Closed("user"), first.state.value)
        val third = f.openTracked(creds())
        assertEquals(LinkState.Idle, third.state.value)
        third.start { }
        awaitTrue { fake.calls.count { it.startsWith("connect ") } == 2 }

        assertEquals(1, fake.calls.count { it.startsWith("initialize") })
        assertEquals(1, loader.loaded.size)
    }

    @Test
    fun `different cameras get independent links with different cam counters`() {
        val fake = FakePpppNative()
        val f = factory(fake)
        val a = f.openTracked(creds(deviceId = "cam-a"))
        val b = f.openTracked(creds(deviceId = "cam-b"))
        assertEquals(LinkState.Idle, a.state.value)
        assertEquals(LinkState.Idle, b.state.value)
        assertTrue(a !== b)
        val cams = recorder.all()
            .filter { it.message.contains("[BLOCK_OPEN_LINK]") && it.message.contains("result=started") }
            .map { it.fields["cam"] }
        assertEquals(2, cams.size)
        assertNotEquals(cams[0], cams[1])
        a.start { }
        b.start { }
        awaitTrue { fake.calls.count { it.startsWith("connect ") } == 2 }
        a.close()
        assertTrue(b.state.value !is LinkState.Closed)
    }
    // END_BLOCK_REGISTRY_TESTS

    companion object {
        private const val PASSWORD = "SYNTH1234567890"
        private const val LICENSE_KEY = "SYNTHLIC01"
        private val SECRETS = RedactionFixtures.ALL_SECRETS + listOf(PASSWORD, LICENSE_KEY)
    }
}
