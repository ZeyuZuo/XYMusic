package io.github.xiangyuplayer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import io.github.xiangyuplayer.domain.model.Song
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.search.SearchCategory
import io.github.xiangyuplayer.data.search.SearchResult

@Composable
fun SearchScreen(state: SearchState, model: SearchViewModel, available: Boolean, modifier: Modifier = Modifier, onPlay: (Song) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.query, state.category) { listState.scrollToItem(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); model.submit() }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input, onValueChange = model::input, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.search_input_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (available) submit() }),
                trailingIcon = if (state.input.isNotEmpty()) ({
                    IconButton(onClick = model::clear) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }) else null,
            )
            Button(onClick = { submit() }, enabled = available && state.input.isNotBlank()) {
                Text(stringResource(R.string.search))
            }
        }
        TabRow(selectedTabIndex = state.category.ordinal) {
            SearchCategory.entries.forEach { category ->
                Tab(selected = state.category == category, onClick = { model.select(category) }, text = {
                    Text(stringResource(when (category) {
                        SearchCategory.SONG -> R.string.search_songs
                        SearchCategory.PLAYLIST -> R.string.search_playlists
                        SearchCategory.ARTIST -> R.string.search_artists
                    }))
                })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!available) item { StatusText(R.string.search_login_required) }
            else if (state.query.isBlank()) item { StatusText(R.string.search_initial) }
            else {
                item {
                    Text(stringResource(R.string.search_results_for, state.query),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.items) { result ->
                    SearchResultRow(result, onPlay)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.loading) item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.search_loading), Modifier.padding(start = 12.dp))
                    }
                }
                state.error?.let { error -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::retry) { Text(stringResource(R.string.search_retry)) }
                    }
                } }
                if (!state.loading && state.error == null) {
                    if (state.items.isEmpty()) item { StatusText(R.string.search_empty) }
                    else if (state.hasMore) item {
                        TextButton(onClick = model::more, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.search_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(message: Int) {
    Text(stringResource(message), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchResultRow(result: SearchResult, onPlay: (Song) -> Unit) {
    // Variable height and unrestricted wrapping preserve long titles and all artist names at large font sizes.
    val playLabel = stringResource(R.string.playback_play_song, result.title)
    val interaction = result.song?.let { song ->
        Modifier.clickable(role = Role.Button, onClickLabel = playLabel) { onPlay(song) }
    } ?: Modifier
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(interaction).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(result.title, style = MaterialTheme.typography.titleSmall)
        result.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val details = buildList {
            result.song?.albumTitle?.let { add(it) }
            result.song?.durationMs?.let {
                add(stringResource(R.string.search_duration, it / 60_000, it / 1000 % 60))
            }
            result.songCount?.let { add(stringResource(R.string.search_song_count, it)) }
            result.albumCount?.let { add(stringResource(R.string.search_album_count, it)) }
        }
        if (details.isNotEmpty()) Text(details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
