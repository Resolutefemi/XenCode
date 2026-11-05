package com.xencode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.xencode.feature.agent.data.local.entity.XpEventEntity

@Dao
interface XpEventDao {

    @Insert
    suspend fun insert(event: XpEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<XpEventEntity>)

    @Query("SELECT * FROM xp_events ORDER BY createdAt ASC, id ASC")
    suspend fun getAllOnce(): List<XpEventEntity>

    @Query("SELECT * FROM xp_events WHERE createdAt >= :since ORDER BY createdAt ASC, id ASC")
    suspend fun getSince(since: Long): List<XpEventEntity>

    @Query("SELECT * FROM xp_events WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<XpEventEntity>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_events")
    suspend fun totalXp(): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_events WHERE createdAt >= :since")
    suspend fun totalXpSince(since: Long): Long

    @Query("SELECT COUNT(*) FROM xp_events")
    suspend fun count(): Int

    @Query("DELETE FROM xp_events")
    suspend fun clear()

    /** Paged read (keyset) for streaming backup export. */
    @Query("SELECT * FROM xp_events WHERE createdAt > :lastCreatedAt OR (createdAt = :lastCreatedAt AND id > :lastId) ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastCreatedAt: Long, lastId: Long, limit: Int): List<XpEventEntity>
}
