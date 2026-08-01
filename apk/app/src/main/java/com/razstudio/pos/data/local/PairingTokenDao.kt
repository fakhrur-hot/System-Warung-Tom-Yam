package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import java.util.UUID

/**
 * DAO for [PairingToken].
 *
 * Provides token lifecycle management for LAN Mode device pairing:
 * - Creating and retrieving valid tokens
 * - Validating tokens (exists, not expired, not used)
 * - Marking tokens as used (single-use enforcement)
 * - Deleting tokens
 *
 * Requirements 5.1, 5.3.
 */
@Dao
interface PairingTokenDao {

    /**
     * Returns the [PairingToken] if it exists, is not expired, and has not been used.
     * A token is valid when:
     * - [PairingToken.token] matches the given token
     * - [PairingToken.expiresAtMs] > current time
     * - [PairingToken.usedAtMs] is null
     *
     * @param token The token string to validate
     * @return The valid token, or null if invalid/expired/used
     */
    @Query("""
        SELECT * FROM pairing_tokens 
        WHERE token = :token 
        AND expiresAtMs > :currentTimeMs 
        AND usedAtMs IS NULL 
        LIMIT 1
    """)
    suspend fun getValidToken(token: String, currentTimeMs: Long): PairingToken?

    /**
     * Marks a token as used by setting [PairingToken.usedAtMs].
     * Called after a successful registration to enforce single-use.
     *
     * @param token The token to mark as used
     * @param usedAtMs Timestamp when the token was used
     */
    @Query("UPDATE pairing_tokens SET usedAtMs = :usedAtMs WHERE token = :token")
    suspend fun markUsed(token: String, usedAtMs: Long)

    /**
     * Generates a new valid pairing token with a default validity window.
     * The token is a random UUID and is immediately inserted into the database.
     *
     * @param validityDurationMs How many milliseconds the token is valid for (default 15 minutes)
     * @return The newly created [PairingToken]
     */
    // Deliberately NOT annotated: this is a plain default method that composes insert() below.
    // @Insert belongs on an abstract method whose parameter is the entity — putting it here asks Room
    // to generate an insert for a Long, and the annotation only survived because KSP skips methods
    // that already have a body.
    suspend fun generateToken(validityDurationMs: Long = DEFAULT_VALIDITY_MS): PairingToken {
        val now = System.currentTimeMillis()
        val token = PairingToken(
            token = UUID.randomUUID().toString(),
            expiresAtMs = now + validityDurationMs,
            usedAtMs = null,
            createdAtMs = now
        )
        insert(token)
        return token
    }

    /**
     * Returns the currently valid (not expired, not used) token, if one exists.
     * Useful for checking if there's an active pairing token available.
     *
     * @return A valid token, or null if none exist
     */
    @Query("""
        SELECT * FROM pairing_tokens 
        WHERE expiresAtMs > :currentTimeMs 
        AND usedAtMs IS NULL 
        ORDER BY createdAtMs DESC 
        LIMIT 1
    """)
    suspend fun getCurrentToken(currentTimeMs: Long): PairingToken?

    /**
     * Permanently removes a token from the database.
     *
     * @param token The token to delete
     */
    @Query("DELETE FROM pairing_tokens WHERE token = :token")
    suspend fun deleteToken(token: String)

    /**
     * Insert a new token (used by [generateToken]).
     */
    @Insert(onConflict = REPLACE)
    suspend fun insert(token: PairingToken)

    companion object {
        /**
         * How long a pairing code stays usable. Fifteen minutes is the window between an admin
         * opening the Devices screen and a staff member walking over with their phone — long enough
         * to be practical, short enough that a code left on a screen overnight is already dead.
         */
        const val DEFAULT_VALIDITY_MS: Long = 15 * 60 * 1000
    }
}
