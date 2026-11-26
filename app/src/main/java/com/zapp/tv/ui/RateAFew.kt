package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.data.Rating

/**
 * "Rate a few": popular films as posters. OK goes Like, then Love, then Dislike, then clear, so
 * For you has something to go on from the start.
 */
@Composable
fun RateAFewScreen(mvm: MediaViewModel, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    // What you've watched; popular films only if there's nothing watched yet.
    var fromWatched by remember { mutableStateOf(true) }
    val films by produceState<List<com.zapp.tv.addons.MetaPreview>?>(null) {
        val watched = mvm.watchedToRate()
        fromWatched = watched.isNotEmpty()
        value = watched.ifEmpty { mvm.popularToRate() }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(films) {
        kotlinx.coroutines.delay(150)
        first.tryRequest()
    }
    // Focus never goes missing here: if something takes it away, it comes back to the card you were on.
    var hasFocus by remember { mutableStateOf(false) }
    var lastCard by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(hasFocus, films) {
        if (films == null || hasFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (!hasFocus) (lastCard ?: first).tryRequest()
    }
    val rated = mvm.ratings.byId
    Box(Modifier.fillMaxSize().trapFocus().onFocusChanged { hasFocus = it.hasFocus }.background(ZappColors.Bg)) {
        AmbientGlow(0.7f)
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (fromWatched) "Rate what you've watched" else "Rate a few films", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (fromWatched) "Films and series you've finished. OK: like, love, dislike, or clear. The more you rate, the better For you gets."
                        else "Nothing marked watched yet, so here are popular films. OK: like, love, dislike, or clear.",
                        color = ZappColors.Dim, fontSize = 13.sp,
                    )
                }
                // How far along For you is, and a clear way out.
                val total = rated.size
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (total >= 5) "$total rated" else "$total of 5 rated",
                        color = if (total >= 5) Color(0xFF7EE2A8) else ZappColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(if (total >= 5) "For you is ready on Home" else "For you starts at 5", color = ZappColors.Faint, fontSize = 11.sp)
                }
                Spacer(Modifier.padding(start = 16.dp))
                ZButton(if (total >= 5) "Done, see my picks" else "Done", onDone, style = ButtonStyle.Primary)
            }
            Spacer(Modifier.height(18.dp))
            val list = films
            if (list == null) {
                ZappLoader(40.dp, Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterWidth + 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    itemsIndexed(list, key = { _, m -> m.id }) { i, m ->
                        val r = rated[m.id]?.rating
                        val me = if (i == 0) first else remember { FocusRequester() }
                        Box {
                            PosterCard(
                                meta = m,
                                modifier = Modifier.focusRequesterSafe(me),
                                onFocus = { lastCard = me },
                                onClick = {
                                    val next = when (r) {
                                        null -> Rating.Like
                                        Rating.Like -> Rating.Love
                                        Rating.Love -> Rating.Dislike
                                        Rating.Dislike -> null
                                    }
                                    mvm.rate(m, next)
                                },
                                showTitle = true,
                            )
                            if (r != null) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .padding(6.dp),
                                ) {
                                    RatingGlyph(r, filled = true, color = if (r == Rating.Love) Color(0xFFFF5F8F) else Color.White, size = 16.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
