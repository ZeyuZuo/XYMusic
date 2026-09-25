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
        }, { entryId, _, _ -> played += entryId }, { fail("Unexpected failure") })
        requests.play("old-entry", song("old"))
        yield()
        requests.play("new-entry", song("new"))
        yield()
        waiting.getValue("new").resume(source)
        yield()
        waiting.getValue("old").resume(source)
        yield()
        assertEquals(listOf("new-entry"), played)
    }

    @Test fun lateResponseOfTheSameHashCannotStartAPreviousOccurrence() = runBlocking {
        val waiting = mutableListOf<Continuation<AudioSource>>()
        val played = mutableListOf<String>()
        val same = song("same")
        val requests = PlaybackRequests(this, AudioSourceResolver {
            suspendCoroutine { waiting.add(it) }
        }, { entryId, _, _ -> played += entryId }, { fail("Unexpected failure") })
        requests.play("first", same)
        yield()
        requests.play("second", same)
        yield()
        waiting[1].resume(source)
        yield()
        waiting[0].resume(source)
        yield()
        assertEquals(listOf("second"), played)
    }

    @Test fun accountInvalidationDiscardsEvenNonCooperativeResponse() = runBlocking {
        lateinit var response: Continuation<AudioSource>
        var played = false
        val requests = PlaybackRequests(this, AudioSourceResolver { suspendCoroutine { response = it } },
            { _, _, _ -> played = true }, { fail("Cancellation is not an error") })
        requests.play("old-account", song("old-account"))
        yield()
        requests.cancel()
        response.resume(source)
        yield()
        assertFalse(played)
    }

    @Test fun expectedFailureKeepsItsMeaningWithoutExceptionText() = runBlocking {
        var failure: PlaybackFailure? = null
        val requests = PlaybackRequests(this, AudioSourceResolver { throw PlaybackException(PlaybackFailure.PERMISSION) },
            { _, _, _ -> fail("Denied response cannot play") }, { failure = it })
        requests.play("denied", song("denied"))
        yield()
        assertEquals(PlaybackFailure.PERMISSION, failure)
    }
}
