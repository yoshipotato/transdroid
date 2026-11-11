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

/** Per-site list of mirror domains (user-editable) plus the last mirror that worked, which is tried first. */
class MirrorStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mirrors", Context.MODE_PRIVATE)

    fun base(site: Provider): List<String> {
        val custom = p.getString("custom_" + site.id, null)
        return if (custom.isNullOrBlank()) site.defaultMirrors else custom.split("\n").filter { it.isNotBlank() }
    }

    fun ordered(site: Provider): List<String> {
        val list = base(site)
        val good = p.getString("good_" + site.id, null)
        return if (good != null && good in list) listOf(good) + list.filter { it != good } else list
    }

    fun markGood(siteId: String, host: String) { p.edit().putString("good_$siteId", host).apply() }

    fun set(siteId: String, lines: List<String>) {
        val clean = lines.map { clean(it) }.filter { it.isNotEmpty() }.distinct()
        val e = p.edit()
        if (clean.isEmpty()) e.remove("custom_$siteId") else e.putString("custom_$siteId", clean.joinToString("\n"))
        e.remove("good_$siteId").apply()
    }

    fun resetAll() { p.edit().clear().apply() }

    private fun clean(s: String): String =
        s.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
}
