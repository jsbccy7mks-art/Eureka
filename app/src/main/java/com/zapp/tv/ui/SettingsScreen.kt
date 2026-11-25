@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val UPDATE_LINK = "github.com/jsbccy7mks-art/Eureka/releases/latest/download/zapp-tv.apk"

private enum class SettingsSection(val label: String) {
    Sources("Sources"),
    Live("Live TV and guide"),
    About("Updates and about"),
}

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    onForm: (xtream: Boolean) -> Unit,
    onWatchPlaylist: (Long) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.Sources) }
    var focusedSection by remember { mutableStateOf<SettingsSection?>(null) }
    val firstFocus = remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusTick) }

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        firstFocus.tryRequest()
    }
    LaunchedEffect(focusedSection) {
        val s = focusedSection ?: return@LaunchedEffect
        delay(200)
        section = s
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 36.dp, top = 28.dp, bottom = 20.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                modifier = Modifier.focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(SettingsSection.entries, key = { it.name }) { s ->
                    FocusSurface(
                        onClick = { section = s },
                        modifier = Modifier.fillMaxWidth(),
                        selected = s == section,
                        focusRequester = if (s == section) firstFocus else null,
                        onFocusChange = { if (it) focusedSection = s },
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.03f,
                    ) { focused ->
                        Text(
                            s.label,
                            color = when {
                                focused -> ZappColors.OnFocus
                                s == section -> ZappColors.Text
                                else -> ZappColors.Dim
                            },
                            fontSize = 14.sp,
                            fontWeight = if (s == section || focused) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(28.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (section) {
                SettingsSection.Sources -> SourcesPane(vm, onForm, onWatchPlaylist)
                SettingsSection.Live -> LiveSettingsPane(vm, playback)
                SettingsSection.About -> AboutPane(vm)
            }
        }
    }
}

@Composable
private fun LiveSettingsPane(vm: AppViewModel, playback: Playback) {
    val intervals = listOf(0, 6, 12, 24)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingRow(
            title = "Preview channels while browsing",
            body = "Plays the channel you rest on in the corner of Live TV and in the Home hero.",
            value = SettingValue.Toggle(vm.settings.preview),
            onClick = {
                vm.settings.preview = !vm.settings.preview
                if (!vm.settings.preview) playback.stop()
            },
        )
        SettingRow(
            title = "Update the guide automatically",
            body = "How often the TV guide is downloaded again while the app is open.",
            value = SettingValue.Choice(
                when (vm.settings.guideHours) {
                    0 -> "Off"
                    else -> "Every ${vm.settings.guideHours} hours"
                },
            ),
            onClick = {
                val i = intervals.indexOf(vm.settings.guideHours)
                vm.settings.guideHours = intervals[(i + 1).mod(intervals.size)]
            },
        )
        SettingRow(
            title = "Update guide now",
            body = "Downloads the latest guide for every playlist.",
            value = SettingValue.Action,
            onClick = { vm.refreshGuideAll() },
        )
        SettingRow(
            title = "Refresh all playlists",
            body = "Reloads channel lists from your providers. Favourites and history are kept.",
            value = SettingValue.Action,
            onClick = { vm.refreshAll() },
        )
    }
}

@Composable
private fun AboutPane(vm: AppViewModel) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Zapp TV", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Version ${version ?: "unknown"}, build ${vm.currentBuild}", color = ZappColors.Dim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        val u = vm.update
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(
                title = "Check for updates",
                body = when (u) {
                    UpdateState.Idle -> "Looks for a newer version of Zapp."
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Build ${u.release.build} is ready to install."
                    is UpdateState.Downloading -> "Downloading build ${u.release.build}…"
                    is UpdateState.Failed -> u.message
                },
                value = SettingValue.Action,
                onClick = { vm.checkForUpdates(manual = true) },
            )
            val release = when (u) {
                is UpdateState.Available -> u.release
                is UpdateState.Downloading -> u.release
                is UpdateState.Failed -> u.release
                else -> null
            }
            if (release != null) {
                SettingRow(
                    title = if (u is UpdateState.Downloading) "Downloading ${(u.progress * 100).toInt()}%" else "Install build ${release.build}",
                    body = "Downloads the update and opens the installer. Your playlists and settings are kept.",
                    value = SettingValue.Action,
                    onClick = { vm.installUpdate() },
                )
                if (u is UpdateState.Downloading) {
                    ProgressLine(u.progress, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Updating another way", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "If the built-in updater can't install on this TV, open the Downloader app and enter this address. " +
                        "It always points to the newest version.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(UPDATE_LINK, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ZappColors.Signal)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Zapp TV doesn't include any channels, playlists or accounts. Add your own from Sources.",
            color = ZappColors.Faint,
            fontSize = 12.sp,
        )
    }
}

sealed interface SettingValue {
    data class Toggle(val on: Boolean) : SettingValue
    data class Choice(val label: String) : SettingValue
    data object Action : SettingValue
}

@Composable
fun SettingRow(
    title: String,
    body: String,
    value: SettingValue,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(14.dp),
        focusScale = 1.015f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(body, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            when (value) {
                is SettingValue.Toggle -> Switch(value.on, focused)
                is SettingValue.Choice -> Text(
                    value.label,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Signal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                SettingValue.Action -> ChevronGlyph(if (focused) ZappColors.OnFocus else ZappColors.Faint, 12.dp, down = false)
            }
        }
    }
}

@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    Box(
        Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    checked -> ZappColors.Brand2
                    focused -> Color(0xFFCBD2E0)
                    else -> ZappColors.Line
                },
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}
