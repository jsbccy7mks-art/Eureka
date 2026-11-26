package com.zapp.tv.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frame rate and resolution matching: asks the TV for the display mode that suits the video
 * (through the window's preferred display mode, which Android TV and Fire TV honour), and puts the
 * TV back as it was afterwards.
 */
object DisplayMatch {
    private var applied = false

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    /** The mode the TV was in before Zapp changed it. */
    private var original: Display.Mode? = null

    /** The video that was matched before it started (the player then leaves the TV alone). */
    var matchedVideo: String? = null
    /** For Stream info and the corner note: the video's rate, and what the TV is showing. */
    var videoFps by androidx.compose.runtime.mutableStateOf<Float?>(null)
    var videoFpsMeasured by androidx.compose.runtime.mutableStateOf(false)
    var note by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Android's own "Match content frame rate" choice (Android 12+): 0 never, 1 seamless only, 2 always. */
    fun systemPreference(activity: Activity): Int? = if (Build.VERSION.SDK_INT >= 31) {
        runCatching { (activity.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).matchContentFrameRateUserPreference }.getOrNull()
    } else {
        null
    }

    fun tvNow(activity: Activity): String? = display(activity)?.mode?.let { m ->
        "${m.physicalWidth} × ${m.physicalHeight} at " + String.format(java.util.Locale.UK, "%.3f", m.refreshRate).trimEnd('0').trimEnd('.') + " Hz"
    }

    /**
     * Before a film starts: switch the TV to suit it, then wait (up to [timeoutMs]) until the TV
     * has really settled on the new mode, so nothing plays during the switch.
     */
    suspend fun switchAndWait(activity: Activity, fps: Float, videoHeight: Int, matchRate: Boolean, matchResolution: Boolean, timeoutMs: Long = 4_000): Boolean {
        val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            apply(activity, fps, videoHeight, matchRate, matchResolution)
        } ?: return false
        var stable = 0
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { display(activity)?.mode }
            if (now != null && (now.modeId == target.modeId || abs(now.refreshRate - target.refreshRate) < 0.05f)) {
                if (++stable >= 3) break
            } else {
                stable = 0
            }
            kotlinx.coroutines.delay(120)
        }
        // A beat for the TV's picture to come back after the blank.
        kotlinx.coroutines.delay(350)
        return true
    }

    /** Asks for the best mode; returns it if a change was asked for. */
    fun apply(activity: Activity, fps: Float, videoHeight: Int, matchRate: Boolean, matchResolution: Boolean): Display.Mode? {
        if (!matchRate && !matchResolution) return null
        val d = display(activity) ?: return null
        val modes = d.supportedModes ?: return null
        if (modes.size < 2) return null
        val base = original ?: d.mode.also { original = it }

        // Which sizes to choose from: the video's (matching resolution) or the TV's own.
        val sizes = if (matchResolution && videoHeight > 0) {
            val target = when {
                videoHeight > 1100 -> 2160
                videoHeight > 740 -> 1080
                else -> 720
            }
            val exact = modes.filter { it.physicalHeight == target }
            exact.ifEmpty { modes.filter { it.physicalHeight == base.physicalHeight && it.physicalWidth == base.physicalWidth } }
        } else {
            modes.filter { it.physicalHeight == base.physicalHeight && it.physicalWidth == base.physicalWidth }
        }
        if (sizes.isEmpty()) return null

        val chosen = if (matchRate && fps > 1f) {
            // A refresh rate that is a whole multiple of the frame rate (23.976 on 23.976 or 47.95,
            // 25 on 50); the exact rate if there is one, otherwise the lowest clean multiple.
            sizes.minByOrNull { m ->
                val k = (m.refreshRate / fps).roundToInt().coerceAtLeast(1)
                val miss = abs(m.refreshRate - fps * k)
                (if (miss < 0.05f) 0f else 1000f + miss) + k * 0.01f
            }?.takeIf { m ->
                val k = (m.refreshRate / fps).roundToInt().coerceAtLeast(1)
                abs(m.refreshRate - fps * k) < 0.05f
            }
        } else {
            null
        } ?: sizes.minByOrNull { abs(it.refreshRate - base.refreshRate) }
        chosen ?: return null
        if (chosen.modeId == d.mode.modeId) return null
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = chosen.modeId
        activity.window.attributes = attrs
        applied = true
        note = "${chosen.physicalWidth} × ${chosen.physicalHeight}  ·  " + String.format(java.util.Locale.UK, "%.3f", chosen.refreshRate).trimEnd('0').trimEnd('.') + " Hz"
        return chosen
    }

    /** Back to how the TV was. */
    fun reset(activity: Activity) {
        if (!applied) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = original?.modeId ?: 0
        activity.window.attributes = attrs
        applied = false
        original = null
        matchedVideo = null
    }
}
