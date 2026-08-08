package com.razstudio.pos.data.promos

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SHA-256 signing utility for Shopee Affiliate Open API authentication.
 *
 * Shopee's real signature scheme (verified against the documented Open API auth flow) is a single
 * plain SHA-256 hash of the concatenated string `appId + timestamp + payload + secret` — **not** an
 * HMAC. The resulting header is one composite `Authorization` value:
 * `SHA256 Credential={appId},Timestamp={timestamp},Signature={signature}`.
 */
@Singleton
class ShopeeAuthSigner @Inject constructor() {

    companion object {
        /**
         * Baked affiliate account ID. All commission attribution flows to this account.
         * Changing this requires a new APK build — it is not configurable at runtime.
         */
        const val AFFILIATE_ID = "12352980181"
    }

    /**
     * Computes Shopee's documented request signature: a plain SHA-256 hex digest of
     * `appId + timestamp + payload + secret`.
     *
     * @param appId The Shopee affiliate app ID.
     * @param timestamp Unix epoch seconds.
     * @param payload The raw JSON request body.
     * @param secret The Shopee affiliate app secret.
     * @return Lowercase hex-encoded SHA-256 digest.
     */
    fun sign(appId: String, timestamp: Long, payload: String, secret: String): String {
        val baseString = "$appId$timestamp$payload$secret"
        val digest = MessageDigest.getInstance("SHA-256").digest(baseString.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds the single `Authorization` header Shopee's Open API expects:
     * `SHA256 Credential={appId},Timestamp={timestamp},Signature={signature}`.
     *
     * @param appId The Shopee affiliate app ID.
     * @param secret The Shopee affiliate app secret.
     * @param payload The JSON request body.
     * @param timestamp Unix epoch seconds.
     * @return Map of HTTP headers required for an authenticated request.
     */
    fun buildHeaders(
        appId: String,
        secret: String,
        payload: String,
        timestamp: Long,
    ): Map<String, String> {
        val signature = sign(appId, timestamp, payload, secret)
        return mapOf(
            "Authorization" to "SHA256 Credential=$appId,Timestamp=$timestamp,Signature=$signature",
            "Content-Type" to "application/json",
        )
    }
}
