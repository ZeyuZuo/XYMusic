package io.github.xiangyuplayer.ui.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.domain.model.activeLyricIndex
import io.github.xiangyuplayer.domain.model.lyricSeekPosition
import io.github.xiangyuplayer.ui.playback.PlaybackUiState

@Composable
fun LyricsPanel(playback: PlaybackUiState, lyrics: LyricsUiState, retry: () -> Unit, seek: (String, Long) -> Unit) {
    val song = playback.song ?: return
    val entryId = playback.currentEntryId ?: return
    val current = lyrics.hash == song.hash
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(entryId) { mutableStateOf(true) }
    val startMs = playback.previewStartMs
    val active = if (current && !playback.resolving && startMs != null) activeLyricIndex(lyrics.lines,
        playback.positionMs + startMs) else -1
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow, entryId) {
        if (follow && active >= 0) list.animateScrollToItem(active,
            -(list.layoutInfo.viewportEndOffset / 3))
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
            when {
                !current || lyrics.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.lyrics_loading))
                }
                lyrics.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(lyrics.error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = retry) { Text(stringResource(R.string.lyrics_retry)) }
                }
                lyrics.lines.isEmpty() -> Text(stringResource(R.string.lyrics_empty))
                else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 96.dp)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val position = startMs?.let { lyricSeekPosition(line.timeMs, it, playback.durationMs) }
                        val enabled = playback.connected && playback.seekable && !playback.resolving &&
                            playback.failure == null && position != null
                        val selected = index == active
                        val description = stringResource(R.string.lyrics_current)
                        Text(line.text, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(enabled = enabled) { seek(entryId, position!!); follow = true }
                                .semantics { if (selected) stateDescription = description }
                                .padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
        if (playback.preview) Text(stringResource(R.string.lyrics_preview_hint), style = MaterialTheme.typography.bodySmall)
        if (!follow) TextButton(onClick = { follow = true }) { Text(stringResource(R.string.lyrics_follow)) }
    }
}
