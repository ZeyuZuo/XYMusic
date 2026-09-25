package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.AudioSource
import io.github.xiangyuplayer.domain.model.AudioSourceResolver
import io.github.xiangyuplayer.domain.model.PlaybackException
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread owner. Cancellation, a version check, and entryId keep late responses off a new occurrence. */
class PlaybackRequests(
    private val scope: CoroutineScope,
    private val resolver: AudioSourceResolver,
    private val onReady: (String, Song, AudioSource) -> Unit,
    private val onFailure: (PlaybackFailure) -> Unit,
) {
    private var job: Job? = null
    private var version = 0L

    fun play(entryId: String, song: Song) {
        cancel()
        val request = version
        val target = entryId
        job = scope.launch {
            try {
                val source = resolver.resolve(song)
                if (request == version) onReady(target, song, source)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == version) onFailure((error as? PlaybackException)?.failure ?: PlaybackFailure.RESPONSE)
            }
        }
    }

    fun cancel() {
        version++
        job?.cancel()
        job = null
    }
}
