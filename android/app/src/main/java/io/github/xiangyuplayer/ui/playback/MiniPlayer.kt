package io.github.xiangyuplayer.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.remote.AvatarImages
import io.github.xiangyuplayer.domain.model.PlaybackFailure

@Composable
fun MiniPlayer(state: PlaybackUiState, onToggle: () -> Unit, onRetry: () -> Unit, onQueue: () -> Unit, onOpen: () -> Unit) {
    val song = state.song ?: return
    val context = LocalContext.current
    val images = remember { AvatarImages.create(context) }
    DisposableEffect(images) { onDispose { images.shutdown() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.playback_open), onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(model = song.coverUrl, imageLoader = images,
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    loading = { CoverPlaceholder() }, error = { CoverPlaceholder() })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall)
                if (song.artists.isNotEmpty()) Text(song.artists.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.preview) Text(stringResource(R.string.playback_preview),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                val message = state.failure?.let(::failureMessage)
                    ?: if (state.resolving) R.string.playback_resolving else if (state.buffering) R.string.playback_buffering else if (state.needsSource) R.string.playback_resume_ready else null
                message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = if (state.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.storageError) Text(stringResource(R.string.playback_storage_error), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            when {
                state.resolving -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                state.failure != null -> IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.playback_retry))
                }
                else -> IconButton(onClick = onToggle, enabled = state.connected, modifier = Modifier.size(48.dp)) {
                    if (state.playing) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.playback_pause))
                    else Icon(Icons.Default.PlayArrow, stringResource(R.string.playback_play))
                }
            }
            IconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.playback_queue))
            }
        }
    }
}

@Composable
private fun CoverPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music_note), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun failureMessage(failure: PlaybackFailure): Int = when (failure) {
    PlaybackFailure.ACCOUNT -> R.string.playback_account_error
    PlaybackFailure.NETWORK -> R.string.playback_network_error
    PlaybackFailure.PERMISSION -> R.string.playback_permission_error
    PlaybackFailure.UNAVAILABLE -> R.string.playback_unavailable
    PlaybackFailure.RESPONSE -> R.string.playback_response_error
    PlaybackFailure.PLAYER -> R.string.playback_player_error
}
