package com.zapp.tv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Axon's design rules, so every screen looks like one app: a small set of text sizes, spacing
 * steps and corner shapes. Screens use these rather than their own numbers.
 */
object TextSize {
    /** Tiny tags and badges. */
    val Tag = 9.5.sp
    /** Small capitals headings, labels, times. */
    val Label = 10.5.sp
    /** Secondary lines: details under a title, hints. */
    val Small = 12.sp
    /** Most text: rows, buttons, menus. */
    val Body = 13.5.sp
    /** Headings within a page. */
    val Heading = 15.sp
    /** Page and dialog titles. */
    val Title = 20.sp
    /** Large titles. */
    val Page = 24.sp
    /** The biggest, for heroes. */
    val Hero = 30.sp
}

object Space {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

object Shapes {
    /** Rows, menu items, small panels. */
    val Row = RoundedCornerShape(10.dp)
    /** Panels, dialogs, sheets. */
    val Panel = RoundedCornerShape(16.dp)
    /** Buttons and chips. */
    val Pill = RoundedCornerShape(50)
}
