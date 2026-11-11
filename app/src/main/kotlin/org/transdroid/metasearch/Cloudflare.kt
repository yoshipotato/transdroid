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

object Cloudflare {
    private val TITLE = Regex(
        "<title>\\s*(Just a moment|Attention Required|Checking your browser|Please Wait)",
        RegexOption.IGNORE_CASE
    )

    fun isChallenge(cfMitigated: String?, body: String): Boolean {
        if (cfMitigated != null && cfMitigated.contains("challenge", ignoreCase = true)) return true
        return looksLikeChallengeBody(body)
    }

    fun looksLikeChallengeBody(body: String): Boolean {
        val head = body.take(30000)
        return TITLE.containsMatchIn(head) ||
            head.contains("_cf_chl_opt") ||
            head.contains("cf-browser-verification") ||
            head.contains("Enable JavaScript and cookies to continue")
    }
}
