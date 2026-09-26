package io.github.xiangyuplayer.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.github.xiangyuplayer.data.playback.QueueTransferStore
import io.github.xiangyuplayer.data.recommendation.DailyRecommendationCache

// Called only from Dispatchers.IO. Account data never enters ordinary preferences or backups.
interface SessionPersistence {
    fun read(): SavedSession?
    fun save(session: SavedSession)
    fun clear()
}

class SessionStore(context: Context) : SessionPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account.enc"))
    private val gson = Gson()
    private val playbackFile = AtomicFile(File(context.noBackupFilesDir, PLAYBACK_FILE))
    private val queueTransfers = QueueTransferStore(File(context.noBackupFilesDir, QueueTransferStore.DIRECTORY))
    private val dailyCache = DailyRecommendationCache(context.noBackupFilesDir)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun read(): SavedSession? = synchronized(lock) {
        val saved = readRaw() ?: return null
        if (saved.playbackId != null) saved else {
            val migrated = SavedSession(saved.endpoint, saved.userId, saved.nickname, saved.cookies, UUID.randomUUID().toString())
            write(migrated)
            migrated
        }
    }

    private fun readRaw(): SavedSession? {
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), SavedSession::class.java)
    }

    override fun save(session: SavedSession) = synchronized(lock) {
        val previous = readRaw()
        val id = previous?.takeIf { it.endpoint == session.endpoint && it.userId == session.userId }?.playbackId
            ?: UUID.randomUUID().toString()
        if (previous?.playbackId != id) { queueTransfers.clear(); dailyCache.clear() }
        write(SavedSession(session.endpoint, session.userId, session.nickname, session.cookies, id))
    }

    /** Disk operations share the logout lock: stale writes can never recreate a cleared account cache. */
    internal fun <T> withPlaybackOwner(owner: String, action: () -> T): T? = synchronized(lock) {
        if (readRaw()?.playbackId != owner) null else action()
    }

    private fun write(session: SavedSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            revisions.update { it.copy(value = it.value + 1) }
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun clear() = synchronized(lock) {
        file.delete()
        playbackFile.delete()
        queueTransfers.clear()
        dailyCache.clear()
        revisions.update { SessionRevision(it.value + 1, it.accountEpoch + 1) }
    }

    companion object {
        internal const val PLAYBACK_FILE = "playback.json"
        private const val ALIAS = "xiangyu.account.v1"
        private val lock = Any()
        private val revisions = MutableStateFlow(SessionRevision())
        val changes = revisions.asStateFlow()
    }
}

// Deliberately not a data class: toString must never include session cookies.
class SavedSession(val endpoint: String, val userId: String, val nickname: String, val cookies: List<String>, val playbackId: String? = null)

/** Non-sensitive change signal shared by stores in this process. */
data class SessionRevision(val value: Long = 0, val accountEpoch: Long = 0)
