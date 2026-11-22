package com.jurisgames.ciclo.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.NodeOptions
import com.jurisgames.ciclo.core.Platform
import com.jurisgames.ciclo.ui.CicloApp
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * A lived-in journal on a phone-sized screen: Today, Calendar, Insights and the day log all draw and show what was
 * logged. With -Pshots=<folder> it also saves the pictures for a review (and -Pstore for the Play size).
 */
@OptIn(ExperimentalTestApi::class)
class PhoneShotsTest {
    private val store = System.getProperty("kt.shots.store") == "true"
    private val out = System.getProperty("kt.shots")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    @Test
    fun phone() = runDesktopComposeUiTest(width = if (store) 1080 else 412, height = if (store) 1920 else 860) {
        val home = Files.createTempDirectory("kt-phone").toFile()
        val n = KtNode.open(File(home, "identity").path, File(home, "data").path, System.getProperty("kt.worker"),
            NodeOptions(Platform.DESKTOP, listOf("ws://127.0.0.1:9"), false, false, "127.0.0.1:0", "", ""))
        val l = n.createList(null, "Mi ciclo".takeIf { System.getProperty("kt.lang") == "es" } ?: "My cycle")
        val today = LocalDate.now()
        // four finished cycles of 27-30 days and the current one, cramps on days 1-2, acne before each period
        val starts = listOf(117L, 89L, 61L, 32L, 4L).map { today.minusDays(it) }
        for (s in starts) {
            n.cycleLogPeriod(l.id, s.toString(), 5)
            n.cycleSetTag(l.id, s.toString(), "pain.cramps", true)
            n.cycleSetTag(l.id, s.plusDays(1).toString(), "pain.cramps", true)
            n.cycleSetTag(l.id, s.minusDays(2).toString(), "skin.acne", true)
        }
        n.cycleSetFlow(l.id, today.toString(), 0)
        for (k in 0L..3L) n.cycleSetTemp(l.id, today.minusDays(k).toString(), 3640 + (k * 7 % 20))
        n.cycleSetTag(l.id, today.toString(), "feel.happy", true)
        n.cycleSetTag(l.id, today.toString(), "energy.high", true)
        n.cycleSetNote(l.id, today.toString(), "Long walk after work")
        setContent {
            if (store) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2.625f)) { CicloApp(n) }
            else CicloApp(n)
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("headline"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(onAllNodes(hasText("Day 5 of your cycle", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() || System.getProperty("kt.lang") == "es") { "Today shows the cycle day" }
        check(onAllNodes(hasTestTag("notContraception"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "Today says predictions are not birth control" }
        fun shot(name: String) {
            val o = out ?: return
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val count = roots.fetchSemanticsNodes().size
            val base = roots[0].captureToImage().toAwtImage()
            val img = java.awt.image.BufferedImage(base.getWidth(null), base.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.drawImage(base, 0, 0, null)
            for (i in 1 until count) {
                val top = roots[i].captureToImage().toAwtImage()
                g.color = java.awt.Color(0, 0, 0, 90)
                g.fillRect(0, 0, img.width, img.height)
                g.drawImage(top, (img.width - top.getWidth(null)) / 2, (img.height - top.getHeight(null)) / 2, null)
            }
            g.dispose()
            javax.imageio.ImageIO.write(img, "png", File(o, (if (store) "store-" else "phone-") + "$name.png"))
        }
        shot("1-today")
        onNodeWithTag("tabCalendar").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("legend"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(onAllNodes(hasTestTag("day-" + today), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "the calendar shows this month" }
        shot("2-calendar")
        onNodeWithTag("tabInsights").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("cycleBars"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(onAllNodes(hasTestTag("pattern-pain.cramps"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "the cramps pattern shows" }
        shot("3-insights")
        onNodeWithTag("tabToday").performClick()
        onNodeWithTag("logToday").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("logDate"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(onAllNodes(hasTestTag("tag-feel.happy"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "the day log lists the tags" }
        shot("4-log")
        onNodeWithTag("tag-pain.headache").performClick()
        waitUntil(timeoutMillis = 5_000) { n.cycleDay(l.id, today.toString()).tags.contains("pain.headache") }
        onNodeWithTag("logDone").performClick()
        n.close()
    }
}
