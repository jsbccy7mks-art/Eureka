package com.zapp.tv.ui

import android.app.Activity
import android.os.Build
import android.view.Display
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

    fun apply(activity: Activity, fps: Float, videoHeight: Int, matchRate: Boolean, matchResolution: Boolean) {
        if (!matchRate && !matchResolution) return
        val d = display(activity) ?: return
        val modes = d.supportedModes ?: return
        if (modes.size < 2) return
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
        if (sizes.isEmpty()) return

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
        chosen ?: return
        if (chosen.modeId == d.mode.modeId) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = chosen.modeId
        activity.window.attributes = attrs
        applied = true
    }

    /** Back to how the TV was. */
    fun reset(activity: Activity) {
        if (!applied) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = original?.modeId ?: 0
        activity.window.attributes = attrs
        applied = false
        original = null
    }
}
