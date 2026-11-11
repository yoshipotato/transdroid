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

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Every connection must go through the Android system HTTP proxy, otherwise it is refused:
 *  1. application interceptor: refuses the call if no system proxy is set (before any socket is opened),
 *     and drops pooled connections whenever the system proxy changes;
 *  2. StrictProxySelector: only ever offers the system proxy (never DIRECT);
 *  3. network interceptor: verifies the connection really is an HTTP-proxy connection to the system proxy.
 * If Cloudflare challenges a host, that host switches to WebView loading (see WebFetcher), which is
 * likewise forced onto the system proxy or refused.
 */
class Http(private val web: WebFetcher) {
    class Page(val code: Int, val body: String)

    @Volatile private var lastProxy: String? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxySelector(StrictProxySelector())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val sys = ProxyGuard.require()
            val key = sys.toString()
            if (key != lastProxy) {
                lastProxy = key
                clientPoolEvict()
            }
            chain.proceed(chain.request())
        })
        .addNetworkInterceptor(Interceptor { chain ->
            val sys = ProxyGuard.require()
            val proxy = chain.connection()?.route()?.proxy
            val ok = proxy != null && proxy.type() == Proxy.Type.HTTP &&
                sys.matches(proxy.address() as? InetSocketAddress)
            if (!ok) throw IOException("Connection not via the system proxy - blocked")
            chain.proceed(chain.request())
        })
        .build()

    private fun clientPoolEvict() { client.connectionPool.evictAll() }

    private val webHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Throws(IOException::class)
    fun get(url: String): Page {
        ProxyGuard.require()
        val host = url.toHttpUrl().host
        if (host in webHosts) return viaWeb(url)

        val req = Request.Builder().url(url)
            .header("User-Agent", web.userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (Cloudflare.isChallenge(resp.header("cf-mitigated"), body)) {
                webHosts.add(host)
                return viaWeb(url)
            }
            if (resp.code >= 500 || resp.code == 403 || resp.code == 429) throw IOException("HTTP ${resp.code}")
            return Page(resp.code, body)
        }
    }

    @Throws(IOException::class)
    private fun viaWeb(url: String): Page = Page(200, web.fetch(url))
}
