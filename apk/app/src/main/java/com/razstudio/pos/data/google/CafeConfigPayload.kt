package com.razstudio.pos.data.google

import com.razstudio.pos.data.OperatingMode
import org.json.JSONException
import org.json.JSONObject

/**
 * Task 23.5b — the café configuration carried in the owner's Google account (Requirement 15.6).
 *
 * This is everything the Setup Wizard would otherwise ask an owner to retype onto a replacement
 * device: which topology the café runs, the backend it talks to, its public site, and the owner
 * recovery QR that proves ownership. Restoring it must leave the device in exactly the state
 * finishing Setup would leave it (task 23.6), which is why [toConfig] exists and why the field list
 * here mirrors `AppConfigStore.save` rather than being a looser "profile".
 *
 * ## Why parsing is all-or-nothing
 *
 * [parse] returns `null` unless every field the mode actually needs is present and usable. A half
 * restore is the worst outcome available: the device reports itself configured, the owner puts it on
 * the counter, and it fails at the first order instead of at startup where a human is still looking
 * at it. There is no partial success here — either the café comes back whole or nothing is written
 * and the owner is sent to Setup, which is a state they know how to get out of.
 *
 * A payload written by a newer app version may carry fields this one does not know. Unknown keys are
 * ignored rather than rejected, so an owner who upgrades one device and not another can still
 * restore on both.
 *
 * ## What is deliberately not here
 *
 * No staff credentials, no device registration, no order history. Those belong to a device, not to a
 * café, and copying them would let a replacement device impersonate the one it replaced. A restored
 * device still registers itself and still gets approved.
 */
data class CafeConfigPayload(
    val mode: OperatingMode,
    val cafeName: String,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val websiteUrl: String,
    /**
     * The owner recovery QR — the string a café owner scans to sign in as owner on a new device.
     * This is the sensitive half of the bundle and the reason the storage decision in 23.5a matters:
     * whoever holds this holds the café.
     */
    val ownerRecoveryQr: String,
    /** Informational only — the Cloudflare project names, so an owner can identify their own bundle. */
    val cloudflareDomain: String = "",
    val cloudflareProject: String = "",
    /**
     * The cafe's own setup -- tables, menu, settings, printers -- as a `DatabaseBackupManager`
     * export with the order history stripped out.
     *
     * Without this the bundle is useless off-cloud, which is where it is needed most. A Cloud cafe
     * that restores its URL and key immediately syncs everything else down from Supabase; a LAN or
     * Kiosk cafe has no backend to sync from, so config alone would hand a replacement device a
     * correctly-named till with no tables and an empty menu -- configured, and unable to sell
     * anything.
     *
     * Orders are deliberately not included. They belong to a device's trading history, not to the
     * cafe's setup, and copying them onto a replacement would double-count the day's takings.
     * Blank is valid: a bundle saved before this field existed still restores its config.
     */
    val setupData: String = "",
    /**
     * Menu photo file name -> Drive file id, for the pictures stored beside this manifest.
     *
     * Off-cloud, `LocalImageStore` keeps menu photos in `filesDir/menu-images/` — app-private files
     * that are deleted with the app. A cafe restoring onto a replacement phone would otherwise get
     * its whole menu back as grey placeholders, which for a picture menu is most of the menu.
     *
     * File names rather than menu-item ids, because that is what `LocalImageStore` uses on disk and
     * what `MenuItem.imagePath` already points at.
     */
    val photoFileIds: Map<String, String> = emptyMap(),
    /** Set when written; used to tell two bundles apart in the conflict dialog (task 23.8). */
    val savedAtMs: Long = 0L,
    /** The device that saved it, for the same reason. Never used for authorisation. */
    val savedByDevice: String = "",
) {

    fun toJson(): String = JSONObject().apply {
        put(KEY_VERSION, CURRENT_VERSION)
        put(KEY_MODE, mode.name)
        put(KEY_CAFE_NAME, cafeName)
        put(KEY_SUPABASE_URL, supabaseUrl)
        put(KEY_SUPABASE_ANON_KEY, supabaseAnonKey)
        put(KEY_WEBSITE_URL, websiteUrl)
        put(KEY_OWNER_RECOVERY_QR, ownerRecoveryQr)
        put(KEY_CLOUDFLARE_DOMAIN, cloudflareDomain)
        put(KEY_CLOUDFLARE_PROJECT, cloudflareProject)
        put(KEY_SETUP_DATA, setupData)
        put(KEY_PHOTOS, JSONObject(photoFileIds as Map<*, *>))
        put(KEY_SAVED_AT, savedAtMs)
        put(KEY_SAVED_BY, savedByDevice)
    }.toString()

    companion object {
        /** Bumped only when a field becomes *required*; adding optional fields does not need it. */
        const val CURRENT_VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_MODE = "mode"
        private const val KEY_CAFE_NAME = "cafe_name"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
        private const val KEY_WEBSITE_URL = "website_url"
        private const val KEY_OWNER_RECOVERY_QR = "owner_recovery_qr"
        private const val KEY_CLOUDFLARE_DOMAIN = "cloudflare_domain"
        private const val KEY_CLOUDFLARE_PROJECT = "cloudflare_project"
        private const val KEY_SETUP_DATA = "setup_data"
        private const val KEY_PHOTOS = "photo_file_ids"
        private const val KEY_SAVED_AT = "saved_at_ms"
        private const val KEY_SAVED_BY = "saved_by_device"

        /**
         * Returns the payload, or `null` if it is unusable for any reason — malformed JSON, a mode
         * this build does not know, a version from the future, or a missing field the mode needs.
         *
         * Never throws. The caller is a restore path on a café owner's phone; the only useful
         * answers are "here is your café" and "start Setup".
         */
        fun parse(json: String): CafeConfigPayload? {
            return try {
            val o = JSONObject(json)

            // A future version may have made a field mandatory that this build writes blank. Reading
            // it as if it were v1 would produce a payload that looks complete and is not.
            if (o.optInt(KEY_VERSION, 1) > CURRENT_VERSION) return null

            val mode = OperatingMode.entries.firstOrNull { it.name == o.optString(KEY_MODE) }
                ?: return null
            val cafeName = o.optString(KEY_CAFE_NAME).trim()
            val supabaseUrl = o.optString(KEY_SUPABASE_URL).trim()
            val supabaseAnonKey = o.optString(KEY_SUPABASE_ANON_KEY).trim()

            // The café name is the one field every mode needs — it is what `isModeConfigured`
            // checks off-cloud, so a nameless payload restores to a café that stays locked.
            if (cafeName.isEmpty()) return null

            // Cloud additionally needs a backend it can actually reach. LAN and Kiosk store none by
            // design, so requiring these of them would reject every valid off-cloud bundle.
            if (mode == OperatingMode.CLOUD && (supabaseUrl.isEmpty() || supabaseAnonKey.isEmpty())) {
                return null
            }

            CafeConfigPayload(
                mode = mode,
                cafeName = cafeName,
                supabaseUrl = supabaseUrl,
                supabaseAnonKey = supabaseAnonKey,
                websiteUrl = o.optString(KEY_WEBSITE_URL).trim(),
                ownerRecoveryQr = o.optString(KEY_OWNER_RECOVERY_QR).trim(),
                cloudflareDomain = o.optString(KEY_CLOUDFLARE_DOMAIN).trim(),
                cloudflareProject = o.optString(KEY_CLOUDFLARE_PROJECT).trim(),
                setupData = o.optString(KEY_SETUP_DATA),
                photoFileIds = o.optJSONObject(KEY_PHOTOS)?.let { obj ->
                    obj.keys().asSequence().associateWith { k -> obj.optString(k) }
                        .filterValues { it.isNotBlank() }
                } ?: emptyMap(),
                savedAtMs = o.optLong(KEY_SAVED_AT, 0L),
                savedByDevice = o.optString(KEY_SAVED_BY).trim(),
            )
            } catch (e: JSONException) {
                null
            }
        }
    }
}
