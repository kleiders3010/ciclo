package com.jurisgames.ciclo.desktop

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.NodeOptions
import com.jurisgames.ciclo.core.Platform
import com.jurisgames.ciclo.ui.CicloApp
import com.jurisgames.ciclo.ui.today
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * A partner's summary on two devices: Ana keeps a journal and shares a summary with Sam. Sam's device gets only
 * what Ana ticked (never her notes or symptoms, and never the journal itself), and it follows her changes. Two
 * nodes and a local loopback relay; nothing leaves this machine.
 */
class DesktopPartnerTest {
    @get:Rule
    val rule = createComposeRule()

    private val port = 18800 + (ProcessHandle.current().pid() % 300).toInt()
    private val relay: Process = ProcessBuilder(System.getProperty("kt.cli"), "relay", "--port", port.toString()).redirectErrorStream(true).start().also {
        it.inputStream.bufferedReader().readLine()
    }
    private val nodes = mutableListOf<KtNode>()

    @After
    fun stop() {
        nodes.forEach { runCatching { it.close() } }
        relay.destroyForcibly()
    }

    private fun node(tag: String): KtNode {
        val home = Files.createTempDirectory("kt-$tag").toFile()
        val options = NodeOptions(Platform.DESKTOP, listOf("ws://127.0.0.1:$port"), false, false, "127.0.0.1:0", tag, "")
        return KtNode.open(File(home, "identity").path, File(home, "data").path, System.getProperty("kt.worker"), options).also { nodes += it }
    }

    private fun until(what: String, seconds: Int = 180, ok: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!ok()) {
            check(System.currentTimeMillis() < deadline) { what }
            nodes.forEach { it.syncNow() }
        }
    }

    @Test
    fun partnerSeesOnlyTheSummary() {
        val a = node("ana")
        val b = node("sam")
        a.setDisplayName("Ana")
        b.setDisplayName("Sam")
        val j = a.createList(null, "My cycle")
        val t = LocalDate.now()
        a.cycleLogPeriod(j.id, t.minusDays(30).toString(), 5)
        a.cycleLogPeriod(j.id, t.minusDays(2).toString(), 3)
        a.cycleSetTag(j.id, today(), "feel.happy", true)
        a.cycleSetTag(j.id, today(), "pain.cramps", true)
        a.cycleSetNote(j.id, today(), "private note")
        a.partnerSetShares(j.id, listOf("period"))
        val p = a.createList(null, "Ana's cycle")
        a.partnerInit(p.id, j.id)
        val shared = a.shareList(p.id)
        val invite = a.invite(shared.groupId, 7u)
        a.syncNow()
        b.joinCode(invite.link)
        fun bList() = b.lists().find { runCatching { b.partnerIsView(it.id) }.getOrDefault(false) }
        until("Sam never got the summary") { bList()?.let { runCatching { b.partnerView(it.id).cycleDay }.getOrDefault(-1) } == 3L }
        val bl = bList()!!
        val v = b.partnerView(bl.id)
        check(v.name == "Ana" && v.nextPeriod.isNotEmpty()) { "the chosen items arrive: $v" }
        check(v.feel.isEmpty() && v.fertileFrom.isEmpty()) { "nothing that wasn't ticked: $v" }
        check(b.lists().size == 1) { "Sam's device holds the summary only, never the journal: ${b.lists().map { it.name }}" }
        check(runCatching { b.cycleDays(bl.id) }.getOrDefault(emptyList()).isEmpty()) { "no day of the journal is in the summary" }

        // Ana ticks "how I feel": Sam gets today's feelings, still without the cramps
        a.partnerSetShares(j.id, listOf("period", "feel"))
        until("the change never reached Sam") { b.partnerView(bl.id).feel == listOf("feel.happy") }

        // Sam's screen: no journal of his own, so the summary itself is the home screen (UX review U-C1)
        rule.setContent { CicloApp(b) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("partnerCard-" + bl.name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasTestTag("partnerView"), useUnmergedTree = true).fetchSemanticsNodes().let { check(it.size == 1) { "the summary is on the home screen" } }
        rule.onAllNodes(hasTestTag("partnerHomeStart"), useUnmergedTree = true).fetchSemanticsNodes().let { check(it.size == 1) { "a way to track his own cycle too" } }

        // the summary is counted on to today when Ana's phone hasn't written one since (UX review U-C2)
        val t0 = LocalDate.now()
        val old = v.copy(at = t0.minusDays(5).toString(), cycleDay = 3L, lateDays = 0L, nextPeriod = t0.minusDays(2).toString(),
            expected = t0.minusDays(2).toString(), lateFrom = t0.minusDays(1).toString())
        val moved = com.jurisgames.ciclo.ui.partnerNow(old)
        check(moved.cycleDay == 8L) { "day 3 five days ago is day 8 today: $moved" }
        check(moved.lateDays == 2L) { "expected two days ago, past the usual variation: 2 days late: $moved" }
        check(com.jurisgames.ciclo.ui.partnerNow(old.copy(lateFrom = t0.plusDays(2).toString())).lateDays == 0L) { "within the usual variation: not late" }
        check(com.jurisgames.ciclo.ui.partnerNow(old.copy(lateDays = 1L)).lateDays == 6L) { "late on the day it was written keeps counting" }
    }
}
