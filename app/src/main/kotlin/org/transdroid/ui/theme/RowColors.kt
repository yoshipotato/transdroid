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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Muted accent colours for the different pieces of information on a torrent row. */
data class RowColors(
    val size: Color,
    val peers: Color,
    val ratio: Color,
    val download: Color,
    val upload: Color,
    val paused: Color,
    val metadata: Color,
    val completeBackground: Color,
)

private val LightRowColors = RowColors(
    size = Color(0xFF2F6F8F),
    peers = Color(0xFF8A6418),
    ratio = Color(0xFF7A4E9C),
    download = Color(0xFF2B64AD),
    upload = Color(0xFF2E7D4F),
    paused = Color(0xFFB05A3C),
    metadata = Color(0xFF2A7F7A),
    completeBackground = Color(0xFFDDEFD8),
)

private val DarkRowColors = RowColors(
    size = Color(0xFF7FC4E0),
    peers = Color(0xFFDDB86B),
    ratio = Color(0xFFC9A8E4),
    download = Color(0xFF86B6F2),
    upload = Color(0xFF86D4A6),
    paused = Color(0xFFE8A08A),
    metadata = Color(0xFF7FD0C8),
    completeBackground = Color(0xFF1B3320),
)

@Composable
fun rowColors(): RowColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkRowColors else LightRowColors
