package com.razstudio.pos.data.google

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.razstudio.pos.data.OperatingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Task 23.5b — the café bundle in the owner's own Google Drive, app-private folder
 * (Requirements 15.6, 15.12, 15.17).
 *
 * ## Where this stores things, and what that costs
 *
 * `appDataFolder` is a per-app hidden folder inside the owner's Drive. No other app can read it and
 * the owner does not see it in the Drive UI. **RAZStudio cannot read it either** — which is the
 * whole trade recorded in task 23.5a: the bundle contains the owner recovery QR, so storage the
 * vendor cannot read is storage in which a leak cannot be undone, and equally a lockout has no
 * vendor remedy. The owner holds their café, entirely. Task 23.9 puts that on screen at the moment
 * they save, because it is a real trade and not a detail.
 *
 * ## Why the Drive scope is requested here and not at sign-in
 *
 * `drive.appdata` is a **sensitive** scope: shipping it in the base sign-in request would mean every
 * owner sees a Drive consent prompt on first launch, including the ones who only ever tap Skip, and
 * it would all need justifying at verification (task 23.14). So sign-in asks for `openid email
 * profile` and this class asks for Drive at the first save or restore, via the Authorization API.
 * An owner who never uses backup is never asked for Drive access at all.
 *
 * ## Why raw REST rather than the Drive SDK
 *
 * The Drive Java client pulls a large transitive tree for three requests. OkHttp is already in the
 * app for Realtime, and the API surface used here is a list, a download and an upload.
 */
@Singleton
open class CafeBundleStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "CafeBundleStore"

        /** The sensitive scope. Requested incrementally -- see the class note. */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /**
         * Each cafe is a **folder** named `RAZS.POS-{MODE}-{cafe name}`, e.g.
         * `RAZS.POS-LAN-Kedai Kopi`, holding [BUNDLE_FILE] plus one file per menu photo.
         *
         * A folder rather than a single file because photos are binary and belong beside the JSON
         * that references them; and because one account can hold several cafes -- a WLAN till, a
         * Kiosk, a full-QR shop -- which the chooser lists by parsing these names.
         *
         * The prefix is what makes them findable: a restoring device knows neither its mode nor its
         * cafe name yet, which is the entire point of restoring.
         */
        const val FILE_PREFIX = "RAZS.POS-"

        /** The structured half of a bundle: mode, backend, cafe name, tables, menu, settings. */
        const val BUNDLE_FILE = "cafe.json"

        /** Pre-folder bundles were a single flat file. Still read, never written -- see [listBundles]. */
        private const val LEGACY_FILE_NAME = "cafe-config.json"

        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

        /**
         * The label a mode gets in a folder name.
         *
         * Deliberately NOT `OperatingMode.name`. The enum reads CLOUD / LAN / KIOSK, but the owner
         * chose "Full Online with QR ordering" and "(W)LAN AP", and this string is the one thing
         * they will actually see — in a folder name, in the chooser, on the Settings panel. A
         * backup labelled with an internal enum is a backup they cannot identify.
         */
        fun labelFor(mode: OperatingMode): String = when (mode) {
            OperatingMode.CLOUD -> "FullQR"
            OperatingMode.LAN -> "WLAN"
            OperatingMode.KIOSK -> "Kiosk"
        }

        private fun modeFromLabel(label: String): OperatingMode? =
            // Both spellings are accepted on the way in: bundles written before the labels existed
            // carry the enum name, and an owner should not lose a café to a rename.
            OperatingMode.entries.firstOrNull { labelFor(it).equals(label, ignoreCase = true) }
                ?: OperatingMode.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }

        fun folderNameFor(mode: OperatingMode, cafeName: String): String =
            // Drive treats the name as opaque text, but a stray quote would break the `q=` filter
            // this class searches with, so the characters that matter there are dropped.
            FILE_PREFIX + labelFor(mode) + "-" + cafeName.replace("'", "").replace("\\", "").trim()

        /**
         * Split `RAZS.POS-LAN-Kedai Kopi` back into `LAN` and `Kedai Kopi`.
         *
         * Splits on the FIRST hyphen after the prefix, because a cafe name may contain hyphens
         * ("Kopi-O Corner") and a mode never does. Returns null for anything that does not parse,
         * so a stray folder somebody dropped in cannot appear in the chooser as a cafe.
         */
        fun parseFolderName(name: String): Pair<String, String>? {
            if (!name.startsWith(FILE_PREFIX)) return null
            val rest = name.removePrefix(FILE_PREFIX)
            val dash = rest.indexOf('-')
            if (dash <= 0 || dash == rest.length - 1) return null
            val mode = rest.substring(0, dash)
            val cafe = rest.substring(dash + 1).trim()
            if (cafe.isEmpty()) return null
            if (modeFromLabel(mode) == null) return null
            return mode to cafe
        }
    }

    /** One cafe bundle in the account, as the chooser lists it. */
    data class RemoteBundle(
        val folderId: String,
        val mode: String,
        val cafeName: String,
        val modifiedTime: String,
    )

    sealed class AuthResult {
        data class Granted(val accessToken: String) : AuthResult()
        /** Consent is needed; the caller must launch [pendingIntent] and retry afterwards. */
        data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : AuthResult()
        data class Failed(val reason: String) : AuthResult()
    }

    sealed class ListResult {
        /** Zero, one or many. The caller shows a chooser only when there is a choice to make. */
        data class Found(val bundles: List<RemoteBundle>) : ListResult()
        data class Failed(val reason: String) : ListResult()
    }

    sealed class LoadResult {
        data class Found(val payload: CafeConfigPayload) : LoadResult()
        /** The account has no bundle. Distinct from a failure: this owner simply never saved one. */
        data object None : LoadResult()
        /**
         * A bundle exists but is unusable. Deliberately NOT merged with [None] — an owner whose
         * bundle is corrupt needs to know it will not come back, rather than believing they never
         * saved. Restoring nothing is the correct action in both cases (task 23.10).
         */
        data class Unusable(val reason: String) : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }

    // A dedicated client. ApiClient's shared client carries NoInternetGuard, which resolves nothing
    // outside the café's own backend — correct there, fatal here. This talks to Google, and only
    // ever in Cloud Mode, where internet is expected (task 23.4 keeps LAN and Kiosk off this path).
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Asks for `drive.appdata`, showing the owner a consent screen the first time.
     *
     * Returns [AuthResult.NeedsConsent] rather than launching anything itself: launching an intent
     * needs an Activity result contract, which belongs to the screen, not to a data class.
     */
    open suspend fun authorizeDrive(activity: Activity): AuthResult =
        suspendCancellableCoroutine { cont ->
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()

            Identity.getAuthorizationClient(activity)
                .authorize(request)
                .addOnSuccessListener { result ->
                    val pending = result.pendingIntent
                    when {
                        result.hasResolution() && pending != null ->
                            cont.resume(AuthResult.NeedsConsent(pending))
                        result.accessToken != null ->
                            cont.resume(AuthResult.Granted(result.accessToken!!))
                        else ->
                            cont.resume(AuthResult.Failed("authorized with no access token"))
                    }
                }
                .addOnFailureListener { e ->
                    Log.d(TAG, "Drive authorization failed", e)
                    cont.resume(AuthResult.Failed(e.message ?: "authorization failed"))
                }
        }

    /**
     * Every cafe this account holds, newest first.
     *
     * Legacy flat-file bundles are included so an owner who saved before folders existed still sees
     * their cafe rather than an empty chooser.
     */
    open suspend fun listBundles(accessToken: String): ListResult = withContext(Dispatchers.IO) {
        try {
            val q = "(mimeType = 'FOLDER' or name = 'LEGACY') and trashed = false"
                .replace("FOLDER", FOLDER_MIME).replace("LEGACY", LEGACY_FILE_NAME)
            val url = "$DRIVE_FILES?spaces=appDataFolder" +
                "&q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                "&orderBy=modifiedTime desc" +
                "&fields=files(id,name,modifiedTime)&pageSize=100"

            val body = get(url, accessToken)
                ?: return@withContext ListResult.Failed("could not list your Google Drive")
            val files = JSONObject(body).optJSONArray("files")
                ?: return@withContext ListResult.Found(emptyList())

            val bundles = mutableListOf<RemoteBundle>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val id = f.optString("id")
                if (id.isBlank()) continue
                val name = f.optString("name")
                val modified = f.optString("modifiedTime")

                if (name == LEGACY_FILE_NAME) {
                    // A pre-folder bundle names neither mode nor cafe in the file name, so the only
                    // way to label it is to read it. Worth one extra request: the alternative is an
                    // unlabelled row in a chooser whose whole job is telling cafes apart.
                    val payload = get("$DRIVE_FILES/$id?alt=media", accessToken)
                        ?.let { CafeConfigPayload.parse(it) }
                    if (payload != null) {
                        bundles += RemoteBundle(id, payload.mode.name, payload.cafeName, modified)
                    }
                    continue
                }

                val parsed = parseFolderName(name) ?: continue
                bundles += RemoteBundle(id, parsed.first, parsed.second, modified)
            }
            ListResult.Found(bundles)
        } catch (e: Exception) {
            Log.d(TAG, "List failed", e)
            ListResult.Failed(e.message ?: "could not reach Google Drive")
        }
    }

    /** Reads one bundle by folder id (or legacy file id), or reports why it cannot. */
    open suspend fun load(accessToken: String, folderId: String): LoadResult = withContext(Dispatchers.IO) {
        try {
            // A legacy bundle IS the file; a folder holds one. Trying the folder listing first and
            // falling back keeps both shapes on one path.
            val fileId = findChild(accessToken, folderId, BUNDLE_FILE) ?: folderId

            val body = get("$DRIVE_FILES/$fileId?alt=media", accessToken)
                ?: return@withContext LoadResult.Failed("could not download the saved configuration")

            val payload = CafeConfigPayload.parse(body)
                ?: return@withContext LoadResult.Unusable(
                    "the saved configuration is incomplete or was written by a newer version"
                )

            LoadResult.Found(payload)
        } catch (e: Exception) {
            Log.d(TAG, "Load failed", e)
            LoadResult.Failed(e.message ?: "could not reach Google Drive")
        }
    }

    /**
     * Downloads a menu photo by Drive file id into [target]. Returns false on any failure.
     *
     * Photos are restored one at a time and a failure is per-photo on purpose: a cafe that comes
     * back with its tables, its menu and four of five pictures is open for business. Refusing the
     * whole restore over an image would not be.
     */
    open suspend fun downloadPhoto(accessToken: String, fileId: String, target: java.io.File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$DRIVE_FILES/$fileId?alt=media")
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                http.newCall(request).execute().use { response ->
                    val stream = response.body?.byteStream() ?: return@use false
                    if (!response.isSuccessful) return@use false
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> stream.copyTo(out) }
                    true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Photo download failed for $fileId", e)
                false
            }
        }

    /**
     * Writes the bundle into its folder, creating the folder if needed, and uploads [photos].
     *
     * Returns null on success, else a reason. The JSON is written last: a folder holding photos and
     * no manifest reads as "no cafe saved" and is harmless, whereas a manifest referencing photos
     * that were never uploaded restores a menu of broken images.
     */
    open suspend fun save(
        accessToken: String,
        payload: CafeConfigPayload,
        photos: List<java.io.File> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        try {
            val wantedName = folderNameFor(payload.mode, payload.cafeName)
            val folderId = findFolderByName(accessToken, wantedName)
                ?: createFolder(accessToken, wantedName)
                ?: return@withContext "Google Drive would not create the cafe folder"

            val photoIds = mutableMapOf<String, String>()
            photos.forEach { file ->
                val existing = findChild(accessToken, folderId, file.name)
                val id = uploadBinary(accessToken, folderId, file, existing)
                if (id != null) photoIds[file.name] = id
            }

            val json = payload.copy(photoFileIds = photoIds).toJson()
            val existingJson = findChild(accessToken, folderId, BUNDLE_FILE)
            val ok = if (existingJson != null) {
                val request = Request.Builder()
                    .url("$DRIVE_UPLOAD/$existingJson?uploadType=media")
                    .patch(json.toRequestBody(JSON_MEDIA))
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                http.newCall(request).execute().use { it.isSuccessful }
            } else {
                createTextFile(accessToken, folderId, BUNDLE_FILE, json)
            }

            if (ok) null else "Google Drive rejected the upload"
        } catch (e: Exception) {
            Log.d(TAG, "Save failed", e)
            e.message ?: "could not reach Google Drive"
        }
    }

    /** Removes one cafe folder, so an owner can take that cafe back out of their account. */
    open suspend fun delete(accessToken: String, folderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$DRIVE_FILES/$folderId")
                .delete()
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use {
                if (it.isSuccessful) null else "Google Drive rejected the deletion"
            }
        } catch (e: Exception) {
            e.message ?: "could not reach Google Drive"
        }
    }

    // ── Drive plumbing ───────────────────────────────────────────────────────────────────────────

    private fun findFolderByName(accessToken: String, name: String): String? {
        val q = "name = 'NAME' and mimeType = 'FOLDER' and trashed = false"
            .replace("NAME", name.replace("'", "")).replace("FOLDER", FOLDER_MIME)
        val url = "$DRIVE_FILES?spaces=appDataFolder" +
            "&q=" + java.net.URLEncoder.encode(q, "UTF-8") +
            "&fields=files(id)&pageSize=1"
        val body = get(url, accessToken) ?: return null
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).optString("id").ifBlank { null }
    }

    private fun findChild(accessToken: String, folderId: String, name: String): String? {
        val q = "'PARENT' in parents and name = 'NAME' and trashed = false"
            .replace("PARENT", folderId).replace("NAME", name.replace("'", ""))
        val url = "$DRIVE_FILES?spaces=appDataFolder" +
            "&q=" + java.net.URLEncoder.encode(q, "UTF-8") +
            "&fields=files(id)&pageSize=1"
        val body = get(url, accessToken) ?: return null
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).optString("id").ifBlank { null }
    }

    private fun createFolder(accessToken: String, name: String): String? {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("parents", org.json.JSONArray().put("appDataFolder"))
        }.toString()
        val request = Request.Builder()
            .url("$DRIVE_FILES?fields=id")
            .post(metadata.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()?.let { JSONObject(it).optString("id").ifBlank { null } }
        }
    }

    private fun createTextFile(accessToken: String, folderId: String, name: String, content: String): Boolean {
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", org.json.JSONArray().put(folderId))
        }.toString()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadata.toRequestBody(JSON_MEDIA),
            )
            .addPart(
                okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                content.toRequestBody(JSON_MEDIA),
            )
            .build()
        val request = Request.Builder()
            .url("$DRIVE_UPLOAD?uploadType=multipart")
            .post(multipart)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(request).execute().use { it.isSuccessful }
    }

    /** Returns the Drive file id, or null. Replaces [existingId] in place when given. */
    private fun uploadBinary(
        accessToken: String,
        folderId: String,
        file: java.io.File,
        existingId: String?,
    ): String? = try {
        val media = file.readBytes().toRequestBody(IMAGE_MEDIA)
        if (existingId != null) {
            val request = Request.Builder()
                .url("$DRIVE_UPLOAD/$existingId?uploadType=media&fields=id")
                .patch(media)
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use { if (it.isSuccessful) existingId else null }
        } else {
            val metadata = JSONObject().apply {
                put("name", file.name)
                put("parents", org.json.JSONArray().put(folderId))
            }.toString()
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addPart(
                    okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                    metadata.toRequestBody(JSON_MEDIA),
                )
                .addPart(okhttp3.Headers.headersOf("Content-Type", "image/jpeg"), media)
                .build()
            val request = Request.Builder()
                .url("$DRIVE_UPLOAD?uploadType=multipart&fields=id")
                .post(multipart)
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()?.let { JSONObject(it).optString("id").ifBlank { null } }
            }
        }
    } catch (e: Exception) {
        // Per-photo failure. See downloadPhoto: a cafe missing one picture is still open.
        Log.d(TAG, "Photo upload failed for ${file.name}", e)
        null
    }

    private fun get(url: String, accessToken: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val IMAGE_MEDIA = "image/jpeg".toMediaType()

/**
 * True when this mode should never attempt Google sign-in (task 23.4, Requirements 15.9, 11.1).
 *
 * LAN and Kiosk have no internet by definition. An attempt would sit on the network until it timed
 * out, in front of an owner opening their till, and `NoInternetGuard` would refuse to resolve the
 * host anyway — so the wait would buy a failure that was certain before it started.
 */
fun OperatingMode.attemptsGoogleSignIn(): Boolean = this == OperatingMode.CLOUD
