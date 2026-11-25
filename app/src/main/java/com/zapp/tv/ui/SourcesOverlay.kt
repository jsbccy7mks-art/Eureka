package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.addons.StreamBatch
import com.zapp.tv.addons.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lists every source the add-ons offer for a film or episode. With autoplay on, it starts the
 * first playable source (following your add-on order) as soon as the add-ons have answered.
 */
@Composable
fun SourcesOverlay(
    mvm: MediaViewModel,
    request: PlayRequest,
    onPick: (StreamItem) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val addons by mvm.addons.collectAsState()
    val order = addons.map { it.manifest.name }
    val batches = remember(request.videoId) { mutableStateListOf<StreamBatch>() }
    var finished by remember(request.videoId) { mutableStateOf(false) }
    var autoPlay by remember(request.videoId) { mutableStateOf(request.autoPlay) }
    val firstFocus = remember { FocusRequester() }
    val expected = addons.count { it.addon.enabled && it.manifest.supports("stream", request.meta.type, request.videoId) }

    LaunchedEffect(request.videoId) {
        withTimeoutOrNull(30_000) {
            mvm.streams(request.meta.type, request.videoId)
                .take(expected.coerceAtLeast(1))
                .collect { batch -> batches.add(batch) }
        }
        finished = true
    }

    val settings = mvm.settings
    val viaDebrid = mvm.debridConnected
    val debridName = mvm.debridNames().firstOrNull()

    // Ask the debrid services which torrents they already have, where they can tell.
    var ready by remember(request.videoId) { mutableStateOf<Set<String>?>(null) }
    val hashes = batches.flatMap { b -> b.streams.mapNotNull { it.infoHash?.lowercase() } }.distinct()
    LaunchedEffect(finished, hashes.size) {
        if (viaDebrid && hashes.isNotEmpty() && (finished || hashes.size % 25 == 0)) {
            ready = runCatching { mvm.debrid.ready(hashes) }.getOrNull() ?: ready
        }
    }
    fun isReady(s: StreamItem): Boolean? = when {
        s.infoHash != null && ready != null -> s.infoHash.lowercase() in ready!! || s.markedReady == true
        else -> s.markedReady
    }
    val cap = when (settings.maxQuality) { 1 -> 1080; 2 -> 720; else -> Int.MAX_VALUE }
    fun keep(s: StreamItem): Boolean {
        if (s.qualityRank > cap) return false
        if (!settings.cachedOnly) return true
        return when {
            s.infoHash != null -> viaDebrid && isReady(s) == true
            s.playable -> isReady(s) != false
            else -> false
        }
    }
    fun rank(s: StreamItem): Int = (if (isReady(s) == true) 10_000 else 0) + s.qualityRank

    val sorted = batches.sortedBy { b -> order.indexOf(b.addonName).let { if (it < 0) Int.MAX_VALUE else it } }
    val all = sorted.flatMap { b ->
        val kept = b.streams.filter(::keep)
        if (settings.bestFirst) kept.sortedByDescending(::rank) else kept
    }
    val hiddenCount = sorted.sumOf { it.streams.size } - all.size
    fun canPlay(s: StreamItem) = s.playable || (viaDebrid && s.infoHash != null)

    // Autoplay: wait briefly for the add-ons to answer, then take the first playable source.
    LaunchedEffect(autoPlay, finished, batches.size) {
        if (!autoPlay) return@LaunchedEffect
        val preferred = request.preferBingeGroup?.let { g -> all.firstOrNull { canPlay(it) && it.bingeGroup == g } }
        val firstAddonDone = sorted.firstOrNull()?.addonName == order.firstOrNull { name -> addons.any { it.manifest.name == name && it.addon.enabled } }
        if (preferred != null) {
            onPick(preferred)
        } else if (finished || firstAddonDone) {
            all.firstOrNull { canPlay(it) }?.let { onPick(it) } ?: run { autoPlay = false }
        }
    }
    LaunchedEffect(autoPlay) {
        if (autoPlay) {
            delay(12_000)
            all.firstOrNull { canPlay(it) }?.let { onPick(it) }
            autoPlay = false
        }
    }
    LaunchedEffect(all.isNotEmpty(), autoPlay) { if (!autoPlay && all.isNotEmpty()) firstFocus.tryRequest() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg),
    ) {
        (request.meta.background ?: request.meta.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ZappColors.Bg.copy(alpha = 0.6f), 1f to ZappColors.Bg)))

        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = 40.dp, bottom = 20.dp)) {
            Column(Modifier.width(250.dp)) {
                Text(request.meta.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                request.episodeLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Dim, fontSize = 13.sp, maxLines = 2)
                }
                Spacer(Modifier.height(16.dp))
                val waiting = (expected - batches.size).coerceAtLeast(0)
                Text(
                    when {
                        !finished && waiting > 0 -> "Asking ${if (waiting == 1) "1 add-on" else "$waiting add-ons"}…"
                        all.isEmpty() -> "No sources found."
                        else -> "${all.size} sources from ${sorted.count { it.streams.isNotEmpty() }} add-ons"
                    },
                    color = ZappColors.Faint,
                    fontSize = 12.sp,
                )
                if (hiddenCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$hiddenCount hidden by your source settings" + if (settings.cachedOnly) " (ready-to-play only)" else "",
                        color = ZappColors.Faint,
                        fontSize = 11.sp,
                    )
                }
                sorted.filter { it.error != null }.forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(if (it.addonName.isBlank()) it.error.orEmpty() else "${it.addonName}: ${it.error}", color = ZappColors.Faint, fontSize = 11.sp, maxLines = 2)
                }
                if (request.resumeMs > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("Resumes at ${formatClock(request.resumeMs)}", color = ZappColors.Signal, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(28.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (autoPlay) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ZappColors.Brand2, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Finding the best source…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        val stop = remember { FocusRequester() }
                        LaunchedEffect(Unit) { stop.tryRequest() }
                        ZButton("Let me choose", { autoPlay = false }, focusRequester = stop)
                    }
                } else if (all.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!finished) {
                            CircularProgressIndicator(color = ZappColors.Brand2, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                        } else {
                            Text(
                                if (hiddenCount > 0) "Nothing ready to play. Turn off \"Ready to play only\" in Settings to see all $hiddenCount sources."
                                else "None of your add-ons found a source for this.",
                                color = ZappColors.Dim,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(14.dp))
                            val back = remember { FocusRequester() }
                            LaunchedEffect(Unit) { back.tryRequest() }
                            ZButton("Back", onClose, focusRequester = back)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                        items(all.size) { i ->
                            val s = all[i]
                            val showHeader = i == 0 || all[i - 1].addonName != s.addonName
                            Column {
                                if (showHeader) {
                                    Text(
                                        s.addonName,
                                        color = ZappColors.Dim,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 6.dp),
                                    )
                                }
                                StreamRow(s, if (i == 0) firstFocus else null, if (s.playable) null else if (s.infoHash != null) debridName else null, isReady(s) == true) {
                                    if (canPlay(s)) onPick(s)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(s: StreamItem, requester: FocusRequester?, debrid: String?, readyNow: Boolean, onClick: () -> Unit) {
    val note = when {
        s.playable -> null
        s.infoHash != null && debrid != null -> if (readyNow) "Ready on $debrid" else "Through $debrid (may need downloading first)"
        s.infoHash != null -> "Torrent: connect a debrid service in Settings to play it"
        s.externalUrl != null -> "Opens a website, which Zapp can't show"
        s.ytId != null -> "YouTube video"
        else -> "Not playable"
    }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = requester,
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.01f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        when {
                            focused -> Color(0xFFE2E7F2)
                            s.quality == "4K" -> ZappColors.Brand1.copy(alpha = 0.55f)
                            s.quality == "1080p" -> ZappColors.Brand2.copy(alpha = 0.35f)
                            else -> ZappColors.GlassHigh
                        },
                    )
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.quality ?: "SD?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (focused) ZappColors.OnFocus else Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.name.replace('\n', ' '),
                    color = if (focused) ZappColors.OnFocus else if (s.playable || debrid != null) ZappColors.Text else ZappColors.Faint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.description.isNotBlank()) {
                    Text(
                        s.description,
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        maxLines = if (focused) 5 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val tags = s.tags
                if (tags.isNotEmpty() || note != null || readyNow) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (readyNow) Tag("Ready")
                        tags.forEach { Tag(it) }
                        note?.let { Text(it, color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = 11.sp) }
                    }
                }
            }
            s.size?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
