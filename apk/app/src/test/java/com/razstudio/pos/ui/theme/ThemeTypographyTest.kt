package com.razstudio.pos.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 10.6 — the typography axis degrades to the system face rather than breaking.
 *
 * Before this, `ThemePreset` carried colour only: twelve values and no typeface. The presets were
 * nonetheless described as having one ("Luxury: Playfair Display"), which was an intention nobody had
 * implemented — the six named faces appeared nowhere in the repo.
 *
 * Two of the six could never have been implemented as named: **Helvetica Neue** (Linotype/Monotype)
 * and **Impact** (Monotype) are proprietary, and bundling either would be infringement. They are
 * substituted by Inter and Anton, both SIL OFL.
 *
 * The property under test is that a preset *without* a face is a first-class case, not an oversight:
 * Tom Yam ships the platform default on purpose, and any future colour-only preset must keep working
 * with no special case at the call site.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeTypographyTest {

    @Test
    fun aPresetWithNoFaceReturnsTheDefaultsUntouched() {
        // Tom Yam is the house identity and deliberately uses the platform face.
        assertNull("Tom Yam must not carry a display face", ThemePreset.TOM_YAM.displayFont)

        val t = ThemePreset.TOM_YAM.toTypography()
        val d = Typography()
        assertEquals(d.displayLarge.fontFamily, t.displayLarge.fontFamily)
        assertEquals(d.bodyLarge.fontFamily, t.bodyLarge.fontFamily)
    }

    @Test
    fun everyOtherPresetCarriesAFace() {
        ThemePreset.entries.filter { it != ThemePreset.TOM_YAM }.forEach {
            assertNotNull("${it.displayName} should have a display face", it.displayFont)
        }
    }

    @Test
    fun theFaceReachesHeadingsOnly() {
        val t = ThemePreset.LUXURY.toTypography()
        val d = Typography()

        // Display + headline take the preset face...
        listOf(t.displayLarge, t.displayMedium, t.displaySmall,
               t.headlineLarge, t.headlineMedium, t.headlineSmall).forEach {
            assertEquals(ThemePreset.LUXURY.displayFont, it.fontFamily)
        }

        // ...and nothing else does. A price or a menu row in a display face trades legibility for
        // branding exactly where legibility is worth most, and these faces carry Latin only, so on
        // the Chinese, Tamil and Thai locales the fallback would show up in body text everywhere.
        listOf(
            t.bodyLarge to d.bodyLarge, t.bodyMedium to d.bodyMedium, t.bodySmall to d.bodySmall,
            t.labelLarge to d.labelLarge, t.labelMedium to d.labelMedium, t.labelSmall to d.labelSmall,
            t.titleLarge to d.titleLarge, t.titleMedium to d.titleMedium, t.titleSmall to d.titleSmall,
        ).forEach { (actual, expected) ->
            assertEquals("body/label/title must keep the platform face",
                expected.fontFamily, actual.fontFamily)
        }
    }

    @Test
    fun everyPresetProducesAUsableTypography() {
        // Nothing may throw, whatever the preset — including the ones with no face.
        ThemePreset.entries.forEach {
            val t = it.toTypography()
            assertNotNull("${it.displayName} produced no typography", t)
            assertTrue(t.bodyLarge.fontSize.value > 0f)
        }
    }

    @Test
    fun sizesAndWeightsAreNotDisturbedByTheFaceSwap() {
        // Only fontFamily changes. A preset that silently altered the type scale would make screens
        // reflow differently per theme, which is a layout bug wearing a branding costume.
        val d = Typography()
        val t = ThemePreset.EDGY.toTypography()
        assertEquals(d.headlineLarge.fontSize, t.headlineLarge.fontSize)
        assertEquals(d.headlineLarge.fontWeight, t.headlineLarge.fontWeight)
        assertEquals(d.displaySmall.lineHeight, t.displaySmall.lineHeight)
    }

    @Test
    fun theColourRampIsUnaffectedByTheTypographyWork() {
        // Regression guard: adding a parameter to the enum must not disturb the twelve colours the
        // presets already carried.
        ThemePreset.entries.forEach {
            assertNotNull(it.shade600)
            assertNotNull(it.shade900)
            assertNotNull(it.outline)
        }
        assertEquals(7, ThemePreset.entries.size)
    }
}
