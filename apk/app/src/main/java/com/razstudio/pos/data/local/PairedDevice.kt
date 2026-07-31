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
    val lastSeenMs: Long
)
