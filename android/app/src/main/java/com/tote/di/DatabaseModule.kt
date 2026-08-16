package com.tote.di

import android.content.Context
import androidx.room.Room
import com.tote.data.local.CatalogDao
import com.tote.data.local.ToteDatabase
import com.tote.data.local.ToteMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ToteDatabase =
        Room.databaseBuilder(context, ToteDatabase::class.java, "tote.db")
            // NO fallbackToDestructiveMigration, deliberately.
            //
            // It used to be here, and it was safe only while this database held nothing but a
            // disposable copy of server state. Phase 4 puts the photo capture queue in here:
            // forty photos taken in a garage with no signal, which the server has never seen.
            // For that data this database is the ONLY copy, and a destructive fallback would
            // delete it on the next schema bump — silently, with no crash and no error, leaving
            // the JPEGs orphaned on disk with nothing recording what they were of.
            //
            // Without the fallback, a missing migration is a LOUD failure (the app refuses to
            // open the database) instead of a quiet one. That is the trade, and it is the right
            // way round: a crash gets fixed, a silent wipe gets discovered months later by
            // someone who thinks the camera is broken.
            //
            // `ToteDatabaseMigrationTest` walks every exported schema version, so shipping a
            // bump without a migration fails in CI rather than on a phone.
            .addMigrations(*ToteMigrations.ALL)
            .build()

    @Provides
    fun provideCatalogDao(db: ToteDatabase): CatalogDao = db.catalogDao()
}
