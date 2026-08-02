package com.razstudio.pos.data.local

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Menu images on local disk, standing in for Supabase Storage off-cloud (task 4.3, Requirement 7.3).
 *
 * LAN and Kiosk cafés have no object storage and, in Kiosk, no internet at all — so an image has to
 * live on the device that serves it. Files go in app-private storage
 * (`filesDir/menu-images/`), which means they are removed with the app and are not visible to the
 * gallery: a café's menu photos are its own, and they should not turn up in a staff member's camera
 * roll.
 *
 * ### Why paths look like Supabase's
 *
 * The stored [MenuItem.imagePath] keeps the same `{id}-{timestamp}.jpg` shape the cloud path uses, so
 * the replace-then-delete-old-image logic in the menu screens works identically on both backends
 * without learning which one it is talking to. [MenuItem.imageUrl] holds a `file://` URI, which Coil
 * loads exactly as it loads an `https://` one.
 *
 * ### On the timestamp in the name
 *
 * The name changes on every upload rather than being overwritten in place. Coil caches by URL, so
 * reusing the name would leave the old photo on screen after the owner replaced it — the kind of bug
 * that looks like the upload silently failed.
 */
@Singleton
class LocalImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Decode [imageBase64] and store it for [menuItemId].
     *
     * Returns the storage-relative [path] and a `file://` [url], mirroring the cloud upload response
     * so callers can persist both fields unchanged.
     *
     * Accepts a bare base64 payload or a full `data:image/...;base64,...` URI, because the image
     * pickers in the admin screens produce the latter and the cloud endpoint tolerates both.
     */
    fun save(menuItemId: String, imageBase64: String, nowMillis: Long): Stored {
        val payload = imageBase64.substringAfter("base64,", imageBase64)
        val bytes = Base64.decode(payload, Base64.DEFAULT)

        // The id reaches this from a menu row and is app-generated, but it ends up in a filename, so
        // it is sanitised anyway — a stray "../" here would write outside app-private storage.
        val safeId = menuItemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val name = "$safeId-$nowMillis.jpg"

        val file = File(dir, name)
        file.writeBytes(bytes)
        return Stored(path = name, url = file.toUriString())
    }

    /**
     * Delete a previously stored image. Returns true if a file was removed.
     *
     * A missing file is not an error: the caller is replacing or clearing an image, and both are
     * already in the desired state if the file has gone. [path] is reduced to its filename first, so
     * a full `file://` URL or a stored path both work and neither can escape the directory.
     */
    /**
     * The on-disk file a stored photo name maps to.
     *
     * Exposed so a Drive restore can write the picture back to the exact path `MenuItem.imagePath`
     * already points at — otherwise the menu would come back referencing files that are not there.
     */
    fun fileFor(name: String): File = File(dir, name)

    /** Every stored menu photo, for backing the whole set up to Drive in one pass. */
    fun allFiles(): List<File> = dir.listFiles()?.filter { it.isFile }.orEmpty()

    fun delete(path: String): Boolean {
        val name = path.substringAfterLast('/').ifBlank { return false }
        if (name == "." || name == "..") return false
        val file = File(dir, name)
        return file.exists() && file.delete()
    }

    /** Absolute `file://` URL for a stored path, or empty if nothing is stored there. */
    fun urlFor(path: String): String {
        if (path.isBlank()) return ""
        val file = File(dir, path.substringAfterLast('/'))
        return if (file.exists()) file.toUriString() else ""
    }

    private fun File.toUriString(): String = "file://$absolutePath"

    /** Result of a [save] — the same two fields the cloud upload response carries. */
    data class Stored(val path: String, val url: String)

    private companion object {
        const val DIR_NAME = "menu-images"
    }
}
