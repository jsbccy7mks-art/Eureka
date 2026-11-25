@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import com.zapp.tv.data.ChannelRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Dest(val label: String, val glyph: Glyph) {
    Home("Home", Glyph.Home),
    Live("Live TV", Glyph.Live),
    Guide("TV guide", Glyph.Guide),
    Settings("Settings", Glyph.Settings),
}

private val RailWidth = 76.dp
private val RailOpenWidth = 220.dp

@Composable
fun ZappRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playback = remember { Playback(context.applicationContext, coroutineScope) { vm.resolveUrl(it) } }
    val playlists by vm.playlists.collectAsState()

    var dest by rememberSaveable { mutableStateOf(Dest.Home) }
    var playerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<Boolean?>(null) } // true = Xtream, false = M3U
    var focusTick by remember { mutableIntStateOf(0) }
    var exitArmed by remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }

    /** Ask the visible screen to take focus once it has been drawn. */
    fun requestContentFocus(waitMs: Long = 120) {
        coroutineScope.launch {
            delay(waitMs)
            focusTick++
        }
    }

    fun open(d: Dest) {
        dest = d
        requestContentFocus()
    }

    fun watch(row: ChannelRow) {
        vm.lastChannelUrl = row.channel.streamUrl
        playback.play(row.channel)
        playerOpen = true
    }

    DisposableEffect(Unit) { onDispose { playback.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                playback.stop()
                playerOpen = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.loaded, playlists.isEmpty()) {
        if (vm.loaded && playlists.isNotEmpty()) requestContentFocus(300)
    }

    // Home and Live TV keep a preview running; elsewhere the player stops.
    LaunchedEffect(dest, playerOpen) {
        if (!playerOpen && dest != Dest.Live && dest != Dest.Home) playback.stop()
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_500)
            exitArmed = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()

        when {
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZappLogo(34.dp)
            }

            playlists.isEmpty() -> WelcomeScreen(vm, onForm = { form = it })

            else -> {
                // Back from a screen goes to the sidebar; Back on the sidebar twice exits.
                BackHandler(enabled = !playerOpen && form == null) {
                    if (!railFocused) {
                        railFocus.tryRequest()
                    } else if (exitArmed) {
                        (context as? Activity)?.finish()
                    } else {
                        exitArmed = true
                        vm.showToast("Press back again to exit")
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = RailWidth),
                ) {
                    when (dest) {
                        Dest.Home -> HomeScreen(
                            vm = vm,
                            playback = playback,
                            focusTick = focusTick,
                            onWatch = { row, categoryId ->
                                vm.setScope(row.channel.playlistId, categoryId)
                                watch(row)
                            },
                            onOpenLive = { open(Dest.Live) },
                            onOpenGuide = { open(Dest.Guide) },
                        )
                        Dest.Live -> Column(Modifier.fillMaxSize()) {
                            ScreenHeader("Live TV", vm.guideStatus)
                            LiveScreen(vm, playback, focusTick) { watch(it) }
                        }
                        Dest.Guide -> Column(Modifier.fillMaxSize()) {
                            ScreenHeader("TV guide", vm.guideStatus)
                            GuideScreen(vm, focusTick) { watch(it) }
                        }
                        Dest.Settings -> SettingsScreen(
                            vm = vm,
                            playback = playback,
                            focusTick = focusTick,
                            onForm = { form = it },
                            onWatchPlaylist = { id ->
                                vm.selectPlaylist(id)
                                open(Dest.Live)
                            },
                        )
                    }
                }

                Sidebar(
                    current = dest,
                    updateReady = vm.update is UpdateState.Available,
                    railFocus = railFocus,
                    onFocusChanged = { railFocused = it },
                    onSelect = { open(it) },
                )
            }
        }

        form?.let { xtream ->
            PlaylistForm(
                xtream = xtream,
                vm = vm,
                onDone = {
                    form = null
                    dest = Dest.Live
                    requestContentFocus(400)
                },
                onCancel = {
                    form = null
                    requestContentFocus()
                },
            )
        }

        if (playerOpen && playback.channel != null) {
            val rows = vm.channels.collectAsLazyPagingItems()
            PlayerOverlay(
                vm = vm,
                playback = playback,
                rows = rows,
                onClose = {
                    playerOpen = false
                    requestContentFocus(60)
                },
            )
        }

        vm.busy?.let { BusyOverlay(it) }

        ToastHost(
            message = vm.toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

/** Title row above Live TV and the guide, with the guide status and a clock. */
@Composable
private fun ScreenHeader(title: String, guideStatus: String?) {
    val now = rememberNow(15_000)
    val timeFormat = rememberTimeFormat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(start = 28.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.weight(1f))
        if (guideStatus != null) {
            CircularProgressIndicator(color = ZappColors.Dim, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text(guideStatus, color = ZappColors.Dim, fontSize = 12.sp)
            Spacer(Modifier.width(20.dp))
        }
        Text(timeFormat.format(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Icon rail that opens into a labelled menu while the remote is on it. */
@Composable
private fun Sidebar(
    current: Dest,
    updateReady: Boolean,
    railFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onSelect: (Dest) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) RailOpenWidth else RailWidth, tween(180), label = "rail")

    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(
                if (open) {
                    Brush.horizontalGradient(listOf(Color(0xFA0A0E1A), Color(0xF00A0E1A), Color(0x000A0E1A)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0x660A0E1A), Color(0x000A0E1A)))
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 22.dp),
        ) {
            LogoMark(34.dp, Modifier.padding(start = 7.dp))
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .onFocusChanged {
                        open = it.hasFocus
                        onFocusChanged(it.hasFocus)
                    }
                    .focusRestorer()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dest.entries.forEach { d ->
                    val selected = d == current
                    FocusSurface(
                        onClick = { onSelect(d) },
                        modifier = Modifier.width(if (open) RailOpenWidth - 28.dp else 48.dp),
                        selected = selected,
                        focusRequester = if (selected) railFocus else null,
                        shape = RoundedCornerShape(14.dp),
                        focusScale = 1.04f,
                    ) { focused ->
                        if (d == Dest.Settings && updateReady) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 31.dp, top = 9.dp)
                                    .size(7.dp)
                                    .background(ZappColors.Brand2, RoundedCornerShape(50)),
                            )
                        }
                        Row(
                            Modifier
                                .height(48.dp)
                                .padding(start = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlyphIcon(
                                d.glyph,
                                when {
                                    focused -> ZappColors.OnFocus
                                    selected -> ZappColors.Text
                                    else -> ZappColors.Dim
                                },
                                22.dp,
                            )
                            AnimatedVisibility(visible = open, enter = fadeIn(tween(160)), exit = fadeOut(tween(80))) {
                                Text(
                                    d.label,
                                    color = when {
                                        focused -> ZappColors.OnFocus
                                        selected -> ZappColors.Text
                                        else -> ZappColors.Dim
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1.3f))
        }
    }
}
