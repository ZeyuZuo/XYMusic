package io.github.xiangyuplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val apiKey = stringPreferencesKey("api_base_url")

    val apiBaseUrl = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it[apiKey].orEmpty() }

    suspend fun saveApiBaseUrl(value: String) {
        val normalized = ApiEndpoint.parse(value, allowHttp = BuildConfig.DEBUG).toString()
        store.edit { it[apiKey] = normalized }
    }
}
