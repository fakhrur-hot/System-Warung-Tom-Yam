package com.razstudio.opsapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs the one-time self-registration of this operator device into a freshly provisioned café's
 * `devices` table using the service-role key from [ProvisionResult].
 *
 * This is the single place the Operator APK talks to a café's Supabase project with an elevated key
 * rather than through a device session. It happens exactly once per newly-provisioned café,
 * immediately after provisioning succeeds, and the service-role key is never stored past the call.
 */
@Singleton
class OperatorSelfRegistrar @Inject constructor() {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TOKEN_BYTE_LENGTH = 32 // 32 bytes → 64-char hex string
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Inserts an OPERATOR device row into the new café's `devices` table via Postgrest, using
     * the service-role key for authentication.
     *
     * @param supabaseUrl The new café's Supabase project URL (e.g. `https://xxx.supabase.co`).
     * @param supabaseAnonKey The café's anon/public key (used as the `apikey` header).
     * @param supabaseServiceRoleKey The elevated service-role key — used once for this insert, then
     *   discarded by the caller.
     * @return [SelfRegistrationResult] containing the inserted device row's `id` and the raw
     *   session token (not the hash) for local storage.
     */
    suspend fun register(
        supabaseUrl: String,
        supabaseAnonKey: String,
        supabaseServiceRoleKey: String,
    ): SelfRegistrationResult = withContext(Dispatchers.IO) {
        val deviceIdentifier = "operator-${UUID.randomUUID()}"
        val rawToken = generateSessionToken()
        val tokenHash = sha256Hex(rawToken)
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))

        val body = JSONObject().apply {
            put("device_identifier", deviceIdentifier)
            put("role", "OPERATOR")
            put("status", "APPROVED")
            put("session_token_hash", tokenHash)
            put("key_delivered_at", now)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val url = "${supabaseUrl.trimEnd('/')}/rest/v1/devices"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", supabaseAnonKey)
            .addHeader("Authorization", "Bearer $supabaseServiceRoleKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=representation")
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw SelfRegistrationException(
                    "Self-registration failed (HTTP ${resp.code}): $responseBody"
                )
            }

            // Postgrest returns an array when `Prefer: return=representation` is used.
            val array = JSONArray(responseBody)
            if (array.length() == 0) {
                throw SelfRegistrationException("Self-registration returned empty response.")
            }
            val inserted = array.getJSONObject(0)
            val deviceId = inserted.getString("id")

            SelfRegistrationResult(
                deviceId = deviceId,
                sessionToken = rawToken,
            )
        }
    }

    /** Generates a cryptographically secure random 64-character hex token. */
    private fun generateSessionToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 hash of [input], returned as a lowercase hex string. */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/** Successful result of operator self-registration into a new café's devices table. */
data class SelfRegistrationResult(
    /** The UUID assigned by the database to the new devices row. */
    val deviceId: String,
    /** The raw session token (not hashed) — stored locally as the OPERATOR bearer credential. */
    val sessionToken: String,
)

/** Thrown when the self-registration Postgrest insert fails. */
class SelfRegistrationException(message: String) : Exception(message)
