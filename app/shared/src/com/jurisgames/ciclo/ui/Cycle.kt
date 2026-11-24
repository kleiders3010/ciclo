package com.jurisgames.ciclo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jurisgames.ciclo.core.CalItem
import com.jurisgames.ciclo.core.CycleDay
import com.jurisgames.ciclo.core.CycleSettings
import com.jurisgames.ciclo.core.ListItem
import com.jurisgames.ciclo.core.OutlookView
import com.jurisgames.ciclo.core.PatternItem
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

// ---- colors (status-like meanings, each with a second cue: filled / outlined / ring / dot, and a legend) ----

private val PeriodColor = Color(0xFFD1435B)
private val PeriodDark = Color(0xFFF28B9B)
private val FertileColor = Color(0xFF138A80)
private val FertileDark = Color(0xFF6FD3C7)
private val PmsColor = Color(0xFF7B5EA7)
private val PmsDark = Color(0xFFC3A9EC)

@Composable fun periodColor() = if (LocalLook.current.dark) PeriodDark else PeriodColor
@Composable fun fertileColor() = if (LocalLook.current.dark) FertileDark else FertileColor
@Composable fun pmsColor() = if (LocalLook.current.dark) PmsDark else PmsColor
/** For words (UX review U-C16): at least 4.5:1 on the light surface; the dark-mode tones already are. */
@Composable fun periodText() = if (LocalLook.current.dark) PeriodDark else Color(0xFFB3304A)
@Composable fun fertileText() = if (LocalLook.current.dark) FertileDark else Color(0xFF0E6F67)
/** The day number on a period fill: white on the light-mode red, dark on the light dark-mode pink (U-C16). */
@Composable fun onPeriodFill() = if (LocalLook.current.dark) Color(0xFF3A0010) else Color.White

fun today(): String = LocalDate.now().toString()

fun dayLabel(d: String): String {
    val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: return d
    val now = LocalDate.now()
    return when (date) {
        now -> tr("Today")
        now.minusDays(1) -> tr("Yesterday")
        now.plusDays(1) -> tr("Tomorrow")
        else -> shortName(DateTimeFormatter.ofPattern(if (date.year == now.year) "EEE d MMM" else "d MMM yyyy", Lang.locale).format(date))
    }
}

fun shortDate(d: String): String = runCatching {
    shortName(DateTimeFormatter.ofPattern("d MMM", Lang.locale).format(LocalDate.parse(d)))
}.getOrDefault(d)

private fun monthLabel(m: YearMonth): String = DateTimeFormatter.ofPattern("MMMM yyyy", Lang.locale).format(m).replaceFirstChar { it.uppercase() }

internal fun daysBetween(a: String, b: String): Long = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)) }.getOrDefault(0)

// ---- units (this device's choice; the journal always holds °C and grams) ----

object Units {
    fun file(dir: String) = File(dir, "ui-units")
    fun read(dir: String): Pair<Boolean, Boolean> {
        val t = runCatching { file(dir).readText().trim() }.getOrNull()
        if (t != null && t.length == 2) return (t[0] == 'f') to (t[1] == 'l')
        val us = java.util.Locale.getDefault().country == "US"
        return us to us
    }
    fun write(dir: String, fahrenheit: Boolean, pounds: Boolean) = runCatching { file(dir).writeText((if (fahrenheit) "f" else "c") + (if (pounds) "l" else "k")) }
}

/** "1 day" or "{n} days" (UX review U-C20: never "1 días"). */
fun daysText(n: Long): String = if (n == 1L) tr("1 day") else tr("{n} days", "n" to n)

/** Numbers in the device's own style when it speaks Spanish (Argentina and Uruguay write 36,50), else the app's. */
val numberLocale: java.util.Locale get() = java.util.Locale.getDefault().takeIf { Lang.spanish && it.language == "es" } ?: Lang.locale

fun tempText(hundredths: Long, f: Boolean): String =
    if (f) "%.2f °F".format(numberLocale, hundredths / 100.0 * 9 / 5 + 32) else "%.2f °C".format(numberLocale, hundredths / 100.0)

fun parseTemp(text: String, f: Boolean): Long? {
    val v = text.replace(',', '.').filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
    val c = if (f) (v - 32) * 5 / 9 else v
    val h = Math.round(c * 100)
    return h.takeIf { it in 3000..4500 }
}

fun weightText(grams: Long, lb: Boolean): String =
    if (lb) "%.1f lb".format(numberLocale, grams / 453.59237) else "%.1f kg".format(numberLocale, grams / 1000.0)

fun parseWeight(text: String, lb: Boolean): Long? {
    val v = text.replace(',', '.').filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
    val g = Math.round(if (lb) v * 453.59237 else v * 1000)
    return g.takeIf { it in 1_000..500_000 }
}

// ---- the journal screen ----

enum class Tab(val label: String, val tag: String) { Today("Today", "tabToday"), Calendar("Calendar", "tabCalendar"), Insights("Insights", "tabInsights") }

/** The journal: Today, Calendar, Insights, and the day log over them. */
@Composable
fun CyclePane(env: Env, list: ListItem, onMenu: (() -> Unit)?, snackbar: SnackbarHostState, onSettings: () -> Unit, onPartner: () -> Unit) {
    val scope = rememberCoroutineScope()
    val node = env.node
    val day = today()
    val o = remember(node, env.version, list.id, day) { runCatching { node.cycleOutlook(list.id, day) }.getOrNull() } ?: return
    val set = remember(node, env.version, list.id) { runCatching { node.cycleSettings(list.id) }.getOrNull() } ?: return
    val custom = remember(node, env.version, list.id) { runCatching { node.cycleCustomTags(list.id) }.getOrDefault(emptyList()) }
    val customNames = custom.associate { it.id to it.name }
    var tab by remember(list.id) { mutableStateOf(Tab.Today) }
    env.backHandler?.invoke(tab != Tab.Today) { tab = Tab.Today }
    var exportAsk by remember { mutableStateOf(false) }
    var logDate by rememberSaveable { mutableStateOf<String?>(null) } // U-C11: still open after the phone turns
    androidx.compose.runtime.LaunchedEffect(KeyActions.logToday.intValue) {
        if (KeyActions.logToday.intValue > 0) { KeyActions.logToday.intValue = 0; logDate = today() }
    }
    var pastPeriod by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    var reportAsk by remember { mutableStateOf(false) }
    var reportParts by remember { mutableStateOf(REPORT_DEFAULT) }
    var reminders by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.testTag("menu")) { Icon(Ic.Menu, tr("Menu")) } else Spacer(Modifier.width(16.dp))
            Text(list.name, style = MaterialTheme.typography.headlineSmall, color = periodText(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("title"))
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("listMenu")) { Icon(Ic.More, tr("More")) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    @Composable
                    fun item(label: String, tag: String, act: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; act() }, modifier = Modifier.testTag(tag))
                    item(tr("Cycle settings"), "cycleSettings", onSettings)
                    item(tr("Add a past period"), "pastPeriod") { pastPeriod = true }
                    if (PhoneHooks.onRemindersChanged != null) item(tr("Reminders"), "reminders") { reminders = true }
                    item(tr("Share with a partner"), "partner", onPartner)
                    if (env.onPrint != null) item(tr("Report for a doctor"), "report") { reportAsk = true }
                    env.onExport?.let { item(tr("Export CSV"), "export") { exportAsk = true } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Tab.entries) FilterChip(tab == t, { tab = t }, label = { Text(tr(t.label)) }, modifier = Modifier.testTag(t.tag))
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Today -> TodayView(env, list, o, set, customNames, snackbar, onLog = { logDate = it }, onPastPeriod = { pastPeriod = true })
                Tab.Calendar -> CalendarView(env, list, day, onDay = { logDate = it })
                Tab.Insights -> InsightsView(env, list, o, set, customNames)
            }
        }
    }
    logDate?.let { d -> DayLogDialog(env, list, d, set, custom, onDate = { logDate = it }, onClose = { logDate = null }) }
    if (pastPeriod) PastPeriodDialog(o.avgPeriod, pickFirst = o.lastStart.isEmpty(), onClose = { pastPeriod = false }) { start, days ->
        val before = runCatching { node.cycleDays(list.id) }.getOrDefault(emptyList()).associate { it.date to it.flow }
        val s0 = LocalDate.parse(start)
        val touched = (0 until days).map { s0.plusDays(it).toString() }.map { it to (before[it] ?: 0L) }
        runCatching { node.cycleLogPeriod(list.id, start, days) }
        pastPeriod = false
        env.changed()
        scope.launch {
            val r = snackbar.showSnackbar(tr("Added a period from {date}, {days}", "date" to shortDate(start), "days" to daysText(days)), actionLabel = tr("Undo"), duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                for ((date, was) in touched) runCatching { node.cycleSetFlow(list.id, date, was) }
                env.changed()
            }
        }
    }
    if (reminders) RemindersDialog(env) { reminders = false }
    if (reportAsk) AlertDialog(
        onDismissRequest = { reportAsk = false },
        title = { Text(tr("Report for a doctor")) },
        text = {
            Column {
                Text(tr("What goes into the report:"), style = MaterialTheme.typography.bodyMedium)
                for ((k, label) in REPORT_PARTS) CheckRow(tr(label), k in reportParts, "reportPart-$k") { on -> reportParts = if (on) reportParts + k else reportParts - k }
            }
        },
        confirmButton = { TextButton(onClick = { reportAsk = false; report = true }, modifier = Modifier.testTag("reportGo")) { Text(tr("Print")) } },
        dismissButton = { TextButton(onClick = { reportAsk = false }) { Text(tr("Cancel")) } },
    )
    if (exportAsk) AlertDialog(
        onDismissRequest = { exportAsk = false },
        title = { Text(tr("Export CSV")) },
        text = { Text(tr("A spreadsheet with everything you logged, notes included, not encrypted. To move to a new phone, use Save a backup file in ☰.")) },
        confirmButton = { TextButton(onClick = { exportAsk = false; env.onExport?.invoke(list) }, modifier = Modifier.testTag("exportGo")) { Text(tr("Export")) } },
        dismissButton = { TextButton(onClick = { exportAsk = false }) { Text(tr("Cancel")) } },
    )
    if (report) androidx.compose.runtime.LaunchedEffect(Unit) {
        report = false
        val days = runCatching { node.cycleDays(list.id) }.getOrDefault(emptyList())
        env.onPrint?.invoke(tr("Cycle report"), reportHtml(o, set, days, customNames, Units.read(node.dataDir()).first, reportParts))
    }
}

/** The big line of the Today card. */
fun headline(o: OutlookView, set: CycleSettings, day: String): Pair<String, String> {
    if (set.mode == "pregnant") {
        return if (o.pregWeek >= 0) tr("Week {w}, day {d}", "w" to o.pregWeek, "d" to o.pregDay) to tr("Due date: {date}", "date" to shortDate(o.due))
        else tr("Pregnant") to tr("Add the first day of your last period in Cycle settings to see the weeks.")
    }
    if (o.lastStart.isEmpty()) return tr("Welcome") to tr("Log your period when it comes, or add a past one, and Ciclo starts predicting.")
    val next = o.next.firstOrNull()
    val big = tr("Day {n} of your cycle", "n" to o.cycleDay)
    val small = when {
        o.lateDays > 0 -> tr("Later than usual: {days}", "days" to daysText(o.lateDays))
        next == null -> ""
        else -> {
            val until = daysBetween(day, next.start)
            val range = periodRange(o, next.start, day)
            when {
                until <= 0L -> tr("Your period may start any day now")
                range != null -> tr("Period around {from} to {to}", "from" to shortDate(range.first), "to" to shortDate(range.second))
                until == 1L -> tr("Period expected tomorrow")
                else -> tr("Period in {n} days ({date})", "n" to until, "date" to shortDate(next.start))
            }
        }
    }
    return big to small
}

/** When the next period is a guess (cycles not learned yet) or the cycles vary a lot, a range instead of one date
 *  (UX review U-C5): 3 days either side while not learned, else from the shortest to the longest recent cycle. */
fun periodRange(o: OutlookView, start: String, day: String): Pair<String, String>? {
    val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
    val (from, to) = when {
        !o.learned -> s.minusDays(3) to s.plusDays(3)
        o.irregular && o.lastStart.isNotEmpty() -> LocalDate.parse(o.lastStart).let { it.plusDays(o.shortest) to it.plusDays(o.longest) }
        else -> return null
    }
    val first = maxOf(from, LocalDate.parse(day).plusDays(1))
    return if (first < to) first.toString() to to.toString() else null
}

/** Where today falls: the fertile window, ovulation, PMS days. */
fun phaseLine(o: OutlookView, set: CycleSettings, day: String): String? {
    if (set.mode == "pregnant" || o.lastStart.isEmpty()) return null
    val n = o.next.firstOrNull() ?: return null
    if (o.ovulationSeen.isNotEmpty() && daysBetween(o.ovulationSeen, day) in 0..1) return tr("Ovulation seen in your logs: {date}", "date" to shortDate(o.ovulationSeen))
    return when {
        day == n.ovulation -> tr("Ovulation day (estimated): most likely fertile")
        day >= n.fertileFrom && day <= n.fertileTo -> tr("Fertile window (estimated): likely fertile")
        day >= n.pmsFrom && day < n.start -> tr("PMS days may start now")
        daysBetween(day, n.fertileFrom) in 1..7 -> tr("Fertile window from {date} (estimated)", "date" to shortDate(n.fertileFrom))
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayView(env: Env, list: ListItem, o: OutlookView, set: CycleSettings, custom: Map<String, String>, snackbar: SnackbarHostState, onLog: (String) -> Unit, onPastPeriod: () -> Unit) {
    val node = env.node
    val scope = rememberCoroutineScope()
    val day = today()
    val d = remember(node, env.version, list.id, day) { runCatching { node.cycleDay(list.id, day) }.getOrNull() }
    val (big, small) = headline(o, set, day)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth().testTag("todayCard")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(big, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("headline"))
                if (small.isNotEmpty()) Text(small, style = MaterialTheme.typography.titleMedium, color = periodText(), modifier = Modifier.testTag("nextPeriod"))
                phaseLine(o, set, day)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = fertileText(), modifier = Modifier.testTag("phase")) }
                if (set.mode != "pregnant" && o.lastStart.isEmpty()) androidx.compose.material3.Button(onClick = onPastPeriod,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("welcomePast")) { Text(tr("Add my last period")) }
                if (set.mode != "pregnant" && o.lastStart.isNotEmpty()) Text(
                    if (o.learned) tr("Estimated from your last cycles (usually {n} days).", "n" to o.avgCycle) else tr("Estimated from a {n}-day cycle until you have logged two.", "n" to o.avgCycle),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (set.mode != "pregnant" && o.lastStart.isNotEmpty()) ReminderOffer(env, set.bc)
        // the period buttons: started today, still going, or over
        val flowToday = d?.flow ?: 0L
        if (set.mode != "pregnant") {
            val flows = remember(node, env.version, list.id) { runCatching { node.cycleDays(list.id) }.getOrDefault(emptyList()).associate { it.date to it.flow } }
            val pDay = periodDayOf(flows, day)
            Text(if (pDay > 1) tr("Day {n} of your period: still going?", "n" to pDay) else tr("Period today"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("periodQuestion"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((v, label) in Words.FLOW) FilterChip(flowToday == v, {
                    val filled = startPeriod(node, list.id, flows, day, v, o.avgPeriod)
                    runCatching { node.cycleSetFlow(list.id, day, v) }
                    env.changed()
                    if (filled.isNotEmpty()) scope.launch {
                        val r = snackbar.showSnackbar(tr("Period started. The days ahead are filled in as expected; change any day in the calendar."), actionLabel = tr("Undo"), duration = androidx.compose.material3.SnackbarDuration.Long)
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            for ((date, was) in filled) runCatching { node.cycleSetFlow(list.id, date, was) }
                            env.changed()
                        }
                    }
                }, label = { Text(Words.flowLabel(label)) }, modifier = Modifier.testTag("flow$v"))
                if (pDay > 1 || flows.any { (k, f) -> k > day && f >= 2 && daysBetween(day, k) <= 12 }) TextButton(onClick = {
                    // over: no flow today, and the days filled in ahead are taken out again
                    runCatching { node.cycleSetFlow(list.id, day, 0) }
                    for ((k, f) in flows) if (k > day && f >= 2 && daysBetween(day, k) <= 12) runCatching { node.cycleSetFlow(list.id, k, 0) }
                    env.changed()
                }, modifier = Modifier.testTag("periodOver")) { Text(tr("It's over")) }
            }
        }
        if (set.bc == "pill" && o.pillDay > 0) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (o.pillActive) tr("Pill {n} of {total}", "n" to o.pillDay, "total" to set.bcActive) else tr("Break day {n}", "n" to (o.pillDay - set.bcActive)), style = MaterialTheme.typography.titleMedium)
                        if (o.pillActive) Text(if (d?.bcTaken == true) tr("Taken today") else tr("Not taken yet today"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (o.pillActive) Checkbox(d?.bcTaken == true, { on -> runCatching { node.cycleSetBcTaken(list.id, day, on) }; env.changed() },
                        modifier = Modifier.semantics { contentDescription = tr("Took my pill today") }.testTag("pillTaken"))
                }
            }
        }
        if (o.nextChange.isNotEmpty()) Text(
            tr("Next change of your {kind}: {date}", "kind" to tr(Words.BIRTH_CONTROL.firstOrNull { it.first == set.bc }?.second ?: "").lowercase(Lang.locale), "date" to dayLabel(o.nextChange)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = { onLog(day) }, modifier = Modifier.fillMaxWidth().testTag("logToday")) {
            Icon(Ic.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Log how today was"))
        }
        if (d != null && d.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in d.tags) Text(Words.tagLabel(t, custom), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (set.mode != "pregnant") o.next.firstOrNull()?.let { n ->
            Text(tr("Coming up"), style = MaterialTheme.typography.titleSmall)
            Text(tr("Fertile window: {from} to {to}", "from" to shortDate(n.fertileFrom), "to" to shortDate(n.fertileTo)), color = fertileText())
            NotContraception()
            Text(tr("Next period: {from} to {to}", "from" to shortDate(n.start), "to" to shortDate(n.end)), color = periodText())
        } ?: NotContraception()
        if (set.mode == "pregnant") NotContraception()
    }
}

@Composable
private fun NotContraception() = Text(
    tr("Predictions are estimates from your own logs. They are not a method of birth control."),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("notContraception"),
)

/** Which day of a period [day] is (1 = it starts today or no period is going on): flow days at most
 *  [PERIOD_GAP] days apart are one period, as in the engine. */
fun periodDayOf(flows: Map<String, Long>, day: String): Int {
    val t = LocalDate.parse(day)
    var start = t
    var gap = 0
    var k = 1L
    while (k <= 12 + PERIOD_GAP) {
        val f = flows[t.minusDays(k).toString()] ?: 0L
        if (f >= 2) { start = t.minusDays(k); gap = 0 } else if (++gap > PERIOD_GAP) break
        k++
    }
    return if (start == t) 1 else (ChronoUnit.DAYS.between(start, t) + 1).toInt()
}

const val PERIOD_GAP = 2

/** UX review U-C4: the first period day after a period-free stretch fills in the expected days ahead, so a day she
 *  forgets to open the app doesn't shorten the period and every prediction. Returns the days it filled, each with
 *  the flow it had before (for Undo); nothing when a period is already going on or [flow] isn't a period. */
fun startPeriod(node: com.jurisgames.ciclo.core.KtNode, listId: String, flows: Map<String, Long>, day: String, flow: Long, avgPeriod: Long): List<Pair<String, Long>> {
    if (flow < 2 || (flows[day] ?: 0L) >= 2 || periodDayOf(flows, day) > 1) return emptyList()
    val t = LocalDate.parse(day)
    if ((1..PERIOD_GAP + 1).any { (flows[t.minusDays(it.toLong()).toString()] ?: 0L) >= 2 }) return emptyList()
    val len = avgPeriod.coerceIn(2, 12)
    val ahead = (1 until len).map { t.plusDays(it).toString() }.filter { (flows[it] ?: 0L) < 2 }
    if (ahead.isEmpty()) return emptyList()
    runCatching { node.cycleLogPeriod(listId, day, len) }.onFailure { return emptyList() }
    return ahead.map { it to (flows[it] ?: 0L) }
}

// ---- calendar ----

@Composable
private fun CalendarView(env: Env, list: ListItem, day: String, onDay: (String) -> Unit) {
    val node = env.node
    var month by remember { mutableStateOf(YearMonth.now()) }
    val first = WeekFields.of(Lang.locale).firstDayOfWeek
    val start = month.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - first.value + 7) % 7).toLong()) }
    val end = start.plusDays(41)
    val cells = remember(node, env.version, list.id, month, day) {
        runCatching { node.cycleCalendar(list.id, day, start.toString(), end.toString()) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.semantics { contentDescription = tr("Previous month") }.testTag("prevMonth")) { Text("‹", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {}) }
            Text(monthLabel(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("month"), textAlign = TextAlign.Center)
            IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.semantics { contentDescription = tr("Next month") }.testTag("nextMonth")) { Text("›", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {}) }
        }
        Row(Modifier.fillMaxWidth()) {
            for (k in 0 until 7) {
                val dow = DayOfWeek.of((first.value - 1 + k) % 7 + 1)
                Text(shortName(dow.getDisplayName(java.time.format.TextStyle.NARROW, Lang.locale)), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (w in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (k in 0 until 7) {
                    val c = cells.getOrNull(w * 7 + k)
                    // U-C17: the whole cell (about 48 dp on a phone) is the target; the circle sits inside it
                    val cellMod = if (c == null) Modifier else {
                        val desc = dayDescription(c, c.date == day)
                        Modifier.clickable { onDay(c.date) }.semantics(mergeDescendants = true) { contentDescription = desc }.testTag("day-" + c.date)
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).then(cellMod).padding(1.dp)) {
                        if (c != null) CalCell(c, inMonth = YearMonth.from(LocalDate.parse(c.date)) == month, isToday = c.date == day)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend()
    }
}

/** The calendar's marks. Each has its own shape, so none is told apart by colour alone (UX review U-C3): a period day
 *  is filled (spotting: the lower half), an expected period is a dashed ring, the fertile window a dotted ring over a
 *  tint, ovulation a double ring, and today a bar under the number. */
private enum class Mark { Spotting, Expected, Fertile, Ovulation }

private fun Modifier.mark(kind: Mark, color: Color): Modifier = drawBehind {
    val w = 2.dp.toPx()
    val r = size.minDimension / 2 - w / 2
    when (kind) {
        Mark.Spotting -> drawArc(color, 0f, 180f, useCenter = true)
        Mark.Expected -> drawCircle(color, r, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        Mark.Fertile -> drawCircle(color, r, style = Stroke(w, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 4.dp.toPx()))))
        Mark.Ovulation -> {
            drawCircle(color, r, style = Stroke(w))
            drawCircle(color, r - 2.5f * w, style = Stroke(w * 0.75f))
        }
    }
}

/** What a screen reader says for a calendar day: the date and every mark on it, in words (UX review U-C3). */
fun dayDescription(c: CalItem, isToday: Boolean): String {
    val parts = mutableListOf<String>()
    if (isToday) parts += tr("Today")
    parts += runCatching { DateTimeFormatter.ofPattern("EEEE d MMMM", Lang.locale).format(LocalDate.parse(c.date)) }.getOrDefault(c.date)
    if (c.flow > 0) parts += tr("Period") + ": " + Words.flowLabel(Words.FLOW.firstOrNull { it.first == c.flow }?.second ?: "").lowercase(Lang.locale)
    if (c.periodPredicted) parts += tr("Expected period")
    if (c.fertile) parts += tr("Fertile window")
    if (c.ovulation) parts += tr("Ovulation")
    if (c.pms && !c.periodPredicted) parts += tr("PMS")
    if (c.logged && c.flow == 0L) parts += tr("Something logged")
    if (c.periodPredicted || c.fertile || c.ovulation) parts += tr("estimated")
    return parts.joinToString(", ")
}

@Composable
private fun CalCell(c: CalItem, inMonth: Boolean, isToday: Boolean) {
    val period = periodColor()
    val fertile = fertileColor()
    val pms = pmsColor()
    val fill = when {
        c.flow >= 2 -> period
        c.fertile -> fertile.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val textColor = when {
        c.flow >= 2 -> onPeriodFill()
        !inMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    var m = Modifier.fillMaxSize().background(fill, CircleShape)
    if (c.flow == 1L) m = m.mark(Mark.Spotting, period.copy(alpha = 0.55f))
    if (c.fertile && c.flow < 2) m = m.mark(Mark.Fertile, fertile)
    if (c.periodPredicted) m = m.mark(Mark.Expected, period)
    if (c.ovulation) m = m.mark(Mark.Ovulation, fertile)
    Box(m, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(LocalDate.parse(c.date).dayOfMonth.toString(), color = textColor, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clearAndSetSemantics {})
            if (isToday) Box(Modifier.size(width = 14.dp, height = 2.dp).background(textColor, RoundedCornerShape(1.dp)).testTag("todayBar"))
        }
        if (c.logged && c.flow < 2) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        if (c.pms && !c.periodPredicted) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).background(pms, CircleShape))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    @Composable
    fun item(label: String, mark: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { mark(); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.bodySmall) }
    val period = periodColor()
    val fertile = fertileColor()
    val pms = pmsColor()
    FlowRow(Modifier.testTag("legend"), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(tr("Period")) { Box(Modifier.size(14.dp).background(period, CircleShape)) }
        item(tr("Expected period")) { Box(Modifier.size(14.dp).mark(Mark.Expected, period)) }
        item(tr("Fertile window")) { Box(Modifier.size(14.dp).background(fertile.copy(alpha = 0.18f), CircleShape).mark(Mark.Fertile, fertile)) }
        item(tr("Ovulation")) { Box(Modifier.size(18.dp).mark(Mark.Ovulation, fertile)) }
        item(tr("Today")) { Box(Modifier.size(width = 14.dp, height = 2.dp).background(MaterialTheme.colorScheme.onSurface)) }
        item(tr("PMS")) { Box(Modifier.size(6.dp).background(pms, CircleShape)) }
        item(tr("Something logged")) { Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)) }
    }
}

// ---- the day log ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayLogDialog(env: Env, list: ListItem, date: String, set: CycleSettings, custom: List<com.jurisgames.ciclo.core.CustomTagItem>, onDate: (String) -> Unit, onClose: () -> Unit) {
    val node = env.node
    val d = remember(node, env.version, list.id, date) { runCatching { node.cycleDay(list.id, date) }.getOrNull() } ?: return
    val (fahrenheit, pounds) = remember(node) { Units.read(node.dataDir()) }
    // U-C11: what she typed survives the phone turning (saved only on Done or another day)
    var temp by rememberSaveable(date) { mutableStateOf(if (d.temp > 0) tempText(d.temp, fahrenheit).substringBefore(' ') else "") }
    var weight by rememberSaveable(date) { mutableStateOf(if (d.weight > 0) weightText(d.weight, pounds).substringBefore(' ') else "") }
    var note by rememberSaveable(date) { mutableStateOf(d.note) }
    val badTemp = temp.isNotBlank() && parseTemp(temp, fahrenheit) == null
    val badWeight = weight.isNotBlank() && parseWeight(weight, pounds) == null
    var newTag by remember { mutableStateOf<String?>(null) }
    val future = date > today()
    /** Saves the typed fields; false (and nothing closes) while a number can't be read, so it is never dropped. */
    fun saveText(): Boolean {
        if (badTemp || badWeight) return false
        val t = if (temp.isBlank()) 0L else parseTemp(temp, fahrenheit)
        if (t != null && t != d.temp) runCatching { node.cycleSetTemp(list.id, date, t) }
        val w = if (weight.isBlank()) 0L else parseWeight(weight, pounds)
        if (w != null && w != d.weight) runCatching { node.cycleSetWeight(list.id, date, w) }
        if (note.trim() != d.note) runCatching { node.cycleSetNote(list.id, date, note) }
        env.changed()
        return true
    }
    fun toggle(tag: String, on: Boolean) {
        runCatching { node.cycleSetTag(list.id, date, tag, on) }
        env.changed()
    }
    AlertDialog(
        onDismissRequest = { if (saveText()) onClose() },
        confirmButton = { TextButton(onClick = { if (saveText()) onClose() }, modifier = Modifier.testTag("logDone")) { Text(tr("Done")) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (saveText()) onDate(LocalDate.parse(date).minusDays(1).toString()) }, modifier = Modifier.semantics { contentDescription = tr("Previous day") }.testTag("prevDay")) { Text("‹", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics {}) }
                Text(dayLabel(date), Modifier.weight(1f).testTag("logDate"), textAlign = TextAlign.Center)
                IconButton(onClick = { if (saveText()) onDate(LocalDate.parse(date).plusDays(1).toString()) }, enabled = !future && date < today(), modifier = Modifier.semantics { contentDescription = tr("Next day") }.testTag("nextDay")) { Text("›", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics {}) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (future) Text(tr("This day hasn't come yet: you can log it when it does."), style = MaterialTheme.typography.bodyMedium)
                else {
                    Text(tr("Period"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((v, label) in Words.FLOW) FilterChip(d.flow == v, { runCatching { node.cycleSetFlow(list.id, date, v) }; env.changed() }, label = { Text(Words.flowLabel(label)) }, modifier = Modifier.testTag("logFlow$v"))
                    }
                    if (set.bc == "pill") CheckRow(tr("Took my pill"), d.bcTaken, "logPill") { runCatching { node.cycleSetBcTaken(list.id, date, it) }; env.changed() }
                    for ((cat, _) in Words.CATEGORIES) {
                        if (cat in set.hidden) continue
                        if (cat == "peri" && set.mode != "peri" && d.tags.none { it.startsWith("peri.") }) continue
                        val builtin = Words.TAGS[cat].orEmpty().map { "$cat.${it.first}" to tr(it.second) }
                        val mine = custom.filter { it.cat == cat }.map { it.id to it.name }
                        if (builtin.isEmpty() && mine.isEmpty() && cat != "mine") continue
                        Text(Words.catLabel(cat), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for ((id, label) in builtin + mine) {
                                val on = id in d.tags
                                FilterChip(on, { toggle(id, !on) }, label = { Text(label) }, modifier = Modifier.testTag("tag-$id"))
                            }
                            TextButton(onClick = { newTag = cat }, modifier = Modifier.testTag("addTag-$cat")) { Text("+ " + tr("Add")) }
                        }
                    }
                    Text(tr("Body"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(temp, { temp = it }, singleLine = true, label = { Text(tr("Temperature") + if (fahrenheit) " (°F)" else " (°C)") },
                            isError = badTemp, supportingText = if (badTemp) { { Text(if (fahrenheit) tr("Between 86 and 113 °F") else tr("Between 30 and 45 °C")) } } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("logTemp"))
                        OutlinedTextField(weight, { weight = it }, singleLine = true, label = { Text(tr("Weight") + if (pounds) " (lb)" else " (kg)") },
                            isError = badWeight, supportingText = if (badWeight) { { Text(if (pounds) tr("Between 3 and 1100 lb") else tr("Between 1 and 500 kg")) } } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("logWeight"))
                    }
                    Text(tr("Basal temperature: taken right after waking, before getting up."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(note, { if (it.length <= 2000) note = it }, label = { Text(tr("Note")) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("logNote"))
                }
            }
        },
    )
    newTag?.let { cat ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newTag = null },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) runCatching { node.cycleAddCustomTag(list.id, name.trim(), cat) }.getOrNull()?.let { id -> runCatching { node.cycleSetTag(list.id, date, id, true) } }
                    newTag = null
                    env.changed()
                }, modifier = Modifier.testTag("newTagGo")) { Text(tr("Add")) }
            },
            dismissButton = { TextButton(onClick = { newTag = null }) { Text(tr("Cancel")) } },
            title = { Text(tr("New tag in {cat}", "cat" to Words.catLabel(cat))) },
            text = { OutlinedTextField(name, { if (it.length <= 60) name = it }, singleLine = true, placeholder = { Text(tr("Name")) }, modifier = Modifier.testTag("newTagName")) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: String, maxDate: String? = null, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ms = runCatching { LocalDate.parse(initial) }.getOrDefault(LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val limit = maxDate?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val st = rememberDatePickerState(
        initialSelectedDateMillis = ms,
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = limit == null || utcTimeMillis <= limit
        },
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                onClose()
            }, modifier = Modifier.testTag("dateGo")) { Text(tr("OK")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    ) { DatePicker(st) }
}

@Composable
private fun PastPeriodDialog(avg: Long, pickFirst: Boolean = false, onClose: () -> Unit, onSave: (String, Long) -> Unit) {
    var start by remember { mutableStateOf(LocalDate.now().minusDays(28).toString()) }
    var days by remember { mutableStateOf(avg.coerceIn(1, 12)) }
    var picking by remember { mutableStateOf(pickFirst) } // from Welcome: the calendar opens first (U-C15)
    val maxDays = (daysBetween(start, today()) + 1).coerceIn(1, 12)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { onSave(start, days.coerceAtMost(maxDays)) }, modifier = Modifier.testTag("pastGo")) { Text(tr("Add")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        title = { Text(tr("Add a past period")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("pastStart")) { Text(tr("Started: {date}", "date" to dayLabel(start))) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (days > 1) days-- }, modifier = Modifier.testTag("pastLess")) { Text("−", fontSize = 20.sp) }
                    Text(daysText(days.coerceAtMost(maxDays)), modifier = Modifier.testTag("pastDays"))
                    TextButton(onClick = { if (days < maxDays) days++ }, modifier = Modifier.testTag("pastMore")) { Text("+", fontSize = 20.sp) }
                }
                Text(tr("You can change each day's flow in the calendar afterwards."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
    if (picking) DateDialog(start, today(), onPick = { start = it }, onClose = { picking = false })
}

// ---- insights ----

@Composable
private fun InsightsView(env: Env, list: ListItem, o: OutlookView, set: CycleSettings, custom: Map<String, String>) {
    val node = env.node
    val patterns = remember(node, env.version, list.id) { runCatching { node.cyclePatterns(list.id) }.getOrDefault(emptyList()) }
    val days = remember(node, env.version, list.id) { runCatching { node.cycleDays(list.id) }.getOrDefault(emptyList()) }
    val (fahrenheit, pounds) = remember(node) { Units.read(node.dataDir()) }
    var how by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // at a very large text size the three cards stack instead of squeezing (U-C18)
        val stack = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.5f
        @Composable
        fun Stats(m: Modifier) {
            Stat(tr("Cycle"), daysText(o.avgCycle), m.testTag("statCycle"))
            Stat(tr("Period"), daysText(o.avgPeriod), m.testTag("statPeriod"))
            Stat(tr("Varies"), if (o.learned) tr("{a}–{b} days", "a" to o.shortest, "b" to o.longest) else "–", m.testTag("statRange"))
        }
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Stats(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Stats(Modifier.weight(1f)) }
        if (!o.learned) Text(tr("Averages use your settings until two cycles are logged."), style = MaterialTheme.typography.bodySmall)
        if (o.irregular) Text(tr("Your recent cycles differ by 8 days or more. That can be normal, and it is also worth mentioning to a doctor."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("irregular"))

        Text(tr("Cycle history"), style = MaterialTheme.typography.titleMedium)
        CycleBars(o)

        val start = o.lastStart.ifEmpty { LocalDate.now().minusDays(35).toString() }
        val temps = days.filter { it.temp > 0 && it.date >= LocalDate.parse(start).minusDays(if (o.lastStart.isEmpty()) 0 else 0).toString() }
        Text(tr("Temperature this cycle"), style = MaterialTheme.typography.titleMedium)
        if (temps.size < 2) Text(tr("Log your basal temperature on a few mornings to see the curve here."), style = MaterialTheme.typography.bodySmall)
        else LineChart(temps.map { it.date to it.temp }, start, o.coverLine.takeIf { it > 0 }, label = { tempText(it, fahrenheit) }, Modifier.testTag("tempChart"))
        if (o.coverLine > 0) Text(tr("The dashed line is the cover line: after ovulation your temperature stays above it."), style = MaterialTheme.typography.bodySmall)

        val weights = days.filter { it.weight > 0 }.takeLast(90)
        if (weights.size >= 2) {
            Text(tr("Weight"), style = MaterialTheme.typography.titleMedium)
            LineChart(weights.map { it.date to it.weight }, weights.first().date, null, label = { weightText(it, pounds) }, Modifier.testTag("weightChart"))
        }

        Text(tr("Patterns"), style = MaterialTheme.typography.titleMedium)
        if (patterns.isEmpty()) Text(tr("After three cycles with logs, what tends to come back at the same time shows here."), style = MaterialTheme.typography.bodySmall)
        for (p in patterns.take(12)) Text(patternText(p, custom), modifier = Modifier.testTag("pattern-" + p.tag))

        TextButton(onClick = { how = !how }, modifier = Modifier.testTag("howOpen")) { Text(if (how) tr("Hide how predictions work") else tr("How predictions work")) }
        if (how) Text(howText(set), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("how"))
    }
}

fun patternText(p: PatternItem, custom: Map<String, String>): String {
    val name = Words.tagLabel(p.tag, custom)
    val whenText = if (p.fromStart) {
        if (p.from == p.to) tr("on day {a} of your cycle", "a" to p.from) else tr("on days {a}–{b} of your cycle", "a" to p.from, "b" to p.to)
    } else {
        if (p.from == p.to) tr("{a} days before your period", "a" to p.from) else tr("{a}–{b} days before your period", "a" to p.from, "b" to p.to)
    }
    return tr("{name}: usually {when} (in {n} of your last {m} cycles)", "name" to name, "when" to whenText, "n" to p.cyclesWith, "m" to p.cycles)
}

fun howText(set: CycleSettings): String = tr(
    "Your next period is the start of your last one plus your usual cycle: the middle length of your last 6 cycles, so one very long cycle (sometimes a month goes by without a period) or a very short one doesn't throw it off. Until two cycles are logged, the cycle length from Cycle settings is used. Ovulation is estimated {l} days before the next period, and the fertile window is the 5 days before it, that day and the day after. When your logs show ovulation (a temperature rise: three mornings above the six before, the third at least 0.2 °C higher; or a positive ovulation test), the next period is counted from there instead. Everything is worked out on this device from what you log.",
    "l" to set.luteal,
)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Each finished cycle as a bar: its period days in the period color, the rest muted, the length written at the end. */
@Composable
private fun CycleBars(o: OutlookView) {
    val cycles = o.cycles.takeLast(12)
    if (cycles.isEmpty()) {
        Text(tr("Your cycles show here once two periods are logged."), style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxLen = maxOf(cycles.maxOf { it.days }, 35L)
    val periods = o.periods.associateBy { it.start }
    val period = periodColor()
    val rest = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.testTag("cycleBars"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in cycles.reversed()) {
            val pd = periods[c.start]?.days ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortDate(c.start), Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(14.dp)) {
                    Row(Modifier.fillMaxWidth((c.days.toFloat() / maxLen).coerceIn(0.05f, 1f)).height(14.dp)) {
                        Box(Modifier.weight(pd.coerceAtLeast(1).toFloat()).fillMaxSize().background(period, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
                        Spacer(Modifier.width(2.dp))
                        Box(Modifier.weight((c.days - pd).coerceAtLeast(1).toFloat()).fillMaxSize().background(rest, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                    }
                }
                Text(daysText(c.days), Modifier.widthIn(min = 64.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A 2px line over days, with its lowest and highest value written beside it and an optional dashed line. */
@Composable
private fun LineChart(points: List<Pair<String, Long>>, from: String, dashed: Long?, label: (Long) -> String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val base = LocalDate.parse(from)
    val xs = points.map { ChronoUnit.DAYS.between(base, LocalDate.parse(it.first)).toFloat() }
    val ys = points.map { it.second.toFloat() } + listOfNotNull(dashed?.toFloat())
    val (lo, hi) = ys.min() to ys.max()
    val span = (hi - lo).coerceAtLeast(1f)
    val xMax = xs.max().coerceAtLeast(1f)
    Column(modifier) {
        Text(label(points.maxOf { it.second }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 6.dp)) {
            fun at(x: Float, y: Float) = Offset(x / xMax * size.width, size.height - (y - lo) / span * size.height)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            dashed?.let { d -> drawLine(grid, at(0f, d.toFloat()), at(xMax, d.toFloat()), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
            for (k in 1 until points.size) drawLine(color, at(xs[k - 1], points[k - 1].second.toFloat()), at(xs[k], points[k].second.toFloat()), 2.dp.toPx())
            for (k in points.indices) drawCircle(color, 4.dp.toPx(), at(xs[k], points[k].second.toFloat()))
        }
        Text(label(points.minOf { it.second }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- settings for the cycle ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleSettingsDialog(env: Env, list: ListItem, onClose: () -> Unit) {
    val node = env.node
    val set = remember(node, env.version, list.id) { runCatching { node.cycleSettings(list.id) }.getOrNull() } ?: return
    var units by remember { mutableStateOf(Units.read(node.dataDir())) }
    var picking by remember { mutableStateOf<String?>(null) }
    fun put(k: String, v: String) {
        runCatching { node.cycleSetSetting(list.id, k, v) }
        env.changed()
    }
    @Composable
    fun Stepper(label: String, value: Long, lo: Long, hi: Long, key: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            TextButton(onClick = { if (value > lo) put(key, (value - 1).toString()) }, modifier = Modifier.testTag("$key-")) { Text("−", fontSize = 20.sp) }
            Text(value.toString(), Modifier.widthIn(min = 32.dp).testTag("$key-value"), textAlign = TextAlign.Center)
            TextButton(onClick = { if (value < hi) put(key, (value + 1).toString()) }, modifier = Modifier.testTag("$key+")) { Text("+", fontSize = 20.sp) }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("cycleSettingsDone")) { Text(tr("Done")) } },
        title = { Text(tr("Cycle settings")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("What Ciclo is for right now"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((m, label) in Words.MODES) FilterChip(set.mode == m, { put("mode", m) }, label = { Text(tr(label)) }, modifier = Modifier.testTag("mode-$m"))
                }
                if (set.mode == "pregnant") OutlinedButton(onClick = { picking = "lmp" }, modifier = Modifier.testTag("lmp")) {
                    Text(if (set.lmp.isEmpty()) tr("First day of your last period") else tr("Last period started {date}", "date" to dayLabel(set.lmp)))
                }
                HorizontalDivider()
                Text(tr("Until two cycles are logged"), style = MaterialTheme.typography.titleSmall)
                Stepper(tr("Cycle length (days)"), set.cycleLen, 15, 60, "clen")
                Stepper(tr("Period length (days)"), set.periodLen, 1, 12, "plen")
                Stepper(tr("Days from ovulation to period"), set.luteal, 8, 20, "lut")
                Text(tr("Most people have 12 to 14. Change it only if a doctor or your temperature logs tell you yours."), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(tr("Birth control"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, label) in Words.BIRTH_CONTROL) FilterChip(set.bc == k, { put("bc", k) }, label = { Text(tr(label)) }, modifier = Modifier.testTag("bc-${k.ifEmpty { "none" }}"))
                }
                if (set.bc in listOf("pill", "ring", "patch", "shot")) OutlinedButton(onClick = { picking = "bcs" }, modifier = Modifier.testTag("bcStart")) {
                    Text(if (set.bcStart.isEmpty()) tr("When did this pack, ring, patch or shot start?") else tr("Started {date}", "date" to dayLabel(set.bcStart)))
                }
                if (set.bc == "pill") {
                    Stepper(tr("Active pills"), set.bcActive, 1, 120, "bca")
                    Stepper(tr("Break days"), set.bcBreak, 0, 14, "bcb")
                }
                HorizontalDivider()
                Text(tr("Show these in the day log"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((cat, label) in Words.CATEGORIES) {
                        val shown = cat !in set.hidden
                        FilterChip(shown, {
                            val next = if (shown) set.hidden + cat else set.hidden - cat
                            put("hide", next.joinToString(","))
                        }, label = { Text(tr(label)) }, modifier = Modifier.testTag("show-$cat"))
                    }
                }
                HorizontalDivider()
                Text(tr("Units on this device"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!units.first, { units = false to units.second; Units.write(node.dataDir(), units.first, units.second); env.changed() }, label = { Text("°C") }, modifier = Modifier.testTag("unitC"))
                    FilterChip(units.first, { units = true to units.second; Units.write(node.dataDir(), units.first, units.second); env.changed() }, label = { Text("°F") }, modifier = Modifier.testTag("unitF"))
                    FilterChip(!units.second, { units = units.first to false; Units.write(node.dataDir(), units.first, units.second); env.changed() }, label = { Text("kg") }, modifier = Modifier.testTag("unitKg"))
                    FilterChip(units.second, { units = units.first to true; Units.write(node.dataDir(), units.first, units.second); env.changed() }, label = { Text("lb") }, modifier = Modifier.testTag("unitLb"))
                }
            }
        },
    )
    picking?.let { key ->
        DateDialog(if (key == "lmp") set.lmp.ifEmpty { today() } else set.bcStart.ifEmpty { today() }, today(), onPick = { put(key, it) }, onClose = { picking = null })
    }
}

// ---- the report for a doctor ----

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** The report's parts she can leave out (UX review U-C24): sex and notes are off unless she ticks them. */
val REPORT_PARTS = listOf("cycles" to "Periods and cycles", "symptoms" to "Symptoms and other logs", "sex" to "Sex", "temp" to "Temperature", "notes" to "Notes")
val REPORT_DEFAULT = setOf("cycles", "symptoms", "temp")

/** A date for a page someone else reads: "4 Mar 2026", never the raw "2026-03-04". */
fun fullDate(d: String): String = runCatching { shortName(DateTimeFormatter.ofPattern("d MMM yyyy", Lang.locale).format(LocalDate.parse(d))) }.getOrDefault(d)

fun reportHtml(o: OutlookView, set: CycleSettings, days: List<CycleDay>, custom: Map<String, String>, fahrenheit: Boolean, parts: Set<String> = REPORT_DEFAULT): String {
    val sb = StringBuilder()
    sb.append("<html><head><meta charset=\"utf-8\"><style>body{font-family:sans-serif;font-size:12px}table{border-collapse:collapse}td,th{border:1px solid #999;padding:3px 6px;text-align:left}h1{font-size:18px}h2{font-size:14px;margin-top:16px}</style></head><body>")
    sb.append("<h1>").append(esc(tr("Cycle report"))).append("</h1><p>").append(esc(tr("Made on {date} by the Ciclo app from what was logged on this device.", "date" to today()))).append("</p>")
    sb.append("<h2>").append(esc(tr("Summary"))).append("</h2><ul>")
    sb.append("<li>").append(esc(tr("Usual cycle: {n} days", "n" to o.avgCycle))).append(if (o.learned) "" else esc(" (" + tr("from the settings") + ")")).append("</li>")
    if (o.learned) sb.append("<li>").append(esc(tr("Shortest and longest recent cycle: {a} and {b} days", "a" to o.shortest, "b" to o.longest))).append("</li>")
    sb.append("<li>").append(esc(tr("Average period: {n} days", "n" to o.avgPeriod))).append("</li>")
    if (o.lastStart.isNotEmpty()) sb.append("<li>").append(esc(tr("Last period started: {date}", "date" to fullDate(o.lastStart)))).append("</li>")
    if (set.bc.isNotEmpty()) sb.append("<li>").append(esc(tr("Birth control: {kind}", "kind" to tr(Words.BIRTH_CONTROL.first { it.first == set.bc }.second)))).append("</li>")
    if (set.mode == "pregnant" && o.due.isNotEmpty()) sb.append("<li>").append(esc(tr("Due date: {date}", "date" to fullDate(o.due)))).append("</li>")
    sb.append("</ul>")
    if ("cycles" in parts) sb.append("<h2>").append(esc(tr("Periods and cycles"))).append("</h2><table><tr><th>").append(esc(tr("Period started"))).append("</th><th>").append(esc(tr("Period days"))).append("</th><th>").append(esc(tr("Cycle length"))).append("</th></tr>")
    val lens = o.cycles.associate { it.start to it.days }
    if ("cycles" in parts) {
        for (p in o.periods.takeLast(12).reversed()) sb.append("<tr><td>").append(esc(fullDate(p.start))).append("</td><td>").append(p.days).append("</td><td>").append(lens[p.start]?.toString() ?: "–").append("</td></tr>")
        sb.append("</table>")
    }
    val since = o.periods.takeLast(7).firstOrNull()?.start ?: ""
    val counts = days.filter { it.date >= since }.flatMap { it.tags }
        .filter { t -> if (t.startsWith("sex.")) "sex" in parts else "symptoms" in parts }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    if (counts.isNotEmpty()) {
        sb.append("<h2>").append(esc(tr("What was logged, and on how many days"))).append("</h2><table>")
        for ((t, nDays) in counts.take(30)) sb.append("<tr><td>").append(esc(Words.tagLabel(t, custom))).append("</td><td>").append(nDays).append("</td></tr>")
        sb.append("</table>")
    }
    val temps = if ("temp" in parts) days.filter { it.temp > 0 && it.date >= since } else emptyList()
    if (temps.isNotEmpty()) {
        sb.append("<h2>").append(esc(tr("Basal temperature"))).append("</h2><table>")
        for (d in temps.takeLast(60)) sb.append("<tr><td>").append(esc(fullDate(d.date))).append("</td><td>").append(esc(tempText(d.temp, fahrenheit))).append("</td></tr>")
        sb.append("</table>")
    }
    val notes = if ("notes" in parts) days.filter { it.note.isNotBlank() && it.date >= since } else emptyList()
    if (notes.isNotEmpty()) {
        sb.append("<h2>").append(esc(tr("Notes"))).append("</h2><table>")
        for (d in notes.takeLast(40)) sb.append("<tr><td>").append(esc(fullDate(d.date))).append("</td><td>").append(esc(d.note)).append("</td></tr>")
        sb.append("</table>")
    }
    sb.append("<p>").append(esc(tr("Predictions are estimates from your own logs. They are not a method of birth control."))).append("</p></body></html>")
    return sb.toString()
}
