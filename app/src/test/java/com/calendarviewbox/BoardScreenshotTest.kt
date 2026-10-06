package com.calendarviewbox

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.calendarviewbox.data.CalendarMode
import com.calendarviewbox.ui.InkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime

/**
 * Saves pictures of the board at a few screen sizes (the Boox's size depends on its display
 * scaling), so layout problems can be seen before a release. Saved to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val helper = BoardRenderTest()

    private fun shoot(name: String, mode: CalendarMode = CalendarMode.WEEK) {
        val state = helper.sampleState().copy(mode = mode, now = LocalTime.of(13, 30), offlineSinceMillis = null)
        compose.setContent { InkTheme { helper.TestBoard(state) } }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1600dp-h1200dp-land-mdpi")
    fun week1600() = shoot("week-1600")

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h960dp-land-mdpi")
    fun week1280() = shoot("week-1280")

    @Test
    @Config(sdk = [34], qualifiers = "w1067dp-h800dp-land-mdpi")
    fun week1067() = shoot("week-1067")

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h960dp-land-mdpi")
    fun month1280() = shoot("month-1280", CalendarMode.MONTH)

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h960dp-land-mdpi")
    fun twoWeeks1280() = shoot("two-weeks-1280", CalendarMode.TWO_WEEKS)
}
