package com.razstudio.pos.ui.i18n

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every string the app shows exists in all five languages.
 *
 * This test is here because the gap it checks for shipped once. Task 23's sign-in and café-bundle
 * screens were written with English string literals inline, compiled cleanly, passed 243 tests, and
 * were only caught by looking at a screenshot: an English card sitting in the middle of an otherwise
 * Malay screen, on a café's own till.
 *
 * Nothing in the type system prevents that. `Text("Save to Google")` is as valid as
 * `Text(strings.cafeBundleSave)`, and a reviewer reading the diff sees plausible English either way.
 * The only mechanical signal is the one below: if a key exists, it must be filled in everywhere.
 *
 * ## What this catches, and what it cannot
 *
 * It catches a key added to [UiStrings] and left blank or copy-pasted in four locales. It does NOT
 * catch a literal that never became a key at all — for that, read the screen. The companion habit is
 * to grep new UI files for `Text("` before committing.
 */
class UiStringsCompletenessTest {

    /** Every public `String` property on [UiStrings] — the full surface, discovered not listed. */
    private val stringProperties = UiStrings::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
        .sortedBy { it.name }

    private fun valueOf(lang: AppLanguage, getter: java.lang.reflect.Method): String =
        getter.invoke(uiStrings(lang)) as String

    @Test
    fun theSurfaceIsNotAccidentallyEmpty() {
        // A guard on the guard: if reflection stops finding properties, every assertion below
        // silently passes over an empty list.
        assertTrue(
            "expected to discover hundreds of strings, found ${stringProperties.size}",
            stringProperties.size > 200,
        )
    }

    @Test
    fun noStringIsBlankInAnyLanguage() {
        val gaps = mutableListOf<String>()
        AppLanguage.entries.forEach { lang ->
            stringProperties.forEach { getter ->
                if (valueOf(lang, getter).isBlank()) {
                    gaps += "${lang.name}.${getter.name.removePrefix("get").replaceFirstChar { it.lowercase() }}"
                }
            }
        }
        if (gaps.isNotEmpty()) fail("blank strings (${gaps.size}): ${gaps.take(20)}")
    }

    @Test
    fun theTaskTwentyThreeStringsWereActuallyTranslated() {
        // Untranslated keys are worse than missing ones: they read as done. These are checked
        // against English specifically because copy-pasting the English column is exactly how the
        // original defect happened.
        //
        // "Save to Google" and similar are excluded nowhere — every one of these has real prose in
        // it, so an identical Malay value means nobody translated it.
        val keys = listOf(
            "getSignInSubtitle", "getSignInStepSigningIn", "getSignInStepLookingUp",
            "getSignInNoCafeFound", "getSignInSetupThisCafe", "getSignInProblemTitle",
            "getSignInUnavailable", "getSignInBundleUnreadable", "getSignInDriveUnreachable",
            "getSignInRestoreIncomplete", "getSignInConflictTitle", "getSignInConflictBody",
            "getCafeBundleTitle", "getCafeBundleDesc", "getCafeBundleConsentTitle",
            "getCafeBundleConsentBody", "getCafeBundleSaved", "getCafeBundleRemoved",
        )
        val untranslated = mutableListOf<String>()
        keys.forEach { name ->
            val getter = stringProperties.firstOrNull { it.name == name }
                ?: fail("$name is missing from UiStrings entirely").let { return@forEach }
            val english = valueOf(AppLanguage.EN, getter)
            listOf(AppLanguage.MY, AppLanguage.ZH, AppLanguage.TA, AppLanguage.TH).forEach { lang ->
                if (valueOf(lang, getter) == english) untranslated += "${lang.name}.$name"
            }
        }
        if (untranslated.isNotEmpty()) fail("left in English: $untranslated")
    }

    @Test
    fun formatPlaceholdersSurviveTranslation() {
        // A translator who drops "%1$s" produces a message that renders but says nothing useful —
        // "Signed in as ." — and a translator who writes "%1s" produces a crash at format() time.
        // Both are invisible until that exact screen state occurs on that exact language.
        val withArgs = mapOf(
            "getSignInNoCafeFound" to 1,
            "getSignInKeepAccountCafe" to 1,
            "getSignInKeepDeviceCafe" to 1,
            "getSignInConflictBody" to 2,
            "getPaymentGatewaySaveFailed" to 1,
        )
        withArgs.forEach { (name, count) ->
            val getter = stringProperties.first { it.name == name }
            AppLanguage.entries.forEach { lang ->
                val value = valueOf(lang, getter)
                (1..count).forEach { i ->
                    assertTrue(
                        "${lang.name}.$name lost placeholder %$i\$s: $value",
                        value.contains("%$i\$s"),
                    )
                }
            }
        }
    }

    @Test
    fun everyLanguageResolvesToADistinctTable() {
        // Guards a wiring slip in `uiStrings(lang)` — a `when` branch pointing at the wrong locale
        // would make two languages identical, and nothing else in the app would notice.
        val subtitles = AppLanguage.entries.map { uiStrings(it).signInSubtitle }
        assertTrue("every locale must map to its own table", subtitles.toSet().size == AppLanguage.entries.size)
    }
}
