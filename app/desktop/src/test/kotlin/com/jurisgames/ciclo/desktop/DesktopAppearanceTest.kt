package com.jurisgames.ciclo.desktop

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.NodeOptions
import com.jurisgames.ciclo.core.PersonItem
import com.jurisgames.ciclo.core.Platform
import com.jurisgames.ciclo.core.accentColor
import com.jurisgames.ciclo.ui.CicloApp
import com.jurisgames.ciclo.ui.personLabel
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Appearance (DESIGN §2): list color and icon in the list's data, themes per device, avatar tags. */
class DesktopAppearanceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shown(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun themeIsPerDevice() {
        val home = Files.createTempDirectory("kt-look").toFile()
        val options = NodeOptions(Platform.DESKTOP, listOf("ws://127.0.0.1:9"), false, false, "127.0.0.1:0", "", "")
        val n = KtNode.open(File(home, "identity").path, File(home, "data").path, System.getProperty("kt.worker"), options)
        // Ciclo has no lists (the list-style half of this test came from Sobres): the theme half stays.
        rule.setContent { CicloApp(n) }
        // Themes are per device and decide the tone: same hue, different color.
        val before = accentColor("default", 130.0, 2u.toUByte(), false)
        check(before != accentColor("default", 130.0, 2u.toUByte(), true)) { "dark mode has its own tone" }
        rule.waitUntil(5_000) { shown("settingsOpen") }
        rule.onNodeWithTag("settingsOpen").performClick()
        rule.waitUntil(5_000) { shown("theme-ocean") }
        check(!shown("set-dynamic")) { "no wallpaper colors on desktop" }
        rule.onNodeWithTag("theme-ocean").performClick()
        rule.onNodeWithTag("settingsSave").performClick()
        rule.waitUntil(5_000) { !shown("settingsSave") }
        check(n.settings().theme == "ocean")
        n.close()
    }

    @Test
    fun sameNamesGetATag() {
        val a = PersonItem("Alex", false, "A", 10.0, "4F2A")
        val b = PersonItem("Alex", false, "A", 200.0, "91C0")
        val c = PersonItem("Sam", true, "S", 40.0, "0B11")
        val all = listOf(a, b, c)
        check(personLabel(a, all) == "Alex · 4F2A" && personLabel(b, all) == "Alex · 91C0")
        check(personLabel(c, all) == "Sam (you)")
    }
}
