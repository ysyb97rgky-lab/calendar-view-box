package com.calendarviewbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lays chips out left to right, wrapping onto up to [maxLines] lines. If they don't all fit,
 * the last line ends with [more], which is told how many were left out ("+3 more").
 */
@Composable
fun ChipFlow(
    count: Int,
    maxLines: Int,
    modifier: Modifier = Modifier,
    horizontalGap: Dp = 30.dp,
    verticalGap: Dp = 12.dp,
    more: @Composable (hidden: Int) -> Unit,
    item: @Composable (index: Int) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val loose = Constraints(maxWidth = maxWidth)

        val chips: List<Placeable> = subcompose("chips") {
            for (i in 0 until count) item(i)
        }.map { it.measure(loose) }

        // Works out which line each chip goes on, starting a new line when one doesn't fit.
        fun lineOf(items: List<Placeable>): List<Int> {
            val lines = ArrayList<Int>(items.size)
            var line = 0
            var x = 0
            items.forEach { p ->
                if (x > 0 && x + p.width > maxWidth) {
                    line++
                    x = 0
                }
                lines += line
                x += p.width + hGap
            }
            return lines
        }

        val lines = lineOf(chips)
        val lineCount = (lines.lastOrNull() ?: -1) + 1
        var shown = chips.size
        var indicator: Placeable? = null

        if (lineCount > maxLines) {
            // Keep the chips that fit on the allowed lines, then make room for "+N more".
            shown = lines.indexOfFirst { it >= maxLines }.let { if (it < 0) chips.size else it }
            while (shown >= 0) {
                val candidate = subcompose("more_$shown") { more(chips.size - shown) }
                    .first().measure(loose)
                val placed = chips.take(shown) + candidate
                val withIndicator = lineOf(placed)
                if ((withIndicator.lastOrNull() ?: 0) < maxLines) {
                    indicator = candidate
                    break
                }
                shown--
            }
            if (shown < 0) shown = 0
        }

        val toPlace = chips.take(shown) + listOfNotNull(indicator)
        val placedLines = lineOf(toPlace)
        val lineHeights = IntArray((placedLines.lastOrNull() ?: -1) + 1)
        toPlace.forEachIndexed { i, p -> lineHeights[placedLines[i]] = maxOf(lineHeights[placedLines[i]], p.height) }
        val totalHeight = lineHeights.sum() + vGap * (lineHeights.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else toPlace.sumOf { it.width }
        val height = totalHeight.coerceIn(constraints.minHeight, if (constraints.hasBoundedHeight) constraints.maxHeight else totalHeight)

        layout(width, height) {
            var x = 0
            var y = 0
            var currentLine = 0
            toPlace.forEachIndexed { i, p ->
                if (placedLines[i] != currentLine) {
                    y += lineHeights[currentLine] + vGap
                    currentLine = placedLines[i]
                    x = 0
                }
                // Centre each chip vertically within its line.
                p.placeRelative(x, y + (lineHeights[currentLine] - p.height) / 2)
                x += p.width + hGap
            }
        }
    }
}
