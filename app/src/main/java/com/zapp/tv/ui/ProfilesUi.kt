package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.data.Profile
import kotlinx.coroutines.delay

/** Avatar colours: each a pair for a soft diagonal gradient. */
val AvatarColors = listOf(
    Color(0xFF4D7CFF) to Color(0xFFFF5FA2),
    Color(0xFFFF8A3D) to Color(0xFFFF4F8B),
    Color(0xFF22C58B) to Color(0xFF2E9BF0),
    Color(0xFF7A5CFF) to Color(0xFF2FD3F0),
    Color(0xFFFFC94A) to Color(0xFFFF7A45),
    Color(0xFFB45CFF) to Color(0xFFFF6FD8),
    Color(0xFF3DDBD9) to Color(0xFF4D7CFF),
    Color(0xFF8A8F9C) to Color(0xFFE6E6E6),
)

/** A round avatar: the profile's colours and first letter (a small "KIDS" tag for kids profiles). */
@Composable
fun Avatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val (a, b) = AvatarColors[profile.color.mod(AvatarColors.size)]
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.trim().take(1).uppercase().ifBlank { "?" },
            fontFamily = TitleFont,
            fontSize = (size.value * 0.46f).sp,
            color = Color.White,
        )
        if (profile.kids && size >= 48.dp) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = size * 0.1f)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("KIDS", fontSize = (size.value * 0.1f).coerceAtLeast(8f).sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
            }
        }
    }
}

/** "Who's watching?": the profiles in a row, large avatars with names. */
@Composable
fun WhoIsWatching(
    profiles: List<Profile>,
    currentId: Long,
    canClose: Boolean,
    onPick: (Profile) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = canClose, onBack = onClose)
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles.size) {
        delay(80)
        first.tryRequest()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg)
            .trapFocus(),
        contentAlignment = Alignment.Center,
    ) {
        AmbientGlow()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(30.dp)
            Spacer(Modifier.height(22.dp))
            Text("Who's watching?", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEach { p ->
                    var focused by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusSurface(
                            onClick = { onPick(p) },
                            focusRequester = if (p.id == currentId || (profiles.none { it.id == currentId } && p == profiles.first())) first else null,
                            onFocusChange = { focused = it },
                            shape = CircleShape,
                            focusScale = 1.08f,
                            restColor = Color.Transparent,
                            focusColor = Color.Transparent,
                            ring = true,
                            ringCorner = 50.dp,
                        ) {
                            Avatar(p, 96.dp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            p.name,
                            fontSize = 14.sp,
                            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (focused) ZappColors.Text else ZappColors.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(110.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (p.pin != null) {
                            Text("PIN", fontSize = 9.5.sp, color = ZappColors.Faint, letterSpacing = 1.5.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Four-digit PIN entry. Number keys on the remote work, or the on-screen pad. [check] returns true
 * when the PIN is right; a wrong one shakes the dots clear.
 */
@Composable
fun PinPad(title: String, subtitle: String?, check: (String) -> Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    fun type(d: Char) {
        if (entered.length >= 4) return
        wrong = false
        entered += d
        if (entered.length == 4) {
            if (check(entered)) onDone() else {
                wrong = true
                entered = ""
            }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(ZappColors.Panel)
                .padding(26.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = when (e.key) {
                        Key.Zero, Key.NumPad0 -> '0'
                        Key.One, Key.NumPad1 -> '1'
                        Key.Two, Key.NumPad2 -> '2'
                        Key.Three, Key.NumPad3 -> '3'
                        Key.Four, Key.NumPad4 -> '4'
                        Key.Five, Key.NumPad5 -> '5'
                        Key.Six, Key.NumPad6 -> '6'
                        Key.Seven, Key.NumPad7 -> '7'
                        Key.Eight, Key.NumPad8 -> '8'
                        Key.Nine, Key.NumPad9 -> '9'
                        else -> null
                    }
                    if (digit != null) {
                        type(digit)
                        true
                    } else {
                        false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = ZappColors.Dim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (i < entered.length) Color.White else Color.Transparent)
                            .border(1.5.dp, if (wrong) ZappColors.Error else Color(0x66FFFFFF), CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (wrong) "That's not right. Try again." else " ", color = ZappColors.Error, fontSize = 11.5.sp)
            Spacer(Modifier.height(10.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            if (k.isEmpty()) {
                                Spacer(Modifier.size(58.dp, 44.dp))
                            } else {
                                FocusSurface(
                                    onClick = {
                                        if (k == "⌫") entered = entered.dropLast(1) else type(k[0])
                                    },
                                    modifier = Modifier.size(58.dp, 44.dp),
                                    focusRequester = if (k == "1") first else null,
                                    shape = RoundedCornerShape(12.dp),
                                    focusScale = 1.04f,
                                    restColor = ZappColors.Glass,
                                ) { focused ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(k, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else ZappColors.Text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ZButton("Cancel", onCancel, style = ButtonStyle.Quiet)
        }
    }
}

/** Settings, Profiles: add, edit and remove profiles. */
@Composable
fun ProfilesPane(mvm: MediaViewModel) {
    val profiles by mvm.profiles.collectAsState()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val s = mvm.settings

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Each profile has its own Continue watching, library and Home rows. Kids profiles only see family titles and no live TV. Simkl syncs with the first profile.",
                color = ZappColors.Dim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(profiles, key = { it.id }) { p ->
            FocusSurface(
                onClick = { editing = p },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                focusScale = 1.015f,
                restColor = ZappColors.Glass,
            ) { focused ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else ZappColors.Text)
                        Text(
                            listOfNotNull(
                                if (p.kids) "Kids" else null,
                                if (p.pin != null) "PIN" else null,
                                if (p.id == 1L) "Main profile, syncs with Simkl" else null,
                                if (p.id == s.profileId) "In use" else null,
                            ).joinToString(" · ").ifBlank { "Profile" },
                            fontSize = 12.sp,
                            color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        )
                    }
                    Text("Edit", fontSize = 12.sp, color = if (focused) ZappColors.OnFocus else ZappColors.Faint)
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Add profile", { adding = true }, style = ButtonStyle.Primary)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SettingRow(
                title = "Ask who's watching",
                body = "Show the profiles when Zapp opens (when there's more than one).",
                value = SettingValue.Toggle(s.askProfileOnStart),
                onClick = { s.askProfileOnStart = !s.askProfileOnStart },
            )
        }
    }

    if (adding) {
        ProfileDialog(mvm, null) { adding = false }
    }
    editing?.let { p ->
        val live = profiles.firstOrNull { it.id == p.id } ?: p
        ProfileDialog(mvm, live) { editing = null }
    }
}

@Composable
private fun ProfileDialog(mvm: MediaViewModel, profile: Profile?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var color by remember { mutableStateOf(profile?.color ?: (mvm.profiles.value.size % AvatarColors.size)) }
    var kids by remember { mutableStateOf(profile?.kids ?: false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        field.tryRequest()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZappColors.Panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (profile == null) "New profile" else "Edit profile", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TvTextField("Name", name, { name = it.take(20) }, placeholder = "Name", focusRequester = field)
            Text("Colour", color = ZappColors.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarColors.forEachIndexed { i, (a, b) ->
                    FocusSurface(
                        onClick = { color = i },
                        modifier = Modifier
                            .size(34.dp)
                            .border(2.dp, if (i == color) Color.White else Color.Transparent, CircleShape),
                        shape = CircleShape,
                        focusScale = 1.15f,
                        focusColor = Color.Transparent,
                        ring = true,
                        ringCorner = 17.dp,
                    ) {
                        Box(Modifier.fillMaxSize().padding(3.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))))
                    }
                }
            }
            SettingRow(
                title = "Kids profile",
                body = "Only family and animation titles, no live TV, and Settings needs the main profile's PIN (if it has one).",
                value = SettingValue.Toggle(kids),
                onClick = { if (profile?.id != 1L) kids = !kids },
            )
            if (profile != null) {
                SettingRow(
                    title = if (profile.pin == null) "Add a PIN" else "Change PIN",
                    body = "A PIN is asked for when switching to this profile" + if (profile.id == 1L) " and when a kids profile opens Settings." else ".",
                    value = SettingValue.Action,
                    onClick = { settingPin = true },
                )
            }
            if (profile?.pin != null) {
                SettingRow(
                    title = "Remove PIN",
                    body = "Anyone can switch to this profile without a PIN.",
                    value = SettingValue.Action,
                    onClick = { mvm.setPin(profile, null) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Save", {
                    if (profile == null) {
                        mvm.addProfile(name, color, kids)
                    } else {
                        mvm.updateProfile(profile.copy(name = name.trim().ifBlank { profile.name }, color = color, kids = kids && profile.id != 1L))
                    }
                    onClose()
                }, style = ButtonStyle.Primary)
                ZButton("Cancel", onClose, style = ButtonStyle.Quiet)
                if (profile != null && profile.id != 1L) {
                    ZButton(if (confirmDelete) "Really delete?" else "Delete", {
                        if (confirmDelete) {
                            mvm.deleteProfile(profile.id)
                            onClose()
                        } else {
                            confirmDelete = true
                        }
                    }, style = ButtonStyle.Danger)
                }
            }
        }
    }

    if (settingPin && profile != null) {
        var newPin by remember { mutableStateOf<String?>(null) }
        if (newPin == null) {
            PinPad(
                title = "New PIN for ${profile.name}",
                subtitle = "Four digits",
                check = { newPin = it; true },
                onDone = {},
                onCancel = { settingPin = false },
            )
        } else {
            PinPad(
                title = "Enter it again",
                subtitle = null,
                check = { it == newPin },
                onDone = {
                    mvm.setPin(profile, newPin)
                    settingPin = false
                },
                onCancel = { settingPin = false },
            )
        }
    }
}
