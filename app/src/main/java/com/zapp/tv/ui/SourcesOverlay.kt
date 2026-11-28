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
    // A pick that fails (a debrid service that can't get it) drops back to the list.
    var picked by remember(request.videoId) { mutableStateOf(false) }
    val pick: (StreamItem) -> Unit = {
        picked = true
        onPick(it)
    }
    BackHandler(onBack = onClose)
    val addons by mvm.addons.collectAsState()
    val order = addons.map { it.manifest.name }
    // Usually already fetched by the details page; otherwise fetched now.
    val pre = remember(request.videoId) { mvm.prefetchStreams(request.meta.type, request.videoId) }
    val batches = pre.batches
    val finished = pre.finished.value
    var autoPlay by remember(request.videoId) { mutableStateOf(request.autoPlay) }
    val firstFocus = remember { FocusRequester() }
    val expected = addons.count { it.addon.enabled && it.manifest.supports("stream", request.meta.type, request.videoId) }


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
    val context = androidx.compose.ui.platform.LocalContext.current
    // If the HDR setting would leave nothing at all, it steps aside rather than leave an empty list.
    val hdrMode = remember(batches.size, settings.hdrMode) {
        settings.hdrMode.let { m ->
            if (batches.any { b -> b.streams.any { com.zapp.tv.data.PictureFilter.keep(context, m, it) } } || batches.none { it.streams.isNotEmpty() }) m else -1
        }
    }
    fun keep(s: StreamItem): Boolean {
        if (s.qualityRank > cap) return false
        if (hdrMode >= 0 && !com.zapp.tv.data.PictureFilter.keep(context, hdrMode, s)) return false
        if (!settings.cachedOnly) return true
        return when {
            s.infoHash != null -> viaDebrid && isReady(s) == true
            s.playable -> isReady(s) != false
            else -> false
        }
    }
    // Ready first, then quality, nudged by how each add-on and release has done on this box.
    val stats = mvm.sourceStats
    fun rank(s: StreamItem): Int = (if (isReady(s) == true) 10_000 else 0) + s.qualityRank + stats.bonus(s) +
        (if (hdrMode >= 0) com.zapp.tv.data.PictureFilter.bonus(context, hdrMode, s) else 0)

    // Worked out only when the sources or what's ready change (not on every move down the list).
    val sorted = remember(batches.size, order) { batches.sortedBy { b -> order.indexOf(b.addonName).let { if (it < 0) Int.MAX_VALUE else it } } }
    val all = remember(sorted, ready, hdrMode, cap, settings.cachedOnly, settings.bestFirst) {
        sorted.flatMap { b ->
            val kept = b.streams.filter(::keep)
            if (settings.bestFirst) kept.sortedByDescending(::rank) else kept
        }
    }
    val hiddenCount = sorted.sumOf { it.streams.size } - all.size
    fun canPlay(s: StreamItem) = (s.playable || (viaDebrid && s.infoHash != null)) && (s.url ?: s.infoHash) !in request.failed

    // Autoplay: wait briefly for the add-ons to answer, then take the first playable source.
    LaunchedEffect(autoPlay, finished, batches.size) {
        if (!autoPlay) return@LaunchedEffect
        // Same source as last time first (when resuming, or the next episode of the same release),
        // then the same "binge group" (a series' release), then the same add-on at the same quality.
        val last = if (mvm.settings.sameSource) mvm.lastSource(request.meta.id) else null
        val exact = last?.takeIf { it.videoId == request.videoId && it.key != null }?.let { l -> all.firstOrNull { canPlay(it) && (it.url ?: it.infoHash) == l.key } }
        val group = (request.preferBingeGroup ?: last?.binge)?.let { g -> all.firstOrNull { canPlay(it) && it.bingeGroup == g } }
        val preferred = exact ?: group
        val firstAddonDone = sorted.firstOrNull()?.addonName == order.firstOrNull { name -> addons.any { it.manifest.name == name && it.addon.enabled } }
        if (preferred != null) {
            pick(preferred)
        } else if (finished || firstAddonDone) {
            val style = last?.let { l -> all.firstOrNull { canPlay(it) && it.addonName == l.addon && it.qualityRank == l.quality } }
            (style ?: all.firstOrNull { canPlay(it) })?.let { pick(it) } ?: run { autoPlay = false }
        }
    }
    LaunchedEffect(autoPlay) {
        if (autoPlay) {
            delay(12_000)
            all.firstOrNull { canPlay(it) }?.let { pick(it) }
            autoPlay = false
        }
    }
    val resolving = mvm.resolving
    LaunchedEffect(resolving) { if (resolving == null && picked) autoPlay = false }
    LaunchedEffect(all.isNotEmpty(), autoPlay) { if (!autoPlay && all.isNotEmpty()) firstFocus.tryRequest() }

    // Autoplay, or a debrid service fetching the chosen source: the Zapp starting screen, which the
    // player keeps showing until the first picture, so it all reads as one quick start.
    if (autoPlay || resolving != null) {
        Box(Modifier.fillMaxSize().trapFocus()) {
            StartingScreen(
                background = request.meta.background ?: request.video?.thumbnail ?: request.meta.poster,
                title = request.meta.name,
                subtitle = request.episodeLabel,
                status = resolving,
            )
            if (resolving == null) {
                val stop = remember { FocusRequester() }
                LaunchedEffect(Unit) { stop.tryRequest() }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                    ZButton("Let me choose", { autoPlay = false }, style = ButtonStyle.Quiet, focusRequester = stop)
                }
            }
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .background(ZappColors.Bg),
    ) {
        (request.meta.background ?: request.meta.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ZappColors.Bg.copy(alpha = 0.6f), 1f to ZappColors.Bg)))

        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = 40.dp, bottom = 20.dp)) {
            Column(Modifier.width(250.dp)) {
                Text(request.meta.name, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                request.episodeLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Dim, fontSize = TextSize.Body, maxLines = 2)
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
                    fontSize = TextSize.Small,
                )
                if (hiddenCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$hiddenCount hidden by your source settings" + if (settings.cachedOnly) " (ready-to-play only)" else "",
                        color = ZappColors.Faint,
                        fontSize = TextSize.Small,
                    )
                }
                sorted.filter { it.error != null }.forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(if (it.addonName.isBlank()) it.error.orEmpty() else "${it.addonName}: ${it.error}", color = ZappColors.Faint, fontSize = TextSize.Small, maxLines = 2)
                }
                if (request.resumeMs > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("Resumes at ${formatClock(request.resumeMs)}", color = ZappColors.Signal, fontSize = TextSize.Small)
                }
            }
            Spacer(Modifier.width(28.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (autoPlay) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ZappColors.Brand2, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Finding the best source…", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
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
                                fontSize = TextSize.Body,
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
                                        fontSize = TextSize.Small,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 6.dp),
                                    )
                                }
                                StreamRow(s, if (i == 0) firstFocus else null, if (s.playable) null else if (s.infoHash != null) debridName else null, isReady(s) == true, stats.note(s)) {
                                    if (canPlay(s)) pick(s)
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
private fun StreamRow(s: StreamItem, requester: FocusRequester?, debrid: String?, readyNow: Boolean, learned: String? = null, onClick: () -> Unit) {
    val note = when {
        s.playable -> null
        s.infoHash != null && debrid != null -> if (readyNow) "Ready on $debrid" else "Through $debrid (may need downloading first)"
        s.infoHash != null -> "Torrent: connect a debrid service in Settings to play it"
        s.externalUrl != null -> "Opens a website, which Axon can't show"
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
                Text(s.quality ?: "SD?", fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.name.replace('\n', ' '),
                    color = if (focused) ZappColors.OnFocus else if (s.playable || debrid != null) ZappColors.Text else ZappColors.Faint,
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.description.isNotBlank()) {
                    Text(
                        s.description,
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        fontSize = TextSize.Small,
                        lineHeight = 15.sp,
                        // Same height focused or not, so moving down the list never re-lays it out.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val tags = s.tags
                if (tags.isNotEmpty() || note != null || readyNow || learned != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (readyNow) Tag("Ready")
                        learned?.let { Tag(it, highlight = it == "Reliable") }
                        tags.forEach { Tag(it) }
                        note?.let { Text(it, color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = TextSize.Small) }
                    }
                }
            }
            s.size?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = TextSize.Small, fontWeight = FontWeight.Medium)
            }
        }
    }
}
