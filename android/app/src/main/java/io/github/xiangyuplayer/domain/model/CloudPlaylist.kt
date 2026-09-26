package io.github.xiangyuplayer.domain.model

/** Account list identifiers are distinct from public collection and song identifiers. */
data class CloudPlaylistRef(val listId: String, val type: Int) {
    val key: String get() = "$type:$listId"
}

enum class PlaylistCategory { CREATED, COLLECTED, UNKNOWN }

data class CloudPlaylist(
    val ref: CloudPlaylistRef,
    val globalCollectionId: String?,
    val title: String,
    val songCount: Long,
    val coverUrl: String?,
    val systemFlag: Int?,
) {
    val isLiked: Boolean get() = ref.type == 0 && systemFlag == 2
    val category: PlaylistCategory get() = when (ref.type) {
        0 -> PlaylistCategory.CREATED
        1 -> PlaylistCategory.COLLECTED
        else -> PlaylistCategory.UNKNOWN
    }
}
