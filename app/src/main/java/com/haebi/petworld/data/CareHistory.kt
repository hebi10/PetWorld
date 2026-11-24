package com.haebi.petworld.data

import androidx.room.withTransaction
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val CARE_HISTORY_PAGE_SIZE = 50

data class CareHistoryRequest(
    val petId: String,
    val type: CareType?,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val limit: Int = CARE_HISTORY_PAGE_SIZE,
) {
    init {
        require(petId.isNotBlank() && limit > 0)
        require((firstDay == null) == (lastDay == null))
        if (firstDay != null && lastDay != null) require(firstDay <= lastDay)
    }

    fun sameFilter(other: CareHistoryRequest): Boolean =
        copy(limit = other.limit) == other
}

data class CareHistoryPage(val entries: List<CareEntity>, val totalCount: Int)

/**
 * Keep Room's existing schema and the recorded timezone's date semantics.
 * Whole-history reads use SQL LIMIT; bounded dates use a lightweight index
 * across the widest valid UTC offsets, then hydrate only visible record IDs.
 */
internal class CareHistoryReader(private val database: PetWorldDatabase) {
    private val dao = database.careDao()

    fun observe(request: CareHistoryRequest): Flow<CareHistoryPage> =
        database.invalidationTracker.createFlow("care_entries")
            .map { read(request) }
            .distinctUntilChanged()

    suspend fun read(request: CareHistoryRequest): CareHistoryPage = database.withTransaction {
        val type = request.type?.name
        val firstDay = request.firstDay
        val lastDay = request.lastDay
        if (firstDay == null || lastDay == null) {
            return@withTransaction CareHistoryPage(
                dao.historyFirst(request.petId, type, request.limit),
                dao.countHistory(request.petId, type),
            )
        }

        val from = firstDay.atStartOfDay().toInstant(ZoneOffset.MAX).toEpochMilli()
        val until = lastDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.MIN).toEpochMilli()
        val ids = ArrayList<String>(minOf(request.limit, CARE_HISTORY_PAGE_SIZE))
        var count = 0
        val fallbackZone = ZoneId.systemDefault()
        val zoneCache = mutableMapOf<String, ZoneId>()
        for (item in dao.historyIndex(request.petId, type, from, until)) {
            val zone = zoneCache.getOrPut(item.timezoneId) {
                runCatching { ZoneId.of(item.timezoneId) }.getOrDefault(fallbackZone)
            }
            val day = Instant.ofEpochMilli(item.occurredAt).atZone(zone).toLocalDate()
            if (day < firstDay || day > lastDay) continue
            count++
            if (ids.size < request.limit) ids.add(item.id)
        }
        val recordsById = ids.chunked(200).flatMap { chunk ->
            dao.historyByIds(request.petId, chunk)
        }.associateBy { it.id }
        CareHistoryPage(ids.map { recordsById.getValue(it) }, count)
    }
}
