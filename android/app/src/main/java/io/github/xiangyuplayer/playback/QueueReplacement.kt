package io.github.xiangyuplayer.playback

import java.util.UUID

/** Service-thread ticket. Invalidating it also invalidates IO which has already started. */
internal class QueueReplacement {
    data class Ticket(val reference: String, val owner: String, val epoch: Long)
    var pending: Ticket? = null
        private set

    fun begin(owner: String, epoch: Long): Ticket =
        Ticket(UUID.randomUUID().toString(), owner, epoch).also { pending = it }

    fun accepts(ticket: Ticket, owner: String?, epoch: Long): Boolean =
        pending == ticket && ticket.owner == owner && ticket.epoch == epoch

    fun invalidate(): Ticket? = pending.also { pending = null }
}
