package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song

/** Null if the list is empty or any item failed to parse; callers must not mutate the queue in that case. */
internal fun completeSongs(parsed: List<Song?>): List<Song>? {
    if (parsed.isEmpty()) return null
    return parsed.map { it ?: return null }
}

/**
 * Queue-facing command semantics. The service applies the result to the player only after this
 * layer has either left the queue untouched or completed a valid mutation.
 */
internal class QueueCommands(private val queue: PlaybackQueue) {
    sealed class Result {
        data class Play(val entry: QueueEntry, val start: Boolean, val resumeMs: Long = 0L) : Result()
        data object Publish : Result()
        data class Seek(val positionMs: Long) : Result()
        data object Ignored : Result()
        data object Invalid : Result()
    }

    fun insertAndPlay(song: Song) = Result.Play(queue.play(song), start = true)

    fun enqueueNext(song: Song): Result {
        val empty = queue.current == null
        val entry = queue.insertNext(song)
        return if (empty) Result.Play(entry, start = false) else Result.Publish
    }

    /** Parse and index checks happen before [PlaybackQueue.replace], so a bad list cannot stop playback. */
    fun replaceAndPlay(songs: List<Song>?, selectedIndex: Int?): Result {
        if (songs.isNullOrEmpty() || selectedIndex == null || selectedIndex !in songs.indices) return Result.Invalid
        return Result.Play(queue.replace(songs, selectedIndex), start = true)
    }

    fun selectEntry(entryId: String?): Result {
        val entry = entryId?.let(queue::select) ?: return Result.Invalid
        return Result.Play(entry, start = true)
    }

    fun retry(entryId: String?, positionMs: Long): Result {
        val current = queue.current ?: return Result.Ignored
        if (entryId == null || entryId != current.entryId) return Result.Ignored
        return Result.Play(current, start = true, resumeMs = positionMs.coerceAtLeast(0))
    }

    fun seek(entryId: String?, positionMs: Long): Result {
        val current = queue.current ?: return Result.Ignored
        if (entryId == null || entryId != current.entryId) return Result.Ignored
        if (positionMs < 0) return Result.Invalid
        return Result.Seek(positionMs)
    }
}
