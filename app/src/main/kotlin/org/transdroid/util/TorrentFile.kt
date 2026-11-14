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
package org.transdroid.util

/**
 * Total content size in bytes of a .torrent file (its single file's length, or the sum of all
 * files'), or null when the data isn't a readable torrent.
 */
fun torrentFileSize(data: ByteArray): Long? = try {
    val root = BencodeReader(data).read() as? Map<*, *>
    val info = root?.get("info") as? Map<*, *>
    val files = info?.get("files") as? List<*>
    if (files != null) {
        files.sumOf { ((it as? Map<*, *>)?.get("length") as? Long) ?: 0L }
    } else {
        info?.get("length") as? Long
    }?.takeIf { it > 0 }
} catch (e: Exception) {
    null
}

private class BencodeReader(private val d: ByteArray) {
    private var i = 0

    fun read(): Any? = when (d[i].toInt().toChar()) {
        'i' -> {
            i++
            val end = d.indexOf('e'.code.toByte(), i)
            val n = String(d, i, end - i, Charsets.ISO_8859_1).toLong()
            i = end + 1
            n
        }
        'l' -> {
            i++
            val list = ArrayList<Any?>()
            while (d[i].toInt().toChar() != 'e') list.add(read())
            i++
            list
        }
        'd' -> {
            i++
            val map = HashMap<String, Any?>()
            while (d[i].toInt().toChar() != 'e') {
                val key = readString()
                map[key] = read()
            }
            i++
            map
        }
        else -> readString()
    }

    private fun readString(): String {
        val colon = d.indexOf(':'.code.toByte(), i)
        val len = String(d, i, colon - i, Charsets.ISO_8859_1).toInt()
        val s = String(d, colon + 1, len, Charsets.ISO_8859_1)
        i = colon + 1 + len
        return s
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (k in from until size) if (this[k] == b) return k
        throw IllegalArgumentException("bad bencode")
    }
}
