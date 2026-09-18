package io.github.xiangyuplayer.domain.model

/** Temporary URL stays inside the service/data boundary; never persisted or included in toString. */
class AudioSource(
    val url: String,
    val access: PlaybackAccess,
    val previewDurationMs: Long? = null,
    val previewStartMs: Long = 0,
)
enum class PlaybackAccess { FULL, PREVIEW }
enum class PlaybackFailure { ACCOUNT, NETWORK, PERMISSION, UNAVAILABLE, RESPONSE, PLAYER }
class PlaybackException(val failure: PlaybackFailure) : Exception()

fun interface AudioSourceResolver {
    suspend fun resolve(song: Song): AudioSource
}
