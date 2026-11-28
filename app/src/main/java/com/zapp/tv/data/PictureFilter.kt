package com.zapp.tv.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.zapp.tv.addons.StreamItem

/**
 * Settings, Playback, HDR: which sources to hide or put last, by their picture (SDR, HDR, Dolby
 * Vision), and in Auto by what this TV and box say they can show.
 */
object PictureFilter {
    /** HDR types the screen reports (Display.HdrCapabilities), read once. */
    @Volatile private var caps: IntArray? = null

    private fun supported(context: Context): IntArray = caps ?: runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes
    }.getOrNull().let { it ?: IntArray(0) }.also { caps = it }

    fun screenHasHdr(context: Context): Boolean = supported(context).any {
        it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HLG || it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
    }

    fun screenHasDolbyVision(context: Context): Boolean = supported(context).any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }

    /** False when the setting hides this source. */
    fun keep(context: Context, mode: Int, s: StreamItem): Boolean {
        val k = s.pictureKind
        return when (mode) {
            2 -> k == 0
            3 -> k < 2
            // Auto: Dolby Vision with no HDR fallback plays with wrong colours without Dolby Vision.
            else -> !(k == 3 && !screenHasDolbyVision(context))
        }
    }

    /** A sorting nudge: below zero puts a source after the others (the ready ones still lead). */
    fun bonus(context: Context, mode: Int, s: StreamItem): Int {
        val k = s.pictureKind
        if (k == 0) return 0
        val avoid = when (mode) {
            1 -> true
            0 -> if (k >= 2) !screenHasDolbyVision(context) && !screenHasHdr(context) else !screenHasHdr(context)
            else -> false
        }
        return if (avoid) -5_000 else 0
    }

    fun label(mode: Int): String = listOf("Auto", "Prefer SDR", "SDR only", "No Dolby Vision")[mode.coerceIn(0, 3)]
}
