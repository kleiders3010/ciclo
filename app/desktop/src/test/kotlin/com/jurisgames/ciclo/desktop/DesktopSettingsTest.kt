package com.jurisgames.ciclo.desktop

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.NodeOptions
import com.jurisgames.ciclo.core.Platform
import com.jurisgames.ciclo.ui.CicloApp
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The settings screen shows exactly this platform's settings and keeps what's saved. */
class DesktopSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun node(home: File, platform: Platform): KtNode {
        val options = NodeOptions(platform, listOf("ws://127.0.0.1:9"), false, false, "127.0.0.1:0", "", "")
        return KtNode.open(File(home, "identity").path, File(home, "data").path, System.getProperty("kt.worker"), options)
    }

    private fun shown(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun desktopSettingsAndTheyStick() {
        val home = Files.createTempDirectory("kt-settings").toFile()
        val n = node(home, Platform.DESKTOP)
        rule.setContent { CicloApp(n) }
        rule.onNodeWithTag("settingsOpen").performClick()
        rule.waitUntil(5_000) { shown("set-background") }
        rule.onNodeWithTag("advancedOpen").performClick() // network help is folded away (U-T11)
        rule.waitUntil(5_000) { shown("help-off") }
        check(shown("set-relay")) { "relay setting on desktop (D51)" }
        check(shown("set-autostart"))
        check(!shown("set-notify")) { "the change feed follows tasks only, so Ciclo has no such switch (UX review U-C19)" }
        check(shown("help-on") && shown("help-off") && !shown("help-wifi_and_charging"))
        rule.onNodeWithTag("set-background").performScrollTo().performClick()
        rule.onNodeWithTag("help-off").performScrollTo().performClick()
        rule.onNodeWithTag("settingsSave").performClick()
        rule.waitUntil(5_000) { !shown("settingsSave") }
        n.close()
        val again = node(home, Platform.DESKTOP).settings()
        check(!again.syncInBackground && again.helpOthers == "off" && again.relayForOthers == true) { again.toString() }
    }

    @Test
    fun phonesHaveNoRelayOrAutostartSetting() {
        val n = node(Files.createTempDirectory("kt-settings-phone").toFile(), Platform.PHONE)
        rule.setContent { CicloApp(n) }
        rule.onNodeWithTag("settingsOpen").performClick()
        rule.waitUntil(5_000) { shown("set-background") }
        rule.onNodeWithTag("advancedOpen").performClick() // network help is folded away (U-T11)
        rule.waitUntil(5_000) { shown("help-off") }
        check(!shown("set-relay")) { "not shown at all on phones (D51)" }
        check(!shown("set-autostart"))
        check(shown("help-wifi_and_charging") && shown("help-wifi_while_open") && shown("help-off") && !shown("help-on"))
        val s = n.settings()
        check(runCatching { n.setSettings(s.copy(relayForOthers = true)) }.isFailure) { "refused on phones" }
    }
}
