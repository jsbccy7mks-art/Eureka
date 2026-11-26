package com.zapp.tv.ui

import org.robolectric.RuntimeEnvironment
import com.zapp.tv.ZappApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's main pieces can be created, so a build that would crash on opening never ships. Runs
 * with every build (and blocks it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpensTest {
    @Test
    fun mainPiecesStart() {
        val app = RuntimeEnvironment.getApplication() as ZappApp
        val vm = AppViewModel(app)
        MediaViewModel(app)
        val playback = Playback(app, CoroutineScope(Dispatchers.Main), vm.settings) { "" }
        playback.player.release()
    }
}
