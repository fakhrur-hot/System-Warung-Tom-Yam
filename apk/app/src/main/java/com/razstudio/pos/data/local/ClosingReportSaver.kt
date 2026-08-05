package com.razstudio.pos.data.local

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.razstudio.pos.data.AppConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the closing report into the device's own Downloads folder.
 *
 * ## Why the device and not the cloud
 *
 * The report used to be emailed, via a Brevo integration that the app never actually invoked — the
 * `report_email` setting was written by Settings and read by nobody. Saving to Downloads needs no
 * mail provider, no Google Drive scope, no account linked at all: the owner closes the till and the
 * file is on the tablet, openable from any file manager and shareable however they already share
 * things.
 *
 * ## Two storage paths, because minSdk is 26
 *
 * On API 29+ this goes through `MediaStore.Downloads`, which needs **no permission at all** —
 * scoped storage lets an app write into Downloads without asking. Below 29 there is no MediaStore
 * Downloads collection, so it falls back to the public directory, which does need
 * `WRITE_EXTERNAL_STORAGE`. That permission is declared `maxSdkVersion="28"` so modern devices —
 * every till this actually ships on — never request it.
 */
@Singleton
class ClosingReportSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfigStore: AppConfigStore,
) {

    /** Where the file ended up, or why it did not. */
    sealed class Result {
        data class Saved(val fileName: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Download [url] and save it as `<cafe_name>-YYMMDDHHMM.html` in Downloads.
     *
     * @param timezone the café's own zone. The timestamp is the moment of closing **as the café
     *   experiences it** — a stall shutting at 2 AM in Kuala Lumpur wants `0200` in the name, not
     *   whatever that instant happens to be in UTC. Getting this wrong would misfile every report
     *   from a café that trades past midnight, which is most of them.
     */
    suspend fun saveToDownloads(url: String, timezone: String): Result = withContext(Dispatchers.IO) {
        val fileName = buildFileName(timezone)
        try {
            val http = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val bytes = http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext Result.Failed("HTTP ${r.code}")
                r.body?.bytes() ?: return@withContext Result.Failed("Empty report")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(fileName, bytes)
            } else {
                writeViaLegacyFile(fileName, bytes)
            }
            Log.d(TAG, "Closing report saved as $fileName")
            Result.Saved(fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the closing report", e)
            Result.Failed(e.message ?: "Could not save the report")
        }
    }

    /**
     * `tani_tom_yam-2608040200.html`
     *
     * Lower-cased with spaces collapsed to underscores, and anything that is not a letter, digit or
     * underscore dropped — a café called "Kopi & Roti (Cawangan 2)" must not produce a filename that
     * a file manager, a share sheet or a Windows machine refuses to handle.
     */
    private fun buildFileName(timezone: String): String {
        val slug = appConfigStore.cafeName()
            .lowercase()
            .replace(Regex("\\s+"), "_")
            .filter { it.isLetterOrDigit() || it == '_' }
            .trim('_')
            .ifBlank { "cafe" }

        val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.systemDefault() }
        val stamp = DateTimeFormatter.ofPattern("yyMMddHHmm")
            .withZone(zone)
            .format(Instant.now())

        return "$slug-$stamp.html"
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(fileName: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hides the row until the bytes are on disk, so a file manager cannot show a
            // half-written report if the process dies mid-copy.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads folder rejected the file")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open the file for writing")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun writeViaLegacyFile(fileName: String, bytes: ByteArray) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
    }

    private companion object {
        const val TAG = "ClosingReportSaver"
    }
}
