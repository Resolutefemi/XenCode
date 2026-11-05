package com.xencode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only XP ledger. Every award is one row; summaries are derived on read,
 * so the curve can evolve without rewriting history.
 *
 * Table created by migration 59 (`59_add_xp_events.sql`).
 */
@Entity(tableName = "xp_events")
data class XpEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning chat session (or 0-length for global events like manual adjustments). */
    val sessionId: String,
    /** [com.xencode.feature.agent.domain.telemetry.XpTelemetry.Kind] name. */
    val kind: String,
    val amount: Long,
    /** Free-form label shown in /xp (turn summary line, goal title, ...). */
    val label: String,
    val createdAt: Long
)
