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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.transdroid.R
import org.transdroid.metasearch.Providers
import org.transdroid.ui.components.EmptyState
import org.transdroid.ui.theme.LocalStatusColors
import org.transdroid.ui.theme.rowColors
import org.transdroid.ui.torrents.UiError
import org.transdroid.util.formatBytes

/**
 * Searches several torrent sites at once. Tapping a result adds it to the active server immediately
 * and the screen stays put, so several results can be added in a row.
 */
@Composable
fun MetaSearchScreen(
    viewModel: MetaSearchViewModel,
    addToServer: (String, Long?, (UiError?) -> Unit) -> Boolean,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var showSites by remember { mutableStateOf(false) }
    var showMirrors by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        // Coming from a screenshot, the search is already running: don't cover the results with the keyboard
        if (!viewModel.ui.value.searched) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // The proxy may have been set up (or removed) while the app was in the background
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshProxy() }

    fun doSearch(query: String = ui.query) {
        viewModel.search(query)
        keyboard?.hide()
    }

    Scaffold(
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                SearchBarPill(
                    query = ui.query,
                    onQueryChange = viewModel::setQuery,
                    onBack = onBack,
                    onSearch = { doSearch() },
                    focusRequester = focusRequester,
                    menu = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.meta_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.meta_sites)) },
                                    onClick = { menuOpen = false; showSites = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.meta_mirrors)) },
                                    onClick = { menuOpen = false; showMirrors = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.meta_clear_history)) },
                                    onClick = { menuOpen = false; viewModel.clearHistory() },
                                )
                            }
                        }
                    },
                )
                SiteStatusRow(ui.sites, onTap = { line ->
                    if (line.detail.isNotEmpty()) Toast.makeText(context, "${line.name}: ${line.detail}", Toast.LENGTH_LONG).show()
                })
                if (ui.searching) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        TextButton(onClick = viewModel::stopSearch) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.meta_stop))
                        }
                    }
                } else if (ui.searched) {
                    Text(
                        stringResource(R.string.search_result_count, ui.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.proxy == null -> NoProxyNotice(Modifier.align(Alignment.Center))
                ui.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.results) { result ->
                        ResultRow(
                            result = result,
                            added = result.key in ui.added,
                            busy = ui.busyKey == result.key,
                            onClick = { viewModel.addTorrent(result, addToServer) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                ui.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.searched -> Text(
                    stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                ui.history.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.history) { past ->
                        Row(
                            Modifier.fillMaxWidth().clickable { doSearch(past) }.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Text(past, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                else -> EmptyState(
                    icon = Icons.Rounded.TravelExplore,
                    title = stringResource(R.string.meta_empty_title),
                    message = stringResource(R.string.meta_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    ui.addFailure?.let { reason ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAddFailure,
            title = { Text(stringResource(R.string.not_added_title)) },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = viewModel::dismissAddFailure) { Text(stringResource(R.string.dialog_ok)) } },
        )
    }

    if (showSites) {
        AlertDialog(
            onDismissRequest = { showSites = false },
            title = { Text(stringResource(R.string.meta_sites)) },
            text = {
                Column {
                    for (p in Providers.all) {
                        val enabled = ui.sites.firstOrNull { it.id == p.id }?.state != SiteState.OFF
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.setSiteEnabled(p.id, !enabled) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = enabled, onCheckedChange = { viewModel.setSiteEnabled(p.id, it) })
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSites = false }) { Text(stringResource(R.string.meta_done)) } },
        )
    }

    if (showMirrors) {
        val texts = remember { mutableStateMapOf<String, String>().also { m -> Providers.all.forEach { m[it.id] = viewModel.mirrorsText(it) } } }
        AlertDialog(
            onDismissRequest = { showMirrors = false },
            title = { Text(stringResource(R.string.meta_mirrors)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.meta_mirrors_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (p in Providers.all) {
                        OutlinedTextField(
                            value = texts[p.id] ?: "",
                            onValueChange = { texts[p.id] = it },
                            label = { Text(p.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveMirrors(texts.toMap()); showMirrors = false }) {
                    Text(stringResource(R.string.meta_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resetMirrors(); showMirrors = false }) {
                        Text(stringResource(R.string.meta_reset))
                    }
                    TextButton(onClick = { showMirrors = false }) { Text(stringResource(R.string.meta_cancel)) }
                }
            },
        )
    }
}

/** Back arrow + query field + clear button + menu in one rounded capsule, like the rest of the app's search bar. */
@Composable
private fun SearchBarPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    menu: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.details_back))
        }
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear))
            }
        }
        menu()
    }
}

/** One small status line per site: searching, found N, failed or switched off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SiteStatusRow(sites: List<SiteLine>, onTap: (SiteLine) -> Unit) {
    val colors = rowColors()
    val statusColors = LocalStatusColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (line in sites) {
            val (mark, color) = when (line.state) {
                SiteState.OK -> "✓" to statusColors.seeding
                SiteState.FAILED -> "✗" to MaterialTheme.colorScheme.error
                SiteState.SEARCHING -> "…" to colors.size
                SiteState.IDLE -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                SiteState.OFF -> "off" to MaterialTheme.colorScheme.outline
            }
            Text(
                "${line.name} $mark",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.clickable { onTap(line) },
            )
        }
    }
}

@Composable
private fun NoProxyNotice(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
        Text(stringResource(R.string.meta_no_proxy_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.meta_no_proxy_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultRow(result: MetaResult, added: Boolean, busy: Boolean, onClick: () -> Unit) {
    val statusColors = LocalStatusColors.current
    val colors = rowColors()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (added) colors.completeBackground else Color.Transparent)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                result.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(
                if (result.sizeBytes > 0) formatBytes(result.sizeBytes) else "?",
                result.sources.joinToString(", "),
            ).joinToString(" · ")
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.size,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip(
                    icon = Icons.Rounded.ArrowUpward,
                    value = result.seeders,
                    color = if (result.seeders == 0) MaterialTheme.colorScheme.error else statusColors.seeding,
                )
                StatChip(icon = Icons.Rounded.ArrowDownward, value = result.leechers, color = colors.peers)
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    added -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.meta_added_icon),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    else -> Icon(
                        Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.search_download),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(value.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
