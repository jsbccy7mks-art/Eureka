package com.zapp.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.addons.MetaPreview

/**
 * What to watch next, full screen, while the credits carry on in a small window: the suggestion's
 * colours and backdrop (its trailer after a moment, muted), why it's suggested, its logo, scores
 * and story, with Play and Details. Left and Right move between suggestions.
 */
@Composable
fun PostPlayPage(
    mvm: MediaViewModel,
    suggestions: List<MediaViewModel.Suggestion>,
    finished: MetaPreview? = null,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
) {
    var index by remember(suggestions) { mutableStateOf(0) }
    val s = suggestions.getOrNull(index) ?: return
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(suggestions) {
        kotlinx.coroutines.delay(200)
        playFocus.tryRequest()
    }
    // Get it ready to play while you look (films).
    LaunchedEffect(s.meta.id) {
        if (s.meta.type == "movie") {
            mvm.prefetchStreams("movie", s.meta.id)
            kotlinx.coroutines.delay(3_000)
            mvm.prepareLink("movie", s.meta.id, s.meta.id, s.meta.name)
        }
    }
    val ratings by produceState(emptyList<com.zapp.tv.data.RatingBadge>(), s.meta.id) { value = mvm.ratings(s.meta.id, s.meta.type) }
    var trailerOn by remember(s.meta.id) { mutableStateOf(false) }
    LaunchedEffect(s.meta.id) {
        kotlinx.coroutines.delay(4_000)
        trailerOn = true
    }

    Box(Modifier.fillMaxSize().background(ZappColors.Bg)) {
        BlurredBackdrop(s.meta.background ?: s.meta.poster, dim = 0.45f)
        // The backdrop (then its trailer, muted) at the top right, fading into the page.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.88f)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(*easedMask(start = 0f, end = 0.55f)), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(*easedMask(start = 1f, end = 0.5f)), blendMode = BlendMode.DstIn)
                },
        ) {
            androidx.compose.animation.Crossfade(targetState = s.meta.background, animationSpec = tween(700), label = "ppBg", modifier = Modifier.fillMaxSize()) { u ->
                if (u != null) AsyncImage(model = hiRes(u), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().drift())
            }
            if (trailerOn && s.trailer != null) TrailerBackground(s.trailer, Modifier.fillMaxSize(), sound = false)
        }

        // One optional press: how was the film you just watched?
        if (finished != null) {
            Row(Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("How was ${finished.name}?", color = ZappColors.Dim, fontSize = TextSize.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                Spacer(Modifier.width(14.dp))
                RatingButtons(mvm.ratingOf(finished.id), size = 36.dp) { r -> mvm.rate(finished, r) }
            }
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) },
            label = "ppInfo",
            modifier = Modifier.align(Alignment.BottomStart),
        ) { i ->
            val cur = suggestions.getOrNull(i) ?: return@AnimatedContent
            val m = cur.meta
            Column(Modifier.padding(start = 64.dp, bottom = 56.dp).widthIn(max = 620.dp)) {
                Text("UP NEXT  ·  " + cur.reason.uppercase(), color = ZappColors.Brand2.copy(alpha = 0.95f), fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                TitleLogo(m.id, m.logo, m.name, Modifier.heightIn(max = 96.dp).widthIn(max = 420.dp)) {
                    MovieTitle(m.name, if (m.name.length > 24) 36.sp else 44.sp, maxLines = 2)
                }
                Spacer(Modifier.height(10.dp))
                FactsLine(m.releaseInfo, m.imdbRating, null, m.genres, size = 12.sp)
                if (ratings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    RatingBadges(ratings)
                }
                m.description?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFD0D3DC), fontSize = TextSize.Body, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                var onPlayButton by remember { mutableStateOf(true) }
                Row(
                    Modifier.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionLeft && onPlayButton && index > 0 -> { index--; true }
                            e.key == Key.DirectionRight && !onPlayButton && index < suggestions.size - 1 -> { index++; true }
                            else -> false
                        }
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZButton("Play", { onPlay(m) }, style = ButtonStyle.Primary, focusRequester = playFocus, onFocusChange = { if (it) onPlayButton = true })
                    ZButton("Details", { onDetails(m) }, onFocusChange = { if (it) onPlayButton = false })
                    if (suggestions.size > 1) {
                        Spacer(Modifier.width(14.dp))
                        // Which suggestion this is.
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            suggestions.indices.forEach { d ->
                                Box(
                                    Modifier
                                        .size(if (d == index) 18.dp else 6.dp, 6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (d == index) Color.White else Color(0x55FFFFFF)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
