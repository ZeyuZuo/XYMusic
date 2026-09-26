package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class FmStatus { IDLE, STARTING, LOADING, READY, EMPTY, ERROR }
enum class FmFeedbackStatus { IDLE, SENDING, SUCCESS, ERROR }

/** Service state, with recommendation and explicit feedback requests serialized on the main thread. */
internal class FmCoordinator(
    private val scope: CoroutineScope,
    private val queue: PlaybackQueue,
    private val fetch: suspend (remaining: Int) -> List<Song>,
    private val started: (QueueEntry) -> Unit,
    private val changed: () -> Unit,
    private val submitDislike: suspend (Song, Int) -> Unit = { _, _ -> error("Feedback unavailable") },
    private val disliked: (String) -> Unit = {},
) {
    var status = FmStatus.IDLE
        private set
    private var job: Job? = null
    private var generation = 0L
    private var enabled = false
    private val recent = ArrayDeque<String>()
    private var lastEntry: String? = null
    private var initialEntry: String? = null
    var feedbackStatus = FmFeedbackStatus.IDLE
        private set
    var feedbackEntryId: String? = null
        private set
    private val acknowledged = linkedSetOf<String>()
    val busy get() = status == FmStatus.STARTING || status == FmStatus.LOADING || feedbackStatus == FmFeedbackStatus.SENDING

    fun canDislike(entryId: String?): Boolean {
        val entry = queue.current ?: return false
        return entry.entryId == entryId && queue.sessionType == QueueSessionType.FM && !busy &&
            entryId !in acknowledged && entry.song.source == "personal_fm" &&
            entry.song.sourceId?.toLongOrNull()?.let { it > 0 } == true
    }

    /** Never invoked by skip, completion, process restoration or recommendation retry. */
    fun dislike(entryId: String?): Boolean {
        if (!canDislike(entryId)) return false
        val entry = queue.current ?: return false
        val remaining = queue.remaining
        val ticket = ++generation
        feedbackEntryId = entry.entryId
        feedbackStatus = FmFeedbackStatus.SENDING
        changed()
        job = scope.launch {
            try {
                submitDislike(entry.song, remaining)
                if (ticket != generation || queue.sessionType != QueueSessionType.FM) return@launch
                acknowledged.add(entry.entryId)
                while (acknowledged.size > RECENT_LIMIT) acknowledged.remove(acknowledged.first())
                feedbackStatus = FmFeedbackStatus.SUCCESS
                // A response for an earlier item must not skip the user's newer selection.
                if (queue.current?.entryId == entry.entryId) disliked(entry.entryId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ticket == generation) feedbackStatus = FmFeedbackStatus.ERROR
            } finally {
                if (ticket == generation) {
                    job = null
                    changed()
                    // A track may have advanced while feedback occupied the single request slot.
                    check()
                }
            }
        }
        return true
    }

    fun start() {
        if (queue.sessionType == QueueSessionType.FM || busy) return
        enabled = true
        request(initial = true)
    }

    /** Only an explicit play/resume enables replenishment after process restoration or system stop. */
    fun playbackStarted() {
        if (queue.sessionType != QueueSessionType.FM) return
        enabled = true
        val entry = queue.current
        if (entry != null && entry.entryId != lastEntry) {
            lastEntry = entry.entryId
            recent.addLast(entry.song.hash.lowercase())
            while (recent.size > RECENT_LIMIT) recent.removeFirst()
            queue.trimHistory(HISTORY_LIMIT)
        }
        // Entering FM performs one request even if the first batch is unusually small.
        if (entry != null && initialEntry == entry.entryId) { initialEntry = null; return }
        check()
    }

    fun check() {
        if (enabled && queue.sessionType == QueueSessionType.FM && !busy &&
            status != FmStatus.EMPTY && status != FmStatus.ERROR && queue.remaining <= REFILL_AT) request(initial = false)
    }

    fun retry() {
        if (busy) return
        if (queue.sessionType != QueueSessionType.FM) { start(); return }
        enabled = true
        if (queue.remaining <= REFILL_AT) request(initial = false)
    }

    /** New account, ordinary replacement or stop invalidates even a non-cooperative late fetch. */
    fun cancel(clearHistory: Boolean = false) {
        generation++
        job?.cancel()
        job = null
        enabled = false
        initialEntry = null
        status = FmStatus.IDLE
        feedbackStatus = FmFeedbackStatus.IDLE
        feedbackEntryId = null
        if (clearHistory) { recent.clear(); lastEntry = null; acknowledged.clear() }
        changed()
    }

    private fun request(initial: Boolean) {
        val ticket = ++generation
        status = if (initial) FmStatus.STARTING else FmStatus.LOADING
        changed()
        job = scope.launch {
            try {
                val batch = fetch(if (initial) 0 else queue.remaining)
                if (ticket != generation || !enabled) return@launch
                if (!initial && queue.sessionType != QueueSessionType.FM) return@launch
                val excluded = if (initial) mutableSetOf() else
                    (recent + queue.upcoming.map { it.song.hash.lowercase() } + listOfNotNull(queue.current?.song?.hash?.lowercase())).toMutableSet()
                val capacity = if (initial) PENDING_LIMIT else (PENDING_LIMIT - queue.remaining).coerceAtLeast(0)
                val accepted = batch.filter { excluded.add(it.hash.lowercase()) }.take(capacity)
                if (capacity == 0) {
                    status = FmStatus.READY
                } else if (accepted.isEmpty()) {
                    status = FmStatus.EMPTY
                } else {
                    status = FmStatus.READY
                    if (initial) {
                        recent.clear()
                        lastEntry = null
                        val entry = queue.replace(accepted, 0, QueueSessionType.FM)
                        initialEntry = entry.entryId
                        started(entry)
                    } else queue.append(accepted)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ticket == generation) status = FmStatus.ERROR
            } finally {
                if (ticket == generation) { job = null; changed() }
            }
        }
    }

    companion object {
        const val REFILL_AT = 2
        const val PENDING_LIMIT = 10
        const val HISTORY_LIMIT = 20
        const val RECENT_LIMIT = 50
    }
}
