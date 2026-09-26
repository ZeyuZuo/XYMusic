package io.github.xiangyuplayer.data.remote

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Headers

/** Transport only. Map business errors and validated response fields in a repository. */
interface KuGouApi {
    @Headers("X-Apicache-Bypass: 1")
    @POST("personal/fm")
    suspend fun personalFm(@Body body: io.github.xiangyuplayer.data.recommendation.FmRequest): JsonObject

    @Headers("X-Apicache-Bypass: 1")
    @POST("everyday/recommend")
    suspend fun dailyRecommendation(@Body body: Map<String, String> = mapOf("platform" to "android")): JsonObject

    @POST("register/dev")
    suspend fun registerDevice(): JsonObject

    @POST("captcha/sent")
    suspend fun sendCaptcha(@Body body: Map<String, String>): JsonObject

    @POST("login/cellphone")
    suspend fun loginCellphone(@Body body: Map<String, String>): JsonObject

    @POST("login/token")
    suspend fun refreshLogin(@Body body: Map<String, String> = emptyMap()): JsonObject

    @POST("user/verify")
    suspend fun verifyUser(@Body body: Map<String, String> = emptyMap()): JsonObject

    @POST("user/detail")
    suspend fun userDetail(@Body body: Map<String, String> = emptyMap()): JsonObject

    @GET("search")
    suspend fun searchSongs(
        @Query("keywords") keywords: String,
        @Query("page") page: Int = 1,
        @Query("pagesize") pageSize: Int = 30,
        @Query("type") type: String = "song",
    ): JsonObject

    @GET("song/url")
    suspend fun songUrl(
        @Query("hash") hash: String,
        @Query("album_id") albumId: String? = null,
        @Query("album_audio_id") albumAudioId: String? = null,
        @Query("quality") quality: String = "128",
        @Query("free_part") freePart: Int? = null,
    ): JsonObject

    @Headers("X-Apicache-Bypass: 1")
    @GET("search/lyric")
    suspend fun searchLyrics(@Query("hash") hash: String): JsonObject

    // The proxy cache keys only by URL; lyric IDs and download keys remain in the body.
    @Headers("X-Apicache-Bypass: 1")
    @POST("lyric")
    suspend fun lyrics(@Body body: Map<String, String>): JsonObject
}
