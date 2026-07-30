package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a queued print job.
 * Each job targets a specific printer and carries the serialized document payload.
 * Retry logic handles Bluetooth reconnection failures.
 */
@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey val id: String,           // UUID
    val printerId: String,                 // FK to PrinterConfig.id
    val documentType: String,              // "KITCHEN_SLIP" or "RECEIPT"
    val payload: String,                   // Serialized document data (JSON)
    val status: String,                    // PrintJobStatus enum as string
    val createdAt: String,
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * Status lifecycle for a print job.
 */
enum class PrintJobStatus {
    QUEUED,
    PRINTING,
    COMPLETED,
    FAILED
}
