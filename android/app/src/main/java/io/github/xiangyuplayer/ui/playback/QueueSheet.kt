package io.github.xiangyuplayer.ui.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.playback.PlaybackMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: PlaybackUiState, onSelect: (String) -> Unit, onRemove: (String) -> Unit,
    onClear: () -> Unit, onMode: (PlaybackMode) -> Unit, onDismiss: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_count, state.queue.size), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { confirmClear = true }, enabled = state.connected && state.queue.isNotEmpty()) {
                    Text(stringResource(R.string.queue_clear))
                }
            }
            PlaybackModeMenu(state.mode, state.connected, onMode, Modifier.padding(horizontal = 8.dp))
            state.actionMessage?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp)) }
            if (state.queue.isEmpty()) Text(stringResource(R.string.queue_empty), Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(state.queue, key = { it.hash.lowercase() }) { song ->
                    val current = song.hash.equals(state.song?.hash, ignoreCase = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 64.dp)
                            .semantics { selected = current }
                            .clickable(enabled = state.connected, role = Role.Button,
                                onClickLabel = stringResource(R.string.playback_play_song, song.title)) { onSelect(song.hash) }
                            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(song.title, style = MaterialTheme.typography.titleSmall,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (song.artists.isNotEmpty()) Text(song.artists.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                            if (current) Text(stringResource(R.string.queue_current), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onRemove(song.hash) }, enabled = state.connected, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.queue_remove_song, song.title))
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.queue_clear)) },
        text = { Text(stringResource(R.string.queue_clear_detail)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.queue_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun PlaybackModeMenu(mode: PlaybackMode, enabled: Boolean, onMode: (PlaybackMode) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(modeLabel(mode)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlaybackMode.entries.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(modeLabel(value))) },
                    modifier = Modifier.semantics { selected = mode == value },
                    onClick = { expanded = false; onMode(value) })
            }
        }
    }
}

private fun modeLabel(mode: PlaybackMode) = when (mode) {
    PlaybackMode.SEQUENTIAL -> R.string.mode_sequential
    PlaybackMode.SHUFFLE -> R.string.mode_shuffle
    PlaybackMode.REPEAT_ONE -> R.string.mode_repeat_one
}
