package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.AudioSource
import io.github.xiangyuplayer.domain.model.AudioSourceResolver
import io.github.xiangyuplayer.domain.model.PlaybackAccess
import io.github.xiangyuplayer.domain.model.PlaybackException
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestsTest {
    private val source = AudioSource("https://audio.example.invalid/test.mp3", PlaybackAccess.FULL)
    private fun song(id: String) = Song(id, id, emptyList())

    @Test fun lateResponseCannotReplaceLatestSelection() = runBlocking {
        val waiting = mutableMapOf<String, Continuation<AudioSource>>()
        val played = mutableListOf<String>()
        val requests = PlaybackRequests(this, AudioSourceResolver { song ->
            suspendCoroutine { waiting[song.hash] = it }
        }, { song, _ -> played += song.hash }, { fail("Unexpected failure") })
        requests.play(song("old"))
        yield()
        requests.play(song("new"))
        yield()
        waiting.getValue("new").resume(source)
        yield()
        waiting.getValue("old").resume(source)
        yield()
        assertEquals(listOf("new"), played)
    }

    @Test fun accountInvalidationDiscardsEvenNonCooperativeResponse() = runBlocking {
        lateinit var response: Continuation<AudioSource>
        var played = false
        val requests = PlaybackRequests(this, AudioSourceResolver { suspendCoroutine { response = it } },
            { _, _ -> played = true }, { fail("Cancellation is not an error") })
        requests.play(song("old-account"))
        yield()
        requests.cancel()
        response.resume(source)
        yield()
        assertFalse(played)
    }

    @Test fun expectedFailureKeepsItsMeaningWithoutExceptionText() = runBlocking {
        var failure: PlaybackFailure? = null
        val requests = PlaybackRequests(this, AudioSourceResolver { throw PlaybackException(PlaybackFailure.PERMISSION) },
            { _, _ -> fail("Denied response cannot play") }, { failure = it })
        requests.play(song("denied"))
        yield()
        assertEquals(PlaybackFailure.PERMISSION, failure)
    }
}
