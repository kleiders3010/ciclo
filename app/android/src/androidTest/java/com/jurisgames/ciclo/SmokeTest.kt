package com.jurisgames.ciclo

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jurisgames.ciclo.ui.ownJournal
import com.jurisgames.ciclo.ui.today
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Headless emulator smoke test: the whole stack (Kotlin UI → UniFFI → Rust engine), then a reminder is set. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun logAPeriod() {
        val node = (rule.activity.application as TasksApplication).node
        if (ownJournal(node, node.lists()) == null) {
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("firstStart")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("firstStart").performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("flow3")).fetchSemanticsNodes().isNotEmpty() }
        val j = ownJournal(node, node.lists())!!
        rule.onNodeWithTag("flow3").performClick()
        rule.waitUntil(5_000) { node.cycleDay(j.id, today()).flow == 3L }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("nextPeriod")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("flow0").performClick() // leave the emulator's journal as it was
        rule.waitUntil(5_000) { node.cycleDay(j.id, today()).flow == 0L }
        // reminders compute and set an alarm without failing
        check(Reminders.schedule(rule.activity).isSuccess) { "reminders scheduled" }
        // iroh's DNS resolver and network monitor can reach Android (kt-ffi src/android.rs).
        check(com.jurisgames.ciclo.core.androidContextReady()) { "Android context registered" }
    }
}
