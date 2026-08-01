package com.razstudio.pos.util

/**
 * Trivial Caesar-shift (shift = 3) obfuscation for the debug-build admin login shortcut.
 * This is NOT security — it's a deliberately weak, easily-reversed obfuscation so a
 * developer/tester can type a café's name without it being a literal plaintext password
 * on screen. Only reachable from a `BuildConfig.DEBUG` build; the backend independently
 * requires its own `ALLOW_DEBUG_ADMIN` deployment secret before honoring it at all.
 */
object CaesarCipher {
    private const val SHIFT = 3

    /** e.g. "mycafe" -> "pbfdih" */
    fun encode(input: String): String = shift(input, SHIFT)

    /** e.g. "pbfdih" -> "mycafe" */
    fun decode(input: String): String = shift(input, -SHIFT)

    private fun shift(input: String, amount: Int): String = input.map { c ->
        when {
            c in 'a'..'z' -> shiftWithin(c, 'a', amount)
            c in 'A'..'Z' -> shiftWithin(c, 'A', amount)
            else -> c
        }
    }.joinToString("")

    private fun shiftWithin(c: Char, base: Char, amount: Int): Char {
        val alphabetSize = 26
        val offset = ((c - base) + amount).mod(alphabetSize)
        return base + offset
    }
}
