package com.razstudio.pos.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.razstudio.pos.R

/**
 * Runtime-swappable colour presets. Each preset provides a complete 10-shade ramp that maps
 * directly onto [lightColorScheme] or [darkColorScheme], using the same role assignments as [TomYamLightColors].
 *
 * Presets are intentionally self-contained: no resource reads, no context needed. That keeps
 * theme switching instant — no disk I/O, no recompose lag beyond the normal MaterialTheme
 * propagation.
 *
 * The ramp names (TomYam50…) are kept stable in the resource files for build-time café branding;
 * this enum is the in-process runtime layer on top of that.
 */
enum class ThemePreset(
    val displayName: String,
    // 10-shade ramp
    private val rawShade50: Color,
    private val rawShade100: Color,
    private val rawShade200: Color,
    private val rawShade300: Color,
    private val rawShade400: Color,
    private val rawShade500: Color,
    private val rawShade600: Color,   // primary accent
    private val rawShade700: Color,   // hover / pressed
    private val rawShade800: Color,
    private val rawShade900: Color,   // headings
    private val rawMuted: Color,
    private val rawOutline: Color,

    /**
     * The preset's display typeface, applied to **headings only** — never to body text.
     *
     * Two reasons, and the second is the one that matters in a café. First, these six faces are
     * Latin-only by construction: none contains CJK, Tamil or Thai glyphs, so on four of the app's
     * five languages Android would fall back per-glyph anyway. Confining them to headings keeps that
     * fallback where it is least visible. Second, a POS is read under time pressure — setting prices
     * and menu rows in Dancing Script or Impact would trade legibility for branding at exactly the
     * moment legibility is worth most.
     *
     * `null` means "use the system face throughout", which is what TOM_YAM and MINIMALIST want.
     */
    val displayFont: FontFamily? = null,
    val isDark: Boolean = false,
) {

    /**  Tom Yam — deep red. The build-time default and the house identity. */
    TOM_YAM(
        displayName = "Tom Yam",
        rawShade50  = Color(0xFFFEF3F1),
        rawShade100 = Color(0xFFFADEDB),
        rawShade200 = Color(0xFFF0B2AC),
        rawShade300 = Color(0xFFE0786E),
        rawShade400 = Color(0xFFC83C30),
        rawShade500 = Color(0xFFB0160C),
        rawShade600 = Color(0xFF9B0600),
        rawShade700 = Color(0xFF7A0500),
        rawShade800 = Color(0xFF5C0400),
        rawShade900 = Color(0xFF400200),
        rawMuted    = Color(0xFF6B7280),
        rawOutline  = Color(0xFFF0B2AC),
    ),

    /**  Luxury — warm ivory and antique gold. Opulent, high-end. */
    LUXURY(
        displayName = "Luxury",
        rawShade50  = Color(0xFFF8F4E8),
        rawShade100 = Color(0xFFEDE4CC),
        rawShade200 = Color(0xFFD4C4A0),
        rawShade300 = Color(0xFFB8A070),
        rawShade400 = Color(0xFF9C8448),
        rawShade500 = Color(0xFFC9A227),
        rawShade600 = Color(0xFFD4AF37),
        rawShade700 = Color(0xFFB8941F),
        rawShade800 = Color(0xFF2C1810),
        rawShade900 = Color(0xFF1A0F0A),
        rawMuted    = Color(0xFF8B7355),
        rawOutline  = Color(0xFFD4C4A0),
        // Playfair Display — high-contrast didone, the classic luxury serif
        displayFont = FontFamily(Font(R.font.playfair_display)),
    ),

    /**  Elegant — champagne and rose gold. Refined, timeless. */
    ELEGANT(
        displayName = "Elegant",
        rawShade50  = Color(0xFFFDF8F5),
        rawShade100 = Color(0xFFF5EBE0),
        rawShade200 = Color(0xFFE8D5C4),
        rawShade300 = Color(0xFFD4B8A0),
        rawShade400 = Color(0xFFBF9880),
        rawShade500 = Color(0xFFB07B6A),
        rawShade600 = Color(0xFFB5706A),
        rawShade700 = Color(0xFF9C5B55),
        rawShade800 = Color(0xFF3D2420),
        rawShade900 = Color(0xFF2A1815),
        rawMuted    = Color(0xFF9E7B6E),
        rawOutline  = Color(0xFFE8D5C4),
        // Cormorant Garamond — a refined old-style serif
        displayFont = FontFamily(Font(R.font.cormorant_garamond)),
    ),

    /**  Minimalist — pure white and charcoal. Clean, uncluttered. */
    MINIMALIST(
        displayName = "Minimalist",
        rawShade50  = Color(0xFFFAFAFA),
        rawShade100 = Color(0xFFF4F4F5),
        rawShade200 = Color(0xFFE4E4E7),
        rawShade300 = Color(0xFFD1D1D6),
        rawShade400 = Color(0xFFA1A1AA),
        rawShade500 = Color(0xFF71717A),
        rawShade600 = Color(0xFF27272A),
        rawShade700 = Color(0xFF18181B),
        rawShade800 = Color(0xFF09090B),
        rawShade900 = Color(0xFF000000),
        rawMuted    = Color(0xFF71717A),
        rawOutline  = Color(0xFFE4E4E7),
        // Inter — a neutral grotesque, and the open stand-in for Helvetica Neue (proprietary)
        displayFont = FontFamily(Font(R.font.inter)),
    ),

    /**  Bold — cobalt blue and electric. High contrast, energetic. */
    BOLD(
        displayName = "Bold",
        rawShade50  = Color(0xFFEFF6FF),
        rawShade100 = Color(0xFFDBEAFE),
        rawShade200 = Color(0xFFBFDBFE),
        rawShade300 = Color(0xFF93C5FD),
        rawShade400 = Color(0xFF60A5FA),
        rawShade500 = Color(0xFF3B82F6),
        rawShade600 = Color(0xFF1D4ED8),
        rawShade700 = Color(0xFF1E3A8A),
        rawShade800 = Color(0xFF1E2A5E),
        rawShade900 = Color(0xFF0F172A),
        rawMuted    = Color(0xFF64748B),
        rawOutline  = Color(0xFFBFDBFE),
        // Anton — heavy condensed poster face; the open stand-in for Impact (proprietary)
        displayFont = FontFamily(Font(R.font.anton)),
    ),

    /**  Soft — blush pink and lavender. Gentle, romantic. */
    SOFT(
        displayName = "Soft",
        rawShade50  = Color(0xFFFDF2F8),
        rawShade100 = Color(0xFFFCE7F3),
        rawShade200 = Color(0xFFFBCFE8),
        rawShade300 = Color(0xFFF9A8D4),
        rawShade400 = Color(0xFFF472B6),
        rawShade500 = Color(0xFFEC4899),
        rawShade600 = Color(0xFFBE185D),
        rawShade700 = Color(0xFF9D174D),
        rawShade800 = Color(0xFF831843),
        rawShade900 = Color(0xFF500724),
        rawMuted    = Color(0xFF9F8595),
        rawOutline  = Color(0xFFFBCFE8),
        // Dancing Script — a soft script
        displayFont = FontFamily(Font(R.font.dancing_script)),
    ),

    /**  Edgy — charcoal and electric lime. Sharp, rebellious. */
    EDGY(
        displayName = "Edgy",
        rawShade50  = Color(0xFFF0FDF4),
        rawShade100 = Color(0xFFDCFCE7),
        rawShade200 = Color(0xFFBBF7D0),
        rawShade300 = Color(0xFF86EFAC),
        rawShade400 = Color(0xFF4ADE80),
        rawShade500 = Color(0xFF22C55E),
        rawShade600 = Color(0xFF16A34A),
        rawShade700 = Color(0xFF15803D),
        rawShade800 = Color(0xFF1C1C1C),
        rawShade900 = Color(0xFF0A0A0A),
        rawMuted    = Color(0xFF6B7280),
        rawOutline  = Color(0xFFBBF7D0),
        // Oswald — tall condensed grotesque
        displayFont = FontFamily(Font(R.font.oswald)),
    ),

    /**  Material Dark — standard Material 3 dark theme */
    MATERIAL_DARK(
        displayName = "Material Dark",
        rawShade50  = Color(0xFFE3E3E3), // light text
        rawShade100 = Color(0xFFC7C7C7),
        rawShade200 = Color(0xFFBB86FC), // primary accent (light purple)
        rawShade300 = Color(0xFF03DAC6), // secondary accent (teal)
        rawShade400 = Color(0xFFCF6679), // tertiary accent
        rawShade500 = Color(0xFF985EFF),
        rawShade600 = Color(0xFFBB86FC), // primary
        rawShade700 = Color(0xFF3700B3),
        rawShade800 = Color(0xFF1E1E1E), // card background
        rawShade900 = Color(0xFF121212), // background
        rawMuted    = Color(0xFF9E9E9E),
        rawOutline  = Color(0xFF373737),
        displayFont = null,
        isDark      = true,
    ),

    /**  Amoled Dark — pure black theme for high-contrast and battery saving */
    AMOLED_DARK(
        displayName = "Amoled Dark",
        rawShade50  = Color(0xFFF5F5F5), // white text
        rawShade100 = Color(0xFFE0E0E0),
        rawShade200 = Color(0xFF00E676), // vibrant green primary accent
        rawShade300 = Color(0xFF29B6F6), // vibrant light blue
        rawShade400 = Color(0xFFAB47BC), // purple
        rawShade500 = Color(0xFF00C853),
        rawShade600 = Color(0xFF00E676), // primary
        rawShade700 = Color(0xFF009624),
        rawShade800 = Color(0xFF121212), // card background
        rawShade900 = Color(0xFF000000), // background (pure black)
        rawMuted    = Color(0xFF888888),
        rawOutline  = Color(0xFF222222),
        displayFont = null,
        isDark      = true,
    );

    val shade50: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam50 else rawShade50
    val shade100: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam100 else rawShade100
    val shade200: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam200 else rawShade200
    val shade300: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam300 else rawShade300
    val shade400: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam400 else rawShade400
    val shade500: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam500 else rawShade500
    val shade600: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam600 else rawShade600
    val shade700: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam700 else rawShade700
    val shade800: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam800 else rawShade800
    val shade900: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYam900 else rawShade900
    val muted: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYamMuted else rawMuted
    val outline: Color get() = if (this == TOM_YAM && TomYamColors.isInitialized) TomYamColors.TomYamOutline else rawOutline

    /**
     * Material3 typography with this preset's face applied to **display and headline styles only**.
     *
     * Body, label and title styles keep the platform default deliberately. A POS is read under time
     * pressure — a menu row or a price in Dancing Script or Anton trades legibility for branding at
     * the moment legibility is worth most — and these six faces carry Latin only, so on the Chinese,
     * Tamil and Thai locales Android would fall back per-glyph anyway. Confining them to headings
     * puts that fallback where it shows least.
     *
     * A preset with no [displayFont] returns the defaults untouched, so the colour-only presets
     * (Tom Yam, and any future one) need no special case anywhere.
     */
    fun toTypography(): Typography {
        val face = displayFont ?: return Typography()
        val base = Typography()
        return base.copy(
            displayLarge  = base.displayLarge.copy(fontFamily = face),
            displayMedium = base.displayMedium.copy(fontFamily = face),
            displaySmall  = base.displaySmall.copy(fontFamily = face),
            headlineLarge = base.headlineLarge.copy(fontFamily = face),
            headlineMedium = base.headlineMedium.copy(fontFamily = face),
            headlineSmall = base.headlineSmall.copy(fontFamily = face),
        )
    }

    /** Build a Material3 [ColorScheme] from this preset's ramp, using the same role map as
     *  [TomYamLightColors] so every screen recolours consistently without component changes. */
    fun toColorScheme(): ColorScheme {
        return if (isDark) {
            darkColorScheme(
                primary              = shade200,
                onPrimary            = shade900,
                primaryContainer     = shade800,
                onPrimaryContainer   = shade100,

                secondary            = shade300,
                onSecondary          = shade900,
                secondaryContainer   = shade800,
                onSecondaryContainer = shade100,

                tertiary             = shade400,
                onTertiary           = shade900,
                tertiaryContainer    = shade800,
                onTertiaryContainer  = shade100,

                background           = shade900,
                onBackground         = shade50,
                surface              = shade800,
                onSurface            = shade50,
                surfaceVariant       = shade800,
                onSurfaceVariant     = muted,

                // ── Dialog / sheet grounds ──────────────────────────────────────────────
                // See the light-scheme note below: unset, these fall back to Material's stock
                // baseline palette and every dialog in the app ignores the active preset.
                surfaceContainerLowest = shade900,
                surfaceContainerLow    = shade800,
                surfaceContainer       = shade800,
                surfaceContainerHigh   = shade700,
                surfaceContainerHighest = shade700,
                surfaceTint            = shade200,

                outline              = outline,
                outlineVariant       = shade700,

                // Validation / destructive: standard red/rose in dark theme
                error                = Color(0xFFF2B8B5),
                onError              = Color(0xFF601410),
                errorContainer       = Color(0xFF8C1D18),
                onErrorContainer     = Color(0xFFF9DEDC),
            )
        } else {
            lightColorScheme(
                primary              = shade600,
                onPrimary            = Color.White,
                primaryContainer     = shade100,
                onPrimaryContainer   = shade900,

                secondary            = shade500,
                onSecondary          = Color.White,
                secondaryContainer   = shade100,
                onSecondaryContainer = shade900,

                tertiary             = shade700,
                onTertiary           = Color.White,
                tertiaryContainer    = shade200,
                onTertiaryContainer  = shade900,

                background           = shade50,
                onBackground         = shade900,
                surface              = Color.White,
                onSurface            = shade900,
                surfaceVariant       = shade100,
                onSurfaceVariant     = muted,

                // ── Dialog / sheet grounds ──────────────────────────────────────────────
                //
                // Material3 draws a ModalBottomSheet on `surfaceContainerLow` and an AlertDialog on
                // `surfaceContainerHigh`. Leaving those unset does NOT fall back to `surface` — it
                // falls back to Material's own baseline palette, which is a lavender-tinted white.
                // So until now every dialog and sheet in the app was drawn in stock Material lavender
                // no matter which preset was active: the Tom Yam red build had lavender sheets, and so
                // did every other one. The preset only ever reached the content inside them.
                //
                // Mapping them onto the preset's own ramp fixes that and gives dialogs the intended
                // reading order: a dialog sits one step DARKER than the white content panels it
                // contains, so a scroll area inside it (which stays `surface` = white) reads as a
                // lighter inset rather than melting into the dialog around it.
                surfaceContainerLowest = Color.White,
                surfaceContainerLow    = shade50,
                surfaceContainer       = shade50,
                surfaceContainerHigh   = shade100,
                surfaceContainerHighest = shade100,
                // The elevation-overlay tint, so a raised surface warms toward the preset rather
                // than toward Material's default purple.
                surfaceTint            = shade600,

                outline              = outline,
                outlineVariant       = shade200,

                // Validation / destructive: standard red — unchanged across all presets
                error                = Color(0xFFBA1A1A),
                onError              = Color.White,
                errorContainer       = Color(0xFFFFDAD6),
                onErrorContainer     = Color(0xFF410002),
            )
        }
    }

    companion object {
        val DEFAULT = TOM_YAM

        fun fromName(name: String?): ThemePreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
