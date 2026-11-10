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
package org.transdroid.data

import android.content.Context
import android.net.ConnectivityManager

/** Reads the device's system-level HTTP proxy. */
object SystemProxy {

    /** The proxy host (an IP or hostname), or null when none is set or it only has a PAC script. */
    fun host(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val fromConnectivity = cm?.defaultProxy?.host?.takeIf { it.isNotBlank() }
        return fromConnectivity ?: System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
    }
}
