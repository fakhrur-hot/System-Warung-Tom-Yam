package com.razstudio.opsapp.data.api

/**
 * Emitted when the backend returns 401 for a café whose session token was previously valid.
 * This means the OPERATOR credential has been revoked by the café's admin via Devices & Staff.
 *
 * The Cafe_Profile_Shell observes these events to:
 * - Show "Access revoked for this café" to the user
 * - Offer to remove the stale Cafe_Card (disconnect)
 *
 * Satisfies Requirement 6.2 / Correctness Property 4.
 */
data class AccessRevocationEvent(
    /** The café's device row id — matches ConnectedCafeEntity.id */
    val cafeId: String,
    /** Human-readable café name for the UI message */
    val cafeName: String,
)
