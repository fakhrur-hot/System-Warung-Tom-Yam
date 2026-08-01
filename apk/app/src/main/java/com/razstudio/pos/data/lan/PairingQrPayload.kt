package com.razstudio.pos.data.lan

import org.json.JSONObject

/**
 * What a LAN pairing QR actually contains (task 7.1, Requirement 5.1).
 *
 * Three fields, because a Client needs all three and can guess none of them: [host] and [port] say
 * where the Server is, and [pairingToken] is the single-use code that authorises this one
 * registration.
 *
 * ### Why JSON rather than a URL
 *
 * A `http://host:port/join?invite=…` string would be shorter, and it is what Cloud Mode uses — but
 * off-cloud that is a link resolving nowhere, so anything that helpfully offers to open it (a generic
 * QR scanner, a messaging-app preview) sends the operator to a dead page and makes a working pairing
 * look broken. A JSON object is inert: only this app knows what to do with it.
 *
 * ### Why org.json and not kotlinx.serialization
 *
 * The serialization *compiler plugin* is not applied to this module — only Ktor's runtime artifact is
 * on the classpath. Adding the plugin to encode two strings and an int would change the build for
 * every source file, and the rest of this codebase already speaks `org.json`.
 *
 * [TYPE] and [VERSION] travel with the payload so a future format change is detectable rather than
 * mis-parsed: a scanner meeting an unfamiliar version can say "this code is from a newer version of
 * the app" instead of failing with a parse error.
 */
data class PairingQrPayload(
    val host: String,
    val port: Int,
    val pairingToken: String,
) {
    fun encode(): String = JSONObject()
        .put("type", TYPE)
        .put("version", VERSION)
        .put("host", host)
        .put("port", port)
        .put("pairingToken", pairingToken)
        .toString()

    companion object {
        const val TYPE = "warungpos.lan.pair"
        const val VERSION = 1

        /**
         * Parse a scanned string, or null if it is not one of our pairing codes.
         *
         * Null rather than an exception for every rejection — wrong type, newer version, malformed —
         * because the scanner points at whatever the operator holds up, and a table QR or a payment
         * code landing in frame is expected, not exceptional.
         */
        fun decode(raw: String): PairingQrPayload? = runCatching {
            val o = JSONObject(raw)
            if (o.optString("type") != TYPE) return null
            if (o.optInt("version", -1) != VERSION) return null

            val host = o.optString("host").trim()
            val token = o.optString("pairingToken").trim()
            val port = o.optInt("port", -1)
            if (host.isBlank() || token.isBlank() || port !in 1..65535) return null

            PairingQrPayload(host = host, port = port, pairingToken = token)
        }.getOrNull()
    }
}
