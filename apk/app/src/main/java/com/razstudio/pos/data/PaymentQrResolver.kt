package com.razstudio.pos.data

import android.content.Context
import android.util.Log
import com.razstudio.pos.ui.util.PaymentQrPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this device's cached Payment QR in step with the café's current one
 * (task 16.2, Requirements 14.5, 14.6).
 *
 * ### Why a content hash, and not a timestamp
 *
 * The image lives at a stable path, so a URL alone cannot tell "unchanged" from "replaced" — every
 * device would keep serving whatever it downloaded first. The branding Edge Function already hit this
 * for the café logo and solved it by appending `?v=<updated_at>`; a content hash is the stricter
 * version of the same idea, and it also means re-uploading a byte-identical image causes no churn.
 *
 * ### The failure this exists to prevent
 *
 * A stale Payment QR sends a customer's money **to the previous account**, and every device would look
 * like it was working. There is no audit trail to catch it afterwards (Requirement 14.10), so
 * detection has to be structural rather than a matter of remembering to refresh.
 *
 * ### Role decides who is authoritative — and getting this backwards destroys data
 *
 * - **Admin** device: its own local file is the origin. It must NOT be overwritten from the server,
 *   because the admin is the one who just uploaded it, and during the transition the backend cannot
 *   store a Payment QR at all (the `branding` table has no column for one yet). A naive
 *   "server always wins" resolver would therefore see `paymentQrHash = null` and silently delete the
 *   QR the admin had just added.
 * - **Ordering staff** device: the server is the origin. A differing hash means refetch; a null hash
 *   means the admin removed it, so clear locally and let the Show QR button disappear.
 */
@Singleton
class PaymentQrResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfigStore: AppConfigStore,
    private val secureStorage: SecureStorage,
    private val noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard,
) {
    // Task 18.1 — the client task 18.1 did not list, and the riskiest of them: it fetches an
    // image over HTTPS from cloud storage and does not look like an API call.
    private val http by lazy { OkHttpClient.Builder().dns(noInternetGuard).build() }

    /** What [reconcile] did, so callers (and tests) can assert on the decision rather than guess. */
    enum class Outcome { NO_CHANGE, UPDATED, CLEARED, ADMIN_IS_AUTHORITATIVE, FETCH_FAILED }

    /**
     * Reconciles the local cache against [remoteHash] / [remoteUrl] as returned by `GET /branding`.
     *
     * Safe to call on every branding fetch: when the hashes already agree it does no I/O.
     */
    suspend fun reconcile(remoteHash: String?, remoteUrl: String?): Outcome = withContext(Dispatchers.IO) {
        val localHash = appConfigStore.paymentQrHash()

        // The admin device is the origin of its own upload — never clobber it from the server.
        //
        // But only while it HAS one. A replacement admin phone — fresh install, owner QR scanned —
        // holds nothing, so there is no upload to protect and the server is the only copy in
        // existence. Short-circuiting on role alone left that device permanently without the café's
        // payment QR: the backend had both hash and URL, and the one device authorised to fix it was
        // the one device that never looked. Which is exactly the case this whole mechanism is for,
        // since admin phones are replaced like any other.
        val adminHasOwnCopy = localHash != null || PaymentQrPipeline.storedFileOrNull(context) != null
        if (secureStorage.getRole() == SecureStorage.Role.ADMIN && adminHasOwnCopy) {
            return@withContext Outcome.ADMIN_IS_AUTHORITATIVE
        }

        // Admin removed it: drop the local copy so Show QR disappears here too (Requirement 14.5).
        if (remoteHash.isNullOrBlank()) {
            if (localHash == null) return@withContext Outcome.NO_CHANGE
            PaymentQrPipeline.deleteFromInternal(context)
            appConfigStore.setPaymentQrHash(null)
            appConfigStore.setPaymentQrUrl(null)
            return@withContext Outcome.CLEARED
        }

        if (remoteHash == localHash && PaymentQrPipeline.storedFileOrNull(context) != null) {
            return@withContext Outcome.NO_CHANGE
        }

        // Hash differs, or we have a hash with no file behind it — fetch the current image.
        val url = remoteUrl?.takeIf { it.isNotBlank() } ?: return@withContext Outcome.FETCH_FAILED
        val bytes = try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Outcome.FETCH_FAILED
                resp.body?.bytes() ?: return@withContext Outcome.FETCH_FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Payment QR fetch failed from $url", e)
            return@withContext Outcome.FETCH_FAILED
        }

        // Verify it still decodes as a QR before trusting it. A truncated or proxy-mangled download
        // that we cached blindly would leave staff showing an unscannable code with no way to tell.
        if (PaymentQrPipeline.decodeQrPayloadFromBytes(bytes) == null) {
            Log.w(TAG, "Downloaded Payment QR does not decode; keeping the previous image")
            return@withContext Outcome.FETCH_FAILED
        }

        PaymentQrPipeline.saveBytesToInternal(context, bytes)
        appConfigStore.setPaymentQrHash(remoteHash)
        appConfigStore.setPaymentQrUrl(url)
        Outcome.UPDATED
    }

    private companion object {
        const val TAG = "PaymentQrResolver"
    }
}
