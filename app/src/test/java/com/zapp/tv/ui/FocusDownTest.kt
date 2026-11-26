package com.zapp.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class FocusDownTest {
    @get:Rule val rule = createComposeRule()

    private val focused = mutableStateOf("none")

    @Composable
    private fun Rows(useRowScrolling: Boolean, ring: Boolean, trap: Boolean, first: FocusRequester) {
        PivotScrolling(260.dp) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().fadeAbove(250.dp).then(if (trap) Modifier.trapVertical(up = null) else Modifier),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 254.dp, bottom = 270.dp),
            ) {
                items(6) { r ->
                    Column {
                        Text("Row $r")
                        val extra = 232.dp
                        val content: @Composable () -> Unit = {
                            LazyRow(
                                modifier = Modifier.extendLeft(extra).fadeLeftOf(extra + 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 48.dp + extra, end = 48.dp),
                            ) {
                                items(20) { c ->
                                    FocusSurface(
                                        onClick = {},
                                        modifier = Modifier.width(208.dp).aspectRatio(1.9f),
                                        focusRequester = if (r == 0 && c == 0) first else null,
                                        onFocusChange = { if (it) focused.value = "$r/$c" },
                                        focusScale = 1f,
                                        ring = ring,
                                    ) { Text("$r/$c") }
                                }
                            }
                        }
                        if (useRowScrolling) RowScrolling(48.dp + extra, content = content) else PivotScrolling(48.dp + extra, content = content)
                    }
                }
            }
        }
    }

    private fun run(useRowScrolling: Boolean, ring: Boolean, trap: Boolean): String {
        val first = FocusRequester()
        rule.setContent { Rows(useRowScrolling, ring, trap, first) }
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        val start = focused.value
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        val afterDown = focused.value
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        return "start=$start down=$afterDown then=${focused.value}"
    }

    /** Down from a row's first card lands on the next row; after moving along, Down still goes down. */
    @Test fun downGoesToTheNextRow() {
        val r = run(useRowScrolling = true, ring = true, trap = true)
        assertEquals("start=0/0 down=1/0 then=2/1", r)
    }
}
