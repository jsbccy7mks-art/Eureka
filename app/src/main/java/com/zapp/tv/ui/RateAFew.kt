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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    val films by produceState<List<com.zapp.tv.addons.MetaPreview>?>(null) { value = mvm.popularToRate() }
    val first = remember { FocusRequester() }
    LaunchedEffect(films) {
        kotlinx.coroutines.delay(150)
        first.tryRequest()
    }
    val rated = mvm.ratings.byId
    Box(Modifier.fillMaxSize().trapFocus().background(ZappColors.Bg)) {
        AmbientGlow(0.7f)
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rate a few films", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("OK: like, love, dislike, or clear. The more you rate, the better For you gets.", color = ZappColors.Dim, fontSize = 13.sp)
                }
                Text("${films.orEmpty().count { rated[it.id] != null }} rated", color = ZappColors.Faint, fontSize = 13.sp)
                Spacer(Modifier.padding(start = 16.dp))
                ZButton("Done", onDone, style = ButtonStyle.Primary)
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
                        Box {
                            PosterCard(
                                meta = m,
                                modifier = if (i == 0) Modifier.focusRequesterSafe(first) else Modifier,
                                onFocus = {},
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
