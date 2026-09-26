package io.github.xiangyuplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.domain.model.RecommendationDateSource
import io.github.xiangyuplayer.domain.model.Song

@Composable
fun DailyRecommendationScreen(
    state: DailyRecommendationState, listState: LazyListState, modifier: Modifier = Modifier,
    onRefresh: () -> Unit, onBack: () -> Unit, onLogin: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit, onNext: (Song) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) }
            Text(stringResource(R.string.daily_recommend), style = MaterialTheme.typography.headlineMedium)
        }
        if (!state.available) item {
            Text(stringResource(R.string.daily_login))
            TextButton(onClick = onLogin) { Text(stringResource(R.string.daily_sign_in)) }
        } else {
            val recommendation = state.recommendation
            item {
                if (recommendation != null) {
                    Text(stringResource(if (recommendation.dateSource == RecommendationDateSource.SERVER)
                        R.string.daily_date else R.string.daily_fetched_date, recommendation.date))
                    Text(stringResource(R.string.daily_count, recommendation.songs.size))
                    if (state.previous) Text(stringResource(R.string.daily_previous), color = MaterialTheme.colorScheme.tertiary)
                }
                Text(stringResource(R.string.daily_online_playback), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { recommendation?.let { onPlay(it.songs, 0) } },
                        enabled = !recommendation?.songs.isNullOrEmpty()) { Text(stringResource(R.string.daily_play_all)) }
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.daily_refresh)) }
                }
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.daily_loading))
                }
                state.error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.search_retry)) }
                }
                if (state.cacheFailed) Text(stringResource(R.string.daily_cache_failed))
                if (recommendation != null && recommendation.songs.isEmpty()) Text(stringResource(R.string.daily_empty))
            }
            itemsIndexed(recommendation?.songs.orEmpty()) { index, song ->
                SongRow(song, { onPlay(recommendation!!.songs, index) }, { onNext(song) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onPlay: () -> Unit, onNext: () -> Unit) {
    val playLabel = stringResource(R.string.playback_play_song, song.title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = playLabel, onClick = onPlay)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(model = song.coverUrl, contentDescription = null, modifier = Modifier.size(56.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall)
                Text(song.artists.joinToString(" / "), style = MaterialTheme.typography.bodyMedium)
                song.albumTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                song.durationMs?.let { Text(stringResource(R.string.search_duration, it / 60_000, it / 1000 % 60), style = MaterialTheme.typography.bodySmall) }
            }
        }
        var expanded by remember(song) { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.song_actions, song.title)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.playback_play)) }, onClick = { expanded = false; onPlay() })
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = { expanded = false; onNext() })
            }
        }
    }
}
