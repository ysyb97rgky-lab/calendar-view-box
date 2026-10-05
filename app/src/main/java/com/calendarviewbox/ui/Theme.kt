package com.calendarviewbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** E-ink panels show 16 grey levels. These four plus white stay distinct. */
object Ink {
    val Black = Color(0xFF000000)
    val DarkGrey = Color(0xFF3A3A3A)
    val Grey = Color(0xFF808080)
    val LightGrey = Color(0xFFB8B8B8)
    val White = Color(0xFFFFFFFF)
}

/** How one calendar's events are marked, since colour isn't available. */
data class EventStyle(val fill: Color, val border: Color?, val text: Color)

// Lightest first, so the first calendars don't turn the screen heavy and black.
val eventStyles = listOf(
    EventStyle(fill = Ink.White, border = Ink.Black, text = Ink.Black),     // outlined
    EventStyle(fill = Ink.LightGrey, border = null, text = Ink.Black),      // light grey
    EventStyle(fill = Ink.Black, border = null, text = Ink.White),          // solid black
    EventStyle(fill = Ink.DarkGrey, border = null, text = Ink.White),       // dark grey
)

fun styleOf(index: Int?): EventStyle = eventStyles[(index ?: 0).mod(eventStyles.size)]

@Composable
fun InkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.Black,
            onPrimary = Ink.White,
            secondary = Ink.DarkGrey,
            onSecondary = Ink.White,
            background = Ink.White,
            onBackground = Ink.Black,
            surface = Ink.White,
            onSurface = Ink.Black,
            surfaceVariant = Ink.White,
            onSurfaceVariant = Ink.DarkGrey,
            outline = Ink.Black,
        ),
    ) {
        // Material3's default text style pins line height at 24sp, which clips the larger
        // text on this board. Let each font size use its natural line height instead.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
            content = content,
        )
    }
}

@Composable
fun Marker(style: EventStyle, size: Dp) {
    val shape = RoundedCornerShape(3.dp)
    var m = Modifier.size(size).background(style.fill, shape)
    if (style.border != null) m = m.border(2.dp, style.border, shape)
    Box(m)
}

/** Flat button with no ripple, since animations smear on e-ink. */
@Composable
fun InkButton(label: String, selected: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .border(2.dp, Ink.Black, shape)
            .background(if (selected) Ink.Black else Ink.White, shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 18.dp, vertical = if (small) 6.dp else 10.dp)
    ) {
        Text(
            label,
            fontSize = if (small) 17.sp else 20.sp,
            color = if (selected) Ink.White else Ink.Black,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private val clock24 = DateTimeFormatter.ofPattern("HH:mm")
private val clock12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "9:41 am" or "09:41". */
fun formatClock(t: LocalTime, use24h: Boolean): String =
    if (use24h) t.format(clock24) else t.format(clock12).lowercase(Locale.ENGLISH)

/** Compact event time: "9am", "9:30am" or "9:30". */
fun formatShortTime(t: LocalTime, use24h: Boolean): String {
    if (use24h) return t.format(DateTimeFormatter.ofPattern("H:mm"))
    val pattern = if (t.minute == 0) "ha" else "h:mma"
    return t.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).lowercase(Locale.ENGLISH)
}
