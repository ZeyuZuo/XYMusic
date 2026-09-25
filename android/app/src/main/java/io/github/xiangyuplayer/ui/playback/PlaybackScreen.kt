package io.github.xiangyuplayer.ui.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.SubcomposeAsyncImage
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.remote.AvatarImages
import io.github.xiangyuplayer.ui.lyrics.LyricsPanel
import io.github.xiangyuplayer.ui.lyrics.LyricsUiState
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.ui.theme.XiangyuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    state: PlaybackUiState,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onSeek: (String, Long) -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onQueue: () -> Unit = {},
    onMode: (PlaybackMode) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    lyrics: LyricsUiState = LyricsUiState(),
    onLyricsRetry: () -> Unit = {},
    onBack: () -> Unit,
) {
    val song = state.song ?: return
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val images = remember { AvatarImages.create(context) }
    DisposableEffect(images) { onDispose { images.shutdown() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.playback_now)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showLyrics, onClick = { showLyrics = false }, label = { Text(stringResource(R.string.playback_cover)) })
                FilterChip(selected = showLyrics, onClick = { showLyrics = true }, label = { Text(stringResource(R.string.lyrics_title)) })
            }
            if (showLyrics) {
                key(state.currentEntryId) { LyricsPanel(state, lyrics, onLyricsRetry, onSeek) }
            } else Surface(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                SubcomposeAsyncImage(
                    model = song.coverUrl, imageLoader = images, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    loading = { FullCoverPlaceholder() }, error = { FullCoverPlaceholder() },
                )
            }
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall)
                if (song.artists.isNotEmpty()) Text(song.artists.joinToString(" / "),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.preview) Text(stringResource(R.string.playback_preview), color = MaterialTheme.colorScheme.primary)
                val message = state.failure?.let(::failureMessage)
                    ?: if (state.resolving) R.string.playback_resolving else if (state.buffering) R.string.playback_buffering else if (state.needsSource) R.string.playback_resume_ready else null
                message?.let { Text(stringResource(it),
                    color = if (state.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.storageError) Text(stringResource(R.string.playback_storage_error), color = MaterialTheme.colorScheme.error)
            }
            PlaybackProgress(state, onSeek, Modifier.widthIn(max = 560.dp).fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = onPrevious, enabled = state.connected && state.hasPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.playback_previous))
                }
                if (state.resolving) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    FilledIconButton(
                        onClick = if (state.failure != null) onRetry else onToggle,
                        enabled = state.connected || state.failure != null,
                        modifier = Modifier.size(72.dp),
                    ) {
                        when {
                            state.failure != null -> Icon(Icons.Default.Refresh, stringResource(R.string.playback_retry), Modifier.size(32.dp))
                            state.playing -> Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.playback_pause), Modifier.size(32.dp))
                            else -> Icon(Icons.Default.PlayArrow, stringResource(R.string.playback_play), Modifier.size(32.dp))
                        }
                    }
                }
                IconButton(onClick = onNext, enabled = state.connected && state.hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.playback_next))
                }
            }
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                PlaybackModeMenu(state.mode, state.connected, onMode)
                TextButton(onClick = onQueue) { Text(stringResource(R.string.playback_queue)) }
            }
        }
    }
}

@Composable
private fun PlaybackProgress(state: PlaybackUiState, onSeek: (String, Long) -> Unit, modifier: Modifier) {
    if (state.song == null) return
    val entryId = state.currentEntryId ?: return
    val enabled = state.connected && state.seekable && state.failure == null && !state.resolving
    var dragged by remember(entryId, state.durationMs, enabled) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val fraction = dragged ?: if (duration != null) state.positionMs.toFloat() / duration else 0f
    val position = dragged?.let { (it * (duration ?: 0L)).toLong() } ?: state.positionMs
    val elapsed = playbackTime(position)
    val total = duration?.let { playbackTime(it) } ?: stringResource(R.string.playback_time_unknown)
    val label = stringResource(R.string.playback_progress)
    val description = stringResource(R.string.playback_progress_description, elapsed, total)
    Column(modifier) {
        Slider(
            value = fraction.coerceIn(0f, 1f), enabled = enabled,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { value -> duration?.let { onSeek(entryId, (value * it).toLong()) } }
                dragged = null
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = description
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(elapsed, style = MaterialTheme.typography.labelLarge)
            Text(total, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return stringResource(R.string.playback_time, seconds / 60, seconds % 60)
}

@Composable
private fun FullCoverPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music_note), null,
            modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, fontScale = 1.5f, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlaybackScreenPreview() {
    XiangyuTheme {
        PlaybackScreen(
            PlaybackUiState(
                song = Song("preview", "很长的歌曲标题，也应该完整展示，不截断重要信息", listOf("第一位歌手", "第二位歌手", "第三位歌手")),
                currentEntryId = "preview-entry",
                connected = true, preview = true, positionMs = 12_000, durationMs = 60_000, seekable = true,
            ), onToggle = {}, onRetry = {}, onSeek = { _, _ -> }, onBack = {},
        )
    }
}
