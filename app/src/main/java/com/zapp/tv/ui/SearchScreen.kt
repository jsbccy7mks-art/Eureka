@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ProgrammeHit
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    mvm: MediaViewModel,
    onOpenTitle: (com.zapp.tv.addons.MetaPreview) -> Unit,
    onWatch: (Channel) -> Unit,
) {
    val results by vm.searchResults.collectAsState()
    val mediaResults by mvm.searchResults.collectAsState()
    LaunchedEffect(vm.searchText) { mvm.search(vm.searchText) }
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusTick) }

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        fieldFocus.tryRequest()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Column(Modifier.padding(start = 40.dp, end = 36.dp)) {
            Text("Search", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Channels, programmes, films and series",
                value = vm.searchText,
                onValueChange = { vm.setSearch(it) },
                placeholder = "Press OK and type a channel or programme name",
                last = true,
                focusRequester = fieldFocus,
                modifier = Modifier.width(520.dp),
            )
        }
        Spacer(Modifier.height(22.dp))

        val r = results
        when {
            vm.searchText.trim().length < 2 -> Hint("Type at least two letters. Results come from your playlists, the TV guide and your add-ons.")
            r == null && mediaResults == null -> Hint("Searching…")
            r != null && r.channels.isEmpty() && r.programmes.isEmpty() && mediaResults.isNullOrEmpty() ->
                Hint("Nothing found for “${r.query}”.")
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 30.dp),
            ) {
                mediaResults.orEmpty().forEach { (ref, metas) ->
                    item(key = "m:" + ref.key) {
                        val memory = rememberFocusMemory()
                        Column {
                            SectionTitle(
                                (if (ref.def.type == "movie") "Films" else "Series") + " from ${ref.addon.manifest.name}",
                            )
                            LazyRow(
                                modifier = Modifier.restoreFocus(memory),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 40.dp),
                            ) {
                                items(metas, key = { it.id }) { meta ->
                                    PosterCard(
                                        meta = meta,
                                        modifier = Modifier.rememberFocus(memory, meta.id),
                                        onFocus = {},
                                        onClick = { onOpenTitle(meta) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (r != null && r.channels.isNotEmpty()) {
                    item(key = "channels") {
                        val channelMemory = rememberFocusMemory()
                        Column {
                            SectionTitle("Channels")
                            LazyRow(
                                modifier = Modifier.restoreFocus(channelMemory),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 40.dp),
                            ) {
                                items(r.channels, key = { it.channel.id }) { row ->
                                    ChannelCard(
                                        modifier = Modifier.rememberFocus(channelMemory, row.channel.id),
                                        row = row,
                                        now = now,
                                        playing = playback.isOn(row.channel),
                                        requester = remember { FocusRequester() },
                                        onFocus = {},
                                        onClick = {
                                            vm.setScope(row.channel.playlistId, null)
                                            onWatch(row.channel)
                                        },
                                        onLongClick = { vm.toggleFavorite(row.channel) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (r != null && r.programmes.isNotEmpty()) {
                    item(key = "programmes-title") { SectionTitle("In the TV guide") }
                    items(r.programmes, key = { "p" + it.programmeId }) { hit ->
                        ProgrammeHitRow(
                            hit = hit,
                            now = now,
                            timeFormat = timeFormat,
                            onClick = {
                                if (hit.start <= now && hit.stop > now) {
                                    scope.launch {
                                        val channel = vm.channelById(hit.channelId) ?: return@launch
                                        vm.setScope(channel.playlistId, null)
                                        onWatch(channel)
                                    }
                                } else {
                                    vm.showToast("${hit.title} starts at ${timeFormat.format(hit.start)} on ${hit.channelName}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = ZappColors.Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 40.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
    )
}

@Composable
private fun ProgrammeHitRow(hit: ProgrammeHit, now: Long, timeFormat: java.text.DateFormat, onClick: () -> Unit) {
    val live = hit.start <= now && hit.stop > now
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 34.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.015f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hit.icon != null) {
                AsyncImage(
                    model = hit.icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(46.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZappColors.Glass),
                )
            } else {
                Box(Modifier.width(82.dp), contentAlignment = Alignment.Center) {
                    LogoTile(hit.channelLogo, hit.channelName, 40.dp, onLight = focused)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hit.title,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${hit.channelName}, ${timeFormat.format(hit.start)} – ${timeFormat.format(hit.stop)}",
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (live) {
                Spacer(Modifier.width(10.dp))
                LivePill()
            }
        }
    }
}
