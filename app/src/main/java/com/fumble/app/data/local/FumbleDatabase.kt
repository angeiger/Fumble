package com.fumble.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoDecisionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FumbleDatabase : RoomDatabase() {

    abstract fun photoDecisionDao(): PhotoDecisionDao

    companion object {
        /**
         * Pre-rename filename, kept deliberately. A new name would mean a new, empty
         * database and every photo the user has already ruled on coming back.
         */
        const val NAME = "inder.db"

        /**
         * Adds the favourite queue's completion flag.
         *
         * Written out rather than left to a destructive fallback: by the time
         * favourites arrived people were already using the app, and wiping the table
         * would have shown them every photo they had ever kept all over again.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE photo_decision " +
                        "ADD COLUMN favorite_applied INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
