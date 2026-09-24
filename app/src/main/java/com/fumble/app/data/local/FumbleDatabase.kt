package com.fumble.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoDecisionEntity::class],
    version = 4,
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

        /**
         * Re-queues every favourite, without touching the schema.
         *
         * Up to 4.0.0 favouriting only set Android's `IS_FAVORITE` flag, which Google
         * Photos does not show — so those photos were recorded as done while in practice
         * nothing findable had happened. From 4.1.0 favourites are moved into their own
         * album. Clearing the flag here lets the next apply move the old ones too,
         * instead of leaving them stranded as the only favourites that never arrived.
         *
         * A data-only migration still needs a version bump, or it would never run.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE photo_decision SET favorite_applied = 0 " +
                        "WHERE decision = 'FAVORITE'"
                )
            }
        }

        /**
         * Adds the column that marks album copies, and re-queues every favourite once
         * more.
         *
         * 4.1.0 trusted MediaStore's word that a move had happened. For photos in
         * another app's media area — every WhatsApp picture — it had not: the album
         * stayed empty while the rows were marked done. From 4.1.1 each favourite is
         * located before and after the move and copied where it cannot be moved, so
         * running them all through again settles them for real. Ones already in the
         * album are recognised and cost nothing.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_decision ADD COLUMN copy_of INTEGER")
                db.execSQL(
                    "UPDATE photo_decision SET favorite_applied = 0 " +
                        "WHERE decision = 'FAVORITE'"
                )
            }
        }
    }
}
