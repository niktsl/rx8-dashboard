package com.rx8.dash.data

import com.rx8.dash.core.maint.MaintenanceDefaults
import com.rx8.dash.core.maint.MaintenanceItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Maintenance reminders. Defaults from core are seeded on first use without overwriting user edits. */
class MaintenanceRepository(db: Rx8Database) {

    private val dao: MaintenanceDao = db.maintenanceDao()
    private val seedLock = Mutex()
    @Volatile private var seeded = false

    val items: Flow<List<MaintenanceItem>> = dao.observeAll()
        .onStart { seedDefaults() }
        .map { list -> list.map { it.toItem() } }

    /**
     * Inserts any core default whose id is missing (first run, or a default added in a later app version).
     * Existing rows are never touched.
     */
    suspend fun seedDefaults() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            dao.insertIfAbsent(
                MaintenanceDefaults.items.mapIndexed { index, item -> item.toEntity(sortOrder = index) },
            )
            seeded = true
        }
    }

    suspend fun get(id: String): MaintenanceItem? = dao.getById(id)?.toItem()

    /** Inserts or replaces [item], keeping its existing sort position. */
    suspend fun update(item: MaintenanceItem) {
        val order = dao.getById(item.id)?.sortOrder ?: (dao.count() + 100)
        dao.upsert(item.toEntity(sortOrder = order))
    }

    /** Records the service as done now at [odometerKm]. */
    suspend fun markDone(id: String, odometerKm: Double, nowMs: Long = System.currentTimeMillis()) {
        val existing = dao.getById(id) ?: return
        dao.upsert(existing.copy(lastKm = odometerKm, lastDateMs = nowMs))
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    companion object {
        fun MaintenanceEntity.toItem(): MaintenanceItem = MaintenanceItem(
            id = id,
            title = title,
            intervalKm = intervalKm,
            intervalDays = intervalDays,
            lastKm = lastKm,
            lastDateMs = lastDateMs,
            notes = notes,
        )

        fun MaintenanceItem.toEntity(sortOrder: Int): MaintenanceEntity = MaintenanceEntity(
            id = id,
            title = title,
            intervalKm = intervalKm,
            intervalDays = intervalDays,
            lastKm = lastKm,
            lastDateMs = lastDateMs,
            notes = notes,
            sortOrder = sortOrder,
        )
    }
}
