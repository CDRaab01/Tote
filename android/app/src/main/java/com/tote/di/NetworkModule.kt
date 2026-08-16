package com.tote.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.tote.BuildConfig
import com.tote.data.remote.ApiService
import com.tote.data.remote.AuthInterceptor
import com.tote.data.remote.ScanTimeoutInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

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

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        scanTimeout: ScanTimeoutInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // BODY logging in a release build would print bearer tokens to logcat.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
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
}
