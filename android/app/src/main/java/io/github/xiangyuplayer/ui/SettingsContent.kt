package io.github.xiangyuplayer.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.auth.AccountProfile
import io.github.xiangyuplayer.data.auth.Account
import io.github.xiangyuplayer.ui.auth.AccountCard
import io.github.xiangyuplayer.ui.auth.AuthState
import io.github.xiangyuplayer.ui.theme.XiangyuTheme

@Composable
fun SettingsContent(
    state: AuthState,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onVerify: () -> Unit,
    onRefreshProfile: () -> Unit,
    endpointEditor: @Composable () -> Unit,
) {
    var showEndpoint by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { AccountCard(state, onLogin, onLogout, onVerify, onRefreshProfile) }
        item {
            SettingsGroup(stringResource(R.string.preferences_group)) {
                SettingsInfo(Icons.Default.Settings, stringResource(R.string.appearance), stringResource(R.string.follow_system))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsInfo(Icons.Default.Info, stringResource(R.string.about), stringResource(R.string.about_detail))
            }
        }
        item {
            SettingsGroup(stringResource(R.string.development_group)) {
                Surface(onClick = { showEndpoint = !showEndpoint }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.music_service)) },
                        supportingContent = { Text(stringResource(if (state.configured) R.string.service_configured else R.string.service_not_configured)) },
                        leadingContent = { SettingsIcon(Icons.Default.Build) },
                        trailingContent = { Icon(if (showEndpoint) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            stringResource(if (showEndpoint) R.string.collapse_service else R.string.expand_service)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (showEndpoint) Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) { endpointEditor() }
            }
        }
        item {
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsInfo(icon: ImageVector, title: String, detail: String) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) },
        leadingContent = { SettingsIcon(icon) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
}

// Sample account is restricted to Preview; production always consumes AuthViewModel state.
@Preview(name = "Account · light", showBackground = true, widthDp = 412, heightDp = 892)
@Preview(name = "Account · dark", showBackground = true, widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Account · large type", showBackground = true, widthDp = 412, heightDp = 892, fontScale = 1.5f)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsPreview() {
    XiangyuTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }, bottomBar = {
            NavigationBar {
                listOf(R.string.home to Icons.Default.Home, R.string.search to Icons.Default.Search,
                    R.string.library to Icons.Default.Favorite, R.string.settings to Icons.Default.Settings).forEach { (label, icon) ->
                    NavigationBarItem(selected = label == R.string.settings, onClick = {}, icon = { Icon(icon, null) },
                        label = { Text(stringResource(label)) })
                }
            }
        }) { padding ->
            SettingsContent(
                state = AuthState(ready = true, configured = true, verified = true, account = Account("100000", "相遇听众"),
                    profile = AccountProfile(null, 128, 36, 2048)),
                modifier = Modifier.padding(padding), onLogin = {}, onLogout = {}, onVerify = {}, onRefreshProfile = {},
                endpointEditor = { Text(stringResource(R.string.api_description)) },
            )
        }
    }
}
