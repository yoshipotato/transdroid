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

import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun encPath(s: String) = enc(s).replace("+", "%20")
private fun toInt(s: String?) = s?.replace(",", "")?.trim()?.toIntOrNull() ?: 0

abstract class Provider(val id: String, val name: String, val defaultMirrors: List<String>) {
    abstract fun searchUrl(host: String, query: String): String

    /** Cheap sanity check so parked/dead domains that return HTTP 200 trigger a fail-over to the next mirror. */
    abstract fun looksValid(host: String, body: String): Boolean
    abstract fun parse(host: String, body: String): List<FoundTorrent>

    /** Only for sites whose search page has no magnet link (1337x). */
    open fun resolveMagnet(http: Http, host: String, t: FoundTorrent): String? = null
}

object Providers {
    val all: List<Provider> = listOf(X1337, UIndex, PirateBay, LimeTorrents, Eztv)
    fun byId(id: String?): Provider? = all.firstOrNull { it.id == id }
}

object X1337 : Provider(
    "1337x", "1337x",
    listOf("1337x.to", "1337x.st", "x1337x.ws", "x1337x.eu", "x1337x.se")
) {
    override fun searchUrl(host: String, query: String) = "https://$host/search/${encPath(query)}/1/"
    override fun looksValid(host: String, body: String) = body.contains("1337x", ignoreCase = true)

    override fun parse(host: String, body: String): List<FoundTorrent> {
        val doc = Jsoup.parse(body, "https://$host/")
        val out = ArrayList<FoundTorrent>()
        for (row in doc.select("table.table-list tbody tr")) {
            val a = row.selectFirst("td.coll-1 a[href^=/torrent/]") ?: continue
            out.add(
                FoundTorrent(
                    a.text(), null, null,
                    Sizes.parse(row.selectFirst("td.coll-4")?.text() ?: ""),
                    toInt(row.selectFirst("td.coll-2")?.text()),
                    toInt(row.selectFirst("td.coll-3")?.text()),
                    name, id, a.attr("href")
                )
            )
        }
        return out
    }

    override fun resolveMagnet(http: Http, host: String, t: FoundTorrent): String? {
        val path = t.detailPath ?: return null
        val page = http.get("https://$host$path")
        return Jsoup.parse(page.body).selectFirst("a[href^=magnet]")?.attr("href")
    }
}

object UIndex : Provider("uindex", "uindex", listOf("uindex.org")) {
    override fun searchUrl(host: String, query: String) = "https://$host/search.php?search=${enc(query)}&c=0"
    override fun looksValid(host: String, body: String) = body.contains("uindex", ignoreCase = true)

    override fun parse(host: String, body: String): List<FoundTorrent> {
        val doc = Jsoup.parse(body, "https://$host/")
        val out = ArrayList<FoundTorrent>()
        for (row in doc.select("tr")) {
            val mag = row.selectFirst("a[href^=magnet]")?.attr("href") ?: continue
            val hash = Magnets.hashOf(mag) ?: continue
            val title = row.selectFirst("a[href*=details]")?.text()?.takeIf { it.isNotBlank() }
                ?: Magnets.nameOf(mag) ?: continue
            val tds = row.children().toList().filter { it.tagName() == "td" }.map { it.text().trim() }
            val size = tds.firstNotNullOfOrNull { s -> Sizes.parse(s).takeIf { it > 0 } } ?: 0L
            val ints = tds.filter { it.matches(Regex("[0-9][0-9,]*")) }.map { toInt(it) }
            out.add(FoundTorrent(title, hash, mag, size, ints.getOrElse(0) { 0 }, ints.getOrElse(1) { 0 }, name))
        }
        return out
    }
}

object PirateBay : Provider(
    "tpb", "The Pirate Bay",
    // apibay.org = the JSON API that thepiratebay.org itself uses; the others are classic-HTML mirrors.
    listOf("apibay.org", "thepiratebay10.org", "tpb.party")
) {
    private fun isApi(host: String) = host.startsWith("apibay")

    override fun searchUrl(host: String, query: String) =
        if (isApi(host)) "https://$host/q.php?q=${enc(query)}&cat=0"
        else "https://$host/search/${encPath(query)}/1/99/0"

    override fun looksValid(host: String, body: String) =
        if (isApi(host)) body.contains("info_hash") || body.contains("No results returned")
        else body.contains("pirate", ignoreCase = true)

    override fun parse(host: String, body: String): List<FoundTorrent> =
        if (isApi(host)) parseApi(body) else parseHtml(host, body)

    private fun parseApi(body: String): List<FoundTorrent> {
        val json = if (body.trimStart().startsWith("<"))
            Jsoup.parse(body).selectFirst("pre")?.wholeText() ?: body else body
        val arr = try { JSONArray(json.trim()) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<FoundTorrent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hash = o.optString("info_hash").lowercase()
            if (hash.length != 40 || hash.all { it == '0' }) continue
            val nm = o.optString("name")
            out.add(
                FoundTorrent(
                    nm, hash, Magnets.build(hash, nm),
                    o.optString("size").toLongOrNull() ?: 0L,
                    toInt(o.optString("seeders")), toInt(o.optString("leechers")), name
                )
            )
        }
        return out
    }

    private fun parseHtml(host: String, body: String): List<FoundTorrent> {
        val doc = Jsoup.parse(body, "https://$host/")
        val out = ArrayList<FoundTorrent>()
        for (row in doc.select("table#searchResult tr")) {
            val mag = row.selectFirst("a[href^=magnet]")?.attr("href") ?: continue
            val title = row.selectFirst("a.detLink")?.text() ?: Magnets.nameOf(mag) ?: continue
            val tds = row.select("td")
            out.add(
                FoundTorrent(
                    title, Magnets.hashOf(mag), mag,
                    Sizes.parse(row.selectFirst("font.detDesc")?.text() ?: ""),
                    toInt(tds.getOrNull(tds.size - 2)?.text()), toInt(tds.lastOrNull()?.text()), name
                )
            )
        }
        return out
    }
}

object LimeTorrents : Provider(
    "lime", "LimeTorrents",
    listOf("limetorrents.lol", "limetorrents.info", "limetorrents.cc")
) {
    private val HASH = Regex("([0-9A-Fa-f]{40})")

    override fun searchUrl(host: String, query: String) = "https://$host/search/all/${encPath(query)}/seeds/1/"
    override fun looksValid(host: String, body: String) =
        body.contains("limetorrents", ignoreCase = true) || body.contains("table2")

    override fun parse(host: String, body: String): List<FoundTorrent> {
        val doc = Jsoup.parse(body, "https://$host/")
        val out = ArrayList<FoundTorrent>()
        for (row in doc.select("table.table2 tr")) {
            val links = row.select("div.tt-name a")
            if (links.isEmpty()) continue
            val hash = HASH.find(links.first()!!.attr("href"))?.value?.lowercase() ?: continue
            val title = links.last()!!.text()
            val tds = row.select("td.tdnormal")
            out.add(
                FoundTorrent(
                    title, hash, Magnets.build(hash, title),
                    Sizes.parse(tds.getOrNull(1)?.text() ?: ""),
                    toInt(row.selectFirst("td.tdseed")?.text()),
                    toInt(row.selectFirst("td.tdleech")?.text()), name
                )
            )
        }
        return out
    }
}

object Eztv : Provider(
    "eztv", "EZTV",
    listOf("eztv.re", "eztv.wf", "eztvx.to", "eztv.tf", "eztv.yt")
) {
    override fun searchUrl(host: String, query: String) =
        "https://$host/search/${encPath(query.trim().replace(Regex("\\s+"), "-"))}"

    override fun looksValid(host: String, body: String) = body.contains("eztv", ignoreCase = true)

    override fun parse(host: String, body: String): List<FoundTorrent> {
        val doc = Jsoup.parse(body, "https://$host/")
        val out = ArrayList<FoundTorrent>()
        for (row in doc.select("tr.forum_header_border")) {
            val mag = row.selectFirst("a.magnet")?.attr("href") ?: continue
            val title = row.selectFirst("a.epinfo")?.text() ?: Magnets.nameOf(mag) ?: continue
            val tds = row.select("td")
            val size = tds.map { Sizes.parse(it.text()) }.firstOrNull { it > 0 } ?: 0L
            out.add(FoundTorrent(title, Magnets.hashOf(mag), mag, size, toInt(tds.lastOrNull()?.text()), 0, name))
        }
        return out
    }
}
