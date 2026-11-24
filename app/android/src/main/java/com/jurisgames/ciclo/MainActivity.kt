package com.jurisgames.ciclo

import com.jurisgames.ciclo.ui.tr
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import com.jurisgames.ciclo.ui.CicloApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lock's state for the whole process (U-C10): a recreated Activity reads it instead of starting over. */
private object LockState {
    var stoppedAt = 0L
    var locked = false
}

class MainActivity : ComponentActivity() {
    private val app get() = application as TasksApplication

    // APP-RULES 216: the activity keeps running through rotation and dark mode (configChanges in the manifest), so the
    // bars' colours are set again here instead of by a fresh start
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // the app draws behind the status and navigation bars and pads itself
        super.onCreate(savedInstanceState)
        // A CSV shared earlier is the whole journal in clear text: never left in the cache (round 5, S9).
        if (savedInstanceState == null) java.io.File(cacheDir, "exports").deleteRecursively()
        opened.value = ourLink(intent)
        QrScanner.prepare(this) // play: fetch Google's scanner module before anyone taps Scan
        com.jurisgames.ciclo.ui.PhoneHooks.deviceLockAvailable = keyguard.isDeviceSecure
        com.jurisgames.ciclo.ui.PhoneHooks.onRemindersChanged = { Reminders.schedule(this); applySecure() }
        com.jurisgames.ciclo.ui.PhoneHooks.notificationsEnabled = { getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
        com.jurisgames.ciclo.ui.PhoneHooks.askNotifications = { done -> askForNotifications(done) }
        applySecure()
        setContent {
            if (locked.value) {
                LockScreen()
                return@setContent
            }
            var node by remember { mutableStateOf(app.node) }
            val scope = rememberCoroutineScope()
            // Images on tasks: the system photo picker (no storage permission), shrunk first.
            var onPicked by remember { mutableStateOf<((ByteArray) -> Unit)?>(null) }
            val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                val done = onPicked
                onPicked = null
                if (uri != null && done != null) scope.launch {
                    val bytes = withContext(Dispatchers.IO) { Images.compress(this@MainActivity, uri) }
                    if (bytes != null) done(bytes) else Toast.makeText(this@MainActivity, tr("Couldn't read that image"), Toast.LENGTH_LONG).show()
                }
            }
            val importList = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    val msg = try {
                        val l = withContext(Dispatchers.IO) { node.importListFile(null, contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                        tr("Imported {name}", "name" to l.name)
                    } catch (e: Exception) {
                        com.jurisgames.ciclo.ui.userError(e, tr("This isn't a Ciclo backup file."))
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            fun restore(read: () -> ByteArray, password: String?) = scope.launch {
                try {
                    val restored = withContext(Dispatchers.IO) {
                        val bytes = read()
                        // A password-locked backup: the app asks for its password first (audit T11).
                        if (password == null && com.jurisgames.ciclo.core.backupNeedsPassword(bytes)) {
                            com.jurisgames.ciclo.ui.PendingRestore.bytes = bytes
                            null
                        } else app.restoreFrom(bytes, password)
                    }
                    if (restored != null) {
                        node = restored
                        node.startLive()
                    }
                } catch (e: Exception) {
                    node = app.node
                    Toast.makeText(this@MainActivity, com.jurisgames.ciclo.ui.userError(e, tr("Restore failed")), Toast.LENGTH_LONG).show()
                }
            }
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) restore({ contentResolver.openInputStream(uri)!!.use { it.readBytes() } }, null)
            }
            // U-C12: the whole journal in one file she keeps (Drive, Downloads), the format Restore reads
            val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val tmp = java.io.File(cacheDir, "backup-out.ktbackup")
                            try {
                                node.writeBackup(tmp.path)
                                contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                            } finally { tmp.delete() }
                        }.isSuccess
                    }
                    Toast.makeText(this@MainActivity, if (ok) tr("Backup saved") else tr("Couldn't save the backup"), Toast.LENGTH_LONG).show()
                }
            }
            CicloApp(node, backHandler = { enabled, back -> androidx.activity.compose.BackHandler(enabled, back) }, onSaveBackup = { saveBackup.launch("Ciclo-${java.time.LocalDate.now()}.ktbackup") }, onPickImage = { done ->
                onPicked = done
                pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, decodeImage = Images::decode, dynamicScheme = if (Build.VERSION.SDK_INT >= 31) { dark -> if (dark) androidx.compose.material3.dynamicDarkColorScheme(this) else androidx.compose.material3.dynamicLightColorScheme(this) } else null, onShare = { link ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), tr("Share invite")))
            }, update = update.value, required = required.value, onUpdate = { link ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
            }, onScan = { QrScanner.scan(this, it) }, onPrint = { name, html -> Print.page(this, name, html) }, openedCode = opened.value, onSettings = { app.applyBackgroundSync(it.syncInBackground) }, onRestore = { pick.launch(arrayOf("*/*")) }, onRestoreWith = { bytes, pw -> restore({ bytes }, pw) }, onExport = { l ->
                // The activity as a CSV file, shared to any app (Sheets, email, Drive).
                // Only the export being shared stays (round 5, S9): older copies of the whole journal go first.
                val dir = java.io.File(cacheDir, "exports").apply { listFiles()?.forEach { it.delete() }; mkdirs() }
                val file = java.io.File(dir, com.jurisgames.ciclo.core.exportFileStem(l.name) + ".csv")
                file.writeText(node.exportCsv(l.id, com.jurisgames.ciclo.ui.Words.tagLabels()))
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/csv")
                    .putExtra(Intent.EXTRA_SUBJECT, l.name)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, tr("Export {name}", "name" to l.name)))
            })
            // Only someone with a shared list is told about shared lists (cold UX review U-T5).
            var blocked by remember { mutableStateOf(app.node.groups().any { !it.personal } && BackgroundCheck.shouldExplain(this@MainActivity)) }
            if (blocked) androidx.compose.material3.AlertDialog(
                onDismissRequest = { blocked = false },
                title = { androidx.compose.material3.Text(tr("Updates only while the app is open")) },
                text = { androidx.compose.material3.Text(tr("This phone is stopping Ciclo from syncing in the background, so your other devices and your partner's summary only update while the app is open. To fix it, allow background activity for Ciclo in its battery settings.")) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { blocked = false; BackgroundCheck.openSettings(this@MainActivity) }, modifier = androidx.compose.ui.Modifier.testTag("bgSettings")) {
                        androidx.compose.material3.Text(tr("Battery settings"))
                    }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { blocked = false }) { androidx.compose.material3.Text(tr("Not now")) } },
            )
        }
        holdSplash()
    }

    /** The splash stays until the first sync round or ~1.5 s (DESIGN §8), so lists open current. */
    private fun holdSplash() {
        val shownAt = android.os.SystemClock.uptimeMillis()
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val ready = app.node.firstRoundDone() || android.os.SystemClock.uptimeMillis() - shownAt > 1500
                if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        })
    }

    /** A newer version, if the daily check found one. */
    private val update = mutableStateOf<com.jurisgames.ciclo.core.UpdateItem?>(null)

    /** At most once a day, off the main thread. */
    private fun checkForUpdate() {
        val prefs = getSharedPreferences("update", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked", 0) < 24 * 3600_000L) return
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toULong()
        lifecycleScope.launch(Dispatchers.IO) {
            // A failed check is just "nothing found this time": tried again next launch.
            val found = runCatching { app.node.checkUpdate(current, Store.UPDATE_CHANNEL) }.getOrElse { return@launch }
            prefs.edit().putLong("checked", now).apply()
            update.value = found
        }
    }

    /** The invite or device link that opened the app, if any. */
    private val opened = mutableStateOf<String?>(null)

    /** Why this build must update (from the signed operator manifest), if it must. */
    private val required = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened.value = ourLink(intent)
    }

    /** Only a VIEW of one of our own join / link pages is taken from an intent (any app can send one). */
    private fun ourLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val text = intent.dataString?.trim() ?: return null
        return text.takeIf { INVITE_LINK.matches(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only with window focus (Android 10+).
        if (hasFocus) checkClipboardOnce()
    }

    /**
     * Install from an invite (DESIGN §7): the join page copies the link before sending
     * people to the store, so the first launch of this install looks for it. The clip's
     * description is checked first, which shows no "pasted" notice; the text is read only if
     * it may be a link, and taken only if it is one of ours. Otherwise, tapping the link
     * again opens the app with it.
     */
    private fun checkClipboardOnce() {
        val marker = java.io.File(noBackupFilesDir, "clipboard_checked")
        if (marker.exists()) return
        marker.writeText("")
        if (opened.value != null) return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val d = cm.primaryClipDescription ?: return
        if (!d.hasMimeType("text/*")) return
        if (Build.VERSION.SDK_INT >= 31 && d.classificationStatus == android.content.ClipDescription.CLASSIFICATION_COMPLETE &&
            d.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) < 0.5f
        ) return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
        if (INVITE_LINK.matches(text)) {
            opened.value = text
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() // the code is a secret; don't leave it around
        }
    }

    private companion object {
        val INVITE_LINK = Regex("https://apps\\.jurisgames\\.com/ciclo/(join|link)/?#[^\\s]+")
    }

    // ---- the lock: the phone's own PIN, pattern or fingerprint (Reminders and lock) ----

    private val keyguard by lazy { getSystemService(android.app.KeyguardManager::class.java) }
    // U-C10: kept for the whole process, not the Activity: turning the phone or a dark-mode change makes a new
    // Activity, which must neither ask for the PIN again within the minute nor open while locked.
    private val locked = mutableStateOf(LockState.locked)
    private fun setLocked(v: Boolean) { locked.value = v; LockState.locked = v }
    private fun lockOn() = com.jurisgames.ciclo.ui.ReminderPrefs.read(app.node.dataDir()).lock && keyguard.isDeviceSecure

    /** Hidden from the recent apps screen and screenshots while the lock is on. */
    private fun applySecure() {
        if (lockOn()) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Suppress("DEPRECATION") // the device-credential screen; BiometricPrompt needs a FragmentActivity
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) setLocked(false)
    }

    @Suppress("DEPRECATION")
    private fun askUnlock() {
        val i = keyguard.createConfirmDeviceCredentialIntent(tr("Ciclo is locked"), tr("Unlock to see your journal")) ?: run { setLocked(false); return }
        unlock.launch(i)
    }

    @androidx.compose.runtime.Composable
    private fun LockScreen() {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().testTag("locked"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(tr("Ciclo is locked"), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = ::askUnlock, modifier = androidx.compose.ui.Modifier.testTag("unlock")) { androidx.compose.material3.Text(tr("Unlock")) }
        }
    }

    override fun onStart() {
        super.onStart()
        // locked again after a minute away (or a fresh start)
        if (lockOn() && (LockState.stoppedAt == 0L || android.os.SystemClock.elapsedRealtime() - LockState.stoppedAt > 60_000)) {
            if (!locked.value) { setLocked(true); askUnlock() }
        }
        Reminders.schedule(this)
        app.node.markSeen() // what's on screen needs no notification
        app.reportDeviceState()
        multicast.acquire() // Android drops multicast (LAN beacons) without it
        app.node.startLive() // app open: live on every layer
        checkForUpdate()
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toULong()
        required.value = runCatching { app.node.requiredUpdate(current, Store.UPDATE_CHANNEL) }.getOrNull()
    }

    /** Asked where it matters, never at start (UX review U-C9): from "Turn on" on Today or a reminder switched on.
     *  The phone's own dialog the first time; once refused, the app's notification settings. */
    private var notifyDone: (() -> Unit)? = null
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifyDone?.invoke(); notifyDone = null }
    private val notifySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { notifyDone?.invoke(); notifyDone = null }

    private fun askForNotifications(done: () -> Unit) {
        if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { done(); return }
        notifyDone = done
        val prefs = getSharedPreferences("notify", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply()
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notifySettings.launch(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Held while the app is open, for the same-Wi-Fi beacons (§6). */
    private val multicast by lazy {
        getSystemService(android.net.wifi.WifiManager::class.java).createMulticastLock("ciclo-lan").apply { setReferenceCounted(false) }
    }

    override fun onStop() {
        LockState.stoppedAt = android.os.SystemClock.elapsedRealtime()
        Reminders.schedule(this) // today's logs may change what comes next
        if (multicast.isHeld) multicast.release()
        app.node.stopLive()
        SyncWorker.flushNow(this) // everything is saved; send it without waiting (§8)
        super.onStop()
    }
}
