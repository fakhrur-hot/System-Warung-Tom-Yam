package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a Client Device that has been paired with this Server Device in LAN Mode.
 *
 * Each row records the device's identity ([id], [name], [model]), its assigned [role] in the café,
 * its current connection [status], a [credentialHash] used to verify pairing credentials, and
 * [lastSeenMs] so the operator can see which devices are recently active.
 *
 * The operator can view and revoke paired devices from the Devices screen (Requirement 5.6). Pairing
 * credentials are single-use and expire; [credentialHash] is a hash of the credential actually
 * accepted during pairing, not the raw credential, so the credential cannot be recovered from the
 * database (Requirement 5.4).
 */
@Entity(tableName = "paired_devices")
data class PairedDevice(
    /** Stable device identifier — generated on the Client Device at pairing time. Primary key. */
    @PrimaryKey val id: String,
    /** Human-readable device name (e.g. Android device name) shown on the Devices screen. */
    val name: String,
    /** Device model string (e.g. "Samsung Galaxy Tab A8") shown on the Devices screen. */
    val model: String,
    /** Role assigned to this device (e.g. "ORDERING"), kept in sync by the Server Device. */
    val role: String,
    /** Current connection status (e.g. "APPROVED", "PENDING", "REVOKED"). */
    val status: String,
    /** BCrypt/SHA-256 hash of the pairing credential accepted for this device. Never the raw value. */
    val credentialHash: String,
    /** Epoch-millisecond timestamp of the most recent successful request from this device. */
    val lastSeenMs: Long,
    /**
     * The raw credential, held ONLY until the client collects it once (task 5.1).
     *
     * The client learns its credential the same way it does in Cloud Mode: it polls
     * `devices-status` while waiting on the approval screen, and the api key arrives in that
     * response. So the Server Device has to be able to hand the value over exactly once, which a
     * hash cannot do — hence this column alongside [credentialHash].
     *
     * It is cleared the moment it is delivered, so the window is from registration to the paired
     * device's first poll after approval, and only ever on the Server Device's own app-private
     * storage. After that this is null and [credentialHash] is the only record, which is the state
     * Requirement 5.4 describes.
     */
    val pendingCredential: String? = null
)
