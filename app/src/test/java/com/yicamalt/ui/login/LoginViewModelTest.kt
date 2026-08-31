// FILE: LoginViewModelTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify LoginViewModel submit flow: success, invalid-credentials error, redaction, canSubmit gating.
//   SCOPE: success + failure scenarios for V-M-UI-LOGIN using a fake LoginPort + test dispatcher.
//   DEPENDS: M-UI-LOGIN, M-AUTH (LoginPort), TestInfrastructure
//   LINKS: V-M-UI-LOGIN
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.login

import com.yicamalt.auth.AuthError
import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.LoginPort
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Fake LoginPort with scriptable outcome. */
private class FakeLoginPort : LoginPort {
    var outcome: Outcome = Outcome.Success
    var passwordReceived: String? = null
        private set
    sealed class Outcome {
        object Success : Outcome()
        object Invalid : Outcome()
    }

    override suspend fun login(email: String, password: String, method: AuthMethod): AuthSession {
        passwordReceived = password
        return when (outcome) {
            Outcome.Success -> AuthSession("acc", "ref", Long.MAX_VALUE, "u-1", AuthMethod.PASSWORD)
            Outcome.Invalid -> throw AuthError.InvalidCredentials
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recorder.plant()
    }

    @AfterEach fun tearDown() {
        recorder.unplant()
        Dispatchers.resetMain()
    }

    @Test
    fun `scenario_1 success shows loading then success`() = runTest(testDispatcher) {
        val port = FakeLoginPort().apply { outcome = FakeLoginPort.Outcome.Success }
        val vm = LoginViewModel(port)
        vm.email.value = "user@example.com"
        vm.password.value = "secret-pw"
        vm.submit()
        // While running, state becomes Loading before advancing.
        assertEquals(LoginUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(LoginUiState.Success, vm.state.value)
        recorder.assertMarkerAppeared("BLOCK_LOGIN_SUBMIT")
        recorder.assertMarkerAppeared("BLOCK_LOGIN_SUCCESS")
    }

    @Test
    fun `scenario_2 invalid credentials shows error message`() = runTest(testDispatcher) {
        val port = FakeLoginPort().apply { outcome = FakeLoginPort.Outcome.Invalid }
        val vm = LoginViewModel(port)
        vm.email.value = "user@example.com"
        vm.password.value = "wrong"
        vm.submit()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s is LoginUiState.Error, "state should be Error, was $s")
        recorder.assertMarkerAppeared("BLOCK_LOGIN_ERROR")
    }

    @Test
    fun `scenario_3 redaction password never logged`() = runTest(testDispatcher) {
        val port = FakeLoginPort().apply { outcome = FakeLoginPort.Outcome.Success }
        val vm = LoginViewModel(port)
        vm.email.value = "user@example.com"
        vm.password.value = "super-secret-password"
        vm.submit()
        advanceUntilIdle()
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("super-secret-password"),
        )
        // The submit marker must NOT contain the raw password.
        assertTrue(!recorder.bufferText().contains("super-secret-password"))
    }

    @Test
    fun `scenario_4 empty input disables submit`() {
        val vm = LoginViewModel(FakeLoginPort())
        // both blank
        assertFalse(vm.canSubmit())
        // only email
        vm.email.value = "user@example.com"
        assertFalse(vm.canSubmit())
        // only password
        vm.email.value = ""
        vm.password.value = "pw"
        assertFalse(vm.canSubmit())
        // both present
        vm.email.value = "user@example.com"
        assertTrue(vm.canSubmit())
    }

    @Test
    fun `submit does nothing when canSubmit is false`() = runTest(testDispatcher) {
        val port = FakeLoginPort()
        val vm = LoginViewModel(port) // inputs blank
        vm.submit()
        advanceUntilIdle()
        assertEquals(LoginUiState.Idle, vm.state.value)
        assertEquals(null, port.passwordReceived, "login must not be called when inputs blank")
    }
}