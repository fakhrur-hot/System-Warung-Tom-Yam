package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a pairing token used for device registration in LAN Mode.
 *
 * Tokens have a limited validity window ([expiresAtMs]) and can only be used once ([usedAtMs]).
 * The token itself is the primary key — a random UUID string that clients present during
 * registration. A token is valid when:
 * - It exists in the database
 * - [expiresAtMs] is in the future
 * - [usedAtMs] is null (not yet used)
 *
 * Requirements 5.1, 5.3.
 */
@Entity(tableName = "pairing_tokens")
data class PairingToken(
    /** The token string itself — primary key, a random UUID. */
    @PrimaryKey val token: String,
    /** Epoch-millisecond timestamp after which this token is no longer valid. */
    val expiresAtMs: Long,
    /** Epoch-millisecond timestamp when the token was used, or null if not yet used. */
    val usedAtMs: Long? = null,
    /** Epoch-millisecond timestamp when this token was created. */
    val createdAtMs: Long
)