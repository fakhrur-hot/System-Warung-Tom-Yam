package com.razstudio.opsapp.data.promos

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SHA-256 signing utility for Shopee Affiliate Open API authentication.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.ShopeeAuthSigner` (post-bugfix version —
 * see .kiro/specs/shopee-affiliate-ads-apk/bugfix). Shopee's documented signature scheme is a
 * single plain SHA-256 hash of the concatenated string `appId + timestamp + payload + secret` —
 * **not** an HMAC. The resulting header is one composite `Authorization` value:
 * `SHA256 Credential={appId},Timestamp={timestamp},Signature={signature}`.
 */
@Singleton
class ShopeeAuthSigner @Inject constructor() {

    companion object {
        /**
         * Baked affiliate account ID. All commission attribution flows to this account.
         * Changing this requires a new build — it is not configurable at runtime.
         */
        const val AFFILIATE_ID = "12352980181"
    }

    /**
     * Computes Shopee's documented request signature: a plain SHA-256 hex digest of
     * `appId + timestamp + payload + secret`.
     */
    fun sign(appId: String, timestamp: Long, payload: String, secret: String): String {
        val baseString = "$appId$timestamp$payload$secret"
        val digest = MessageDigest.getInstance("SHA-256").digest(baseString.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Builds the single `Authorization` header Shopee's Open API expects:
     * `SHA256 Credential={appId},Timestamp={timestamp},Signature={signature}`.
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
