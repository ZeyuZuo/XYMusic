package io.github.xiangyuplayer.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.text.NumberFormat
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R

@Composable
fun AccountCard(state: AuthState, onLogin: () -> Unit, onLogout: () -> Unit, onVerify: () -> Unit, onRefreshProfile: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val account = state.account
    var avatarFailed by remember(state.profile?.avatarUrl, state.profileRevision) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp),
                ) {
                    ProfileAvatar(account?.userId, state.profile?.avatarUrl, state.profileRevision, state.sessionGeneration) { avatarFailed = true }
                }
                Spacer(Modifier.weight(1f))
                if (account != null) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = state.ready && !state.busy) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.account_actions))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.refresh_profile)) },
                                enabled = !state.profileLoading, onClick = { menuExpanded = false; onRefreshProfile() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.check_session)) },
                                onClick = { menuExpanded = false; onVerify() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; confirmLogout = true })
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (account == null) stringResource(R.string.login_account)
                        else account.nickname.ifBlank { stringResource(R.string.kugou_account) },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (account != null) Text(
                    stringResource(R.string.account_number, account.userId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account != null && state.verified && !state.busy) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.account_connected), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Text(stringResource(when {
                    !state.ready -> R.string.session_loading
                    state.busy -> R.string.account_working
                    !state.configured -> R.string.configure_service_first
                    account == null -> R.string.account_description
                    else -> R.string.account_unverified
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AuthMessage(state.message, state.diagnostic)
            if (account != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCount(state.profile?.fans, R.string.profile_fans, Modifier.weight(1f))
                    ProfileCount(state.profile?.follows, R.string.profile_follows, Modifier.weight(1f))
                    ProfileCount(state.profile?.visitors, R.string.profile_visitors, Modifier.weight(1f))
                }
                if (state.profileLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.profile_loading), style = MaterialTheme.typography.bodySmall)
                }
                if (state.profileFailed || avatarFailed) {
                    AuthMessage(if (state.profileFailed) R.string.profile_failed else R.string.avatar_failed,
                        if (state.profileFailed) state.profileDiagnostic else null)
                    TextButton(onClick = onRefreshProfile, enabled = !state.busy && !state.profileLoading,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry_profile)) }
                }
            }
            if (account == null) FilledTonalButton(onClick = onLogin, enabled = state.ready && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.phone_login))
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.logout)) }, text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ProfileCount(count: Long?, label: Int, modifier: Modifier) {
    val title = stringResource(label)
    val value = count?.let { NumberFormat.getIntegerInstance().format(it) } ?: stringResource(R.string.profile_unknown)
    val description = stringResource(R.string.profile_count_description, title,
        if (count == null) stringResource(R.string.profile_unavailable) else value)
    Column(modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
