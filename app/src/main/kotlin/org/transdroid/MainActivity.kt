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
package org.transdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.transdroid.metasearch.ActivityHolder
import org.transdroid.ui.TransdroidApp
import org.transdroid.data.SettingsRepository
import org.transdroid.ui.theme.ListFontScales
import org.transdroid.ui.theme.LocalListFontScales
import org.transdroid.ui.theme.LocalNameTrims
import org.transdroid.ui.theme.TransdroidTheme
import org.transdroid.widget.publishWidgetPreviews

/** Intent extra set by the home screen widget's Add button; see [org.transdroid.widget.TransdroidWidget]. */
const val EXTRA_OPEN_ADD_TORRENT = "open_add_torrent"

class MainActivity : ComponentActivity() {

    private var pendingTorrentUrl by mutableStateOf<String?>(null)
    private var pendingOpenAddTorrent by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only read the launch intent on a fresh start; on recreation, restore the (possibly
        // already consumed) pending value so an added torrent isn't offered again on rotate
        if (savedInstanceState == null) {
            pendingTorrentUrl = extractTorrentUrl(intent)
            pendingOpenAddTorrent = intent?.getBooleanExtra(EXTRA_OPEN_ADD_TORRENT, false) == true
        } else {
            pendingTorrentUrl = savedInstanceState.getString(STATE_PENDING_TORRENT_URL)
            pendingOpenAddTorrent = savedInstanceState.getBoolean(STATE_PENDING_OPEN_ADD_TORRENT)
        }
        lifecycleScope.launch { publishWidgetPreviews(applicationContext) }
        val settings = appContainer.settingsRepository
        val fontScales = combine(settings.nameFontScale, settings.detailFontScale) { name, details ->
            ListFontScales(name, details)
        }
        setContent {
            val scales by fontScales.collectAsState(initial = ListFontScales())
            val nameTrims by settings.nameTrims.collectAsState(initial = SettingsRepository.DEFAULT_NAME_TRIMS)
            TransdroidTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                CompositionLocalProvider(LocalListFontScales provides scales, LocalNameTrims provides nameTrims) {
                    TransdroidApp(
                        useTwoPane = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                        pendingTorrentUrl = pendingTorrentUrl,
                        onPendingTorrentUrlConsumed = { pendingTorrentUrl = null },
                        pendingOpenAddTorrent = pendingOpenAddTorrent,
                        onPendingOpenAddTorrentConsumed = { pendingOpenAddTorrent = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActivityHolder.current = this
        // The proxy may have changed while the app was in the background
        lifecycleScope.launch { runCatching { appContainer.syncServerUrlToSystemProxy() } }
    }

    override fun onStop() {
        if (ActivityHolder.current === this) ActivityHolder.current = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    /** Hides the system navigation bar; a swipe from the screen edge shows it briefly. */
    private fun hideNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractTorrentUrl(intent)?.let { pendingTorrentUrl = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD_TORRENT, false)) {
            pendingOpenAddTorrent = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_TORRENT_URL, pendingTorrentUrl)
        outState.putBoolean(STATE_PENDING_OPEN_ADD_TORRENT, pendingOpenAddTorrent)
    }

    /** Pulls a magnet link, torrent URL or .torrent content URI out of VIEW/SEND intents. */
    private fun extractTorrentUrl(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf {
            it.startsWith("magnet:") || it.startsWith("content:") || it.startsWith("file:")
        }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf {
            it.startsWith("magnet:") || it.startsWith("http://") || it.startsWith("https://")
        }
        else -> null
    }

    private companion object {
        const val STATE_PENDING_TORRENT_URL = "pending_torrent_url"
        const val STATE_PENDING_OPEN_ADD_TORRENT = "pending_open_add_torrent"
    }
}
