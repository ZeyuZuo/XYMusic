package io.github.xiangyuplayer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(state: AuthState, model: AuthViewModel, onBack: () -> Unit) {
    BackHandler { model.leaveLogin(); onBack() }
    LaunchedEffect(state.account) { if (state.account != null) onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.phone_login)) }, navigationIcon = {
            IconButton(onClick = { model.leaveLogin(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Default.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.login_welcome), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.login_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.configured) Text(stringResource(R.string.login_configure_service), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(value = state.phone, onValueChange = model::phone,
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                label = { Text(stringResource(R.string.phone_number)) },
                prefix = { Text(stringResource(R.string.phone_prefix)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(value = state.code, onValueChange = model::code,
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                label = { Text(stringResource(R.string.sms_code)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            OutlinedButton(onClick = model::sendCode,
                enabled = state.ready && state.configured && !state.busy && state.remaining == 0 && Regex("1[3-9][0-9]{9}").matches(state.phone),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.remaining > 0) stringResource(R.string.resend_after, state.remaining) else stringResource(R.string.send_code))
            }
            var accountOptions by remember { mutableStateOf(false) }
            TextButton(onClick = { accountOptions = !accountOptions }, enabled = !state.busy) {
                Text(stringResource(R.string.multiple_accounts))
            }
            if (accountOptions) {
                Text(stringResource(R.string.multiple_accounts_detail), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = state.userId, onValueChange = model::userId,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                    label = { Text(stringResource(R.string.account_id_optional)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            Button(onClick = model::login,
                enabled = state.ready && state.configured && !state.busy && Regex("1[3-9][0-9]{9}").matches(state.phone) && state.code.length in 4..8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.login))
            }
            Text(stringResource(R.string.login_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AccountCard(state: AuthState, onLogin: () -> Unit, onLogout: () -> Unit, onVerify: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            val account = state.account
            Text(if (account == null) stringResource(R.string.login_account) else account.nickname.ifBlank { stringResource(R.string.kugou_account) },
                style = MaterialTheme.typography.titleLarge)
            if (account != null) Text(stringResource(R.string.account_number, account.userId))
            Text(stringResource(when {
                !state.ready -> R.string.session_loading
                !state.configured -> R.string.configure_service_first
                account == null -> R.string.account_description
                state.verified -> R.string.account_connected
                else -> R.string.account_unverified
            }), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(stringResource(it)) }
            if (account == null) Button(onClick = onLogin, enabled = state.ready && !state.busy) {
                Text(stringResource(R.string.phone_login))
            } else {
                TextButton(onClick = onVerify, enabled = !state.busy) { Text(stringResource(R.string.check_session)) }
                TextButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text(stringResource(R.string.logout)) }
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.logout)) }, text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } })
}
