package com.razstudio.pos.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.razstudio.pos.R

/**
 * Runtime-swappable colour presets. Each preset provides a complete 10-shade ramp that maps
 * directly onto [lightColorScheme], using the same role assignments as [TomYamLightColors].
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
    val emoji: String,
    // 10-shade ramp
    val shade50: Color,
    val shade100: Color,
    val shade200: Color,
    val shade300: Color,
    val shade400: Color,
    val shade500: Color,
    val shade600: Color,   // primary accent
    val shade700: Color,   // hover / pressed
    val shade800: Color,
    val shade900: Color,   // headings
    val muted: Color,
    val outline: Color,

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
) {

    /** 🌶 Tom Yam — deep red. The build-time default and the house identity. */
    TOM_YAM(
        displayName = "Tom Yam",
        emoji = "🌶",
        shade50  = Color(0xFFFEF3F1),
        shade100 = Color(0xFFFADEDB),
        shade200 = Color(0xFFF0B2AC),
        shade300 = Color(0xFFE0786E),
        shade400 = Color(0xFFC83C30),
        shade500 = Color(0xFFB0160C),
        shade600 = Color(0xFF9B0600),
        shade700 = Color(0xFF7A0500),
        shade800 = Color(0xFF5C0400),
        shade900 = Color(0xFF400200),
        muted    = Color(0xFF6B7280),
        outline  = Color(0xFFF0B2AC),
    ),

    /** 🎩 Luxury — warm ivory and antique gold. Opulent, high-end. */
    LUXURY(
        displayName = "Luxury",
        emoji = "🎩",
        shade50  = Color(0xFFF8F4E8),
        shade100 = Color(0xFFEDE4CC),
        shade200 = Color(0xFFD4C4A0),
        shade300 = Color(0xFFB8A070),
        shade400 = Color(0xFF9C8448),
        shade500 = Color(0xFFC9A227),
        shade600 = Color(0xFFD4AF37),
        shade700 = Color(0xFFB8941F),
        shade800 = Color(0xFF2C1810),
        shade900 = Color(0xFF1A0F0A),
        muted    = Color(0xFF8B7355),
        outline  = Color(0xFFD4C4A0),
        // Playfair Display — high-contrast didone, the classic luxury serif
        displayFont = FontFamily(Font(R.font.playfair_display)),
    ),

    /** 💎 Elegant — champagne and rose gold. Refined, timeless. */
    ELEGANT(
        displayName = "Elegant",
        emoji = "💎",
        shade50  = Color(0xFFFDF8F5),
        shade100 = Color(0xFFF5EBE0),
        shade200 = Color(0xFFE8D5C4),
        shade300 = Color(0xFFD4B8A0),
        shade400 = Color(0xFFBF9880),
        shade500 = Color(0xFFB07B6A),
        shade600 = Color(0xFFB5706A),
        shade700 = Color(0xFF9C5B55),
        shade800 = Color(0xFF3D2420),
        shade900 = Color(0xFF2A1815),
        muted    = Color(0xFF9E7B6E),
        outline  = Color(0xFFE8D5C4),
        // Cormorant Garamond — a refined old-style serif
        displayFont = FontFamily(Font(R.font.cormorant_garamond)),
    ),

    /** ⬜ Minimalist — pure white and charcoal. Clean, uncluttered. */
    MINIMALIST(
        displayName = "Minimalist",
        emoji = "⬜",
        shade50  = Color(0xFFFAFAFA),
        shade100 = Color(0xFFF4F4F5),
        shade200 = Color(0xFFE4E4E7),
        shade300 = Color(0xFFD1D1D6),
        shade400 = Color(0xFFA1A1AA),
        shade500 = Color(0xFF71717A),
        shade600 = Color(0xFF27272A),
        shade700 = Color(0xFF18181B),
        shade800 = Color(0xFF09090B),
        shade900 = Color(0xFF000000),
        muted    = Color(0xFF71717A),
        outline  = Color(0xFFE4E4E7),
        // Inter — a neutral grotesque, and the open stand-in for Helvetica Neue (proprietary)
        displayFont = FontFamily(Font(R.font.inter)),
    ),

    /** 🔥 Bold — cobalt blue and electric. High contrast, energetic. */
    BOLD(
        displayName = "Bold",
        emoji = "🔥",
        shade50  = Color(0xFFEFF6FF),
        shade100 = Color(0xFFDBEAFE),
        shade200 = Color(0xFFBFDBFE),
        shade300 = Color(0xFF93C5FD),
        shade400 = Color(0xFF60A5FA),
        shade500 = Color(0xFF3B82F6),
        shade600 = Color(0xFF1D4ED8),
        shade700 = Color(0xFF1E3A8A),
        shade800 = Color(0xFF1E2A5E),
        shade900 = Color(0xFF0F172A),
        muted    = Color(0xFF64748B),
        outline  = Color(0xFFBFDBFE),
        // Anton — heavy condensed poster face; the open stand-in for Impact (proprietary)
        displayFont = FontFamily(Font(R.font.anton)),
    ),

    /** 🌸 Soft — blush pink and lavender. Gentle, romantic. */
    SOFT(
        displayName = "Soft",
        emoji = "🌸",
        shade50  = Color(0xFFFDF2F8),
        shade100 = Color(0xFFFCE7F3),
        shade200 = Color(0xFFFBCFE8),
        shade300 = Color(0xFFF9A8D4),
        shade400 = Color(0xFFF472B6),
        shade500 = Color(0xFFEC4899),
        shade600 = Color(0xFFBE185D),
        shade700 = Color(0xFF9D174D),
        shade800 = Color(0xFF831843),
        shade900 = Color(0xFF500724),
        muted    = Color(0xFF9F8595),
        outline  = Color(0xFFFBCFE8),
        // Dancing Script — a soft script
        displayFont = FontFamily(Font(R.font.dancing_script)),
    ),

    /** ⚡ Edgy — charcoal and electric lime. Sharp, rebellious. */
    EDGY(
        displayName = "Edgy",
        emoji = "⚡",
        shade50  = Color(0xFFF0FDF4),
        shade100 = Color(0xFFDCFCE7),
        shade200 = Color(0xFFBBF7D0),
        shade300 = Color(0xFF86EFAC),
        shade400 = Color(0xFF4ADE80),
        shade500 = Color(0xFF22C55E),
        shade600 = Color(0xFF16A34A),
        shade700 = Color(0xFF15803D),
        shade800 = Color(0xFF1C1C1C),
        shade900 = Color(0xFF0A0A0A),
        muted    = Color(0xFF6B7280),
        outline  = Color(0xFFBBF7D0),
        // Oswald — tall condensed grotesque
        displayFont = FontFamily(Font(R.font.oswald)),
    );

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
    fun toColorScheme(): ColorScheme = lightColorScheme(
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

        outline              = outline,
        outlineVariant       = shade200,

        // Validation / destructive: standard red — unchanged across all presets
        error                = Color(0xFFBA1A1A),
        onError              = Color.White,
        errorContainer       = Color(0xFFFFDAD6),
        onErrorContainer     = Color(0xFF410002),
    )

    companion object {
        val DEFAULT = TOM_YAM

        fun fromName(name: String?): ThemePreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
