package com.razstudio.pos.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Asserts that an unbranded (no profile) build resolves exactly the template palette values declared
 * in [res/values/colors.xml].
 *
 * Requirement 4.4: A MISSING profile SHALL yield a working, unbranded template build — never a build
 * failure and never a half-branded result.
 *
 * Without this test, a partial failure in [TomYamColors.initialize] or a mis-wired resource name
 * would produce wrong colors silently. This test pins each named color constant to the exact hex
 * declared in the template's colors.xml, so a missing or mis-mapped resource fails loudly here
 * rather than silently producing a half-branded palette at runtime.
 */
@RunWith(RobolectricTestRunner::class)
class TemplatePaletteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset the cache before each test so we always exercise the initialization path.
        // Access the private cache via re-initialization: clear by re-initializing with a fresh call.
        TomYamColors.initialize(context)
    }

    /**
     * Converts a packed ARGB integer (as returned by [Context.getColor]) to a Compose [Color].
     * This mirrors [androidx.core.content.ContextCompat.getColor] + [Color(Int)] used in
     * [TomYamColors.initialize], so the comparison is apples-to-apples.
     */
    private fun resolveColor(resId: Int): Color = Color(context.getColor(resId))

    // ── Primary ramp ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun tomYam50ResolvesToTemplateValue() {
        // colors.xml: #EEF2FF — page background / input tint
        assertEquals(resolveColor(R.color.tom_yam_50), TomYamColors.TomYam50)
    }

    @Test
    fun tomYam100ResolvesToTemplateValue() {
        // colors.xml: #E0E7FF — containers, chips
        assertEquals(resolveColor(R.color.tom_yam_100), TomYamColors.TomYam100)
    }

    @Test
    fun tomYam200ResolvesToTemplateValue() {
        // colors.xml: #C7D2FE
        assertEquals(resolveColor(R.color.tom_yam_200), TomYamColors.TomYam200)
    }

    @Test
    fun tomYam300ResolvesToTemplateValue() {
        // colors.xml: #A5B4FC
        assertEquals(resolveColor(R.color.tom_yam_300), TomYamColors.TomYam300)
    }

    @Test
    fun tomYam400ResolvesToTemplateValue() {
        // colors.xml: #818CF8
        assertEquals(resolveColor(R.color.tom_yam_400), TomYamColors.TomYam400)
    }

    @Test
    fun tomYam500ResolvesToTemplateValue() {
        // colors.xml: #6366F1
        assertEquals(resolveColor(R.color.tom_yam_500), TomYamColors.TomYam500)
    }

    @Test
    fun tomYam600ResolvesToTemplateValue() {
        // colors.xml: #4F46E5 — primary accent (buttons, active states)
        assertEquals(resolveColor(R.color.tom_yam_600), TomYamColors.TomYam600)
    }

    @Test
    fun tomYam700ResolvesToTemplateValue() {
        // colors.xml: #4338CA — hover / pressed
        assertEquals(resolveColor(R.color.tom_yam_700), TomYamColors.TomYam700)
    }

    @Test
    fun tomYam800ResolvesToTemplateValue() {
        // colors.xml: #3730A3
        assertEquals(resolveColor(R.color.tom_yam_800), TomYamColors.TomYam800)
    }

    @Test
    fun tomYam900ResolvesToTemplateValue() {
        // colors.xml: #312E81 — headings / primary text
        assertEquals(resolveColor(R.color.tom_yam_900), TomYamColors.TomYam900)
    }

    // ── Neutral shades ────────────────────────────────────────────────────────────────────────────

    @Test
    fun tomYamMutedResolvesToTemplateValue() {
        // colors.xml: #6B7280 — secondary text
        assertEquals(resolveColor(R.color.tom_yam_muted), TomYamColors.TomYamMuted)
    }

    @Test
    fun tomYamOutlineResolvesToTemplateValue() {
        // colors.xml: #C7D2FE — same hue as 200, used for borders
        assertEquals(resolveColor(R.color.tom_yam_outline), TomYamColors.TomYamOutline)
    }

    // ── Explicit hex values ───────────────────────────────────────────────────────────────────────
    //
    // These pin the template values to their exact hex codes from colors.xml. If a branded build
    // accidentally shadows colors.xml without providing all twelve entries, the mismatching shades
    // will fail here rather than silently producing a half-branded palette (Requirement 4.4).

    @Test
    fun templateValuesMatchColorsXmlExactly() {
        // Each expected value is the literal hex from colors.xml, converted to ARGB with full alpha.
        val expectedRamp = mapOf(
            "TomYam50"      to 0xFFEEF2FF.toInt(),  // #EEF2FF
            "TomYam100"     to 0xFFE0E7FF.toInt(),  // #E0E7FF
            "TomYam200"     to 0xFFC7D2FE.toInt(),  // #C7D2FE
            "TomYam300"     to 0xFFA5B4FC.toInt(),  // #A5B4FC
            "TomYam400"     to 0xFF818CF8.toInt(),  // #818CF8
            "TomYam500"     to 0xFF6366F1.toInt(),  // #6366F1
            "TomYam600"     to 0xFF4F46E5.toInt(),  // #4F46E5
            "TomYam700"     to 0xFF4338CA.toInt(),  // #4338CA
            "TomYam800"     to 0xFF3730A3.toInt(),  // #3730A3
            "TomYam900"     to 0xFF312E81.toInt(),  // #312E81
            "TomYamMuted"   to 0xFF6B7280.toInt(),  // #6B7280
            "TomYamOutline" to 0xFFC7D2FE.toInt(),  // #C7D2FE
        )

        val actualColors = mapOf(
            "TomYam50"      to TomYamColors.TomYam50,
            "TomYam100"     to TomYamColors.TomYam100,
            "TomYam200"     to TomYamColors.TomYam200,
            "TomYam300"     to TomYamColors.TomYam300,
            "TomYam400"     to TomYamColors.TomYam400,
            "TomYam500"     to TomYamColors.TomYam500,
            "TomYam600"     to TomYamColors.TomYam600,
            "TomYam700"     to TomYamColors.TomYam700,
            "TomYam800"     to TomYamColors.TomYam800,
            "TomYam900"     to TomYamColors.TomYam900,
            "TomYamMuted"   to TomYamColors.TomYamMuted,
            "TomYamOutline" to TomYamColors.TomYamOutline,
        )

        for ((name, expectedArgb) in expectedRamp) {
            val actual = actualColors.getValue(name)
            val expected = Color(expectedArgb)
            assertEquals(
                "$name must resolve to template hex #${Integer.toHexString(expectedArgb).uppercase().drop(2)}, " +
                    "got ${actual} — a missing or mismatched profile entry would produce a half-branded palette",
                expected,
                actual,
            )
        }
    }
}
