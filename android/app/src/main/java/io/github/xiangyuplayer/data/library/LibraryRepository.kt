package io.github.xiangyuplayer.data.library

import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository {
    suspend fun fetch(session: AccountSession, page: Int): LibraryPage = withContext(Dispatchers.IO) {
        require(page > 0)
        val saved = session.saved
        val client = KuGouClient(saved.endpoint)
        try {
            client.session.restore(ApiEndpoint.parse(saved.endpoint, BuildConfig.DEBUG), saved.cookies)
            LibraryResponse.parse(client.api.userPlaylists(LibraryRequest(page)))
        } finally {
            client.close()
        }
    }
}

/** All paging parameters are explicit; credentials remain in the API client's cookie jar. */
data class LibraryRequest(val page: Int, val pagesize: Int = PAGE_SIZE) {
    init { require(page > 0 && pagesize in 1..PAGE_SIZE) }

    companion object { const val PAGE_SIZE = 30 }
}
