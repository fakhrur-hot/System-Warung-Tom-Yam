package com.warungtomyam.pos.ui.i18n

import com.warungtomyam.pos.data.local.MenuItem

/**
 * The languages the operator can switch between in the app.
 *
 * Default is [MY] (Bahasa Malaysia) — the app always opens in BM. The top-right
 * language button shows the current language's [buttonLabel]; tapping it reveals
 * the other four. The selection is persisted (see [LanguageManager]) and survives
 * app close/reopen/restart.
 *
 * The app ships with 5 languages, matching the customer website: Bahasa Malaysia
 * (default), English, 中文, தமிழ், and ไทย. Bahasa Malaysia is the authored source
 * for menu-item names (see [MenuItem] — the mandatory name field in menu management
 * is BM, not English); the other languages are manual per-item overrides, with
 * English used as the last-resort fallback if a translated field is left blank.
 */
enum class AppLanguage(val buttonLabel: String, val displayName: String, val serverCode: String) {
    MY("MY", "Bahasa Malaysia", "BM"),
    EN("EN", "English", "EN"),
    ZH("中文", "中文", "ZH"),
    TA("TA", "தமிழ்", "TA"),
    TH("TH", "ไทย", "TH");

    /** The menu-item name in this language, falling back to Bahasa Malaysia then English. */
    fun menuName(item: MenuItem): String {
        val localized = when (this) {
            MY -> item.nameBm
            EN -> item.nameEn
            ZH -> item.nameZh
            TA -> item.nameTa
            TH -> item.nameTh
        }
        return localized.ifBlank { item.nameBm.ifBlank { item.nameEn } }
    }

    /**
     * Localize an order line's frozen [nameSnapshot] (which the backend bakes as the ENGLISH name
     * plus any " (size)" suffix) into this language, using the live [item] from the current menu:
     * swap the English base for the localized name, keeping the suffix. Falls back to the raw
     * snapshot when the item was deleted or its English base no longer matches (menu renamed).
     */
    fun localizedSnapshotName(nameSnapshot: String, item: MenuItem?): String {
        if (item == null || item.nameEn.isBlank() || !nameSnapshot.startsWith(item.nameEn)) {
            return nameSnapshot.trim()
        }
        return menuName(item) + nameSnapshot.substring(item.nameEn.length)
    }

    companion object {
        val DEFAULT = MY

        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Map a café-wide server default code (BM/EN/ZH/TA/TH; "MY" also accepted) to a language. */
        fun fromServerCode(code: String?): AppLanguage = when (code?.uppercase()) {
            "BM", "MY" -> MY
            "EN" -> EN
            "ZH" -> ZH
            "TA" -> TA
            "TH" -> TH
            else -> DEFAULT
        }
    }
}
