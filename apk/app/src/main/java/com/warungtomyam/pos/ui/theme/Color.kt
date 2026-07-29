package com.warungtomyam.pos.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Tom Yam" brand palette — ported 1:1 from the customer-facing web app, where Tailwind's
 * `emerald` scale is overridden to a deep-red ramp anchored on #9B0600. Light-only, matching
 * the web. Admin + ordering-staff screens now share this identity via [WarungTomYamTheme].
 */
val TomYam50 = Color(0xFFFEF3F1)  // page background / input tint ("white slightly red")
val TomYam100 = Color(0xFFFADEDB) // containers, chips
val TomYam200 = Color(0xFFF0B2AC)
val TomYam300 = Color(0xFFE0786E)
val TomYam400 = Color(0xFFC83C30)
val TomYam500 = Color(0xFFB0160C)
val TomYam600 = Color(0xFF9B0600) // primary accent — buttons, active states
val TomYam700 = Color(0xFF7A0500) // hover / pressed
val TomYam800 = Color(0xFF5C0400)
val TomYam900 = Color(0xFF400200) // headings / item names / primary text

/** Warm muted brown for secondary text (mirrors the web review theme's --muted). */
val TomYamMuted = Color(0xFF8A6F66)
val TomYamOutline = Color(0xFFD8B5AE)

// NOTE: Order-status colors (green = free/done, orange = pending, purple = cooking,
// blue = ready, grey = unknown) are intentionally NOT part of this brand ramp. They
// encode meaning and stay hardcoded where they are used.
