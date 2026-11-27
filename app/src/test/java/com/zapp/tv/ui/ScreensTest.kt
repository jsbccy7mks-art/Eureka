package com.zapp.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.zapp.tv.ZappApp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of screens, for checking the look without a TV. Only runs when SCREENS=1 (a separate
 * CI step); the pictures are saved under app/build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        rule.mainClock.advanceTimeBy(300)
    }

    private fun save(name: String) {
        // Drawn straight from the window (waiting for the screen to be "idle" never ends: glows loop).
        var bmp: Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A made-up colourful picture standing in for artwork (nothing loads from the internet here). */
    private fun fakeArt(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF1B3A6B.toInt(), 0xFFB0413E.toInt(), 0xFFE8A33D.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = android.graphics.RadialGradient(w * 0.72f, h * 0.3f, h * 0.5f, 0xCCFFF1C9.toInt(), 0x00FFF1C9, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFF0E1A2E.toInt()
        c.drawCircle(w * 0.3f, h * 1.05f, h * 0.55f, p)
        return b
    }

    private fun glass(on: Boolean) {
        FocusStyle.glass = on
        GlassScene.image = if (on) fakeArt(64, 36).asImageBitmap() else null
    }

    @Test fun glassAppearance() {
        glass(true)
        try {
            settings("Appearance", 0, "glass-") { vm ->
                // Settings sets the style from what's saved, so choose Glass there.
                vm.settings.uiStyle = 1
                glass(true)
            }
        } finally { glass(false) }
    }

    @Test fun glassHome() {
        assumeTrue(System.getenv("SCREENS") == "1")
        glass(true)
        try {
            rule.mainClock.autoAdvance = false
            val art = fakeArt(960, 540).asImageBitmap()
            val cardFocus = androidx.compose.ui.focus.FocusRequester()
            rule.setContent {
                ZappTheme {
                    Box(Modifier.fillMaxSize().background(ZappColors.Bg)) {
                        AmbientBackground()
                        androidx.compose.foundation.Image(art, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.5f to androidx.compose.ui.graphics.Color.Transparent, 0.85f to ZappColors.Bg.copy(alpha = 0.6f))))
                        androidx.compose.foundation.layout.Column(Modifier.padding(start = 72.dp, top = 250.dp)) {
                            androidx.compose.foundation.layout.Column(
                                Modifier
                                    .glass(Shapes.Panel, elevation = 20.dp)
                                    .padding(horizontal = 20.dp, vertical = 18.dp)
                                    .width(380.dp),
                            ) {
                                androidx.compose.material3.Text("FILM  ·  TRENDING", fontSize = TextSize.Tag, color = ZappColors.Faint)
                                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                                MovieTitle("The Long Winter", 30.sp, maxLines = 1)
                                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                                androidx.compose.material3.Text("2026  ·  ★ 7.9  ·  2h 4m  ·  Drama", fontSize = TextSize.Small, color = ZappColors.Text)
                                androidx.compose.foundation.layout.Spacer(Modifier.height(9.dp))
                                androidx.compose.material3.Text("A lighthouse keeper and her estranged son ride out the longest night of the year as a storm cuts the island off.", fontSize = TextSize.Small, color = ZappColors.Dim, maxLines = 3)
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.height(22.dp))
                            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                                repeat(5) { i ->
                                    FocusSurface(
                                        onClick = {},
                                        modifier = Modifier.width(170.dp).height(96.dp),
                                        focusRequester = if (i == 1) cardFocus else null,
                                        ring = true,
                                    ) {
                                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF2B4A7A + i * 0x101010L), androidx.compose.ui.graphics.Color(0xFF7A3B2B)))))
                                    }
                                }
                            }
                        }
                        GlassTabBar(
                            locked = false,
                            dests = Dest.entries,
                            profile = null,
                            onProfiles = {},
                            current = Dest.Home,
                            updateReady = true,
                            railFocus = remember { androidx.compose.ui.focus.FocusRequester() },
                            open = true,
                            onOpenChange = {},
                            onDown = {},
                            onSelect = {},
                        )
                    }
                }
            }
            rule.mainClock.advanceTimeBy(1500)
            rule.runOnUiThread { cardFocus.requestFocus() }
            rule.mainClock.advanceTimeBy(1200)
            save("glass-home")
        } finally { glass(false) }
    }

    private fun settings(section: String, downs: Int, prefix: String = "", before: (AppViewModel) -> Unit = {}) {
        assumeTrue(System.getenv("SCREENS") == "1")
        val app = RuntimeEnvironment.getApplication() as ZappApp
        val vm = AppViewModel(app)
        val mvm = MediaViewModel(app)
        before(vm)
        val tick = androidx.compose.runtime.mutableIntStateOf(0)
        // Endless animations (glows) would never let the screen settle; time moves only when told.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ZappTheme {
                val scope = rememberCoroutineScope()
                val playback = remember { Playback(app, scope, vm.settings) { "" } }
                Box(Modifier.fillMaxSize().background(ZappColors.Bg)) {
                    AmbientBackground()
                    SettingsScreen(mvm, section, vm, playback, tick.intValue, onForm = {}, onWatchPlaylist = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        tick.intValue = 1
        rule.mainClock.advanceTimeBy(1200)
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(downs) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        rule.mainClock.advanceTimeBy(800)
        save("${prefix}settings-$section")
    }

    @Test fun profiles() = settings("Profiles", 1)

    @Test fun whatsNew() {
        assumeTrue(System.getenv("SCREENS") == "1")
        val app = RuntimeEnvironment.getApplication() as ZappApp
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ZappTheme {
                Box(Modifier.fillMaxSize().background(ZappColors.Bg)) {
                    AmbientBackground()
                    WhatsNewScreen(Changelog.read(app).take(3)) {}
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        save("whats-new")
    }

    @Test fun wrapped() {
        assumeTrue(System.getenv("SCREENS") == "1")
        rule.mainClock.autoAdvance = false
        rule.setContent { ZappTheme { WrappedScreen(WrappedPeriod(2026, 10)) {} } }
        rule.mainClock.advanceTimeBy(2500)
        save("wrapped-1")
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeBy(2500)
        save("wrapped-2")
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeBy(2500)
        save("wrapped-3")
    }

    @Test fun collectionCards() {
        assumeTrue(System.getenv("SCREENS") == "1")
        rule.mainClock.autoAdvance = false
        fun c(id: Long, name: String, kind: String, tint: Long = 0) = MediaViewModel.CollectionSummary(id, name, null, null, kind = kind, tint = tint)
        rule.setContent {
            ZappTheme {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(ZappColors.Bg).padding(40.dp)) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                        CollectionCard(c(-301, "Christmas Classics", "seasonal"), 220.dp) {}
                        CollectionCard(c(-302, "Christmas Favourites", "seasonal"), 220.dp) {}
                        CollectionCard(c(-303, "Family Christmas", "seasonal"), 220.dp) {}
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                        CollectionCard(c(-304, "Christmas Comedies", "seasonal"), 220.dp) {}
                        CollectionCard(c(-200008, "Netflix", "network", 0xFFE50914), 220.dp) {}
                        CollectionCard(c(-200009, "Prime Video", "network", 0xFF00A8E1), 220.dp) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        save("collection-cards")
    }

    @Test fun app() {
        assumeTrue(System.getenv("SCREENS") == "1")
        val app = RuntimeEnvironment.getApplication() as ZappApp
        val vm = AppViewModel(app)
        rule.mainClock.autoAdvance = false
        rule.setContent { ZappTheme { ZappRoot(vm) } }
        rule.mainClock.advanceTimeBy(600)
        save("app-opening")
    }

    @Test fun playback() = settings("Playback", 4)
    @Test fun appearance() = settings("Appearance", 2)
    @Test fun accounts() = settings("Accounts", 1)
    @Test fun system() = settings("About", 1)
    @Test fun home() = settings("HomeLayout", 1)
    @Test fun live() = settings("Live", 0)
    @Test fun playlists() = settings("Sources", 0)
}
