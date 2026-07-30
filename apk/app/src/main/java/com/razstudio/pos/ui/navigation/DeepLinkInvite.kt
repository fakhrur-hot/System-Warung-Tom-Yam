package com.razstudio.pos.ui.navigation

/**
 * Lightweight one-shot holder for an invite token that arrived via a deep link
 * (`https://<host>/join?invite=TOKEN`). [MainActivity] writes it from the launch intent;
 * `OrderingConnectViewModel` reads and clears it on init to pre-fill the invite field so
 * the device can register immediately. Deliberately process-scoped and transient — it is
 * never persisted.
 */
object DeepLinkInvite {
    @Volatile
    var pendingToken: String? = null

    @Volatile
    var pendingRecoverToken: String? = null

    /** Return and clear the pending token (consume-once). */
    fun consume(): String? {
        val t = pendingToken
        pendingToken = null
        return t
    }

    /** Return and clear the pending recovery token (consume-once). */
    fun consumeRecover(): String? {
        val t = pendingRecoverToken
        pendingRecoverToken = null
        return t
    }
}
