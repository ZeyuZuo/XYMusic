package io.github.xiangyuplayer.domain.model

/** Store identifiers, never a long-lived playback URL. Durations are milliseconds. */
data class Song(
    val hash: String,
    val title: String,
    val artists: List<String>,
    val albumId: String? = null,
    val albumAudioId: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
)
