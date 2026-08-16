package com.tote.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.tote.BuildConfig
import com.tote.data.local.SessionTokens
import com.tote.data.local.TokenStore
import com.tote.data.remote.ApiService
import com.tote.data.remote.AuthInterceptor
import com.tote.data.remote.RefreshApi
import com.tote.data.remote.ScanTimeoutInterceptor
import com.tote.data.remote.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Marks the bare client/Retrofit used only for session renewal — no auth header, no
 * authenticator, so a 401 on renewal cannot recurse into another renewal.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The server is allowed to add fields without breaking an older client on someone's
        // phone — the update train is not instant.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun logging(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // BODY logging in a release build would print bearer tokens to logcat.
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        scanTimeout: ScanTimeoutInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient {
        val logging = logging()
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            // Renews the session on a 401 and replays the call, so an expired access token is
            // invisible to every screen instead of surfacing as a permanent failure.
            .authenticator(tokenAuthenticator)
            // Must be an application interceptor, not a network one: `withReadTimeout` only
            // affects the chain from this point on.
            .addInterceptor(scanTimeout)
            .addInterceptor(logging)
            // Stated rather than left to the defaults, because one call deliberately overrides
            // them and an implicit baseline makes that override impossible to reason about.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // BuildConfig.SERVER_URL comes from local.properties / the TOTE_SERVER_URL Actions
        // variable, and is overridden at runtime by the Dragonfly config broker.
        .baseUrl(BuildConfig.SERVER_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshRetrofit(@RefreshClient client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideRefreshApi(@RefreshClient retrofit: Retrofit): RefreshApi =
        retrofit.create(RefreshApi::class.java)

    @Provides
    @Singleton
    fun provideSessionTokens(store: TokenStore): SessionTokens = store
}
