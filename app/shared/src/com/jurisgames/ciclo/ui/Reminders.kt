package com.jurisgames.ciclo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Reminders, per device (a plain file next to the app's data; the phone's alarms read it). Times are minutes after
 * midnight in the device's own time.
 */
data class ReminderPrefs(
    // off until she says yes to the offer on Today, which is where the phone asks for notifications (UX review U-C9)
    val period: Boolean = false,
    val periodDays: Int = 2,
    val fertile: Boolean = false,
    val pill: Boolean = false,
    val pillAt: Int = 21 * 60,
    val change: Boolean = false,
    val log: Boolean = false,
    val logAt: Int = 20 * 60,
    /** When the period, fertile-window and change reminders ring (UX review U-C26: was fixed at 9:00). */
    val morningAt: Int = 9 * 60,
    /** Ask for the phone's own lock (PIN, pattern, fingerprint) to open Ciclo. */
    val lock: Boolean = false,
    /** The offer on Today was answered (Turn on or No thanks): it isn't shown again. */
    val offered: Boolean = false,
) {
    fun write(dir: String) = runCatching {
        File(dir, FILE).writeText(
            listOf("period=$period", "periodDays=$periodDays", "fertile=$fertile", "pill=$pill", "pillAt=$pillAt", "change=$change", "log=$log", "logAt=$logAt", "lock=$lock", "offered=$offered", "morningAt=$morningAt").joinToString("\n"),
        )
    }

    companion object {
        const val FILE = "ui-reminders"
        fun read(dir: String): ReminderPrefs {
            val m = runCatching { File(dir, FILE).readLines() }.getOrDefault(emptyList()).mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = ReminderPrefs()
            fun b(k: String, v: Boolean) = m[k]?.toBooleanStrictOrNull() ?: v
            fun i(k: String, v: Int, r: IntRange) = m[k]?.toIntOrNull()?.takeIf { it in r } ?: v
            return ReminderPrefs(
                b("period", d.period), i("periodDays", d.periodDays, 0..7), b("fertile", d.fertile), b("pill", d.pill), i("pillAt", d.pillAt, 0..1439),
                b("change", d.change), b("log", d.log), i("logAt", d.logAt, 0..1439), morningAt = i("morningAt", d.morningAt, 0..1439), lock = b("lock", d.lock), offered = b("offered", d.offered),
            )
        }
    }
}

/** Where the desktop keeps its daily backups (null on a phone: Android backs up through the phone's own backup). */
object BackupInfo {
    @Volatile var folder: String? = null
}

/** What only a phone can do: alarms and its own lock. Null on the desktop. */
object PhoneHooks {
    @Volatile var onRemindersChanged: (() -> Unit)? = null
    @Volatile var deviceLockAvailable: Boolean = false
    /** Whether the phone lets Ciclo post notifications (null on the desktop). */
    @Volatile var notificationsEnabled: (() -> Boolean)? = null
    /** Ask the phone for notifications (its own dialog, or its settings once refused); [onDone] when back. */
    @Volatile var askNotifications: ((onDone: () -> Unit) -> Unit)? = null
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(min: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val st = androidx.compose.material3.rememberTimePickerState(min / 60, min % 60, is24Hour = Lang.spanish || java.util.Locale.getDefault().country != "US")
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { onPick(st.hour * 60 + st.minute); onClose() }, modifier = Modifier.testTag("timeOk")) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        text = { androidx.compose.material3.TimePicker(st) },
    )
}

/** U-C9: once the first period is logged, Today offers the reminders in one line, and only a yes asks the phone. */
@Composable
fun ReminderOffer(env: Env, bc: String) {
    val ask = PhoneHooks.askNotifications ?: return
    val dir = env.node.dataDir()
    var p by remember(env.version) { mutableStateOf(ReminderPrefs.read(dir)) }
    if (p.offered) return
    fun put(n: ReminderPrefs) { p = n; n.write(dir); PhoneHooks.onRemindersChanged?.invoke() }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().testTag("reminderOffer")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (bc) {
                "pill" -> tr("Ciclo can remind you 2 days before your next period, and to take your pill.")
                "ring", "patch", "shot" -> tr("Ciclo can remind you 2 days before your next period, and when to change your {kind}.", "kind" to tr(Words.BIRTH_CONTROL.firstOrNull { it.first == bc }?.second ?: "").lowercase(Lang.locale))
                else -> tr("Ciclo can remind you 2 days before your next period.")
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = {
                    put(p.copy(period = true, pill = bc == "pill", change = bc in setOf("ring", "patch", "shot"), offered = true))
                    ask { env.changed() }
                }, modifier = Modifier.testTag("offerOn")) { Text(tr("Turn on")) }
                TextButton(onClick = { put(p.copy(offered = true)) }, modifier = Modifier.testTag("offerNo")) { Text(tr("No thanks")) }
            }
        }
    }
}

fun timeText(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun RemindersDialog(env: Env, onClose: () -> Unit) {
    val dir = env.node.dataDir()
    var p by remember { mutableStateOf(ReminderPrefs.read(dir)) }
    var notifyOn by remember { mutableStateOf(PhoneHooks.notificationsEnabled?.invoke() ?: true) }
    fun put(n: ReminderPrefs) {
        // a reminder turned on is where the phone is asked, never at start (U-C9)
        val more = n.period && !p.period || n.fertile && !p.fertile || n.pill && !p.pill || n.change && !p.change || n.log && !p.log
        p = n.copy(offered = true)
        p.write(dir)
        PhoneHooks.onRemindersChanged?.invoke()
        if (more && !notifyOn) PhoneHooks.askNotifications?.invoke { notifyOn = PhoneHooks.notificationsEnabled?.invoke() ?: true }
    }
    @Composable
    // U-C14: the whole row, words included, is the switch (48 dp), read as one by a screen reader
    fun Toggle(label: String, on: Boolean, tag: String, set: (Boolean) -> Unit) = Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = set).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(on, null)
    }
    @Composable
    // U-C26: any minute, on the phone's own clock picker (was ±30 minutes)
    fun Time(label: String, min: Int, tag: String, set: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = true }, modifier = Modifier.widthIn(min = 72.dp).testTag("$tag-value")) { Text(timeText(min)) }
        }
        if (open) TimeDialog(min, onPick = set, onClose = { open = false })
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("remindersDone")) { Text(tr("Done")) } },
        title = { Text(tr("Reminders")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!notifyOn) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("notifyOff")) {
                    Text(tr("Notifications are off for Ciclo, so these won't ring."), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { PhoneHooks.askNotifications?.invoke { notifyOn = PhoneHooks.notificationsEnabled?.invoke() ?: true } }) { Text(tr("Turn on")) }
                }
                Toggle(tr("Before my period"), p.period, "remPeriod") { put(p.copy(period = it)) }
                if (p.period) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Days before"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (p.periodDays > 0) put(p.copy(periodDays = p.periodDays - 1)) }, modifier = Modifier.testTag("remDays-")) { Text("−", fontSize = 20.sp) }
                    Text(p.periodDays.toString(), Modifier.widthIn(min = 32.dp).testTag("remDays-value"), textAlign = TextAlign.Center)
                    TextButton(onClick = { if (p.periodDays < 7) put(p.copy(periodDays = p.periodDays + 1)) }, modifier = Modifier.testTag("remDays+")) { Text("+", fontSize = 20.sp) }
                }
                Toggle(tr("When my fertile window starts"), p.fertile, "remFertile") { put(p.copy(fertile = it)) }
                Toggle(tr("My pill (when birth control is the pill)"), p.pill, "remPill") { put(p.copy(pill = it)) }
                if (p.pill) Time(tr("At"), p.pillAt, "remPillAt") { put(p.copy(pillAt = it)) }
                Toggle(tr("Changing my ring, patch or shot"), p.change, "remChange") { put(p.copy(change = it)) }
                Toggle(tr("A daily nudge to log"), p.log, "remLog") { put(p.copy(log = it)) }
                if (p.log) Time(tr("At"), p.logAt, "remLogAt") { put(p.copy(logAt = it)) }
                if (p.period || p.fertile || p.change) Time(tr("Period, fertile and change reminders at"), p.morningAt, "remMorning") { put(p.copy(morningAt = it)) }
                Text(tr("Reminders say only \"Ciclo reminder\" on the lock screen; the rest shows once the phone is unlocked."), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
