package com.tote.di

import android.content.Context
import androidx.room.Room
import com.tote.data.local.CatalogDao
import com.tote.data.local.ToteDatabase
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
            // The catalog cache is a disposable snapshot of server state, so a schema mismatch
            // can safely rebuild it — the next refresh restores everything. This will NOT be
            // safe once Phase 4 adds the photo capture queue to this database: queued captures
            // are data that exists nowhere else. Split the queue into its own database, or add
            // real migrations, BEFORE that lands.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCatalogDao(db: ToteDatabase): CatalogDao = db.catalogDao()
}
