// FILE: LoginViewModel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Login screen view-model: collects credentials, calls AuthRepository.login, exposes UI state.
//   SCOPE: email/password state, submit(), LoginUiState transitions; emit trace markers for submit/success/error.
//   DEPENDS: M-AUTH
//   LINKS: M-UI-LOGIN, M-AUTH
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.auth.AuthError
import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.LoginPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// START_MODULE_MAP
//   LoginViewModel - drives login form state + submit flow.
//   LoginUiState - Idle | Loading | Success | Error(message).
// END_MODULE_MAP

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data object Success : LoginUiState
    data class Error(val message: String) : LoginUiState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: LoginPort,
) : ViewModel() {

    val email = MutableStateFlow("")
    val password = MutableStateFlow("")

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Whether the submit button should be enabled (non-blank inputs + not loading). */
    fun canSubmit(): Boolean =
        email.value.isNotBlank() && password.value.isNotBlank() && _state.value !is LoginUiState.Loading

    fun submit() {
        if (!canSubmit()) return
        val emailVal = email.value
        // START_BLOCK_LOGIN_SUBMIT
        LoginLog.d("[Login][vm][BLOCK_LOGIN_SUBMIT] email=${emailVal.redact()} method=password")
        // END_BLOCK_LOGIN_SUBMIT
        _state.value = LoginUiState.Loading
        viewModelScope.launch {
            try {
                auth.login(emailVal, password.value, AuthMethod.PASSWORD)
                // START_BLOCK_LOGIN_SUCCESS
                LoginLog.d("[Login][vm][BLOCK_LOGIN_SUCCESS] login succeeded")
                // END_BLOCK_LOGIN_SUCCESS
                _state.value = LoginUiState.Success
            } catch (e: AuthError.InvalidCredentials) {
                // START_BLOCK_LOGIN_ERROR
                LoginLog.d("[Login][vm][BLOCK_LOGIN_ERROR] reason=invalid_credentials")
                // END_BLOCK_LOGIN_ERROR
                _state.value = LoginUiState.Error("Неверный email или пароль")
            } catch (e: Throwable) {
                // START_BLOCK_LOGIN_ERROR
                LoginLog.d("[Login][vm][BLOCK_LOGIN_ERROR] reason=network")
                // END_BLOCK_LOGIN_ERROR
                _state.value = LoginUiState.Error("Ошибка сети. Попробуйте снова.")
            }
        }
    }

    fun reset() { _state.value = LoginUiState.Idle }

    private fun String.redact(): String =
        if (isEmpty()) "" else this.first() + "***@" + (substringAfterLast('@', "unknown"))
}

internal object LoginLog {
    fun d(message: String) = timber.log.Timber.d(message)
}