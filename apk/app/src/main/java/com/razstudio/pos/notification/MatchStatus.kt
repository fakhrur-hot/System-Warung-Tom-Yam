package com.razstudio.pos.notification

/**
 * The correlation state of a captured payment notification to a pending order.
 */
enum class MatchStatus {
    /** Successfully matched to exactly one pending order. */
    MATCHED,
    /** Multiple orders share the same total amount — needs admin disambiguation. */
    AMBIGUOUS,
    /** No active order matches this amount. */
    UNMATCHED,
    /** Admin dismissed as non-order (personal transfer, etc.). */
    DISMISSED,
}
