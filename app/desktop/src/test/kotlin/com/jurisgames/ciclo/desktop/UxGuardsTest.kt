package com.jurisgames.ciclo.desktop

import java.io.File
import org.junit.Test

/** Source guards for the cold UX review rules (APP-RULES 136-146). */
class UxGuardsTest {
    /** APP-RULES 137: what an exception says reaches the screen only through userError (the pattern is built, rule 130). */
    @Test
    fun errorsReachTheScreenOnlyThroughUserError() {
        val raw = Regex("\\b(e|it|err)\\." + "mess" + "age\\b")
        val roots = listOf("../shared/src", "../android/src/main/java").map(::File)
        val found = mutableListOf<String>()
        var files = 0
        for (root in roots) root.walkTopDown().filter { it.extension == "kt" && it.name !in setOf("Strings.kt", "kt_ffi.kt", "Measure.kt") }.forEach { f ->
            files++
            // A log line (stderr, Android log) is not the screen.
            f.readLines().forEachIndexed { i, line -> if (raw.containsMatchIn(line) && "System.err" !in line && "Log." !in line) found += "${f.name}:${i + 1}: ${line.trim()}" }
        }
        check(files > 15) { "scanned only $files files: is the scan looking in the right folders?" }
        check(found.isEmpty()) { "raw exception text on screen:\n" + found.joinToString("\n") }
    }

    /** UX review U-C6: [com.jurisgames.ciclo.ui.tr] reads the shared Spanish first, so a cycle word that the shared
     *  map also defines with another meaning is never shown. Each such word needs its own lookup (Words.flowLabel). */
    @Test
    fun cycleWordsAreNotHiddenBySharedWords() {
        val own = setOf("Light") // the flow word, shown through Words.flowLabel
        val clash = com.jurisgames.ciclo.ui.ES.keys.filter { k ->
            k !in own && com.jurisgames.ciclo.ui.ES_CYCLE[k]?.let { it != com.jurisgames.ciclo.ui.ES[k] } == true
        }
        check(clash.isEmpty()) { "Spanish words that ES hides in ES_CYCLE: $clash" }
        check(com.jurisgames.ciclo.ui.ES["Light"] != com.jurisgames.ciclo.ui.ES_CYCLE["Light"]) { "the exception list is stale: remove Light from it" }
    }

    /** UX review U-C3: every calendar day tells a screen reader its date and marks, not only the day number. */
    @Test
    fun calendarDaysAreDescribed() {
        val src = File("../shared/src/com/jurisgames/ciclo/ui/Cycle.kt").readText()
        val cell = src.substringAfter("private fun CalCell(").substringBefore("\n@Composable")
        val grid = src.substringAfter("private fun CalendarView(").substringBefore("\n@Composable")
        check("contentDescription = desc" in grid && "dayDescription(c, c.date == day)" in grid) { "calendar days lost their spoken description" }
        check("BorderStroke(2.dp" !in cell) { "a calendar mark that is only a coloured ring is back (use Modifier.mark)" }
    }

    /** UX review U-C7: Ciclo keeps a journal and summaries. No text it shows (English or Spanish) speaks of lists or
     *  of Tasks, the app the shared core came from. Scans every tr("…") in Ciclo's own code and CORE_WORDS. */
    @Test
    fun noListWordsOnCicloScreens() {
        val lit = Regex("tr\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val roots = listOf("../shared/src/com/jurisgames/ciclo/ui", "../android/src/main/java", "src/main/kotlin").map(::File)
        val keys = roots.flatMap { r -> r.walk().filter { it.isFile && it.extension == "kt" && it.name != "Strings.kt" && it.name != "EsCycle.kt" }.toList() }
            .flatMap { f -> lit.findAll(f.readText()).map { it.groupValues[1].replace("\\\"", "\"") }.toList() }
            .plus(com.jurisgames.ciclo.ui.CORE_WORDS.values).toSet()
        check(keys.size > 100) { "found only ${keys.size} texts: is the scan looking in the right folders?" }
        val bad = Regex("\\b(lists?|listas?|Tasks)\\b")
        fun words(s: String) = s.replace(Regex("\\{[^}]*\\}"), "")
        val found = keys.flatMap { k ->
            val es = com.jurisgames.ciclo.ui.ES[k] ?: com.jurisgames.ciclo.ui.ES_CYCLE[k] ?: ""
            listOfNotNull(k.takeIf { bad.containsMatchIn(words(it)) }, es.takeIf { bad.containsMatchIn(words(it)) })
        }
        check(found.isEmpty()) { "list/Tasks words on Ciclo screens:\n" + found.joinToString("\n") }
    }
}
