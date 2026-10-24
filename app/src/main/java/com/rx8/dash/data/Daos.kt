package com.rx8.dash.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(trips: List<TripEntity>)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeById(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips ORDER BY startMs DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startMs ASC")
    suspend fun getAll(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE endMs IS NULL")
    suspend fun getUnfinished(): List<TripEntity>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trips")
    suspend fun clear()
}

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun observeForTrip(tripId: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getForTrip(tripId: Long): List<SampleEntity>

    /** Keyset page over one trip, for streaming CSV export. */
    @Query("SELECT * FROM samples WHERE tripId = :tripId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageForTrip(tripId: Long, afterId: Long, limit: Int): List<SampleEntity>

    /** Keyset page over all samples, for streaming JSON export. */
    @Query("SELECT * FROM samples WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun page(afterId: Long, limit: Int): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    @Query("DELETE FROM samples WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)

    @Query("DELETE FROM samples")
    suspend fun clear()
}

@Dao
interface MaintenanceDao {
    /** Inserts only items whose id is not present yet (used for seeding without overwriting user edits). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(items: List<MaintenanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MaintenanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MaintenanceEntity>)

    @Query("SELECT * FROM maintenance ORDER BY sortOrder ASC, title ASC")
    fun observeAll(): Flow<List<MaintenanceEntity>>

    @Query("SELECT * FROM maintenance ORDER BY sortOrder ASC, title ASC")
    suspend fun getAll(): List<MaintenanceEntity>

    @Query("SELECT * FROM maintenance WHERE id = :id")
    suspend fun getById(id: String): MaintenanceEntity?

    @Query("SELECT COUNT(*) FROM maintenance")
    suspend fun count(): Int

    @Query("DELETE FROM maintenance WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM maintenance")
    suspend fun clear()
}

@Dao
interface DtcHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DtcHistoryEntity>)

    @Query("SELECT * FROM dtc_history ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<DtcHistoryEntity>>

    @Query("SELECT * FROM dtc_history ORDER BY timestampMs ASC")
    suspend fun getAll(): List<DtcHistoryEntity>

    @Query("UPDATE dtc_history SET cleared = 1 WHERE cleared = 0")
    suspend fun markAllCleared()

    @Query("DELETE FROM dtc_history")
    suspend fun clear()
}

@Dao
interface FillUpDao {
    @Insert
    suspend fun insert(fill: FillUpEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fills: List<FillUpEntity>)

    @Query("SELECT * FROM fill_ups ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<FillUpEntity>>

    @Query("SELECT * FROM fill_ups ORDER BY timestampMs ASC")
    suspend fun getAll(): List<FillUpEntity>

    @Query("DELETE FROM fill_ups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM fill_ups")
    suspend fun clear()
}

@Dao
interface StartLogDao {
    @Insert
    suspend fun insert(entry: StartLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<StartLogEntity>)

    @Query("SELECT * FROM start_log ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<StartLogEntity>>

    @Query("SELECT * FROM start_log ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<StartLogEntity>

    @Query("SELECT * FROM start_log ORDER BY timestampMs ASC")
    suspend fun getAll(): List<StartLogEntity>

    @Query("DELETE FROM start_log")
    suspend fun clear()
}
