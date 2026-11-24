package com.jurisgames.ciclo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jurisgames.ciclo.core.InviteInfo
import com.jurisgames.ciclo.core.KtListener
import com.jurisgames.ciclo.core.KtNode
import com.jurisgames.ciclo.core.ListItem
import com.jurisgames.ciclo.core.accentColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The app: your cycle journal (Today, Calendar, Insights), partners' summaries as chips above it.
 * Every "list" of the shared core is a journal or a partner summary. The ☰ menu is a sidebar on wide
 * screens; the journal's own actions are in its ⋮ menu.
 */
@Composable
fun CicloApp(
    node: KtNode,
    onRestore: (() -> Unit)? = null,
    onExport: ((ListItem) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    openedCode: String? = null,
    onSettings: ((com.jurisgames.ciclo.core.Settings) -> Unit)? = null,
    onScan: ((onResult: (String) -> Unit) -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    update: com.jurisgames.ciclo.core.UpdateItem? = null,
    onUpdate: ((String) -> Unit)? = null,
    /** Pick an image (gallery / file) and hand over its compressed bytes. */
    onPickImage: ((onPicked: (ByteArray) -> Unit) -> Unit)? = null,
    /** Turn image bytes into a bitmap (per platform). */
    decodeImage: ((ByteArray) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    /** Why this build must update (signed operator manifest only, never peer data). */
    required: String? = null,
    /** Android 12+: wallpaper colors (Material You), for light or dark. */
    dynamicScheme: ((Boolean) -> androidx.compose.material3.ColorScheme?)? = null,
    /** Print a page (the list's name, the page as HTML): Android's print service, the desktop's print dialog. */
    onPrint: ((String, String) -> Unit)? = null,
    /** Restore a password-locked backup (its bytes, the checked password). */
    onRestoreWith: ((ByteArray, String) -> Unit)? = null,
    /** Write the whole journal to a backup file she picks, the format Restore reads (UX review U-C12). */
    onSaveBackup: (() -> Unit)? = null,
    backHandler: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
) {
    // Bumped whenever the node reports a change (another device, membership, compaction).
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(node) {
        node.setListener(object : KtListener {
            override fun onChange() {
                version++
            }
        })
        onDispose { }
    }
    var restoring by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    AppTheme(node, version, dynamicScheme) {
        CompositionLocalProvider(LocalImageDecoder provides decodeImage) {
            Surface(Modifier.fillMaxSize()) {
                // While a locked backup is restored the old node gets closed: nothing on screen may still read it.
                if (restoring == null) Home(
                    Env(node, version, { version++ }, onRestore, onExport, onShare, onSettings, onScan, onImport, update, onUpdate, onPickImage, required, onPrint, onSaveBackup, backHandler = backHandler),
                    openedCode,
                )
                // A locked backup waits for its password (picked, or restored by Android onto this phone).
                if (onRestoreWith != null) {
                    LaunchedEffect(node) { node.waitingBackup()?.let { PendingRestore.bytes = it } }
                    PendingRestore.bytes?.let { bytes ->
                        RestorePasswordDialog(bytes, onRestore = { pw -> PendingRestore.bytes = null; restoring = bytes to pw }, onCancel = { PendingRestore.bytes = null })
                    }
                    LaunchedEffect(restoring) {
                        val (bytes, pw) = restoring ?: return@LaunchedEffect
                        androidx.compose.runtime.withFrameNanos { } // the screen above is gone first
                        try { onRestoreWith(bytes, pw) } finally { restoring = null }
                    }
                }
            }
        }
    }
}

/** What every part of the screen needs. */
class Env(
    val node: KtNode,
    val version: Int,
    val changed: () -> Unit,
    val onRestore: (() -> Unit)?,
    val onExport: ((ListItem) -> Unit)?,
    val onShare: ((String) -> Unit)?,
    val onSettings: ((com.jurisgames.ciclo.core.Settings) -> Unit)?,
    val onScan: ((onResult: (String) -> Unit) -> Unit)?,
    val onImport: (() -> Unit)?,
    val update: com.jurisgames.ciclo.core.UpdateItem?,
    val onUpdate: ((String) -> Unit)?,
    val onPickImage: ((onPicked: (ByteArray) -> Unit) -> Unit)?,
    val required: String?,
    val onPrint: ((String, String) -> Unit)? = null,
    val onSaveBackup: (() -> Unit)? = null,
    /** Android's Back inside the app (a screen or tab steps back before the app closes; QR U-Q1 / Tasks U-T18). */
    val backHandler: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
)

/** The list shown last, per device (a plain file next to the app's data). */
private fun lastListFile(node: KtNode) = File(node.dataDir(), "ui-last-list")

private enum class Dialog { Settings, Join, LinkThis, LinkCode, Feedback, CycleSettings, Partner }

/** A journal this device keeps (not a partner's summary it was invited to). */
fun ownJournal(node: KtNode, lists: List<ListItem>): ListItem? =
    lists.firstOrNull { l -> runCatching { !node.partnerIsView(l.id) }.getOrDefault(true) }

@Composable
private fun Home(env: Env, openedCode: String?) {
    val node = env.node
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    Notice.text?.let { t -> LaunchedEffect(t) { Notice.text = null; snackbar.showSnackbar(t) } }
    val lists = remember(node, env.version) { node.lists() }
    val current = ownJournal(node, lists)
    val partners = lists.filter { it.id != current?.id && runCatching { node.partnerIsView(it.id) }.getOrDefault(false) }
    val opened = openedCode?.takeIf { it.isNotBlank() }
    var dialog by remember(opened) {
        mutableStateOf(if (opened == null) null else if (opened.contains("/link")) Dialog.LinkThis else Dialog.Join)
    }
    var showPartner by remember { mutableStateOf<ListItem?>(null) }
    // the partner's summary is written from the journal whenever it changes (only what was chosen to share)
    androidx.compose.runtime.LaunchedEffect(node, env.version, current?.id) {
        current?.let { j -> runCatching { node.partnerPublish(j.id, today()) } }
    }

    val menu = MenuActions(
        join = { dialog = Dialog.Join },
        linkThis = { dialog = Dialog.LinkThis },
        linkCode = { dialog = Dialog.LinkCode },
        settings = { dialog = Dialog.Settings },
        feedback = { dialog = Dialog.Feedback },
    )

    // U-C25: Ctrl+T logs today, Ctrl+, opens Settings
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
        when (e.key) {
            Key.T -> { if (current != null && dialog == null) KeyActions.logToday.intValue++; current != null }
            Key.Comma -> { dialog = Dialog.Settings; true }
            else -> false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(keys)) {
        val wide = maxWidth >= 720.dp
        @Composable
        fun Body(onMenu: (() -> Unit)?) {
            Banners(env)
            if (partners.isNotEmpty() && current != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in partners) FilterChip(false, { showPartner = p }, label = { Text(p.name, maxLines = 1) }, modifier = Modifier.testTag("partnerView-" + p.name))
                }
            }
            if (current != null) CyclePane(env, current, onMenu, snackbar, onSettings = { dialog = Dialog.CycleSettings }, onPartner = { dialog = Dialog.Partner })
            // a device that only sees partners' summaries: the summaries are the home screen (UX review U-C1)
            else if (partners.isNotEmpty()) PartnerHome(env, partners, onMenu, onJoin = menu.join)
            else FirstJournal(env, onLinkThis = menu.linkThis, onJoin = menu.join, onMenu = onMenu)
        }
        if (wide) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                Row(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                    Column(Modifier.width(280.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())) {
                        MenuPanel(env, menu, compact = false)
                    }
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 640.dp).fillMaxHeight()) { Body(null) }
                    }
                }
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                ModalDrawerSheet(Modifier.width(300.dp)) {
                    MenuPanel(env, MenuActions(
                        join = { scope.launch { drawer.close() }; menu.join() },
                        linkThis = { scope.launch { drawer.close() }; menu.linkThis() },
                        linkCode = { scope.launch { drawer.close() }; menu.linkCode() },
                        settings = { scope.launch { drawer.close() }; menu.settings() },
                        feedback = { scope.launch { drawer.close() }; menu.feedback() },
                    ), compact = false)
                }
            }) {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) { Body { scope.launch { drawer.open() } } }
                }
            }
        }
    }

    when (dialog) {
        Dialog.Settings -> SettingsDialog(node, onSaved = { env.onSettings?.invoke(it); env.changed() }, onClose = { dialog = null })
        Dialog.Join -> JoinDialog(env, opened?.takeIf { !it.contains("/link") }, onJoined = { env.changed() }, onClose = { dialog = null })
        // A device link never comes prefilled from a link: linking hands over the journal, so the code is typed or
        // scanned here, from the other device's own screen (safety audit round 4, T6).
        Dialog.LinkThis -> LinkThisDialog(env, opened?.contains("/link") == true, scope) { dialog = null }
        Dialog.LinkCode -> runCatching { node.linkCode() }.getOrNull()?.let { info ->
            CodeDialog(tr("Link another device"), tr("On your other device, open ☰ › Link to my other device, then enter this code or scan it."), info, env.onShare) { dialog = null }
        }
        Dialog.Feedback -> FeedbackDialog(node, onSent = { dialog = null; scope.launch { snackbar.showSnackbar(tr("Thanks! Your message was sent.")) } }, onClose = { dialog = null })
        Dialog.CycleSettings -> current?.let { CycleSettingsDialog(env, it) { dialog = null } }
        Dialog.Partner -> current?.let { PartnerDialog(env, it, onShare = env.onShare, onClose = { dialog = null }) }
        null -> {}
    }
    showPartner?.let { p -> PartnerViewDialog(env, p) { showPartner = null } }
}

/** Desktop keys that reach into a screen (UX review U-C25): bumped by the window's key handler. */
object KeyActions {
    val logToday = androidx.compose.runtime.mutableIntStateOf(0)
}

class MenuActions(val join: () -> Unit, val linkThis: () -> Unit, val linkCode: () -> Unit, val settings: () -> Unit, val feedback: () -> Unit)

/** The first screen: Ciclo starts with an empty journal, or this device joins your other one. */
@Composable
fun FirstJournal(env: Env, onLinkThis: () -> Unit, onJoin: () -> Unit, onMenu: (() -> Unit)?) {
    fun start() {
        val node = env.node
        runCatching { node.createList(null, tr("My cycle")) }.getOrNull() ?: return
        env.changed()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.testTag("menu")) { Icon(Ic.Menu, tr("Menu")) } else Spacer(Modifier.width(16.dp))
            Text("Ciclo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("title"))
        }
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Your period, cycle and how you feel. It stays on your own devices."), style = MaterialTheme.typography.titleMedium)
            Text(tr("No account and no ads. Everything is free. A partner sees only what you choose. Backups are encrypted so no server can read them."), style = MaterialTheme.typography.bodyMedium)
            // a partner who was told "install Ciclo" lands here too: the invite is a first-screen choice (UX review U-C1)
            androidx.compose.material3.Button(onClick = ::start, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("firstStart")) { Text(tr("Track my cycle")) }
            androidx.compose.material3.OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("firstJoin")) { Text(tr("I got a partner's invite")) }
            TextButton(onClick = onLinkThis, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("firstLink")) { Text(tr("Link to my own other phone or computer")) }
        }
    }
}

/** The ☰ menu: who you are, joining, devices, files, settings. */
@Composable
private fun MenuPanel(env: Env, a: MenuActions, compact: Boolean) {
    val node = env.node
    var name by remember(node, env.version) { mutableStateOf(node.displayName()) }
    val devices = remember(node, env.version) { node.devices() }
    val isLinking = remember(node, env.version) { node.isLinking() }
    Column(Modifier.padding(vertical = 8.dp)) {
        if (!compact) Text("Ciclo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        OutlinedTextField(
            name, { name = it; runCatching { node.setDisplayName(it.trim()) } },
            label = { Text(tr("Your name")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("name"),
        )
        MenuRow(tr("Open a partner's invite"), "join", a.join)
        if (isLinking) {
            Text(tr("Linking: this device joins your other one as soon as it's online"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("linkingStatus"))
        } else {
            MenuRow(if (devices.size > 1) tr("Your devices ({n}) · Link another device", "n" to devices.size) else tr("Link another device"), "linkDevice", a.linkCode)
            if (devices.size <= 1) MenuRow(tr("Link to my other device"), "linkThis", a.linkThis)
        }
        val pristine = remember(node, env.version) { node.isPristine() }
        if (env.onSaveBackup != null && !pristine) MenuRow(tr("Save a backup file"), "saveBackup", env.onSaveBackup)
        if (env.onRestore != null && pristine) MenuRow(tr("Restore from a backup"), "restore", env.onRestore)
        MenuRow(tr("Settings"), "settingsOpen", a.settings)
        // in the menu itself, not only inside Settings (Kleider, 2026-11-21: "Make sure users always have an easy way to give feedback or report bugs on ALL our apps")
        MenuRow(tr("Send feedback"), "feedbackMenu", a.feedback)
    }
}

@Composable
private fun MenuRow(label: String, tag: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp).testTag(tag))
}

/** Things that need a moment of attention: updates, a restore, a new device. Shown above the list. */
@Composable
private fun Banners(env: Env) {
    val node = env.node
    @Composable
    fun Banner(text: String, tag: String, vararg actions: Pair<String, () -> Unit>) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag(tag))
            for ((label, act) in actions) TextButton(onClick = act, modifier = Modifier.testTag("$tag-$label")) { Text(label) }
        }
    }
    val update = env.update
    if (env.required != null) {
        Banner(tr("Please update to keep sharing with a partner: {v}", "v" to env.required), "required", *listOfNotNull(env.onUpdate?.let { u -> tr("Update") to { u(update?.link ?: "https://apps.jurisgames.com/") } }).toTypedArray())
    } else if (update != null && env.onUpdate != null) {
        val why = if (node.newerVersionSeen()) tr("A newer version is available to use certain features") else tr("Version {v} is available", "v" to update.versionName)
        Banner(why, "update", tr("Update") to { env.onUpdate.invoke(update.link) })
    }
    for (l in remember(node, env.version) { node.recentLinks() }) {
        Banner(tr("New device linked: {name}", "name" to l.name.ifEmpty { tr("a device") }), "recentLink", tr("Undo") to { node.unlinkDevice(l.deviceId); env.changed() })
    }
    // A device an invite let in, shown to every member with an undo (second-pass audit T2-13, rule 17).
    for (j in remember(node, env.version) { runCatching { node.recentJoins() }.getOrDefault(emptyList()) }) {
        val who = j.name.ifEmpty { tr("A new device") }
        Banner(
            if (j.by.isNotEmpty()) tr("{who} joined {list}, let in by {by}'s invite", "who" to who, "list" to j.listName, "by" to j.by) else tr("{who} joined {list}", "who" to who, "list" to j.listName),
            "recentJoin",
            tr("Remove") to { runCatching { node.removeJoined(j.groupId, j.deviceId) }; env.changed() },
            tr("OK") to { runCatching { node.dismissJoin(j.groupId, j.deviceId) }; env.changed() },
        )
    }
    node.restored()?.let { r ->
        Banner(
            tr("Restored from a backup: your journal is back") + if (r.rejoiningGroups > 0u) tr("; sharing with your partner picks up again once their phone is online") else "",
            "restoredInfo",
        )
    }
    val old = remember(node, env.version) { node.replacedDeviceGroups() }
    if (old > 0u) {
        Banner(
            tr("Your previous device can still see what you share. Lost or stolen?"), "oldDevice",
            tr("Remove it") to { node.removeReplacedDevice(); env.changed() }, tr("Keep it") to { node.keepReplacedDevice(); env.changed() },
        )
    }
}

/** An invite or link code: copy / share the link, the words with Copy, the QR code on request. */
@Composable
internal fun CodeDialog(title: String, hint: String, info: InviteInfo, onShare: ((String) -> Unit)?, onClose: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var qr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf("") }
    val until = java.time.format.DateTimeFormatter.ofPattern("d MMM", Lang.locale).format(fromMillis(info.expiresMs.toLong())).let(::shortName)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("codeDone")) { Text(tr("Done")) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(info.link)); copied = tr("Link copied") }, modifier = Modifier.testTag("copyLink")) { Text(tr("Copy link")) }
                    if (onShare != null) androidx.compose.material3.OutlinedButton(onClick = { onShare(info.link) }, modifier = Modifier.testTag("shareInvite")) { Text(tr("Share")) }
                }
                Text(tr("Or send them this code"), style = MaterialTheme.typography.labelLarge)
                Text(info.words.joinToString(" - "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("words"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(info.words.joinToString(" "))); copied = tr("Code copied") }, modifier = Modifier.testTag("copyCode")) { Text(tr("Copy code")) }
                    TextButton(onClick = { qr = !qr }, modifier = Modifier.testTag("showQr")) { Text(if (qr) tr("Hide QR code") else tr("Show QR code")) }
                }
                if (qr) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrView(info.link) }
                Text((if (copied.isNotEmpty()) "$copied · " else "") + tr("Works until {date}", "date" to until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun LinkThisDialog(env: Env, fromLink: Boolean, scope: CoroutineScope, onClose: () -> Unit) {
    val node = env.node
    var words by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    val ownLists = remember(node, env.version) { node.lists().size }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            if (preview == null) {
                TextButton(onClick = {
                    result = tr("Looking…")
                    scope.launch {
                        try {
                            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.linkPreview(words) }
                            // Fixed words say what is handed over; the other side's own name and summary are shown as
                            // theirs (in quotes), never as the app's wording.
                            preview = tr("Linking gives that device everything: your whole journal and every summary you share. Only go on if you made this code yourself on your other device.") +
                                "\n" + tr("It calls itself “{name}” ({summary})", "name" to p.accountName, "summary" to trSummary(p.summary)) + when (ownLists) {
                                0 -> "."
                                1 -> tr(". The journal on this device is added to them.")
                                else -> tr(". The {n} journals and summaries on this device are added to them.", "n" to ownLists)
                            }
                            result = ""
                        } catch (e: Exception) {
                            result = userError(e, tr("That code didn't work. Check it on your other device."))
                        }
                    }
                }, modifier = Modifier.testTag("linkFind")) { Text(tr("Next")) }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        result = try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { node.linkConfirm(words) }
                            env.changed()
                            tr("Linking: this device joins as soon as your other one is online")
                        } catch (e: Exception) {
                            userError(e, tr("Couldn't link. Try again."))
                        }
                    }
                }, modifier = Modifier.testTag("linkGo")) { Text(tr("Link")) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Close")) } },
        title = { Text(tr("Link to my other device")) },
        text = {
            Column {
                if (fromLink) Text(tr("For safety, a device is linked only with the code shown on your other device: type it or scan it here."), modifier = Modifier.testTag("linkFromLink"))
                OutlinedTextField(words, { words = it; preview = null }, label = { Text(tr("Code or link from your other device")) }, modifier = Modifier.testTag("linkThisWords"))
                env.onScan?.let { scan -> TextButton(onClick = { scan { words = it; preview = null } }, modifier = Modifier.testTag("scanLink")) { Text(tr("Scan QR code")) } }
                preview?.let { Text(it, modifier = Modifier.testTag("linkPreview")) }
                if (result.isNotEmpty()) Text(result, modifier = Modifier.testTag("linkResult"))
            }
        },
    )
}
