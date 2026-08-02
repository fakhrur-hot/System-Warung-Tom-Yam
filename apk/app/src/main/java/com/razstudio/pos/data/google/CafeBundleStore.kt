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

        /** The sensitive scope. Requested incrementally — see the class note. */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /** Fixed name, so a second save overwrites rather than accumulating bundles. */
        private const val FILE_NAME = "cafe-config.json"

        private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }

    sealed class AuthResult {
        data class Granted(val accessToken: String) : AuthResult()
        /** Consent is needed; the caller must launch [pendingIntent] and retry afterwards. */
        data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : AuthResult()
        data class Failed(val reason: String) : AuthResult()
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

    /** Reads the account's bundle, or reports why it cannot. */
    open suspend fun load(accessToken: String): LoadResult = withContext(Dispatchers.IO) {
        try {
            val fileId = findFileId(accessToken)
                ?: return@withContext LoadResult.None

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

    /** Writes the bundle, replacing any previous one. Returns null on success, else a reason. */
    open suspend fun save(accessToken: String, payload: CafeConfigPayload): String? =
        withContext(Dispatchers.IO) {
            try {
                val json = payload.toJson()
                val existing = findFileId(accessToken)

                val ok = if (existing != null) {
                    // Media-only update: the metadata (name, parent) is already right, and resending
                    // it risks moving the file out of appDataFolder on a partial write.
                    val request = Request.Builder()
                        .url("$DRIVE_UPLOAD/$existing?uploadType=media")
                        .patch(json.toRequestBody(JSON_MEDIA))
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                    http.newCall(request).execute().use { it.isSuccessful }
                } else {
                    val metadata = JSONObject().apply {
                        put("name", FILE_NAME)
                        put("parents", org.json.JSONArray().put("appDataFolder"))
                    }.toString()

                    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addPart(
                            okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                            metadata.toRequestBody(JSON_MEDIA),
                        )
                        .addPart(
                            okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                            json.toRequestBody(JSON_MEDIA),
                        )
                        .build()

                    val request = Request.Builder()
                        .url("$DRIVE_UPLOAD?uploadType=multipart")
                        .post(multipart)
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                    http.newCall(request).execute().use { it.isSuccessful }
                }

                if (ok) null else "Google Drive rejected the upload"
            } catch (e: Exception) {
                Log.d(TAG, "Save failed", e)
                e.message ?: "could not reach Google Drive"
            }
        }

    /** Removes the bundle, so an owner can take their café key back out of their Google account. */
    open suspend fun delete(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val fileId = findFileId(accessToken) ?: return@withContext null
            val request = Request.Builder()
                .url("$DRIVE_FILES/$fileId")
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

    private fun findFileId(accessToken: String): String? {
        val url = "$DRIVE_FILES?spaces=appDataFolder" +
            "&q=" + java.net.URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8") +
            "&fields=files(id)&pageSize=1"
        val body = get(url, accessToken) ?: return null
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).optString("id").ifBlank { null }
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

/**
 * True when this mode should never attempt Google sign-in (task 23.4, Requirements 15.9, 11.1).
 *
 * LAN and Kiosk have no internet by definition. An attempt would sit on the network until it timed
 * out, in front of an owner opening their till, and `NoInternetGuard` would refuse to resolve the
 * host anyway — so the wait would buy a failure that was certain before it started.
 */
fun OperatingMode.attemptsGoogleSignIn(): Boolean = this == OperatingMode.CLOUD
