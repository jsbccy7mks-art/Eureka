package com.zapp.tv.ui

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Whether you're moving around right now. While you are, the things that animate on their own
 * (the slow zoom on artwork, the glow's pulse) hold still, so the box spends every frame on
 * scrolling; they carry on once you rest. Holding a direction also scrolls with a quicker,
 * even glide.
 */
object Motion {
    var moving by mutableStateOf(false)
        private set
    var held by mutableStateOf(false)
        private set
    @Volatile private var lastKey = 0L

    fun onArrow(down: Boolean, repeat: Int) {
        if (down) {
            lastKey = System.currentTimeMillis()
            if (!moving) moving = true
            val h = repeat > 0
            if (held != h) held = h
        } else if (held) {
            held = false
        }
    }

    /** Run once (from the root): settles back to "resting" a moment after the last press. */
    suspend fun settle() {
        while (true) {
            delay(150)
            if (moving && !held && System.currentTimeMillis() - lastKey > 900) moving = false
        }
    }
}

/** A small counter in the corner (Settings, Updates and about): frames per second and slow frames. */
@Composable
fun FrameCounter(modifier: Modifier = Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    var slow by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val ch = Choreographer.getInstance()
        var frames = 0
        var slowNow = 0
        var windowStart = 0L
        var last = 0L
        var active = true
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!active) return
                if (last != 0L && t - last > 34_000_000L) slowNow++
                last = t
                if (windowStart == 0L) windowStart = t
                frames++
                if (t - windowStart >= 1_000_000_000L) {
                    fps = frames
                    slow = slowNow
                    frames = 0
                    slowNow = 0
                    windowStart = t
                }
                ch.postFrameCallback(this)
            }
        }
        ch.postFrameCallback(cb)
        onDispose { active = false }
    }
    Text(
        "$fps fps  ·  $slow slow",
        color = when {
            slow == 0 -> Color(0xFF7EE2A8)
            slow < 4 -> Color(0xFFFFC94A)
            else -> Color(0xFFFF8A8A)
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
