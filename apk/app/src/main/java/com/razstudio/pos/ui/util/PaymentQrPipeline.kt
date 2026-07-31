package com.razstudio.pos.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Payment QR image processing pipeline.
 *
 * Distinct from [LogoPipeline] in every meaningful way:
 * - Accepts both JPEG and PNG; PNG is kept lossless (JPEG re-compression can smear dense
 *   QR modules until they stop scanning).
 * - Caps dimensions generously (1024 px on the longest side) rather than aggressively.
 * - Does NOT center-crop, does NOT produce a monochrome print raster, has NO print path —
 *   the Payment QR is screen-only (Requirement 14.10).
 * - Verifies scannability with ZXing before accepting the image (Requirement 14.3).
 * - If any re-encoding was applied, decodes both the original and the stored result and
 *   compares their text payloads byte-for-byte; keeps the original bytes if they differ,
 *   so a lossy re-encode can never silently corrupt the payee identifier (Requirement 14.4).
 *
 * Requirements: 14.2, 14.3, 14.4
 */
object PaymentQrPipeline {

    /** Maximum dimension (width or height) for the stored image. */
    private const val MAX_DIMENSION_PX = 1024

    /** File name used for JPEG-originated images saved in internal storage. */
    private const val JPEG_FILENAME = "payment_qr.jpg"

    /** File name used for PNG-originated images saved in internal storage. */
    private const val PNG_FILENAME = "payment_qr.png"

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * Describes the outcome of a successful [process] call.
     *
     * @param storedFile     The file written to internal storage.
     * @param originalPayload The QR text payload decoded from the *original* uploaded image.
     *                        Callers can display or hash this for change-detection.
     * @param wasReEncoded   True if the pipeline applied scaling or format conversion.
     */
    data class QrResult(
        val storedFile: File,
        val originalPayload: String,
        val wasReEncoded: Boolean,
    )

    /**
     * Sealed hierarchy for pipeline outcomes — success or one of two distinct failure reasons.
     */
    sealed class PipelineResult {
        data class Success(val qrResult: QrResult) : PipelineResult()

        /** The image does not contain a machine-readable QR code. */
        data class NoQrFound(val message: String) : PipelineResult()

        /** An I/O or decoding error prevented processing. */
        data class Error(val message: String, val cause: Throwable? = null) : PipelineResult()
    }

    /**
     * Run the full Payment QR pipeline on a picked image URI.
     *
     * 1. Detect MIME type / file extension to decide JPEG vs PNG path.
     * 2. Decode the image into a [Bitmap].
     * 3. ZXing-decode it to confirm a QR is present; reject with [PipelineResult.NoQrFound]
     *    if not (Requirement 14.3).
     * 4. Cap dimensions to [MAX_DIMENSION_PX] if needed.
     * 5. Re-encode only if the bitmap was scaled:
     *    - PNG input → PNG output (lossless).
     *    - JPEG input → JPEG output (quality 90).
     * 6. If re-encoding occurred, ZXing-decode the stored bytes and compare the text payload
     *    byte-for-byte with the original; if they differ, write the *original* bytes instead
     *    (Requirement 14.4).
     * 7. Save to internal storage and return [PipelineResult.Success].
     *
     * @param context   Android context (used for content resolver and [Context.filesDir]).
     * @param imageUri  URI from an image picker (content:// or file://).
     * @param mimeType  MIME type hint (e.g. "image/jpeg", "image/png"). When null the
     *                  pipeline falls back to sniffing the URI path extension.
     */
    fun process(context: Context, imageUri: Uri, mimeType: String? = null): PipelineResult {
        // --- Step 1: Read raw bytes and detect format ---
        val rawBytes: ByteArray = try {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: return PipelineResult.Error("Cannot open input stream for $imageUri")
        } catch (e: Exception) {
            return PipelineResult.Error("Failed to read image: ${e.message}", e)
        }

        val isPng = detectPng(rawBytes, mimeType, imageUri)

        // --- Step 2: Decode bitmap ---
        val original: Bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            ?: return PipelineResult.Error("Cannot decode image — unsupported format or corrupt data")

        // --- Step 3: ZXing-decode to confirm QR is present ---
        val originalPayload: String = try {
            decodeQrPayload(original)
        } catch (e: NotFoundException) {
            original.recycle()
            return PipelineResult.NoQrFound(
                "The selected image does not contain a readable QR code. " +
                    "Please upload a clear photo of the payment QR."
            )
        } catch (e: Exception) {
            original.recycle()
            return PipelineResult.Error("QR decode error: ${e.message}", e)
        }

        // --- Step 4: Cap dimensions ---
        val scaled: Bitmap = scaleBitmap(original)
        val wasScaled = scaled !== original
        if (wasScaled) original.recycle()

        // --- Step 5: Encode to bytes ---
        val encodedBytes: ByteArray = if (isPng) {
            encodeAsPng(scaled)
        } else {
            encodeAsJpeg(scaled, quality = 90)
        }
        val wasReEncoded = wasScaled // only re-encode when dimensions changed

        // --- Step 6: Payload integrity check after re-encoding ---
        val bytesToStore: ByteArray = if (wasReEncoded) {
            val reEncodedPayload: String? = try {
                decodeQrPayloadFromBytes(encodedBytes)
            } catch (_: Exception) {
                null
            }
            if (reEncodedPayload == null || reEncodedPayload != originalPayload) {
                // Re-encode degraded the QR — keep the original bytes
                rawBytes
            } else {
                encodedBytes
            }
        } else {
            // No re-encoding needed: store raw bytes verbatim (lossless pass-through)
            rawBytes
        }

        scaled.recycle()

        // --- Step 7: Save to internal storage ---
        val fileName = if (isPng) PNG_FILENAME else JPEG_FILENAME
        val storedFile = File(context.filesDir, fileName)
        return try {
            storedFile.writeBytes(bytesToStore)
            PipelineResult.Success(
                QrResult(
                    storedFile = storedFile,
                    originalPayload = originalPayload,
                    wasReEncoded = wasReEncoded,
                )
            )
        } catch (e: Exception) {
            PipelineResult.Error("Failed to save processed image: ${e.message}", e)
        }
    }

    /**
     * Load the previously stored Payment QR as a [Bitmap], or null if none is saved.
     * Checks both possible file names so the caller does not need to track format.
     */
    fun loadFromInternal(context: Context): Bitmap? {
        for (name in listOf(PNG_FILENAME, JPEG_FILENAME)) {
            val file = File(context.filesDir, name)
            if (file.exists()) {
                return try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Return the stored Payment QR file (PNG preferred, then JPEG), or null if absent.
     */
    fun storedFileOrNull(context: Context): File? {
        for (name in listOf(PNG_FILENAME, JPEG_FILENAME)) {
            val file = File(context.filesDir, name)
            if (file.exists()) return file
        }
        return null
    }

    /**
     * Write already-verified image [bytes] to internal storage, replacing whatever is there
     * (task 16.2 — used when a staff device downloads the café's current Payment QR).
     *
     * Deletes both possible names first so a PNG replacing a JPEG cannot leave the old file behind for
     * [storedFileOrNull] to find in preference. The PNG/JPEG choice is by content sniffing rather than
     * by trusting a URL extension, and the extension only affects which filename is used — nothing
     * re-encodes here, because re-encoding is exactly what can make a dense QR unscannable.
     *
     * Callers must have verified the bytes decode as a QR before calling this.
     */
    fun saveBytesToInternal(context: Context, bytes: ByteArray): File {
        deleteFromInternal(context)
        val isPng = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val file = File(context.filesDir, if (isPng) PNG_FILENAME else JPEG_FILENAME)
        file.outputStream().use { it.write(bytes) }
        return file
    }

    /**
     * Delete any stored Payment QR files from internal storage.
     */
    fun deleteFromInternal(context: Context) {
        for (name in listOf(PNG_FILENAME, JPEG_FILENAME)) {
            try {
                File(context.filesDir, name).delete()
            } catch (_: Exception) { /* non-fatal */ }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Hash and persistence helpers (Requirements 14.8, 14.9)
    // -----------------------------------------------------------------------------------------

    /**
     * Compute the SHA-256 hex digest of the given file's bytes.
     *
     * Used to key the Payment QR cache: a device holding a different hash refetches before
     * display, and a removed QR clears the cache and hides the button everywhere (Requirement 14.6).
     *
     * @throws IOException if the file cannot be read.
     */
    fun computeSha256Hex(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Persist the Payment QR hash and resolved URL from a successful [process] call.
     *
     * Call this after [process] returns [PipelineResult.Success] and after the caller has
     * determined the URL under which the image will be served (mode-dependent):
     * - Cloud Mode: the Supabase Storage URL returned by the media-upload endpoint.
     * - LAN Mode: `http://<server>:<port>/media/payment-qr.<ext>`.
     * - Kiosk Mode: the empty string or any stable sentinel — Kiosk reads the file directly
     *   and never fetches via URL, but a non-null value is still written so the Show QR button
     *   remains visible.
     *
     * Both values are cleared together (via [AppConfigStore.setPaymentQrHash] / [setPaymentQrUrl]
     * with null) when the admin removes the QR, so the Show QR button disappears everywhere
     * (Requirement 14.5).
     *
     * @param context       Android context — used only to satisfy the [result.storedFile] path.
     * @param result        Successful [QrResult] from [process].
     * @param url           The resolved URL at which this image will be served to staff devices.
     * @param appConfigStore The store to update with the hash and URL.
     */
    fun saveAndPersist(
        context: Context,
        result: QrResult,
        url: String,
        appConfigStore: com.razstudio.pos.data.AppConfigStore,
    ) {
        val hash = computeSha256Hex(result.storedFile)
        appConfigStore.setPaymentQrHash(hash)
        appConfigStore.setPaymentQrUrl(url)
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Return true if the image should be treated as PNG (lossless path).
     * Checks (in order): explicit MIME type hint, PNG file magic bytes, URI path extension.
     */
    internal fun detectPng(rawBytes: ByteArray, mimeType: String?, uri: Uri): Boolean {
        if (mimeType != null) {
            return mimeType.equals("image/png", ignoreCase = true)
        }
        // PNG magic: 8 bytes — 0x89 P N G \r \n 0x1A \n
        if (rawBytes.size >= 8 &&
            rawBytes[0] == 0x89.toByte() &&
            rawBytes[1] == 0x50.toByte() && // 'P'
            rawBytes[2] == 0x4E.toByte() && // 'N'
            rawBytes[3] == 0x47.toByte()    // 'G'
        ) {
            return true
        }
        // Fall back to URI path extension
        val path = uri.path?.lowercase() ?: ""
        return path.endsWith(".png")
    }

    /**
     * Scale the bitmap so its largest dimension does not exceed [MAX_DIMENSION_PX].
     * Returns the original instance unchanged if no scaling is needed.
     */
    internal fun scaleBitmap(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= MAX_DIMENSION_PX) return source

        val scale = MAX_DIMENSION_PX.toFloat() / maxSide
        val newWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    /**
     * ZXing-decode a [Bitmap] and return the QR payload text.
     * Throws [NotFoundException] if no QR code is found.
     */
    internal fun decodeQrPayload(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val result = MultiFormatReader().decode(binaryBitmap)
        return result.text
    }

    /**
     * ZXing-decode image bytes (not yet a Bitmap) and return the QR payload text, or null
     * if decoding fails for any reason. Used for the post-re-encode integrity check.
     */
    internal fun decodeQrPayloadFromBytes(bytes: ByteArray): String? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val payload = decodeQrPayload(bmp)
            bmp.recycle()
            payload
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeAsPng(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    private fun encodeAsJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }
}
