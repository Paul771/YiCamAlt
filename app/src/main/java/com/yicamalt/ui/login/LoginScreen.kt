// FILE: LoginScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Compose login screen: email/password fields, submit button, state-driven loading/error, success callback.
//   SCOPE: stateless rendering driven by LoginViewModel; invokes onLoggedIn on Success.
//   DEPENDS: M-UI-LOGIN (LoginViewModel), M-AUTH
//   LINKS: M-UI-SHELL (Phase-2 nav host)
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// START_MODULE_MAP
//   LoginScreen - Compose entry surface; observes LoginViewModel state and calls onLoggedIn on Success.
// END_MODULE_MAP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val email by viewModel.email.collectAsState()
    val password by viewModel.password.collectAsState()
    val state by viewModel.state.collectAsState()

    // START_BLOCK_NAV_ON_SUCCESS
    LaunchedEffect(state) {
        if (state is LoginUiState.Success) onLoggedIn()
    }
    // END_BLOCK_NAV_ON_SUCCESS

    Scaffold(
        topBar = { TopAppBar(title = { Text("YiCamAlt — Вход") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { viewModel.email.value = it; viewModel.reset() },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.password.value = it; viewModel.reset() },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is LoginUiState.Loading -> CircularProgressIndicator()
                is LoginUiState.Error -> {
                    Text(s.message)
                    Spacer(Modifier.height(12.dp))
                    SubmitButton(viewModel)
                }
                else -> SubmitButton(viewModel)
            }
        }
    }
}

@Composable
private fun SubmitButton(viewModel: LoginViewModel) {
    // s4: empty inputs disable submit
    Button(
        onClick = { viewModel.submit() },
        enabled = viewModel.canSubmit(),
    ) { Text("Войти") }
}