package com.fumble.app.di

import android.content.Context
import androidx.room.Room
import com.fumble.app.data.local.FumbleDatabase
import com.fumble.app.data.local.PhotoDecisionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FumbleDatabase =
        Room.databaseBuilder(context, FumbleDatabase::class.java, FumbleDatabase.NAME)
            .addMigrations(FumbleDatabase.MIGRATION_1_2)
            // Last resort only. Every schema change should ship a real migration —
            // losing the history means showing the user every kept photo again.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePhotoDecisionDao(database: FumbleDatabase): PhotoDecisionDao =
        database.photoDecisionDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
