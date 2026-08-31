// FILE: InMemoryRoom.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Build a Room database backed by an in-memory SupportSQLiteDatabase for tests.
//   SCOPE: construct + close a RoomDatabase for a given DAO set.
//   DEPENDS: M-LOCAL-DB (Room entities/DAOs)
//   LINKS: M-LOCAL-DB
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.yicamalt.database.YiDatabase

// START_MODULE_MAP
//   InMemoryRoom - provides a fresh in-memory YiDatabase per test.
// END_MODULE_MAP

object InMemoryRoom {

    private val initCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            // START_BLOCK_INIT_DB
            timber.log.Timber.d("[DB][init][BLOCK_INIT_DB] schema v${db.version} created")
            // END_BLOCK_INIT_DB
        }
    }

    fun build(): YiDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, YiDatabase::class.java)
            .addCallback(initCallback)
            .allowMainThreadQueries()
            .build()
    }
}