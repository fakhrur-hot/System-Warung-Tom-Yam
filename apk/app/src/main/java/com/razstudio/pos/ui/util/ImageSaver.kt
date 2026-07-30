package com.razstudio.pos.ui.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves bitmaps (e.g. the Owner Recovery QR) as PNGs to the device's local storage.
 * On API 29+ this goes to the shared Pictures collection via MediaStore (visible in the
 * gallery, no runtime permission needed). On older devices it falls back to the app's
 * external Pictures dir, which also needs no permission.
 */
object ImageSaver {

    /**
     * Save [bitmap] as a PNG named [displayName] (without extension). Returns a human-readable
     * location string on success, or null on failure.
     */
    fun savePng(context: Context, bitmap: Bitmap, displayName: String): String? {
        val fileName = "$displayName.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return null
                "Pictures/$fileName"
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: return null
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }
}
