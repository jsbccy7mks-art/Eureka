@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

enum class Tab(val label: String) {
    Live("Live TV"),
    Guide("Guide"),
    Playlists("Playlists"),
    Settings("Settings"),
}

@Composable
fun ZappRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playback = remember { Playback(context.applicationContext, coroutineScope) }
    val playlists by vm.playlists.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var playerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<Boolean?>(null) } // true = Xtream, false = M3U
    var focusTick by remember { mutableIntStateOf(0) }
    var exitArmed by remember { mutableStateOf(false) }
    val tabFocus = remember { FocusRequester() }

    /** Ask the visible screen to take focus once it has been drawn. */
    fun requestContentFocus(waitMs: Long = 120) {
        coroutineScope.launch {
            delay(waitMs)
            focusTick++
        }
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
        if (vm.loaded && playlists.isNotEmpty()) requestContentFocus(250)
    }

    // Only Live TV keeps a preview running in the background.
    LaunchedEffect(tab, playerOpen) {
        if (!playerOpen && tab != Tab.Live) playback.stop()
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
                BackHandler(enabled = !playerOpen && form == null) {
                    if (exitArmed) {
                        (context as? Activity)?.finish()
                    } else {
                        tabFocus.tryRequest()
                        exitArmed = true
                        vm.showToast("Press back again to exit")
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        tab = tab,
                        tabFocus = tabFocus,
                        guideStatus = vm.guideStatus,
                        onTab = { tab = it },
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (tab) {
                            Tab.Live -> LiveScreen(vm, playback, focusTick) { watch(it) }
                            Tab.Guide -> GuideScreen(vm, focusTick) { watch(it) }
                            Tab.Playlists -> PlaylistsScreen(
                                vm = vm,
                                focusTick = focusTick,
                                onForm = { form = it },
                                onWatch = { id ->
                                    vm.selectPlaylist(id)
                                    tab = Tab.Live
                                    requestContentFocus()
                                },
                            )
                            Tab.Settings -> SettingsScreen(vm, playback, focusTick)
                        }
                    }
                }
            }
        }

        form?.let { xtream ->
            PlaylistForm(
                xtream = xtream,
                vm = vm,
                onDone = {
                    form = null
                    tab = Tab.Live
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

@Composable
private fun TopBar(
    tab: Tab,
    tabFocus: FocusRequester,
    guideStatus: String?,
    onTab: (Tab) -> Unit,
) {
    var focusedTab by remember { mutableStateOf<Tab?>(null) }
    val now = rememberNow(15_000)
    val timeFormat = rememberTimeFormat()

    // Like Google TV: resting on a tab opens it.
    LaunchedEffect(focusedTab) {
        val t = focusedTab ?: return@LaunchedEffect
        delay(280)
        if (t != tab) onTab(t)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZappLogo(22.dp)
        Spacer(Modifier.width(32.dp))
        Row(
            Modifier
                .focusRestorer()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                FocusSurface(
                    onClick = { onTab(t) },
                    focusRequester = if (selected) tabFocus else null,
                    onFocusChange = { focused ->
                        if (focused) focusedTab = t else if (focusedTab == t) focusedTab = null
                    },
                    selected = selected,
                    shape = RoundedCornerShape(50),
                    focusScale = 1.05f,
                    selectedColor = ZappColors.Glass,
                ) { focused ->
                    Text(
                        text = t.label,
                        color = when {
                            focused -> ZappColors.OnFocus
                            selected -> ZappColors.Text
                            else -> ZappColors.Dim
                        },
                        fontSize = 13.sp,
                        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (guideStatus != null) {
            CircularProgressIndicator(
                color = ZappColors.Dim,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(guideStatus, color = ZappColors.Dim, fontSize = 12.sp)
            Spacer(Modifier.width(20.dp))
        }
        Text(timeFormat.format(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
