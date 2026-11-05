package com.xencode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xencode.feature.agent.data.local.entity.LlmCallRecordEntity
import kotlinx.coroutines.flow.Flow

/** Per-day call stats aggregated by day (local timezone), for trend charts. day is the epoch day number in the local timezone. */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** Call stats aggregated by provider, for provider rankings. */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val retryCount: Long = 0
)

/** Call stats aggregated by model, for model rankings. */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** Sum of tokens written to the cache (Anthropic cache_creation), counted in cost estimates at a separate unit price. */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int,
    val retryCount: Long = 0
)

/**
 * Usage aggregated by "provider + model", exclusively for period total cost estimation.
 * The unit price is determined jointly by the provider (custom metadata keyed by "providerId:modelName") and the model;
 * aggregating by model alone would drop the provider dimension and miss custom unit prices.
 */
data class ModelProviderCostStats(
    val providerId: String?,
    val model: String?,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long
)

/** Overall summary for the current period, for the overview card. */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** Paged call detail record: a single call + provider name (fetched via LEFT JOIN ai_providers; still shown after the provider is deleted). */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * Aggregated by day (local timezone: shift createdAt by [tzOffsetMillis], then floor-divide by 86400000 to split days).
     * [tzOffsetMillis] is provided by the caller based on the current system timezone (TimeZone.getDefault().getOffset(now)).
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** Aggregated by hour (local timezone); the "today" period shows trends at hourly granularity. */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** Aggregated by provider; LEFT JOIN ai_providers for the provider name (records still show after the provider is deleted). */
    @Query(
        """
        SELECT r.providerId AS providerId,
               p.name AS providerName,
               COUNT(*) AS calls,
               SUM(r.inputTokens) AS inputTokens,
               SUM(r.outputTokens) AS outputTokens,
               SUM(r.cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN r.status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(r.retryCount) AS retryCount
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        GROUP BY r.providerId
        ORDER BY (SUM(r.inputTokens) + SUM(r.outputTokens)) DESC
        """
    )
    fun getProviderStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ProviderCallStats>>

    /** Aggregated by model, in descending order of total consumption. */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelCallStats>>

    /** Token usage aggregated by "provider + model", for period total cost estimation (cost must carry the provider to apply custom unit prices). */
    @Query(
        """
        SELECT providerId AS providerId,
               model AS model,
               SUM(inputTokens) AS inputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY providerId, model
        """
    )
    fun getModelProviderCostStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelProviderCostStats>>

    /** Overall period summary (no GROUP BY, always returns one row). */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getSummary(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<CallSummary>

    /** Total calls in the current period, for showing the page count in pagination. */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getCallsCount(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<Int>

    /** Paged call details (descending, [limit] rows from [offset]), LEFT JOIN for the provider name. */
    @Query(
        """
        SELECT r.*, p.name AS providerName
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        ORDER BY r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(
        start: Long,
        limit: Int,
        offset: Int,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<RecentCallRecord>>
}
