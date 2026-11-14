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
package org.transdroid.ui.metasearch

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.transdroid.R
import org.transdroid.metasearch.Deduper
import org.transdroid.metasearch.FoundTorrent
import org.transdroid.metasearch.Http
import org.transdroid.metasearch.Magnets
import org.transdroid.metasearch.MirrorStore
import org.transdroid.metasearch.Provider
import org.transdroid.metasearch.Providers
import org.transdroid.metasearch.ProxyGuard
import org.transdroid.metasearch.SearchHistory
import org.transdroid.metasearch.SiteToggles
import org.transdroid.metasearch.VerificationCancelledException
import org.transdroid.metasearch.Sizes
import org.transdroid.metasearch.WebFetcher
import org.transdroid.ui.torrents.FREE_SPACE_RESERVE_BYTES
import org.transdroid.ui.torrents.UiError
import org.transdroid.util.formatBytes
import java.util.concurrent.atomic.AtomicInteger

/** One merged search result: the same torrent found on several sites shows up once. */
data class MetaResult(
    val key: String,
    val name: String,
    val seeders: Int,
    val leechers: Int,
    val sizeBytes: Long,
    val sources: List<String>,
    internal val torrent: FoundTorrent,
)

enum class SiteState { OFF, IDLE, SEARCHING, OK, FAILED }

data class SiteLine(val id: String, val name: String, val state: SiteState, val detail: String = "")

data class MetaSearchUi(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<MetaResult> = emptyList(),
    val sites: List<SiteLine> = emptyList(),
    /** The system HTTP proxy in use, or null when there is none (every search is then refused). */
    val proxy: String? = null,
    /** Result keys that were sent to the server during this visit. */
    val added: Set<String> = emptySet(),
    /** The result currently being resolved/added. */
    val busyKey: String? = null,
    val history: List<String> = emptyList(),
)

/**
 * Searches several torrent sites at once (through the Android system proxy only, see [ProxyGuard]), merges
 * the duplicates and sends a tapped result straight to the active server.
 */
class MetaSearchViewModel(private val app: Application) : ViewModel() {
    private val mirrors = MirrorStore(app)
    private val history = SearchHistory(app)
    private val sites = SiteToggles(app)
    private val http: Http

    init {
        ProxyGuard.init(app)
        http = Http(WebFetcher(app))
    }

    private val _ui = MutableStateFlow(
        MetaSearchUi(
            sites = siteLines(emptyMap()),
            proxy = ProxyGuard.current()?.toString(),
            history = history.all(),
        ),
    )
    val ui: StateFlow<MetaSearchUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short texts for the screen to show as toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val searchId = AtomicInteger()
    private var searchJob: Job? = null

    private fun say(resId: Int, vararg args: Any) { _messages.tryEmit(app.getString(resId, *args)) }

    private fun siteLines(state: Map<String, SiteLine>): List<SiteLine> = Providers.all.map { p ->
        when {
            !sites.isEnabled(p) -> SiteLine(p.id, p.name, SiteState.OFF)
            else -> state[p.id] ?: SiteLine(p.id, p.name, SiteState.IDLE)
        }
    }

    fun refreshProxy() {
        _ui.update { it.copy(proxy = ProxyGuard.current()?.toString()) }
    }

    fun setQuery(query: String) { _ui.update { it.copy(query = query) } }

    fun search(query: String = _ui.value.query) {
        val q = query.trim()
        if (q.isEmpty()) return
        val use = sites.enabled()
        refreshProxy()
        if (use.isEmpty()) { say(R.string.meta_all_sites_off); return }
        if (ProxyGuard.current() == null) { say(R.string.meta_no_proxy_short); return }
        history.add(q)
        val id = searchId.incrementAndGet()
        searchJob?.cancel()
        http.resumeWebLoads()
        val dedup = Deduper()
        _ui.update { state ->
            state.copy(
                query = q,
                searching = true,
                searched = true,
                results = emptyList(),
                history = history.all(),
                sites = Providers.all.map { p ->
                    if (p in use) SiteLine(p.id, p.name, SiteState.SEARCHING) else SiteLine(p.id, p.name, SiteState.OFF)
                },
            )
        }
        searchJob = viewModelScope.launch {
            use.map { p -> launch(Dispatchers.IO) { runProvider(p, q, id, dedup) } }.joinAll()
            if (id == searchId.get()) _ui.update { it.copy(searching = false) }
        }
    }

    /** Stops the search in progress; what was found so far stays on screen. */
    fun stopSearch() {
        searchId.incrementAndGet()
        searchJob?.cancel()
        http.abortWebLoads()
        _ui.update { state ->
            state.copy(
                searching = false,
                sites = state.sites.map {
                    if (it.state == SiteState.SEARCHING) it.copy(state = SiteState.IDLE, detail = "stopped") else it
                },
            )
        }
    }

    private fun runProvider(p: Provider, q: String, id: Int, dedup: Deduper) {
        var lastError = "no mirror responded"
        for (host in mirrors.ordered(p)) {
            if (id != searchId.get()) return
            try {
                val page = http.get(p.searchUrl(host, q))
                if (!p.looksValid(host, page.body)) { lastError = "$host: unexpected page"; continue }
                val found = p.parse(host, page.body)
                mirrors.markGood(p.id, host)
                dedup.add(found)
                if (id != searchId.get()) return
                _ui.update { state ->
                    state.copy(
                        results = dedup.snapshot().map { it.toResult() },
                        sites = state.sites.replace(SiteLine(p.id, p.name, SiteState.OK, "${found.size} via $host")),
                    )
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationCancelledException) {
                // The user declined the check: give up on this site for this search (the next search tries again)
                lastError = "verification cancelled"
                break
            } catch (e: Exception) {
                lastError = "$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (id == searchId.get()) {
            _ui.update { it.copy(sites = it.sites.replace(SiteLine(p.id, p.name, SiteState.FAILED, lastError))) }
        }
    }

    private fun List<SiteLine>.replace(line: SiteLine) = map { if (it.id == line.id) line else it }

    private fun FoundTorrent.toResult() = MetaResult(
        key = Sizes.normTitle(name),
        name = name,
        seeders = seeders,
        leechers = leechers,
        sizeBytes = sizeBytes,
        sources = sources.toList().sorted(),
        torrent = this,
    )

    /**
     * Adds the result to the active server right away. [add] hands a magnet link to the torrent list's
     * add function and returns false when no server is set up; its callback gets null on success.
     */
    fun addTorrent(result: MetaResult, add: (String, Long?, (UiError?) -> Unit) -> Boolean) {
        if (_ui.value.busyKey != null) return
        http.resumeWebLoads()
        viewModelScope.launch {
            _ui.update { it.copy(busyKey = result.key) }
            val t = result.torrent
            var magnet = t.magnet
            if (magnet == null) {
                if (ProxyGuard.current() == null) {
                    _ui.update { it.copy(busyKey = null) }
                    say(R.string.meta_no_proxy_short)
                    return@launch
                }
                say(R.string.meta_fetching_magnet)
                magnet = withContext(Dispatchers.IO) { resolveMagnet(t) }
                if (magnet == null) {
                    _ui.update { it.copy(busyKey = null) }
                    say(R.string.meta_magnet_failed)
                    return@launch
                }
                t.magnet = magnet
                if (t.infoHash == null) t.infoHash = Magnets.hashOf(magnet)
            }
            val started = add(magnet, result.sizeBytes) { error ->
                _ui.update { state ->
                    state.copy(busyKey = null, added = if (error == null) state.added + result.key else state.added)
                }
                when (error) {
                    null -> say(R.string.meta_added, result.name)
                    is UiError.NoSpace -> say(
                        R.string.error_no_space,
                        formatBytes(error.sizeBytes),
                        formatBytes(error.freeBytes),
                        formatBytes(FREE_SPACE_RESERVE_BYTES),
                    )
                    else -> say(R.string.meta_add_failed)
                }
            }
            if (!started) {
                _ui.update { it.copy(busyKey = null) }
                say(R.string.meta_no_server)
            }
        }
    }

    private fun resolveMagnet(t: FoundTorrent): String? {
        val p = Providers.byId(t.detailSiteId) ?: return null
        for (host in mirrors.ordered(p)) {
            try {
                val m = p.resolveMagnet(http, host, t)
                if (m != null) return m
            } catch (e: VerificationCancelledException) {
                return null
            } catch (e: Exception) { /* try the next mirror */ }
        }
        return null
    }

    // Settings: which sites to search, their mirror domains, the search history

    fun setSiteEnabled(id: String, enabled: Boolean) {
        sites.setEnabled(id, enabled)
        _ui.update { state ->
            state.copy(
                sites = Providers.all.map { p ->
                    val old = state.sites.firstOrNull { it.id == p.id }
                    when {
                        !sites.isEnabled(p) -> SiteLine(p.id, p.name, SiteState.OFF)
                        old != null && old.state != SiteState.OFF -> old
                        else -> SiteLine(p.id, p.name, SiteState.IDLE)
                    }
                },
            )
        }
    }

    fun siteEnabled(p: Provider) = sites.isEnabled(p)

    fun mirrorsText(p: Provider): String = mirrors.base(p).joinToString("\n")

    fun saveMirrors(texts: Map<String, String>) {
        for ((id, text) in texts) mirrors.set(id, text.lines())
    }

    fun resetMirrors() { mirrors.resetAll() }

    fun clearHistory() {
        history.clear()
        _ui.update { it.copy(history = emptyList()) }
    }

    override fun onCleared() {
        searchId.incrementAndGet()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MetaSearchViewModel(checkNotNull(this[APPLICATION_KEY]) as Application) }
        }
    }
}
