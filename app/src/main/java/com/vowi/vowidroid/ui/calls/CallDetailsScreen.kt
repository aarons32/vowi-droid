package com.vowi.vowidroid.ui.calls

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.data.CallRecord
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LinePreferences
import com.vowi.vowidroid.ui.TimeFormat
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberContactUri
import com.vowi.vowidroid.ui.usableLines
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Block
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.PhoneNumbers
import com.vowi.vowidroid.ui.components.BlockDialog
import com.vowi.vowidroid.ui.components.rememberContactPhoto

/**
 * Every call with one number, as AOSP's call details show a call-log entry:
 * a header with call-back actions, then each call's type, time, duration and
 * line. Delete removes the number's history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailsScreen(number: String, onBack: () -> Unit, onMessage: (String, String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val all by CallLogStore.calls.collectAsStateWithLifecycle()
    val allLines by VowiRepository.lines.collectAsStateWithLifecycle()
    val lines = usableLines()
    val call = rememberCallLauncher()
    val records = all.filter { it.number == number }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val photo = rememberContactPhoto(number)
    val blockedNumbers by BlockedNumbers.numbers.collectAsStateWithLifecycle()
    val isBlocked = blockedNumbers.any { PhoneNumbers.same(it, number) }
    val name = rememberContactName(number)
    val contact = rememberContactUri(number, name)
    // Calls and texts go out on the line of the latest call, when it is still usable.
    val latestLine = records.firstOrNull()?.deviceId
    val line = lines.firstOrNull { it.id == latestLine } ?: LinePreferences.pick(lines, LinePreferences.defaultLine(context))

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.call_details_title)) },
                actions = {
                    IconButton(onClick = { if (isBlocked) BlockedNumbers.unblock(number) else confirmBlock = true }) {
                        Icon(
                            if (isBlocked) Icons.Outlined.Block else Icons.Filled.Block,
                            contentDescription = stringResource(if (isBlocked) R.string.action_unblock else R.string.action_block),
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = records.isNotEmpty()) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(name, number, size = 72.dp, photo = photo)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        name ?: Contacts.format(number),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (name != null) Text(Contacts.format(number), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        HeaderAction(Icons.Filled.Call, stringResource(R.string.dialpad_call), enabled = line != null) { call(line, number) }
                        HeaderAction(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.action_send_message), enabled = line != null) {
                            line?.let { onMessage(it.id, number) }
                        }
                        HeaderAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_number)) {
                            Actions.copy(context, clipboard, number)
                        }
                        val uri = contact
                        if (uri != null) {
                            HeaderAction(Icons.Outlined.Person, stringResource(R.string.action_view_contact)) { Actions.viewContact(context, uri) }
                        } else {
                            HeaderAction(Icons.Outlined.PersonAdd, stringResource(R.string.action_add_contact)) { Actions.addContact(context, number) }
                        }
                    }
                }
                HorizontalDivider()
            }
            items(records, key = { it.id }) { record ->
                val (icon, tint) = callTypeIcon(record)
                val lineName = allLines.firstOrNull { it.id == record.deviceId }?.name
                ListItem(
                    leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
                    headlineContent = { Text(stringResource(callTypeLabel(record))) },
                    supportingContent = {
                        val details = listOfNotNull(
                            DateUtils.formatDateTime(
                                context, record.startedAt.toEpochMilli(),
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                            ),
                            record.durationSeconds.takeIf { it > 0 }?.let { TimeFormat.duration(it) },
                            lineName?.takeIf { allLines.size > 1 },
                        )
                        Text(details.joinToString(" · "))
                    },
                )
            }
        }
    }

    if (confirmBlock) {
        BlockDialog(number, name, onDismiss = { confirmBlock = false }, onConfirm = { BlockedNumbers.block(number) })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.call_details_delete_title)) },
            text = { Text(stringResource(R.string.call_details_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    CallLogStore.deleteNumber(number)
                    onBack()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** How AOSP's call details name each kind of call. */
private fun callTypeLabel(record: CallRecord): Int = when (record.outcome) {
    CallRecord.Outcome.MISSED -> R.string.call_type_missed
    CallRecord.Outcome.REJECTED -> R.string.call_type_declined
    CallRecord.Outcome.CANCELLED -> R.string.call_type_cancelled
    CallRecord.Outcome.FAILED -> R.string.call_type_failed
    CallRecord.Outcome.BLOCKED -> R.string.call_type_blocked
    CallRecord.Outcome.ANSWERED -> if (record.outgoing) R.string.call_type_outgoing else R.string.call_type_incoming
}
