package com.razstudio.opsapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One café this operator device holds a live OPERATOR credential for. */
@Entity(tableName = "connected_cafes")
data class ConnectedCafeEntity(
    @PrimaryKey val id: String,          // this café's device row id (devices.id), not a local UUID
    val cafeName: String,
    val cafeSlug: String,
    val supabaseUrl: String,
    val supabaseAnonKey: String,         // public per-café anon key, from that café's app-config.json
    val sessionToken: String,            // OPERATOR bearer token — device-local only, never synced
    val connectedAt: String,             // ISO-8601, first successful connect
    val lastConnectedAt: String,         // ISO-8601, updated on each shell open
    val ownerKeyUrl: String? = null,     // owner recovery key URL, available when provisioned by this device
)
