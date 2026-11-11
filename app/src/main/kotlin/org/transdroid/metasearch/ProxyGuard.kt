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
import android.net.ConnectivityManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Fail-closed proxy enforcement. The app only ever talks through the Android system-level HTTP proxy.
 * If none is configured, every request is refused instead of falling back to a direct connection.
 */
object ProxyGuard {
    class Sys(val host: String, val port: Int) {
        override fun toString() = "$host:$port"
        fun matches(a: InetSocketAddress?) = a != null && a.hostString.equals(host, ignoreCase = true) && a.port == port
    }

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** The system HTTP proxy right now, or null if there isn't one we can enforce (none set, or PAC-only). */
    fun current(): Sys? {
        val ctx = appCtx
        if (ctx != null) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val pi = cm.defaultProxy
            val h = pi?.host
            if (pi != null && !h.isNullOrEmpty() && pi.port > 0) return Sys(h, pi.port)
        }
        val h = System.getProperty("https.proxyHost") ?: System.getProperty("http.proxyHost")
        val p = (System.getProperty("https.proxyPort") ?: System.getProperty("http.proxyPort"))?.toIntOrNull()
        if (!h.isNullOrEmpty() && p != null && p > 0) return Sys(h, p)
        return null
    }

    @Throws(IOException::class)
    fun require(): Sys = current() ?: throw IOException("No system HTTP proxy configured - connection blocked")
}

/** Always answers with the system proxy; never DIRECT. With no proxy it answers with a proxy that cannot connect. */
class StrictProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val s = ProxyGuard.current()
        val addr = if (s != null) InetSocketAddress.createUnresolved(s.host, s.port)
        else InetSocketAddress.createUnresolved("system-proxy-missing.invalid", 9)
        return listOf(Proxy(Proxy.Type.HTTP, addr))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
}
