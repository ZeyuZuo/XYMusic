package io.github.xiangyuplayer.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Exposes local queue traversal through standard system/earphone media commands. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class QueueSessionPlayer(
    player: Player,
    private val queue: PlaybackQueue,
    private val onSkip: (forward: Boolean) -> Unit,
    private val onResume: () -> Boolean,
    private val onStop: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (queue.hasNext) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (queue.hasPrevious) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    fun refreshQueue() = invalidateState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && onResume()) return Futures.immediateVoidFuture()
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onSkip(true)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onSkip(false)
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }
}
