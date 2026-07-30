package com.razstudio.pos.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Neutral template palette (indigo) — the generic vendor default shipped with the template build,
 * with no café branding. A café-specific build overrides these values to its own colors. The ramp
 * names (TomYam*) are kept stable so the theme wiring downstream needs no changes.
 */
val TomYam50 = Color(0xFFEEF2FF)  // page background / input tint
val TomYam100 = Color(0xFFE0E7FF) // containers, chips
val TomYam200 = Color(0xFFC7D2FE)
val TomYam300 = Color(0xFFA5B4FC)
val TomYam400 = Color(0xFF818CF8)
val TomYam500 = Color(0xFF6366F1)
val TomYam600 = Color(0xFF4F46E5) // primary accent — buttons, active states
val TomYam700 = Color(0xFF4338CA) // hover / pressed
val TomYam800 = Color(0xFF3730A3)
val TomYam900 = Color(0xFF312E81) // headings / primary text

/** Neutral muted slate for secondary text. */
val TomYamMuted = Color(0xFF6B7280)
val TomYamOutline = Color(0xFFC7D2FE)

// NOTE: Order-status colors (green = free/done, orange = pending, purple = cooking,
// blue = ready, grey = unknown) are intentionally NOT part of this brand ramp. They
// encode meaning and stay hardcoded where they are used.
