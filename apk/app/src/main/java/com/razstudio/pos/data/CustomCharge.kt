package com.razstudio.pos.data

import com.razstudio.pos.data.local.MenuItem
import java.util.UUID

/**
 * A **custom charge** is a bill line the cashier types by hand — a name and a price — for something
 * the menu does not carry: a corkage fee, a replacement plate, a catering surcharge, "Ikan Bakar
 * (special order)". It rides the ordinary order plumbing rather than getting its own endpoint.
 *
 * The trick is that a custom charge is modelled as a **synthetic [MenuItem]** that exists only inside
 * one cashier's cart. Every existing layer — the cart, the receipt preview, the staged-items list,
 * the kitchen slip — already knows how to render a `MenuItem` + quantity + unit price, so nothing
 * downstream needs to learn about a second kind of line.
 *
 * Its id carries [CUSTOM_CHARGE_ID_PREFIX] plus a fresh UUID. The prefix is how the server tells a
 * typed line from a real menu id (a real one would be rejected as unavailable); the UUID is what
 * keeps two different manual charges from merging into one "×2" line the way two taps of the same
 * dish do.
 *
 * The typed name travels in [NewOrderItem.customName], and the typed price in the same
 * `unitPrice` field a Small/Medium/Large pick uses. Server-side these are trusted **only** for
 * admin/staff callers — a customer ordering from the QR page cannot name its own price.
 */
const val CUSTOM_CHARGE_ID_PREFIX = "CUSTOM:"

/** Longest custom-charge name accepted; the servers enforce the same cap. */
const val CUSTOM_CHARGE_NAME_MAX = 60

/** Highest custom-charge unit price accepted; the servers enforce the same cap. */
const val CUSTOM_CHARGE_PRICE_MAX = 99_999.99

/** True when this cart line is a hand-typed charge rather than a real menu item. */
val MenuItem.isCustomCharge: Boolean get() = id.startsWith(CUSTOM_CHARGE_ID_PREFIX)

/**
 * Build the throwaway [MenuItem] that represents one hand-typed charge.
 *
 * `doNotTranslate` is set and only [MenuItem.nameEn] is filled: the cashier typed this name in
 * whatever language they were thinking in, and there is no translation of it to fetch. Every
 * localized lookup ([com.razstudio.pos.ui.i18n.AppLanguage.menuName]) falls back to `nameEn`, so it
 * shows verbatim on every surface in every language.
 *
 * This item is never inserted into Room and never appears in the menu picker — it lives in the
 * cart, gets serialized into one order line, and is forgotten.
 */
fun customChargeMenuItem(name: String, price: Double): MenuItem = MenuItem(
    id = CUSTOM_CHARGE_ID_PREFIX + UUID.randomUUID(),
    category = "",
    price = price,
    available = true,
    askMeDaily = false,
    nameEn = name.trim().take(CUSTOM_CHARGE_NAME_MAX),
    doNotTranslate = true,
)

/**
 * Cart line -> wire item, tagging a custom charge with its typed name so the server can snapshot
 * it instead of looking the id up in the menu.
 *
 * Every submit path goes through this rather than building [NewOrderItem] inline, so a manual charge
 * cannot be silently dropped to a nameless, zero-priced line by a caller that forgot about it.
 */
fun MenuItem.toNewOrderItem(
    quantity: Int,
    note: String? = null,
    size: String? = null,
    unitPrice: Double? = null,
    variant: String? = null,
): NewOrderItem = NewOrderItem(
    menuItemId = id,
    quantity = quantity,
    note = note,
    // A custom charge's price is the only price it has — there is no menu row to fall back to.
    unitPrice = if (isCustomCharge) (unitPrice ?: price) else unitPrice,
    size = size,
    variant = variant,
    customName = if (isCustomCharge) nameEn else null,
)

/**
 * Parse a cashier-typed price. Accepts "12", "12.5", "1234.56", and a comma decimal separator
 * ("12,50") since that is what a Malaysian keyboard's local layouts offer. Returns null when the
 * text is not a usable positive amount, which is what disables the Add button.
 */
fun parseCustomChargePrice(text: String): Double? {
    val cleaned = text.trim().removePrefix("RM").trim().replace(",", ".")
    val value = cleaned.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value <= 0.0 || value > CUSTOM_CHARGE_PRICE_MAX) return null
    // Money, so 2dp — "1.005" bills as 1.01, not as a third of a sen.
    //
    // Rounded on the DECIMAL text, not on `value * 100`: 1.005 is really 1.00499999... in binary,
    // so the arithmetic route rounds a half-sen DOWN and quietly under-bills. BigDecimal reads the
    // digits the cashier actually typed and rounds them the way a person would.
    return java.math.BigDecimal(cleaned)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .toDouble()
}
