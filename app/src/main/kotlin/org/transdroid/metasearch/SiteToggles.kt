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
package org.transdroid.metasearch

import android.content.Context

/** Remembers which search sites are switched off. All sites are on by default. */
class SiteToggles(ctx: Context) {
    private val p = ctx.getSharedPreferences("sites", Context.MODE_PRIVATE)

    fun isEnabled(site: Provider) = !p.getBoolean("off_" + site.id, false)

    fun setEnabled(siteId: String, on: Boolean) {
        val e = p.edit()
        if (on) e.remove("off_$siteId") else e.putBoolean("off_$siteId", true)
        e.apply()
    }

    fun enabled(): List<Provider> = Providers.all.filter { isEnabled(it) }
}
