package com.calendarviewbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendarviewbox.BoardState
import com.calendarviewbox.data.Sky
import com.calendarviewbox.data.describeWeather
import com.calendarviewbox.data.skyOf
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val weekdayShort = DateTimeFormatter.ofPattern("EEE")

/** Weather for the header strip: today's icon and temperatures, then the next four days. */
@Composable
fun WeatherHeader(state: BoardState) {
    val weather = state.weather
    val today = weather?.days?.firstOrNull { it.date == state.today }
    if (weather == null || today == null) {
        state.weatherError?.let { Text(it, fontSize = 18.sp, color = Ink.DarkGrey) }
        return
    }
    val currentFresh = System.currentTimeMillis() - weather.fetchedAt < 3 * 60 * 60 * 1000L
    val nowTemp = weather.currentTemp?.takeIf { currentFresh }

    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(today.code, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${today.max}°", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
                Text(" / ${today.min}°", fontSize = 26.sp, color = Ink.DarkGrey)
                if (nowTemp != null) {
                    Spacer(Modifier.width(12.dp))
                    Text("Now $nowTemp°", fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            val rain = today.rainChance?.takeIf { it >= 10 }?.let { ", $it% chance of rain" } ?: ""
            Text(
                describeWeather(today.code) + rain,
                fontSize = 19.sp,
                color = Ink.DarkGrey,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            weather.days.filter { it.date.isAfter(state.today) }.take(4).forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.date.format(weekdayShort), fontSize = 16.sp, color = Ink.DarkGrey)
                    WeatherIcon(day.code, 30.dp)
                    Text("${day.max}° ${day.min}°", fontSize = 16.sp, color = Ink.Black)
                    day.rainChance?.takeIf { it >= 30 }?.let {
                        Text("$it%", fontSize = 14.sp, color = Ink.DarkGrey)
                    }
                }
            }
        }
    }
}

/** Simple line-and-fill icons that read well in greyscale. */
@Composable
fun WeatherIcon(code: Int, size: Dp) {
    val sky = skyOf(code)
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.07f
        when (sky) {
            Sky.SUN -> sun(s * 0.5f, s * 0.5f, s * 0.2f, stroke)
            Sky.PARTLY -> {
                sun(s * 0.36f, s * 0.36f, s * 0.15f, stroke)
                cloud(Ink.Grey, s, dx = s * 0.12f, dy = s * 0.1f, scale = 0.85f)
            }
            Sky.CLOUD -> cloud(Ink.Grey, s, dy = -s * 0.05f)
            Sky.FOG -> {
                for (i in 0..2) {
                    val y = s * (0.35f + i * 0.15f)
                    drawLine(Ink.Black, Offset(s * 0.15f, y), Offset(s * 0.85f, y), stroke, StrokeCap.Round)
                }
            }
            Sky.RAIN -> {
                cloud(Ink.Grey, s, dy = -s * 0.15f)
                for (i in 0..2) {
                    val x = s * (0.34f + i * 0.17f)
                    drawLine(Ink.Black, Offset(x, s * 0.72f), Offset(x - s * 0.06f, s * 0.9f), stroke, StrokeCap.Round)
                }
            }
            Sky.STORM -> {
                cloud(Ink.DarkGrey, s, dy = -s * 0.15f)
                val bolt = Path().apply {
                    moveTo(s * 0.54f, s * 0.56f)
                    lineTo(s * 0.40f, s * 0.78f)
                    lineTo(s * 0.50f, s * 0.78f)
                    lineTo(s * 0.44f, s * 0.96f)
                    lineTo(s * 0.64f, s * 0.70f)
                    lineTo(s * 0.54f, s * 0.70f)
                    close()
                }
                drawPath(bolt, Ink.Black)
            }
            Sky.SNOW -> {
                cloud(Ink.Grey, s, dy = -s * 0.15f)
                listOf(0.34f to 0.76f, 0.52f to 0.84f, 0.68f to 0.76f).forEach { (x, y) ->
                    drawCircle(Ink.Black, s * 0.045f, Offset(s * x, s * y))
                }
            }
        }
    }
}

private fun DrawScope.sun(cx: Float, cy: Float, r: Float, stroke: Float) {
    drawCircle(Ink.Black, r, Offset(cx, cy), style = Stroke(stroke))
    for (i in 0 until 8) {
        val a = i * PI / 4
        val c = cos(a).toFloat()
        val sn = sin(a).toFloat()
        drawLine(
            Ink.Black,
            Offset(cx + c * r * 1.45f, cy + sn * r * 1.45f),
            Offset(cx + c * r * 1.95f, cy + sn * r * 1.95f),
            stroke,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.cloud(color: Color, s: Float, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) {
    val k = s * scale
    drawCircle(color, 0.16f * k, Offset(dx + 0.32f * k, dy + 0.58f * k))
    drawCircle(color, 0.22f * k, Offset(dx + 0.52f * k, dy + 0.46f * k))
    drawCircle(color, 0.15f * k, Offset(dx + 0.72f * k, dy + 0.60f * k))
    drawRect(color, Offset(dx + 0.32f * k, dy + 0.58f * k), Size(0.40f * k, 0.17f * k))
}
