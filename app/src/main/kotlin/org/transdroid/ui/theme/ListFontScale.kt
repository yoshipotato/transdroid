/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/** User-chosen multipliers for the text sizes on the torrent list rows (1f = the default size). */
data class ListFontScales(val name: Float = 1f, val details: Float = 1f)

val LocalListFontScales = compositionLocalOf { ListFontScales() }

/** Texts hidden from the start of torrent names on the list; see [displayName]. */
val LocalNameTrims = compositionLocalOf { emptyList<String>() }

/**
 * This torrent name without any of the [trims] at its start (case-insensitive), plus the separator
 * characters (spaces, dashes, colons, pipes) that followed it. Repeats, so stacked prefixes all go.
 * A name that would end up empty is left alone.
 */
fun String.withoutPrefixes(trims: List<String>): String {
    var name = this
    var changed = true
    while (changed) {
        changed = false
        for (raw in trims) {
            val trim = raw.trim()
            if (trim.isEmpty()) continue
            if (name.trimStart().startsWith(trim, ignoreCase = true)) {
                val rest = name.trimStart().substring(trim.length).trimStart { it.isWhitespace() || it in "-–—:|" }
                if (rest.isNotEmpty()) {
                    name = rest
                    changed = true
                }
            }
        }
    }
    return name.trim()
}

/** This style with its font size and line height multiplied by [factor]. */
fun TextStyle.scaled(factor: Float): TextStyle {
    if (factor == 1f) return this
    return copy(
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    )
}
