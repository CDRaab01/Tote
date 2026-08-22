package com.tote

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tote.work.UploadWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Application entry point.
 *
 * Phase 1 adds the suite config broker read here (`util/SuiteConfigReader` against
 * `content://com.dragonfly.suiteconfig/config/tote`, falling back to local prefs) — the same
 * shape every sibling app uses.
 */
@HiltAndroidApp
class ToteApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Coil rides the app's own OkHttp client.
     *
     * Not a detail: item photos are served from `GET /items/{id}/photos/{order}`, which is
     * authenticated — these are photographs of the inside of someone's house. A default Coil
     * loader has no `AuthInterceptor` and every thumbnail in the review stack would 401.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            // A photo fading in reads as loading; one popping in reads as the list glitching.
            // No placeholder painter — every call site already sits on a panel-toned box.
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Resume any capture uploads stranded by process death. The queue is the one place in
        // this app holding data the server has never seen, so it gets a nudge on every start
        // rather than waiting for the user to open the capture screen.
        UploadWorker.kick(WorkManager.getInstance(this))
    }
}
