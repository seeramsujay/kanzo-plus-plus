package io.kanzo.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kanzo_settings")

class SettingsStore(private val context: Context) {
    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val BUNKER_TICKET = stringPreferencesKey("bunker_ticket")
        val SELECTED_PROJECT = stringPreferencesKey("selected_project")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL] ?: "http://10.0.2.2:4242"
    }

    val bunkerTicket: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BUNKER_TICKET] ?: ""
    }

    val selectedProject: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_PROJECT] ?: "all"
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL] = url
        }
    }

    suspend fun saveBunkerTicket(ticket: String) {
        context.dataStore.edit { preferences ->
            preferences[BUNKER_TICKET] = ticket
        }
    }

    suspend fun saveSelectedProject(slug: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_PROJECT] = slug
        }
    }
}
