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
import org.json.JSONArray

/** Most-recent-first list of past queries, de-duplicated case-insensitively, persisted in SharedPreferences. */
class SearchHistory(ctx: Context) {
    private val p = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(): List<String> {
        val raw = p.getString("queries", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = ArrayList<String>()
        list.add(q)
        for (s in all()) if (!s.equals(q, ignoreCase = true)) list.add(s)
        save(list.take(MAX))
    }

    fun clear() { p.edit().remove("queries").apply() }

    private fun save(list: List<String>) {
        p.edit().putString("queries", JSONArray(list).toString()).apply()
    }

    companion object { private const val MAX = 100 }
}
