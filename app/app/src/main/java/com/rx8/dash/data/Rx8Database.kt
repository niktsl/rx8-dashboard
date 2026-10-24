package com.rx8.dash.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1 schema, exported to app/schemas (see the ksp room.schemaLocation arg). Any future change needs a real
 * Migration plus a MigrationTestHelper test; destructive fallback is never used.
 */
@Database(
    entities = [
        TripEntity::class,
        SampleEntity::class,
        MaintenanceEntity::class,
        DtcHistoryEntity::class,
        FillUpEntity::class,
        StartLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class Rx8Database : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun sampleDao(): SampleDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun dtcHistoryDao(): DtcHistoryDao
    abstract fun fillUpDao(): FillUpDao
    abstract fun startLogDao(): StartLogDao

    companion object {
        const val NAME: String = "rx8dash.db"

        /** Build once per process (AppGraph owns the instance). */
        fun build(context: Context): Rx8Database =
            Room.databaseBuilder(context.applicationContext, Rx8Database::class.java, NAME).build()
    }
}
