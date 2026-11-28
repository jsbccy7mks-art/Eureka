package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ProgrammeHit
import com.zapp.tv.data.SportEvent
import com.zapp.tv.data.SportTeam
import kotlinx.coroutines.launch

/**
 * Sports: the teams you follow, their matches live now, coming up and just played. Each match is
 * found in your TV guide; OK plays the channel it's on.
 */
@Composable
fun SportsScreen(vm: AppViewModel, focusTick: Int, showHeader: Boolean = true, onPlay: (Channel) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSports() }
    val scope = rememberCoroutineScope()
    val now = rememberNow(30_000)
    val timeFormat = rememberTimeFormat()
    var managing by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<Pair<SportEvent, List<ProgrammeHit>>?>(null) }
    val first = remember { FocusRequester() }
    var handled by remember { mutableIntStateOf(focusTick) }
    LaunchedEffect(focusTick, vm.fixtures.isEmpty()) {
        if (focusTick != handled || true) {
            handled = focusTick
            kotlinx.coroutines.delay(120)
            first.tryRequest()
        }
    }

    var leagues by remember { mutableStateOf(false) }

    // Live scores: while this page is open and something is on, refresh once a minute.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val n = System.currentTimeMillis()
            if (vm.fixtures.any { it.liveAt(n) || (it.start in n..(n + 5 * 60_000L)) }) vm.refreshLiveScores()
        }
    }

    fun watch(e: SportEvent) = scope.launch {
        val hits = vm.channelsFor(e)
        when {
            hits.isEmpty() -> vm.showToast(if (e.start > now) "${e.title} isn't in your TV guide yet" else "Couldn't find ${e.title} in your TV guide")
            hits.size == 1 -> playHit(vm, hits[0], now, onPlay)
            else -> choose = e to hits
        }
    }

    if (managing) {
        ManageTeams(vm) { managing = false }
        return
    }
    if (leagues) {
        ManageLeagues(vm) { leagues = false }
        return
    }

    val teams = vm.followedTeams
    val followedLeagues = vm.followedLeagues
    val all = vm.fixtures
    val groups = remember(all, now / 60_000) { dayGroups(all.filterNot { it.off }, now) }
    val firstId = groups.firstOrNull()?.second?.firstOrNull()?.id
    val nothingFollowed = teams.isEmpty() && followedLeagues.isEmpty()
    val leagueNames = followedLeagues.mapNotNull { c -> com.zapp.tv.data.FootballData.COMPETITIONS.firstOrNull { it.code == c }?.name }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(start = if (showHeader) 40.dp else 28.dp, end = 40.dp, top = if (showHeader) 28.dp else 8.dp, bottom = 40.dp),
    ) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (showHeader) Text("Sports", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (nothingFollowed) "Follow leagues and teams to see their matches, and play them on the channel they're on." else (leagueNames + teams.map { it.name }).joinToString("  ·  "),
                        color = ZappColors.Dim,
                        fontSize = TextSize.Small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (vm.fixturesLoading) {
                    ZappLoader(14.dp)
                    Spacer(Modifier.width(12.dp))
                }
                ZButton("Leagues", { leagues = true }, style = if (nothingFollowed) ButtonStyle.Primary else ButtonStyle.Secondary, focusRequester = if (firstId == null) first else null)
                Spacer(Modifier.width(10.dp))
                ZButton(if (teams.isEmpty()) "Follow a team" else "Your teams", { managing = true }, style = ButtonStyle.Secondary)
            }
        }
        groups.forEach { (label, events) ->
            item(key = "g:$label") {
                Column {
                    SectionLabel(label.uppercase())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(events, key = { it.id }) { e ->
                            val live = e.liveAt(now)
                            MatchCard(e, now, timeFormat, if (e.id == firstId) first else null, live = live) { watch(e) }
                        }
                    }
                }
            }
        }
        if (!nothingFollowed && groups.isEmpty() && !vm.fixturesLoading) {
            item(key = "none") {
                Text(
                    if (followedLeagues.isNotEmpty() && !vm.football.hasKey) "Add your free football-data.org token from your phone (Accounts, Football) to see league matches."
                    else "No matches found for what you follow right now.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Body,
                )
            }
        }
    }

    choose?.let { (e, hits) ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { choose = null }) {
            DialogBlurBehind()
            val f = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(60)
                f.tryRequest()
            }
            Column(
                Modifier
                    .width(440.dp)
                    .clip(Shapes.Panel)
                    .background(ZappColors.Panel)
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(e.title, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
                Text(if (e.start > now + 15 * 60_000L) "Showing on (OK sets a reminder)" else "Showing on", color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.padding(horizontal = 10.dp))
                Spacer(Modifier.height(8.dp))
                hits.forEachIndexed { i, h ->
                    val later = h.start > now + 15 * 60_000L
                    MenuItem(if (later) "⏰" else "▶", "${h.channelName}  ·  ${timeFormat.format(h.start)}" + if (later) (if (vm.hasReminder(h)) "  ·  Reminder set" else "  ·  Remind me") else "", if (i == 0) f else null) {
                        choose = null
                        scope.launch { playHit(vm, h, now, onPlay) }
                    }
                }
            }
        }
    }
}

/** Plays the channel when the match is on (or about to be); otherwise sets a reminder for it. */
private suspend fun playHit(vm: AppViewModel, h: ProgrammeHit, now: Long, onPlay: (Channel) -> Unit) {
    if (h.start > now + 15 * 60_000L) {
        vm.remindHit(h)
        return
    }
    vm.channelById(h.channelId)?.let(onPlay)
}

/** Live now, then each day ahead (Today, Tomorrow, then by name), then results from the last few days. */
private fun dayGroups(events: List<SportEvent>, now: Long): List<Pair<String, List<SportEvent>>> {
    val cal = java.util.Calendar.getInstance()
    fun dayIndex(t: Long): Int {
        cal.timeInMillis = now
        val y0 = cal.get(java.util.Calendar.YEAR); val d0 = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = t
        return (cal.get(java.util.Calendar.YEAR) - y0) * 365 + cal.get(java.util.Calendar.DAY_OF_YEAR) - d0
    }
    val live = events.filter { it.liveAt(now) }
    val ahead = events.filter { it.start > now && !it.liveAt(now) && !it.finished }
    val results = events.filter { it.finished && !it.liveAt(now) }.sortedByDescending { it.start }.take(20)
    val out = mutableListOf<Pair<String, List<SportEvent>>>()
    if (live.isNotEmpty()) out += "Live now" to live
    val nameFmt = java.text.SimpleDateFormat("EEEE d MMM", java.util.Locale.getDefault())
    ahead.groupBy { dayIndex(it.start) }.toSortedMap().forEach { (d, list) ->
        if (d > 7) return@forEach
        val label = when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> nameFmt.format(list.first().start) }
        out += label to list.take(30)
    }
    if (results.isNotEmpty()) out += "Results" to results
    return out
}

/** Leagues and cups to follow from football-data.org. */
@Composable
private fun ManageLeagues(vm: AppViewModel, onDone: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDone)
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        done.tryRequest()
    }
    val followed = vm.followedLeagues
    val hasKey = vm.football.hasKey
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leagues", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hasKey) "Every match in the leagues you follow, with live scores. From football-data.org."
                    else "Needs a free football-data.org token. Add it from your phone: Accounts, Football.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                )
            }
            ZButton("Done", onDone, style = ButtonStyle.Primary, focusRequester = done)
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(com.zapp.tv.data.FootballData.COMPETITIONS, key = { it.code }) { c ->
                val on = c.code in followed
                SettingRow(
                    title = c.name,
                    body = c.country,
                    value = SettingValue.Choice(if (on) "Following" else "Follow"),
                    onClick = { vm.followLeague(c.code, !on) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = ZappColors.Brand2, fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 10.dp))
}

/** A match: the two badges and names (or the event's picture), when, the score, and where it's on. */
@Composable
fun MatchCard(e: SportEvent, now: Long, timeFormat: java.text.DateFormat, requester: FocusRequester? = null, live: Boolean = false, onClick: () -> Unit) {
    val day = remember(e.start) { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(e.start) }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(280.dp).height(150.dp),
        focusRequester = requester,
        shape = RoundedCornerShape(14.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.Glass,
        ring = true,
        ringCorner = 14.dp,
    ) {
        if (e.thumb != null && e.home == null) {
            AsyncImage(model = e.thumb, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.5f, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))))
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((e.league ?: e.sport).uppercase(), color = ZappColors.Faint, fontSize = TextSize.Tag, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (live) LivePill()
            }
            Spacer(Modifier.weight(1f))
            if (e.home != null && e.away != null) {
                TeamLine(e.homeBadge, e.home, if (e.finished || live) e.homeScore else null)
                Spacer(Modifier.height(6.dp))
                TeamLine(e.awayBadge, e.away, if (e.finished || live) e.awayScore else null)
            } else {
                Text(e.title, fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    live -> "On now  ·  OK to watch"
                    e.finished -> "Full time  ·  $day"
                    else -> "$day  ·  ${timeFormat.format(e.start)}"
                },
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
            )
        }
    }
}

@Composable
private fun TeamLine(badge: String?, name: String, score: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (badge != null) AsyncImage(model = badge, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        score?.let { Text(it, fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold) }
    }
}

/** Find and follow teams (any sport), or stop following them. */
@Composable
private fun ManageTeams(vm: AppViewModel, onDone: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDone)
    var query by remember { mutableStateOf("") }
    val results by produceState(emptyList<SportTeam>(), query) {
        if (query.trim().length < 2) {
            value = emptyList()
            return@produceState
        }
        kotlinx.coroutines.delay(500)
        value = vm.sports.searchTeams(query)
    }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        field.tryRequest()
    }
    val followed = vm.followedTeams
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your teams", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ZButton("Done", onDone, style = ButtonStyle.Primary)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.widthIn(max = 520.dp)) {
            TvTextField(label = "Find a team", value = query, onValueChange = { query = it }, placeholder = "Arsenal, Lakers, Ferrari…", last = true, focusRequester = field)
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            val list = if (query.trim().length >= 2) results else followed
            if (list.isEmpty()) {
                item { Text(if (query.isBlank()) "Search for a team to follow it." else "No teams found.", color = ZappColors.Dim, fontSize = TextSize.Body) }
            }
            items(list, key = { it.id }) { t ->
                val on = followed.any { it.id == t.id }
                SettingRow(
                    title = t.name,
                    body = listOfNotNull(t.sport, t.league).joinToString("  ·  "),
                    value = SettingValue.Choice(if (on) "Following" else "Follow"),
                    onClick = { vm.follow(t, !on) },
                )
            }
        }
    }
}
