package io.github.xiangyuplayer.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import io.github.xiangyuplayer.ui.search.SearchScreen
import io.github.xiangyuplayer.ui.search.SearchViewModel
import io.github.xiangyuplayer.ui.playback.PlaybackViewModel
import io.github.xiangyuplayer.ui.playback.MiniPlayer
import io.github.xiangyuplayer.ui.playback.PlaybackScreen
import io.github.xiangyuplayer.ui.playback.QueueSheet
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xiangyuplayer.ui.auth.AuthViewModel
import io.github.xiangyuplayer.ui.auth.LoginScreen
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.settings.SettingsStore
import java.io.IOException
import kotlinx.coroutines.launch

private enum class Destination(@StringRes val label: Int, val icon: ImageVector) {
    Home(R.string.home, Icons.Default.Home),
    Search(R.string.search, Icons.Default.Search),
    Library(R.string.library, Icons.Default.Favorite),
    Settings(R.string.settings, Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XiangyuApp(settings: SettingsStore) {
    val auth: AuthViewModel = viewModel()
    val authState by auth.state.collectAsStateWithLifecycle()
    var loginVisible by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    val playback: PlaybackViewModel = viewModel()
    val playbackState by playback.state.collectAsStateWithLifecycle()
    var playbackVisible by rememberSaveable { mutableStateOf(false) }
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playbackState.song, playbackState.connected) {
        if (playbackState.connected && playbackState.song == null) playbackVisible = false
    }
    val search: SearchViewModel = viewModel()
    val searchState by search.state.collectAsStateWithLifecycle()
    val searchRepository = auth.searchRepository.takeIf { authState.ready && authState.account != null }
    LaunchedEffect(searchRepository, authState.sessionGeneration, authState.account?.userId) {
        search.bind(searchRepository, "${authState.sessionGeneration}:${authState.account?.userId}")
    }
    val savedEndpoint by settings.apiBaseUrl.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playbackState.actionMessage) {
        playbackState.actionMessage?.let {
            snackbar.showSnackbar(context.getString(it))
            playback.dismissMessage()
        }
    }
    if (queueVisible) {
        QueueSheet(playbackState, playback::select, playback::remove, playback::clearQueue, playback::setMode) { queueVisible = false }
    }
    if (loginVisible) {
        LoginScreen(authState, auth) { loginVisible = false }
        return
    }
    if (playbackVisible && playbackState.song != null) {
        PlaybackScreen(playbackState, playback::toggle, playback::retry, playback::seekTo,
            onPrevious = playback::previous, onNext = playback::next, onQueue = { queueVisible = true },
            onMode = playback::setMode, snackbar = snackbar) { playbackVisible = false }
        return
    }
    BackHandler(enabled = destination != Destination.Home) {
        destination = Destination.Home
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (destination == Destination.Settings) R.string.settings else R.string.app_name)) },
                actions = {
                    IconButton(onClick = { queueVisible = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.playback_queue))
                    }
                },
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(playbackState, playback::toggle, playback::retry, onQueue = { queueVisible = true }) { playbackVisible = true }
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        if (destination == Destination.Settings) {
            SettingsContent(authState, Modifier.padding(insets), { loginVisible = true }, auth::logout, auth::verify, auth::refreshProfile) {
                EndpointSettings(savedEndpoint, settings, snackbar)
            }
            return@Scaffold
        }
        if (destination == Destination.Search) {
            SearchScreen(searchState, search, searchRepository != null, Modifier.padding(insets), playback::play, playback::enqueue, playbackState.song?.hash)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            when (destination) {
                Destination.Home -> {
                    item { EmptyCard(Icons.Default.Home, R.string.daily_recommend, R.string.recommend_pending) }
                    item { EmptyCard(Icons.Default.Favorite, R.string.personal_fm, R.string.recommend_pending) }
                }
                Destination.Search -> Unit
                Destination.Library -> {
                    item { Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium) }
                    item { EmptyCard(Icons.Default.Favorite, R.string.no_library, R.string.no_library_detail) }
                }
                Destination.Settings -> Unit
            }
        }
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EndpointSettings(saved: String?, settings: SettingsStore, snackbar: SnackbarHostState) {
    var draft by rememberSaveable(saved) { mutableStateOf(saved.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.saved)
    val failedMessage = stringResource(R.string.save_failed)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.api_description))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; invalid = false },
            modifier = Modifier.fillMaxWidth(),
            enabled = saved != null && !saving,
            label = { Text(stringResource(R.string.api_address)) },
            placeholder = { Text(stringResource(R.string.api_address_hint)) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.invalid_address)) }) else null,
        )
        Button(
            enabled = saved != null && !saving && draft.isNotBlank(),
            onClick = {
                val normalized = try {
                    ApiEndpoint.parse(draft, allowHttp = BuildConfig.DEBUG).toString()
                } catch (_: IllegalArgumentException) {
                    invalid = true
                    return@Button
                }
                saving = true
                scope.launch {
                    try {
                        settings.saveApiBaseUrl(normalized)
                        snackbar.showSnackbar(savedMessage)
                    } catch (_: IOException) {
                        snackbar.showSnackbar(failedMessage)
                    } finally {
                        saving = false
                    }
                }
            },
        ) { Text(stringResource(R.string.save)) }
    }
}
