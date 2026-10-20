package com.vowi.vowidroid.ui.calls

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import com.vowi.vowidroid.ui.components.Speech
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.components.AppTitle
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.PhoneNumbers
import com.vowi.vowidroid.ui.components.BlockDialog
import com.vowi.vowidroid.ui.components.rememberContactPhoto
import com.vowi.vowidroid.data.CallRecord
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LinePreferences
import com.vowi.vowidroid.ui.LineStatusCard
import com.vowi.vowidroid.ui.NoLineCard
import com.vowi.vowidroid.ui.PermissionCard
import com.vowi.vowidroid.ui.TimeFormat
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberContactUri
import com.vowi.vowidroid.ui.theme.CallRed
import com.vowi.vowidroid.ui.usableLines

/**
 * The Phone tab: recent calls grouped by day, and the dialpad. As in AOSP's
 * Dialer, a tap shows a call's actions rather than calling; the call button
 * calls, and a long-press offers Copy number, Edit number before call and
 * Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    dialNumber: String?,
    onDialNumberConsumed: () -> Unit,
    callBack: Pair<String, String>?,
    onCallBackConsumed: () -> Unit,
    voicemailLine: String?,
    onVoicemailConsumed: () -> Unit,
    onMessage: (String, String) -> Unit,
    onDetails: (String) -> Unit,
    onSettings: () -> Unit,
    /** Opens a line's settings from the status card. */
    onOpenLine: (String) -> Unit,
) {
    val context = LocalContext.current
    val calls by CallLogStore.calls.collectAsStateWithLifecycle()
    val allLines by VowiRepository.lines.collectAsStateWithLifecycle()
    val lines = usableLines()
    val call = rememberCallLauncher()
    var dialpad by rememberSaveable { mutableStateOf(false) }
    var initialNumber by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun lineFor(record: CallRecord): Line? =
        lines.firstOrNull { it.id == record.deviceId } ?: LinePreferences.pick(lines, LinePreferences.defaultLine(context))

    LaunchedEffect(dialNumber) {
        if (dialNumber != null) {
            initialNumber = dialNumber
            dialpad = true
            onDialNumberConsumed()
        }
    }
    val voicemail by VowiRepository.voicemail.collectAsStateWithLifecycle()
    val callVoicemail = rememberVoicemailCaller()
    // "Call voicemail" on the voicemail notification.
    LaunchedEffect(voicemailLine, lines) {
        val lineId = voicemailLine ?: return@LaunchedEffect
        val line = lines.firstOrNull { it.id == lineId } ?: return@LaunchedEffect
        onVoicemailConsumed()
        callVoicemail(line)
    }
    // "Call back" on a missed-call notification.
    LaunchedEffect(callBack, lines) {
        val (deviceId, number) = callBack ?: return@LaunchedEffect
        if (lines.isEmpty()) return@LaunchedEffect
        onCallBackConsumed()
        call(lines.firstOrNull { it.id == deviceId } ?: LinePreferences.pick(lines, LinePreferences.defaultLine(context)), number)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTitle() },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calls_clear)) },
                                enabled = calls.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { initialNumber = ""; dialpad = true }) {
                Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.dialpad_open))
            }
        },
    ) { padding ->
        val shown = if (missedOnly) calls.filter { it.outcome == CallRecord.Outcome.MISSED } else calls
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Nothing while no permission is missing and every line is registered; the cards decide.
            item { PermissionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { LineStatusCard(onOpenLine, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (lines.isEmpty()) {
                item { NoLineCard(onSettings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            // Voicemail waiting, as AOSP's dialer shows it above the call history.
            lines.filter { voicemail[it.id]?.waiting == true }.forEach { line ->
                item(key = "voicemail-${line.id}") {
                    VoicemailCard(line.name.takeIf { lines.size > 1 }, voicemail[line.id]?.newMessages) { callVoicemail(line) }
                }
            }
            if (calls.isNotEmpty()) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = !missedOnly, onClick = { missedOnly = false }, label = { Text(stringResource(R.string.calls_filter_all)) })
                        FilterChip(selected = missedOnly, onClick = { missedOnly = true }, label = { Text(stringResource(R.string.calls_filter_missed)) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (missedOnly && calls.isNotEmpty()) R.string.calls_empty_missed else R.string.calls_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            var lastDay: String? = null
            groupCalls(shown, context).forEach { group ->
                val record = group.latest
                val day = TimeFormat.day(context, record.startedAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = record.id) {
                    val lineName = allLines.firstOrNull { it.id == record.deviceId }?.name.orEmpty()
                    CallRow(
                        group = group,
                        lineName = lineName.takeIf { allLines.size > 1 },
                        expanded = expandedId == record.id,
                        onToggle = { expandedId = if (expandedId == record.id) null else record.id },
                        onCall = { call(lineFor(record), record.number) },
                        onMessage = { onMessage(lineFor(record)?.id ?: record.deviceId, record.number) },
                        onDetails = { onDetails(record.number) },
                        onEdit = {
                            initialNumber = record.number
                            dialpad = true
                        },
                        onDelete = { CallLogStore.delete(group.records.map { it.id }) },
                    )
                }
            }
        }
    }

    if (dialpad) {
        DialpadSheet(
            initialNumber = initialNumber,
            lines = lines,
            onDismiss = { dialpad = false },
            onCall = { line, number ->
                dialpad = false
                call(line, number)
            },
            onMessage = { line, number ->
                dialpad = false
                (line ?: LinePreferences.pick(lines, LinePreferences.defaultLine(context)))?.let { onMessage(it.id, number) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.calls_clear_title)) },
            text = { Text(stringResource(R.string.calls_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    CallLogStore.clear()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    group: CallGroup,
    lineName: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val record = group.latest
    val name = rememberContactName(record.number)
    val photo = rememberContactPhoto(record.number)
    val blockedNumbers by BlockedNumbers.numbers.collectAsStateWithLifecycle()
    val isBlocked = blockedNumbers.any { PhoneNumbers.same(it, record.number) }
    var menu by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val missed = record.outcome == CallRecord.Outcome.MISSED || record.outcome == CallRecord.Outcome.REJECTED
    // "Missed call, Alex, 3 calls, 5 minutes ago, Work", as AOSP's call log reads a row.
    val description = listOfNotNull(
        stringResource(
            when {
                record.outcome == CallRecord.Outcome.BLOCKED -> R.string.a11y_call_blocked
                record.outcome == CallRecord.Outcome.MISSED -> R.string.a11y_call_missed
                record.outcome == CallRecord.Outcome.REJECTED -> R.string.a11y_call_declined
                record.outgoing -> R.string.a11y_call_outgoing
                else -> R.string.a11y_call_incoming
            },
        ),
        name ?: Speech.number(Contacts.format(record.number)),
        group.records.size.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.a11y_calls, it, it) },
        TimeFormat.short(context, record.startedAt),
        record.durationSeconds.takeIf { it > 0 }?.let { TimeFormat.duration(it) },
        lineName?.ifEmpty { null },
    ).joinToString(", ")
    val actionsLabel = stringResource(if (expanded) R.string.a11y_hide_actions else R.string.a11y_show_actions)
    val menuLabel = stringResource(R.string.action_more)
    Box {
        Column {
            ListItem(
                modifier = Modifier
                    .combinedClickable(onClick = onToggle, onClickLabel = actionsLabel, onLongClick = { menu = true }, onLongClickLabel = menuLabel)
                    .semantics { contentDescription = description },
                leadingContent = { Avatar(name, record.number, photo = photo) },
                headlineContent = {
                    Text(
                        name ?: Contacts.format(record.number),
                        color = if (missed) CallRed else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Up to three call-type icons, then the count, as AOSP's call log groups calls.
                        group.records.take(3).forEach { each ->
                            val (icon, tint) = callTypeIcon(each)
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        }
                        if (group.records.size > 3) Text("(${group.records.size})", style = MaterialTheme.typography.bodySmall)
                        val details = listOfNotNull(
                            TimeFormat.short(context, record.startedAt),
                            record.durationSeconds.takeIf { it > 0 }?.let { TimeFormat.duration(it) },
                            lineName?.ifEmpty { null },
                        )
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingContent = {
                    IconButton(onClick = onCall) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call))
                    }
                },
            )
            if (expanded) {
                val contact = rememberContactUri(record.number, name)
                Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    ActionRow(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.action_send_message), onMessage)
                    val uri = contact
                    if (uri != null) {
                        ActionRow(Icons.Outlined.Person, stringResource(R.string.action_view_contact)) { Actions.viewContact(context, uri) }
                    } else {
                        ActionRow(Icons.Outlined.PersonAdd, stringResource(R.string.action_add_contact)) { Actions.addContact(context, record.number) }
                    }
                    ActionRow(Icons.Outlined.History, stringResource(R.string.call_details_title), onDetails)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_number)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    menu = false
                    Actions.copy(context, clipboard, record.number)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit_before_call)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (isBlocked) R.string.action_unblock else R.string.action_block)) },
                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                onClick = {
                    menu = false
                    if (isBlocked) BlockedNumbers.unblock(record.number) else confirmBlock = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
    if (confirmBlock) {
        BlockDialog(record.number, name, onDismiss = { confirmBlock = false }, onConfirm = { BlockedNumbers.block(record.number) })
    }
}

/** Consecutive calls with one number on one line, on the same day, share a row, as in AOSP's call log. */
internal class CallGroup(val records: List<CallRecord>) {
    val latest: CallRecord get() = records.first()
}

private fun groupCalls(records: List<CallRecord>, context: android.content.Context): List<CallGroup> {
    val groups = ArrayList<CallGroup>()
    var current = ArrayList<CallRecord>()
    for (record in records) {
        val head = current.firstOrNull()
        val sameGroup = head != null && head.number == record.number && head.deviceId == record.deviceId &&
            (head.outcome == CallRecord.Outcome.BLOCKED) == (record.outcome == CallRecord.Outcome.BLOCKED) &&
            TimeFormat.day(context, head.startedAt) == TimeFormat.day(context, record.startedAt)
        if (!sameGroup && current.isNotEmpty()) {
            groups.add(CallGroup(current))
            current = ArrayList()
        }
        current.add(record)
    }
    if (current.isNotEmpty()) groups.add(CallGroup(current))
    return groups
}

@Composable
private fun VoicemailCard(lineName: String?, count: Int?, onCall: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Voicemail, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    count?.takeIf { it > 1 }?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.voicemail_new, it, it) }
                        ?: stringResource(R.string.voicemail_new_one),
                    style = MaterialTheme.typography.titleSmall,
                )
                lineName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onCall) { Text(stringResource(R.string.action_call_voicemail)) }
        }
    }
}

/** One of a call's actions, as a row with its icon, like AOSP's expanded call-log entry. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The call-type icon and its colour: missed and declined calls in red, as in AOSP. */
@Composable
internal fun callTypeIcon(record: CallRecord): Pair<ImageVector, Color> {
    val missed = record.outcome == CallRecord.Outcome.MISSED || record.outcome == CallRecord.Outcome.REJECTED
    return when {
        record.outcome == CallRecord.Outcome.BLOCKED -> Icons.Filled.Block to MaterialTheme.colorScheme.onSurfaceVariant
        missed -> Icons.AutoMirrored.Filled.CallMissed to CallRed
        record.outcome == CallRecord.Outcome.FAILED || record.outcome == CallRecord.Outcome.CANCELLED ->
            Icons.Filled.CallEnd to MaterialTheme.colorScheme.onSurfaceVariant
        record.outgoing -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
