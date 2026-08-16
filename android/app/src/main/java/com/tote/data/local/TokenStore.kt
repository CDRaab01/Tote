package com.tote.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Deliberately its own store, separate from any future app-settings store: auth state and
// settings have different lifecycles, and logout must clear this one WITHOUT wiping the user's
// preferences.
private val Context.authDataStore by preferencesDataStore(name = "tote_auth")

/**
 * The slice of the session the renewal path needs.
 *
 * A seam, not indirection for its own sake: `TokenAuthenticator`'s rules are about *when* to
 * renew, retry, or sign out, and testing them through a real DataStore would mean a Robolectric
 * context and a file-backed singleton shared between tests — flakiness bought for nothing.
 */
interface SessionTokens {
    suspend fun currentAccessToken(): String?

    suspend fun currentRefreshToken(): String?

    suspend fun save(access: String, refresh: String)

    suspend fun clear()
}

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionTokens {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    val accessToken: Flow<String?> = context.authDataStore.data.map { it[accessKey] }

    override suspend fun currentAccessToken(): String? = accessToken.first()

    override suspend fun currentRefreshToken(): String? =
        context.authDataStore.data.map { it[refreshKey] }.first()

    override suspend fun save(access: String, refresh: String) {
        context.authDataStore.edit {
            it[accessKey] = access
            it[refreshKey] = refresh
        }
    }

    override suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }
}
