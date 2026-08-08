package com.razstudio.opsapp.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encodes text (a URL) into a QR-code [Bitmap] using ZXing.
 *
 * Ported verbatim from `apk/app`'s `com.razstudio.pos.ui.util.QrCodeUtil`. Used here for the
 * owner-key QR share on the Done screen (Requirement 3.9).
 */
object QrCodeUtil {

    /**
     * @param content the text to encode (the owner key URL).
     * @param sizePx the square pixel size of the output bitmap.
     * @return a black-on-white QR bitmap, or null if encoding fails.
     */
    fun encode(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
