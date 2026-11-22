package com.jurisgames.ciclo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jurisgames.ciclo.core.InviteInfo
import com.jurisgames.ciclo.core.ListItem
import com.jurisgames.ciclo.core.PartnerSummary

private val SHARES = listOf(
    "period" to "When my next period is due, my cycle day, and if it's late",
    "fertile" to "My fertile window",
    "feel" to "How I feel today (feelings and energy only)",
    "preg" to "Pregnancy week and due date",
)

/**
 * Sharing with a partner: what they see, who sees it, and an invite. The journal itself is never shared: the
 * partner gets a small summary that holds only the ticked items, written by this app.
 */
@Composable
fun PartnerDialog(env: Env, journal: ListItem, onShare: ((String) -> Unit)?, onClose: () -> Unit) {
    val node = env.node
    val shares = remember(node, env.version, journal.id) { runCatching { node.partnerShares(journal.id) }.getOrDefault(emptyList()) }
    val partners = remember(node, env.version, journal.id) { node.partnerLists(journal.id) }
    var invite by remember { mutableStateOf<InviteInfo?>(null) }
    var error by remember { mutableStateOf("") }
    // U-C13: Stop sharing can be taken back while the dialog is open
    var stopped by remember { mutableStateOf<Pair<ListItem, String>?>(null) }
    fun newInvite() {
        runCatching {
            val l = node.createList(null, node.displayName().let { if (it.isBlank()) tr("Your partner's cycle") else tr("{name}'s cycle", "name" to it) })
            node.partnerInit(l.id, journal.id)
            val shared = node.shareList(l.id)
            env.changed()
            invite = node.invite(shared.groupId, 30u)
        }.onFailure { error = userError(it, tr("Couldn't share")) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("partnerDone")) { Text(tr("Done")) } },
        title = { Text(tr("Share with a partner")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameAsk(node)
                Text(tr("Your partner gets a summary, never your journal: only what you tick here, nothing else (no notes, no symptoms, no sex). Change it any time; it updates on their phone each day your phone is online."), style = MaterialTheme.typography.bodyMedium)
                // U-C14: the whole row is the tick, words included, 48 dp tall
                for ((k, label) in SHARES) CheckRow(tr(label), k in shares, "share-$k") { on ->
                    runCatching { node.partnerSetShares(journal.id, if (on) shares + k else shares - k) }
                    env.changed()
                }
                HorizontalDivider()
                if (partners.isEmpty()) Text(tr("Nobody sees a summary yet."), style = MaterialTheme.typography.bodySmall)
                for (p in partners) {
                    val people = remember(node, env.version, p.id) { runCatching { node.people(p.id) }.getOrDefault(emptyList()) }.filter { !it.isMe }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val who = people.joinToString { it.name.ifBlank { tr("Someone") } }
                        // an unused invite can be shown again (a new code for the same summary, not a new summary)
                        if (people.isEmpty()) TextButton(onClick = {
                            runCatching { invite = node.invite(p.groupId, 30u) }.onFailure { error = userError(it, tr("Couldn't share")) }
                        }, modifier = Modifier.weight(1f).testTag("inviteAgain")) { Text(tr("Invite not used yet: show it again")) }
                        else Text(who, Modifier.weight(1f))
                        TextButton(onClick = {
                            runCatching { node.deleteList(p.id) }.onSuccess { stopped = p to who.ifEmpty { tr("Nobody") } }
                            env.changed()
                        }, modifier = Modifier.testTag("stopSharing")) { Text(tr("Stop sharing")) }
                    }
                }
                stopped?.let { (p, who) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("stoppedRow")) {
                        Text(tr("{name} no longer sees your summary.", "name" to who), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { runCatching { node.restoreList(p.groupId, p.id) }; stopped = null; env.changed() }, modifier = Modifier.testTag("stopUndo")) { Text(tr("Undo")) }
                    }
                }
                // an invite with nothing ticked would show the partner only a date (U-C13)
                Button(onClick = ::newInvite, enabled = shares.isNotEmpty(), modifier = Modifier.testTag("partnerInvite")) { Text(tr("Invite a partner")) }
                if (shares.isEmpty()) Text(tr("Tick at least one thing to share."), style = MaterialTheme.typography.bodySmall)
                Text(tr("They need Ciclo too (free). Stop sharing removes the summary from this phone; their copy stops getting updates."), style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
    )
    invite?.let { info -> CodeDialog(tr("Invite your partner"), tr("Send this to your partner. Anyone with this link or code can see the summary until it expires, so send it only to them."), info, onShare) { invite = null } }
}

/** A tick whose whole row, words included, is the target (48 dp), read as one by a screen reader (U-C14). */
@Composable
fun CheckRow(label: String, on: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(on, null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** A partner's summary, as this phone got it. */
@Composable
fun PartnerViewDialog(env: Env, list: ListItem, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("partnerViewDone")) { Text(tr("Close")) } },
        title = { Text(list.name) },
        text = { PartnerViewContent(env, list) },
    )
}

/** A partner's summary moved on to [now]: it was written on [PartnerSummary.at], and the sharer's phone may not have
 *  been online since, so the cycle day, a late period and the pregnancy week are counted on from there (UX review U-C2). */
data class PartnerNow(val cycleDay: Long, val lateDays: Long, val pregWeek: Long)

fun partnerNow(v: PartnerSummary, now: String = today()): PartnerNow {
    val since = daysBetween(v.at, now).coerceAtLeast(0)
    val day = if (v.cycleDay >= 0) v.cycleDay + since else -1
    val late = when {
        v.lateDays > 0 -> v.lateDays + since
        // late only past the usual variation the sharer's phone worked out (U-C5); old summaries: 5 days after it
        v.expected.isNotEmpty() -> if (now > v.lateFrom.ifEmpty { v.expected }) daysBetween(v.expected, now) else 0
        v.nextPeriod.isNotEmpty() && daysBetween(v.nextPeriod, now) > 5 -> daysBetween(v.nextPeriod, now)
        else -> 0
    }
    // the due date is 280 days after the period the pregnancy is counted from
    val week = if (v.pregnant && v.pregWeek >= 0 && v.due.isNotEmpty()) ((280 - daysBetween(now, v.due)) / 7).coerceAtLeast(0) else v.pregWeek
    return PartnerNow(day, late, week)
}

/** What a partner's summary says, as of today. Shown in the dialog and, on a device with no journal, on the home screen. */
@Composable
fun PartnerViewContent(env: Env, list: ListItem) {
    val v = remember(env.node, env.version, list.id) { runCatching { env.node.partnerView(list.id) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("partnerView")) {
        if (v == null || v.at.isEmpty()) {
            Text(tr("Nothing yet: it shows here once their phone is online."))
            return@Column
        }
        val now = partnerNow(v)
        val who = v.name.ifBlank { tr("Your partner") }
        if (v.pregnant && now.pregWeek >= 0) Text(tr("{name} is in week {w} (due {date})", "name" to who, "w" to now.pregWeek, "date" to shortDate(v.due)), style = MaterialTheme.typography.titleMedium)
        if (now.cycleDay >= 0) Text(tr("{name} is on day {n} of the cycle", "name" to who, "n" to now.cycleDay), style = MaterialTheme.typography.titleMedium)
        if (now.lateDays > 0) Text(tr("The period is later than usual: {days}", "days" to daysText(now.lateDays)), color = periodText())
        else if (v.nextPeriod.isNotEmpty()) Text(tr("Next period expected {date}", "date" to dayLabel(v.nextPeriod)), color = periodText())
        if (v.fertileFrom.isNotEmpty() && v.fertileTo >= today()) Text(tr("Fertile window: {from} to {to}", "from" to shortDate(v.fertileFrom), "to" to shortDate(v.fertileTo)), color = fertileText())
        if (v.feel.isNotEmpty() && v.at == today()) Text(tr("Today: {feel}", "feel" to v.feel.joinToString { Words.tagLabel(it, emptyMap()) }))
        Text(tr("As of {date}. Estimates, not a method of birth control.", "date" to dayLabel(v.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The home screen of a device that has partners' summaries and no journal of its own (UX review U-C1): the
 *  summaries themselves, one card each, and a quiet way to start tracking too. */
@Composable
fun PartnerHome(env: Env, partners: List<ListItem>, onMenu: (() -> Unit)?, onJoin: () -> Unit) {
    fun start() {
        runCatching { env.node.createList(null, tr("My cycle")) }.getOrNull() ?: return
        env.changed()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.testTag("menu")) { Icon(Ic.Menu, tr("Menu")) } else Spacer(Modifier.width(16.dp))
            Text("Ciclo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("title"))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (p in partners) ElevatedCard(Modifier.fillMaxWidth().testTag("partnerCard-" + p.name)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleLarge)
                    PartnerViewContent(env, p)
                }
            }
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("partnerHomeJoin")) { Text(tr("Open another invite")) }
            TextButton(onClick = ::start, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("partnerHomeStart")) { Text(tr("Track my own cycle too")) }
        }
    }
}
