package com.jurisgames.ciclo

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.OutlookView
import com.jurisgames.ciclo.ui.ReminderPrefs
import com.jurisgames.ciclo.ui.ownJournal
import com.jurisgames.ciclo.ui.shortDate
import com.jurisgames.ciclo.ui.tr
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Reminders from the journal: computed on the phone, one alarm at a time for the next one due (no exact-alarm
 * permission: a 10-minute window). What was posted is remembered per kind and day, so a reminder comes once.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    const val TAKEN = "com.jurisgames.ciclo.PILL_TAKEN"
    private const val MORNING = 9 * 60

    class Due(val kind: String, val at: LocalDateTime, val title: String, val text: String)

    /** Everything that comes up from `now`, earliest first (pure: tested on the desktop side too). */
    /** Whether `d` is a day to take an active pill (the pack started on `start`, `active` pills then `pause` days off). */
    fun pillDay(start: String, active: Long, pause: Long, d: LocalDate): Boolean {
        val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return false
        val n = ChronoUnit.DAYS.between(s, d)
        return n >= 0 && Math.floorMod(n, active + pause) < active
    }

    fun upcoming(p: ReminderPrefs, o: OutlookView, set: com.jurisgames.ciclo.core.CycleSettings, now: LocalDateTime, takenToday: Boolean): List<Due> {
        val mode = set.mode
        val bc = set.bc
        val out = mutableListOf<Due>()
        val today = now.toLocalDate()
        fun at(d: LocalDate, min: Int) = d.atStartOfDay().plusMinutes(min.toLong())
        val next = o.next.firstOrNull()
        if (mode != "pregnant" && next != null) {
            if (p.period) {
                val d = LocalDate.parse(next.start).minusDays(p.periodDays.toLong())
                val text = if (p.periodDays == 0) tr("Your period may start today.") else tr("Your period may start in {n} days ({date}).", "n" to p.periodDays, "date" to shortDate(next.start))
                out += Due("period", at(d, p.morningAt), tr("Ciclo"), text)
            }
            if (p.fertile) out += Due("fertile", at(LocalDate.parse(next.fertileFrom), p.morningAt), tr("Ciclo"), tr("Your fertile window starts today (estimated)."))
        }
        if (p.pill && bc == "pill" && set.bcStart.isNotEmpty()) {
            // today if it is an active day and not taken yet, else the next active day
            val skipToday = takenToday || at(today, p.pillAt).isBefore(now.minusMinutes(30))
            val d = (0L..60L).map { today.plusDays(it) }.firstOrNull { d ->
                pillDay(set.bcStart, set.bcActive, set.bcBreak, d) && !(d == today && skipToday)
            }
            if (d != null) out += Due("pill", at(d, p.pillAt), tr("Ciclo"), tr("Time for your pill."))
        }
        if (p.change && o.nextChange.isNotEmpty()) out += Due("change", at(LocalDate.parse(o.nextChange), p.morningAt), tr("Ciclo"), tr("Today is the day to change your birth control."))
        if (p.log) out += Due("log", at(today, p.logAt).let { if (it.isBefore(now)) it.plusDays(1) else it }, tr("Ciclo"), tr("How was today? Log it in a few taps."))
        return out.filter { !it.at.isBefore(now.minusMinutes(30)) }.sortedBy { it.at }
    }

    private fun journal(node: KtNode) = ownJournal(node, node.lists())

    /** Write the partner's summary for today, also while the app stays closed (background sync, alarms): otherwise
     *  the partner reads an old cycle day and misses a late period (UX review U-C2). Does nothing without a partner. */
    fun publishPartner(node: KtNode) {
        runCatching { journal(node)?.let { node.partnerPublish(it.id, LocalDate.now().toString()) } }
    }

    private fun state(ctx: Context): Triple<KtNode, String, ReminderPrefs>? {
        val node = (ctx.applicationContext as TasksApplication).node
        val j = journal(node) ?: return null
        return Triple(node, j.id, ReminderPrefs.read(node.dataDir()))
    }

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Set the alarm for the next reminder (or none). Called at start, after each change and after each alarm. */
    fun schedule(ctx: Context) = runCatching {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(intent(ctx))
        val (node, id, p) = state(ctx) ?: return@runCatching
        publishPartner(node)
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val o = node.cycleOutlook(id, today)
        val set = node.cycleSettings(id)
        val taken = node.cycleDay(id, today).bcTaken
        val posted = ctx.getSharedPreferences("reminders-posted", Context.MODE_PRIVATE)
        val next = upcoming(p, o, set, now, taken).firstOrNull { posted.getString(it.kind, "") != it.at.toLocalDate().toString() } ?: return@runCatching
        val ms = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 1000)
        am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60_000L, intent(ctx))
    }

    /** An alarm went off: post what is due now, then set the next one. */
    fun fire(ctx: Context) {
        runCatching {
            val (node, id, p) = state(ctx) ?: return@runCatching
            val now = LocalDateTime.now()
            val today = now.toLocalDate().toString()
            val o = node.cycleOutlook(id, today)
            val set = node.cycleSettings(id)
            val taken = node.cycleDay(id, today).bcTaken
            val posted = ctx.getSharedPreferences("reminders-posted", Context.MODE_PRIVATE)
            for (d in upcoming(p, o, set, now, taken)) {
                if (d.at.isAfter(now.plusMinutes(1)) || ChronoUnit.MINUTES.between(d.at, now) > 120) continue
                val day = d.at.toLocalDate().toString()
                if (posted.getString(d.kind, "") == day) continue
                posted.edit().putString(d.kind, day).apply()
                post(ctx, d)
            }
        }
        schedule(ctx)
    }

    private fun post(ctx: Context, d: Due) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Reminders"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // on the lock screen only "Ciclo reminder": nothing about periods or pills shows before the phone is unlocked
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(tr("Ciclo reminder")).build()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(d.title)
            .setContentText(d.text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (d.kind == "pill") {
            val taken = PendingIntent.getBroadcast(ctx, 8, Intent(ctx, ReminderReceiver::class.java).setAction(TAKEN), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, tr("Taken"), taken).build())
        }
        nm.notify(("rem:" + d.kind).hashCode(), b.build())
    }

    /** "Taken" on the pill reminder: marked in the journal, the reminder goes away. */
    fun taken(ctx: Context) {
        runCatching {
            val (node, id, _) = state(ctx) ?: return@runCatching
            node.cycleSetBcTaken(id, LocalDate.now().toString(), true)
            SyncWorker.flushNow(ctx)
        }
        ctx.getSystemService(NotificationManager::class.java).cancel("rem:pill".hashCode())
        schedule(ctx)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    Reminders.TAKEN -> Reminders.taken(ctx)
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED -> Reminders.schedule(ctx)
                    else -> Reminders.fire(ctx)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
