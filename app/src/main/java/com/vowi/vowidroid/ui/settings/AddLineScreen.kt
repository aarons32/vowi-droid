package com.vowi.vowidroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.R
import com.vowi.vowidroid.SimAccess
import com.vowi.vowidroid.UsbSimReaders
import com.vowi.vowidroid.data.DiscoveredLine
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SIM cards VowiDroid found but no line owns yet, in the phone's slots and in
 * USB SIM readers, and readers it may not use yet. The list keeps looking
 * while it is open, so a card put in now shows up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<DiscoveredLine>?>(null) }
    var confirm by remember { mutableStateOf<DiscoveredLine?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { VowiRepository.discovered() }
                .onSuccess { found = it }
                .onFailure { if (found == null) found = emptyList() }
            delay(3_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_add_line)) },
            )
        },
    ) { padding ->
        val lines = found
        if (lines == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.add_line_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(lines, key = { it.usbPath + "|" + it.iccid }) { line ->
                val reader = line.kind == Line.Kind.USB_READER
                val needsAccess = line.issue == UsbSimReaders.ISSUE_PERMISSION
                ListItem(
                    modifier = Modifier.clickable(enabled = line.issue.isEmpty() || needsAccess) {
                        if (needsAccess) {
                            scope.launch { runCatching { VowiRepository.requestReaderAccess(line.usbPath) } }
                        } else {
                            confirm = line
                        }
                    },
                    leadingContent = { Icon(if (reader) Icons.Filled.Usb else Icons.Filled.SimCard, contentDescription = null) },
                    headlineContent = { Text(line.name) },
                    supportingContent = { Text(issueText(line) ?: stringResource(R.string.add_line_in, line.place)) },
                )
            }
            item {
                Text(
                    stringResource(R.string.add_line_readers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    confirm?.let { line ->
        // A found card can also take over a line whose own card is gone.
        val orphans = remember(line) { VowiRepository.linesWithoutCard() }
        var target by remember(line) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.add_line_choose_title, line.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (line.kind == Line.Kind.PHONE_SIM) Text(stringResource(R.string.add_line_confirm_body))
                    if (orphans.isNotEmpty()) {
                        Choice(stringResource(R.string.add_line_as_new), stringResource(R.string.add_line_as_new_body), target == null) { target = null }
                        orphans.forEach { orphan ->
                            Choice(stringResource(R.string.add_line_use_for, orphan.name), stringResource(R.string.add_line_use_for_body), target == orphan.id) { target = orphan.id }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        runCatching { VowiRepository.addLine(line, target) }
                            .onSuccess { onBack() }
                            .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                    }
                }) { Text(stringResource(R.string.add_line_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodySmall) },
    )
}

/** Why a slot, card or reader cannot be added yet, in words; null when it can. */
@Composable
private fun issueText(line: DiscoveredLine): String? = when (line.issue) {
    "" -> null
    UsbSimReaders.ISSUE_PERMISSION -> stringResource(R.string.add_line_reader_access)
    UsbSimReaders.ISSUE_NO_CARD -> stringResource(R.string.add_line_reader_no_card)
    UsbSimReaders.ISSUE_PIN -> stringResource(R.string.add_line_pin)
    UsbSimReaders.ISSUE_UNUSABLE -> stringResource(R.string.add_line_reader_unusable)
    SimAccess.ISSUE_UNSUPPORTED -> stringResource(R.string.setup_access_unsupported)
    SimAccess.ISSUE_PERMISSION, SimAccess.ISSUE_ACCESS -> stringResource(R.string.settings_sim_access_needed)
    else -> stringResource(R.string.add_line_issue, line.issue)
}
