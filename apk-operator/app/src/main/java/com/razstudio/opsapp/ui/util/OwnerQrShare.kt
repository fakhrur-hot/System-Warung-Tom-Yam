package com.razstudio.opsapp.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the owner-key URL as a QR bitmap, writes it to a PNG in cacheDir, and returns a share
 * Intent wrapped in [Intent.createChooser] — no intermediate screen, matching Requirement 3.9.
 *
 * The shape (FileProvider + ACTION_SEND + createChooser) is ported from `QrPdfGenerator
 * .createShareIntent()` in `apk/app`, with MIME type swapped to `image/png` and the source file
 * taken from `cacheDir` rather than a PDF in filesDir.
 */
object OwnerQrShare {

    /**
     * Builds a chooser intent that shares the [ownerKeyUrl] rendered as a 1024 px QR PNG.
     *
     * @throws IllegalStateException if QrCodeUtil fails to encode (blank URL, encoding error).
     * @throws java.io.IOException if writing the PNG to cache fails (disk full, etc.).
     */
    fun buildShareIntent(context: Context, ownerKeyUrl: String): Intent {
        val bitmap: Bitmap = QrCodeUtil.encode(ownerKeyUrl, sizePx = 1024)
            ?: throw IllegalStateException("Failed to encode QR code for owner key URL.")

        val file = File(context.cacheDir, "owner_qr_${System.currentTimeMillis()}.png")
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

        return Intent.createChooser(send, "Share café owner QR")
    }
}
