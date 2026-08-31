// FILE: FakeCameraStreamServer.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Local socket server emitting a scripted AES-128-encrypted test H.264 stream.
//   SCOPE: start/stop server, expose known key+iv, emit N encrypted frames; deterministic payload.
//   DEPENDS: none
//   LINKS: M-STREAM-DECODE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   FakeCameraStreamServer - emits a deterministic AES-encrypted byte stream on a local port.
//   StreamConfig - port, key, iv, frame count.
// END_MODULE_MAP

class FakeCameraStreamServer {

    data class StreamConfig(
        val port: Int = 18080,
        val key: ByteArray = ByteArray(16) { 0x0A },
        val iv: ByteArray = ByteArray(16) { 0x0B },
        val frames: Int = 8,
    )

    private var running = false
    private var serverThread: Thread? = null
    var emittedBytes: ByteArray = ByteArray(0)
        private set

    fun start(config: StreamConfig = StreamConfig()) {
        running = true
        // Deterministic plaintext: repeating frame marker + counter.
        val plain = (0 until config.frames).joinToString("") { "FRAME$it;" }.toByteArray()
        emittedBytes = encrypt(plain, config.key, config.iv)
        // A real implementation would serve emittedBytes over ServerSocket(config.port).
        // For unit tests we expose the encrypted bytes directly; integration tests bind the socket.
        serverThread = Thread { /* socket loop reserved for Phase-3 */ }
        serverThread?.start()
    }

    fun stop() {
        running = false
        serverThread?.interrupt()
        serverThread = null
    }

    // START_BLOCK_ENCRYPT_FIXTURE
    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }
    // END_BLOCK_ENCRYPT_FIXTURE

    fun isRunning(): Boolean = running
}