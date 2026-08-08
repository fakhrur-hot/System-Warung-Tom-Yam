package com.razstudio.opsapp.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a table QR URL as a PNG and returns a share chooser intent.
 *
 * Same FileProvider + ACTION_SEND shape as [OwnerQrShare], but scoped to table QR cards.
 */
object TableQrShare {

    /**
     * @param tableName used in the chooser title.
     * @param qrUrl the URL/token to encode.
     * @throws IllegalStateException if QR encoding fails.
     * @throws java.io.IOException if writing the PNG to cache fails.
     */
    fun buildShareIntent(context: Context, tableName: String, qrUrl: String): Intent {
        val bitmap: Bitmap = QrCodeUtil.encode(qrUrl, sizePx = 1024)
            ?: throw IllegalStateException("Failed to encode table QR.")

        val file = File(context.cacheDir, "table_qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(send, "Share QR for $tableName")
    }
}
