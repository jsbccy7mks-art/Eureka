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
import androidx.compose.foundation.layout.width
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

    private fun settings(section: String, downs: Int) {
        assumeTrue(System.getenv("SCREENS") == "1")
        val app = RuntimeEnvironment.getApplication() as ZappApp
        val vm = AppViewModel(app)
        val mvm = MediaViewModel(app)
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
        save("settings-$section")
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

    @Test fun cardMarks() {
        assumeTrue(System.getenv("SCREENS") == "1")
        rule.mainClock.autoAdvance = false
        WatchedMarks.ids = setOf("w", "lw")
        LovedMarks.ids = setOf("lw", "lp", "ls")
        ProgressMarks.marks = mapOf("p" to ProgressMark(0.4f), "lp" to ProgressMark(0.7f), "s" to ProgressMark(0.35f, "S2 E5"), "ls" to ProgressMark(0.6f, "S1 E8"))
        val colours = listOf(0xFF1D3557, 0xFF9D3C5C, 0xFF2D6A4F, 0xFF6C4AB6, 0xFF3A3A48, 0xFFA0522D)
        rule.setContent {
            ZappTheme {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxSize().background(ZappColors.Bg).padding(40.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    listOf("w", "lw", "p", "lp", "s", "ls").forEachIndexed { i, id ->
                        Box(
                            Modifier
                                .width(132.dp)
                                .height(198.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(colours[i]), androidx.compose.ui.graphics.Color(0xFFE9C46A))),
                                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                ),
                        ) { CardMarks(id) }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(800)
        save("card-marks")
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

    /** Lets the database (real threads) and the main looper catch up, moving the clock as it goes. */
    private fun settle(ms: Long) {
        var left = ms
        while (left > 0) {
            Thread.sleep(40)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(100)
            left -= 100
        }
    }

    private fun longPress(code: Int) {
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 500, android.view.KeyEvent.ACTION_DOWN, code, 1))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 600, android.view.KeyEvent.ACTION_UP, code, 0))
        }
        rule.mainClock.advanceTimeBy(300)
    }

    /** A made-up playlist with categories, channels and a guide, straight into the app's database. */
    private fun seedLive(app: ZappApp) = kotlinx.coroutines.runBlocking {
        val f = ZappApp::class.java.getDeclaredField("database\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val db = (f.get(app) as Lazy<com.zapp.tv.data.AppDatabase>).value
        val now = System.currentTimeMillis()
        val pid = db.playlistDao().insert(com.zapp.tv.data.Playlist(name = "Home", type = com.zapp.tv.data.TYPE_M3U, url = "", lastUpdated = now, epgUpdated = now))
        val groups = listOf("Entertainment", "Sport", "News", "Kids", "Films", "Documentaries", "Music", "Adult", "Radio")
        val ids = db.categoryDao().insertAll(groups.mapIndexed { i, g -> com.zapp.tv.data.Category(playlistId = pid, remoteId = g, name = g, sortOrder = i) })
        val names = listOf(
            "One HD", "Two HD", "Three", "Four FHD", "Five", "Sport Live 1 UHD", "Sport Live 2 FHD", "Sport Extra", "News 24 HD",
            "World News", "Cartoon Time", "Tiny Tots", "Film Classics HD", "Action Films FHD", "Nature HD", "History", "Hits", "Chill",
        )
        val shows = listOf("The Morning Show", "Premier Football: Live", "Evening News", "Bake Off", "Nature's Edge", "Quiz Night", "Late Film: The Long Road", "Weather")
        val channels = names.mapIndexed { i, n ->
            com.zapp.tv.data.Channel(playlistId = pid, categoryId = ids[(i / 2).coerceAtMost(ids.size - 1)], number = i + 1, name = n, logo = null, streamUrl = "http://example.invalid/$i", epgId = "ch$i")
        }
        db.channelDao().insertAll(channels)
        val slot = 30 * 60_000L
        val start = now - now % slot - slot
        val programmes = channels.flatMapIndexed { i, c ->
            (0 until 10).map { k ->
                val s = start + k * slot + (i % 3) * 10 * 60_000L - 20 * 60_000L
                com.zapp.tv.data.StagedProgramme(playlistId = pid, channelEpgId = c.epgId!!, start = s, stop = s + slot, title = shows[(i + k) % shows.size], description = "Something good to watch.", icon = null)
            }
        }
        db.programmeDao().insertStagingBlocking(programmes)
        db.programmeDao().promoteStaging(pid)
        pid
    }

    @Test fun liveTv() {
        assumeTrue(System.getenv("SCREENS") == "1")
        val app = RuntimeEnvironment.getApplication() as ZappApp
        seedLive(app)
        val vm = AppViewModel(app)
        vm.settings.preview = false
        val tick = androidx.compose.runtime.mutableIntStateOf(0)
        val guide = androidx.compose.runtime.mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ZappTheme {
                val scope = rememberCoroutineScope()
                val playback = remember { Playback(app, scope, vm.settings) { "" } }
                Box(Modifier.fillMaxSize().background(ZappColors.Bg)) {
                    AmbientBackground()
                    Box(Modifier.fillMaxSize().padding(top = 56.dp)) {
                        if (guide.value) GuideScreen(vm, tick.intValue) {} else LiveScreen(vm, playback, tick.intValue) {}
                    }
                }
            }
        }
        settle(2500)
        tick.intValue = 1
        settle(2000)
        // Focus lands on the first channel: the categories slide away, the list is wide.
        repeat(2) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        settle(1200)
        save("live-wide")
        // Hold OK: the channel menu.
        longPress(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        settle(800)
        save("live-channel-menu")
        key(android.view.KeyEvent.KEYCODE_BACK)
        settle(600)
        // Left: the categories come back.
        key(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        settle(1200)
        save("live-categories")
        repeat(2) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        settle(600)
        longPress(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        settle(800)
        save("live-category-menu")
        key(android.view.KeyEvent.KEYCODE_BACK)
        settle(600)
        // The guide, and its category panel (Left from the channel column).
        guide.value = true
        tick.intValue = 2
        settle(2500)
        save("guide")
        // Left back through the programmes (and earlier times) to the channel column, then once more.
        repeat(14) {
            key(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            settle(300)
        }
        settle(900)
        save("guide-categories")
    }

    @Test fun playback() = settings("Playback", 4)
    @Test fun appearance() = settings("Appearance", 2)
    @Test fun accounts() = settings("Accounts", 1)
    @Test fun system() = settings("About", 1)
    @Test fun home() = settings("HomeLayout", 1)
    @Test fun live() = settings("Live", 0)
    @Test fun playlists() = settings("Sources", 0)
}
