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

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet

class FoundTorrent(
    var name: String,
    var infoHash: String?,
    var magnet: String?,
    var sizeBytes: Long,
    var seeders: Int,
    var leechers: Int,
    site: String,
    /** Set when the magnet link is not on the search page (1337x): provider id + path of the detail page. */
    var detailSiteId: String? = null,
    var detailPath: String? = null
) {
    val sources = CopyOnWriteArraySet<String>().also { it.add(site) }

    fun mergeFrom(o: FoundTorrent) {
        sources.addAll(o.sources)
        if (o.seeders > seeders) seeders = o.seeders
        if (o.leechers > leechers) leechers = o.leechers
        if (magnet == null) magnet = o.magnet
        if (infoHash == null) infoHash = o.infoHash
        if (sizeBytes == 0L) sizeBytes = o.sizeBytes
        if (detailPath == null) { detailPath = o.detailPath; detailSiteId = o.detailSiteId }
        if (infoHash == null) magnet?.let { infoHash = Magnets.hashOf(it) }
    }
}

object Magnets {
    private val BTIH = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    private val HEX40 = Regex("[0-9a-fA-F]{40}")
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )

    /** Canonical lowercase 40-char hex info-hash from a magnet link (accepts base32 hashes too). */
    fun hashOf(magnet: String): String? {
        val h = BTIH.find(magnet)?.groupValues?.get(1) ?: return null
        return when {
            HEX40.matches(h) -> h.lowercase()
            h.length == 32 -> base32ToHex(h)
            else -> null
        }
    }

    fun nameOf(magnet: String): String? {
        val m = Regex("[?&]dn=([^&]+)").find(magnet) ?: return null
        return try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { null }
    }

    fun build(hash: String, name: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hash)
        sb.append("&dn=").append(URLEncoder.encode(name, "UTF-8"))
        for (t in TRACKERS) sb.append("&tr=").append(URLEncoder.encode(t, "UTF-8"))
        return sb.toString()
    }

    private fun base32ToHex(s: String): String? {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val sb = StringBuilder()
        for (c in s.uppercase()) {
            val v = alpha.indexOf(c)
            if (v < 0) return null
            value = (value shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                sb.append("%02x".format((value shr bits) and 0xFF))
                value = value and ((1 shl bits) - 1)
            }
        }
        return if (sb.length == 40) sb.toString() else null
    }
}

object Sizes {
    private val RX = Regex("""([0-9][0-9.,]*)\s*([KMGT]?)i?B\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Long {
        val m = RX.find(text) ?: return 0L
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "K" -> 1L shl 10
            "M" -> 1L shl 20
            "G" -> 1L shl 30
            "T" -> 1L shl 40
            else -> 1L
        }
        return (num * mult).toLong()
    }

    fun pretty(b: Long): String {
        if (b <= 0) return "? size"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$b B" else "%.2f %s".format(v, units[i])
    }

    fun normTitle(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}

/** Merges results from all sites; duplicates are matched by info-hash, else by normalised title + similar size. */
class Deduper {
    private val byHash = HashMap<String, FoundTorrent>()
    private val byTitle = HashMap<String, MutableList<FoundTorrent>>()
    private val all = ArrayList<FoundTorrent>()

    @Synchronized
    fun add(items: List<FoundTorrent>) { for (t in items) addOne(t) }

    @Synchronized
    fun snapshot(): List<FoundTorrent> = all.sortedWith(compareByDescending<FoundTorrent> { it.seeders }.thenBy { it.name })

    private fun sizeCompatible(a: FoundTorrent, b: FoundTorrent): Boolean {
        if (a.sizeBytes == 0L || b.sizeBytes == 0L) return true
        val diff = Math.abs(a.sizeBytes - b.sizeBytes).toDouble()
        return diff <= 0.02 * Math.max(a.sizeBytes, b.sizeBytes)
    }

    private fun addOne(t: FoundTorrent) {
        if (t.infoHash == null) t.magnet?.let { t.infoHash = Magnets.hashOf(it) }
        val key = Sizes.normTitle(t.name)
        if (key.isEmpty()) return
        var existing: FoundTorrent? = t.infoHash?.let { byHash[it] }
        if (existing == null) {
            existing = byTitle[key]?.firstOrNull {
                sizeCompatible(it, t) && (it.infoHash == null || t.infoHash == null || it.infoHash == t.infoHash)
            }
        }
        if (existing != null) {
            existing.mergeFrom(t)
            existing.infoHash?.let { byHash[it] = existing }
        } else {
            all.add(t)
            byTitle.getOrPut(key) { ArrayList() }.add(t)
            t.infoHash?.let { byHash[it] = t }
        }
    }
}
