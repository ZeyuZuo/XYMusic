package io.github.xiangyuplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.domain.model.CloudPlaylist
import io.github.xiangyuplayer.domain.model.PlaylistCategory

@Composable
fun LibraryScreen(
    state: LibraryState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val snapshot = state.snapshot
    val liked = remember(snapshot) { snapshot?.items?.filter { it.isLiked }?.singleOrNull() }
    val groups = remember(snapshot, liked) {
        snapshot?.items.orEmpty().filter { it != liked }.groupBy {
            if (it.isLiked) PlaylistCategory.UNKNOWN else it.category
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading") {
            Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineMedium)
        }
        when {
            !state.ready -> item(key = "session_loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_session_loading))
            }
            !state.available -> item(key = "login") {
                Text(stringResource(R.string.library_login))
                TextButton(onClick = onLogin) { Text(stringResource(R.string.daily_sign_in)) }
            }
            else -> {
                item(key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.library_read_only), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRefresh, enabled = state.loadingPage != 1) {
                            Text(stringResource(R.string.daily_refresh))
                        }
                        if (snapshot != null) {
                            Text(stringResource(
                                if (snapshot.complete) R.string.library_loaded_all else R.string.library_loaded_partial,
                                snapshot.items.size,
                            ))
                        }
                        if (state.stale) Text(stringResource(R.string.library_stale), color = MaterialTheme.colorScheme.tertiary)
                        if (state.loadingPage == 1) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.library_loading))
                        }
                        if (state.failedPage == 1) LibraryFailure(state, onRetry)
                    }
                }
                item(key = "liked") {
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.library_liked)) },
                            supportingContent = {
                                Text(if (liked != null) stringResource(R.string.library_song_count, liked.songCount) else stringResource(
                                    when {
                                        snapshot == null -> R.string.library_liked_pending
                                        !snapshot.complete -> R.string.library_liked_more
                                        else -> R.string.library_liked_unidentified
                                    },
                                ))
                            },
                            leadingContent = { PlaylistArtwork(liked, liked = true) },
                        )
                    }
                }
                PlaylistCategory.entries.forEach { category ->
                    val playlists = groups[category].orEmpty()
                    if (category != PlaylistCategory.UNKNOWN || playlists.isNotEmpty()) {
                        item(key = "heading_$category") {
                            Text(stringResource(when (category) {
                                PlaylistCategory.CREATED -> R.string.library_created
                                PlaylistCategory.COLLECTED -> R.string.library_collected
                                PlaylistCategory.UNKNOWN -> R.string.library_other
                            }), style = MaterialTheme.typography.titleMedium)
                        }
                        if (playlists.isEmpty()) item(key = "empty_$category") {
                            Text(stringResource(
                                if (snapshot?.complete == true) R.string.library_category_empty else R.string.library_category_pending,
                            ), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(playlists, key = { "playlist_${it.ref.key}" }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.title) },
                                supportingContent = { Text(stringResource(R.string.library_song_count, playlist.songCount)) },
                                leadingContent = { PlaylistArtwork(playlist) },
                            )
                        }
                    }
                }
                item(key = "paging") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if ((state.loadingPage ?: 0) > 1) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.library_loading_more))
                        }
                        if ((state.failedPage ?: 0) > 1) LibraryFailure(state, onRetry)
                        if (snapshot != null && !snapshot.complete && state.error == null && !state.stale) {
                            TextButton(onClick = onLoadMore, enabled = state.loadingPage == null) {
                                Text(stringResource(R.string.search_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryFailure(state: LibraryState, onRetry: () -> Unit) {
    val error = state.error ?: return
    Text(stringResource(when (error) {
        LibraryError.NETWORK -> R.string.library_load_failed
        LibraryError.RESPONSE -> R.string.library_invalid_response
        LibraryError.CHANGED -> R.string.library_changed
        LibraryError.LIMIT -> R.string.library_limit
    }), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry, enabled = state.loadingPage == null) {
        Text(stringResource(if (error in listOf(LibraryError.CHANGED, LibraryError.LIMIT)) R.string.daily_refresh else R.string.search_retry))
    }
}

@Composable
private fun PlaylistArtwork(playlist: CloudPlaylist?, liked: Boolean = false) {
    Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        if (playlist?.coverUrl != null) AsyncImage(
            model = playlist.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
