// FILE: EventsScreen.kt
// VERSION: 0.1.1
// START_MODULE_CONTRACT
//   PURPOSE: «События» tab (EVT-01, EVT-02, EVT-05, EVT-06; D-01..D-04): camera chips, day button with the archive
//     month-grid picker, freshness subtitle, pull-to-refresh list of event cards grouped by day.
//   SCOPE: thin Compose over EventsViewModel. One onTabEntered per entry into the tab composition; pull = one
//     onPullRefresh. Previews are decoded off the main thread (downsampled) and kept in a small in-memory LRU keyed by
//     the relative preview path. Card tap -> onOpenClip(key) via the view-model event (05-05 renders the clip).
//     No rememberSaveable for keys / device ids; nothing logged here.
//   DEPENDS: M-UI-EVENTS (EventsViewModel, EventsFeed types), M-UI-LIVEVIEW (DayPickerDialog), Compose material3 1.2
//   LINKS: M-UI-EVENTS, V-M-UI-EVENTS, M-UI-SHELL
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yicamalt.ui.live.DayPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

// START_MODULE_MAP
//   EventsScreen - composable: chips, day button + picker, subtitle, pull-to-refresh card list, empty text;
//     routes EventsEvent.OpenClip -> onOpenClip, SessionExpired -> onSessionExpired.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - Phase 5 device bug: the idle pull-to-refresh indicator (grey circle) was always visible over
//     the day row and covered «Сбросить». material3 1.2 draws the resting container above the list box, which did not
//     clip; the box now clips to bounds and the container exists only while pulling or refreshing.
//   PREV_CHANGE: v0.1.0 - GSD plan 05-04: initial events tab screen.
// END_CHANGE_SUMMARY

private const val THUMB_CACHE_SIZE = 60

/** Small access-ordered LRU of decoded previews (keyed by relative preview path). Main thread only. */
private class ThumbCache : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
        size > THUMB_CACHE_SIZE
}

// START_CONTRACT: EventsScreen
//   PURPOSE: Render the events tab and forward user actions to the view-model.
//   INPUTS: { onSessionExpired: () -> Unit - host logout; onOpenClip: (key: String) -> Unit - clip request (05-05);
//     viewModel: EventsViewModel }
//   OUTPUTS: { Unit - composable }
//   SIDE_EFFECTS: onTabEntered once per composition entry (one refresh, D-04); onPullRefresh per pull gesture
//   LINKS: EventsViewModel, DayPickerDialog, D-01..D-04
// END_CONTRACT: EventsScreen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    onSessionExpired: () -> Unit,
    onOpenClip: (key: String) -> Unit,
    viewModel: EventsViewModel = hiltViewModel(),
) {
    // START_BLOCK_RENDER_EVENTS
    val ui by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.onTabEntered() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is EventsEvent.OpenClip -> onOpenClip(e.key)
                EventsEvent.SessionExpired -> onSessionExpired()
            }
        }
    }
    val ptr = rememberPullToRefreshState()
    // One pull = one refresh: start it, wait until the view-model reports "not refreshing", then end the indicator.
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) {
            viewModel.onPullRefresh()
            viewModel.state.first { !it.refreshing }
            ptr.endRefresh()
        }
    }
    val cache = remember { ThumbCache() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = ui.allSelected, onClick = viewModel::selectAllCameras, label = { Text("Все") })
            for (chip in ui.cameras) {
                FilterChip(
                    selected = chip.selected,
                    onClick = { viewModel.toggleCamera(chip.deviceId) },
                    label = { Text(chip.name) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::openDays) { Text(ui.dayLabel) }
            if (ui.selectedDay != null) {
                TextButton(onClick = viewModel::clearDay) { Text("Сбросить") }
            }
            Spacer(Modifier.width(8.dp))
            ui.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
        // START_BLOCK_PULL_INDICATOR
        // material3 1.2 parks the idle PullToRefreshContainer one indicator height above the list with its
        // surface and shadow still drawn; without a clip it showed as a grey circle over the filter row and the
        // «Сбросить» button. The list box clips it, and the container is composed only during a pull or a refresh.
        Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(ptr.nestedScrollConnection)) {
            LazyColumn(Modifier.fillMaxSize()) {
                val empty = ui.emptyText
                if (empty != null) {
                    item {
                        Box(Modifier.fillParentMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(empty, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        }
                    }
                }
                for (section in ui.sections) {
                    item(key = "day-${section.day}") {
                        Text(
                            section.header,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(section.cards, key = { it.key }) { card ->
                        EventCardRow(card, viewModel.thumbFile(card.thumbPath), cache) { viewModel.onCardTap(card.key) }
                    }
                }
            }
            if (ptr.isRefreshing || ptr.progress > 0f) {
                PullToRefreshContainer(state = ptr, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
        // END_BLOCK_PULL_INDICATOR
    }

    if (ui.daysDialogOpen) {
        DayPickerDialog(
            today = ui.today,
            selected = ui.selectedDay,
            markedDays = ui.daysWithEvents,
            onSelect = viewModel::selectDay,
            onDismiss = viewModel::closeDays,
        )
    }
    // END_BLOCK_RENDER_EVENTS
}

@Composable
private fun EventCardRow(card: EventCard, thumb: File?, cache: ThumbCache, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(card.thumbPath, thumb, cache)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.cameraName, style = MaterialTheme.typography.titleSmall)
                Text(card.timeLabel, style = MaterialTheme.typography.bodyMedium)
            }
            if (card.hasClip) {
                Icon(Icons.Filled.PlayCircle, contentDescription = "Есть клип", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?, file: File?, cache: ThumbCache) {
    // START_BLOCK_DECODE_THUMB
    val bitmap by produceState(initialValue = path?.let { cache[it] }, path, file) {
        val cached = path?.let { cache[it] }
        value = cached
        if (path == null || file == null || cached != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) {
            cache[path] = decoded
            value = decoded
        }
    }
    // END_BLOCK_DECODE_THUMB
    val shape = RoundedCornerShape(6.dp)
    val modifier = Modifier.size(width = 128.dp, height = 72.dp).clip(shape)
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
