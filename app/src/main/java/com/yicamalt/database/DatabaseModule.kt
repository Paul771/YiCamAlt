// FILE: DatabaseModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt provider that builds the production YiDatabase with a RoomDatabase.Callback emitting BLOCK_INIT_DB.
//   SCOPE: @Provides singleton YiDatabase + per-DAO accessors.
//   DEPENDS: M-LOCAL-DB, M-CONFIG
//   LINKS: M-LOCAL-DB
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// START_MODULE_MAP
//   DatabaseModule - Hilt module providing YiDatabase + DAOs.
// END_MODULE_MAP

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val initCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            // START_BLOCK_INIT_DB
            timber.log.Timber.d("[DB][init][BLOCK_INIT_DB] schema v${db.version} created")
            // END_BLOCK_INIT_DB
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YiDatabase =
        Room.databaseBuilder(context, YiDatabase::class.java, "yicamalt.db")
            .addCallback(initCallback)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideCameraDao(db: YiDatabase): CameraDao = db.cameraDao()
    @Provides fun provideEventDao(db: YiDatabase): EventDao = db.eventDao()
    @Provides fun provideAppMetaDao(db: YiDatabase): AppMetaDao = db.appMetaDao()
}