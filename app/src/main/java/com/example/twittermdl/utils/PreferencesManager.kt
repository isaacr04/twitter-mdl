package com.example.twittermdl.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.twittermdl.data.UserCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val PASSWORD_KEY = stringPreferencesKey("password")
        private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
        private val CT0_KEY = stringPreferencesKey("ct0")
        private val IS_LOGGED_IN_KEY = booleanPreferencesKey("is_logged_in")

        // Thumbnail settings
        private val GENERATE_GIFS_KEY = booleanPreferencesKey("generate_gifs_for_thumbnails")
        private val DELETE_LOCAL_FILES_KEY = booleanPreferencesKey("delete_local_files_with_history")
    }

    val userCredentials: Flow<UserCredentials?> = context.dataStore.data.map { preferences ->
        val username = preferences[USERNAME_KEY]
        val password = preferences[PASSWORD_KEY]
        val authToken = preferences[AUTH_TOKEN_KEY]
        val ct0 = preferences[CT0_KEY]

        if (username != null && password != null) {
            UserCredentials(username, password, authToken, ct0)
        } else {
            null
        }
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOGGED_IN_KEY] ?: false
    }

    val generateGifsForThumbnails: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GENERATE_GIFS_KEY] ?: true  // Default: enabled
    }

    val deleteLocalFilesWithHistory: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DELETE_LOCAL_FILES_KEY] ?: false  // Default: disabled
    }

    suspend fun saveCredentials(credentials: UserCredentials) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = credentials.username
            preferences[PASSWORD_KEY] = credentials.password
            credentials.authToken?.let { preferences[AUTH_TOKEN_KEY] = it }
            credentials.ct0?.let { preferences[CT0_KEY] = it }
            preferences[IS_LOGGED_IN_KEY] = true
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { preferences ->
            preferences.remove(USERNAME_KEY)
            preferences.remove(PASSWORD_KEY)
            preferences.remove(AUTH_TOKEN_KEY)
            preferences.remove(CT0_KEY)
            preferences[IS_LOGGED_IN_KEY] = false
        }
    }

    suspend fun updateAuthTokens(authToken: String, ct0: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_TOKEN_KEY] = authToken
            preferences[CT0_KEY] = ct0
        }
    }

    suspend fun setGenerateGifsForThumbnails(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GENERATE_GIFS_KEY] = enabled
        }
    }

    suspend fun setDeleteLocalFilesWithHistory(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DELETE_LOCAL_FILES_KEY] = enabled
        }
    }
}
