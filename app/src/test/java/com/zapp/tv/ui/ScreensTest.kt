package com.zapp.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

    @Test fun playback() = settings("Playback", 4)
    @Test fun appearance() = settings("Appearance", 2)
    @Test fun accounts() = settings("Accounts", 1)
    @Test fun system() = settings("About", 1)
    @Test fun home() = settings("HomeLayout", 1)
    @Test fun live() = settings("Live", 0)
    @Test fun playlists() = settings("Sources", 0)
}
