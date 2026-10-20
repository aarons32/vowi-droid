package com.vowi.vowidroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.VowiRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Call forwarding for one line, laid out like AOSP's GSM call forwarding
 * settings: always, when busy, when unanswered and when unreachable. VowiDroid
 * has no XCAP (Ut) client, so it sends the standard forwarding codes of
 * 3GPP TS 22.030 to the carrier as USSD and shows what the carrier answers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallForwardingScreen(lineId: String, onBack: () -> Unit) {
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    val line = lines.firstOrNull { it.id == lineId }
    val scope = rememberCoroutineScope()
    // One USSD conversation at a time per line.
    val busy = remember { Mutex() }
    val status = remember { mutableStateMapOf<Forwarding, ForwardingStatus>() }
    var editing by remember { mutableStateOf<Forwarding?>(null) }

    fun run(kind: Forwarding, code: String) {
        status[kind] = ForwardingStatus.Checking
        scope.launch {
            busy.withLock {
                val result = runCatching { VowiRepository.ussd(lineId, code) }
                result.onSuccess { if (!it.final) runCatching { VowiRepository.ussdCancel(lineId) } }
                status[kind] = result.fold({ ForwardingStatus.from(it.text) }, { ForwardingStatus.Failed(it.message.orEmpty().removePrefix("ims: ")) })
            }
        }
    }

    fun checkAll() = Forwarding.entries.forEach { run(it, it.query) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.forwarding_title)) },
                actions = {
                    IconButton(onClick = ::checkAll, enabled = line?.registered == true) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.forwarding_check))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            line?.takeIf { lines.size > 1 }?.let {
                Text(it.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
            Forwarding.entries.forEach { kind ->
                ListItem(
                    modifier = Modifier.clickable(enabled = line?.registered == true) { editing = kind },
                    headlineContent = { Text(stringResource(kind.label)) },
                    supportingContent = {
                        Text(
                            when (val current = status[kind]) {
                                null -> stringResource(R.string.forwarding_unknown)
                                ForwardingStatus.Checking -> stringResource(R.string.forwarding_checking)
                                ForwardingStatus.Off -> stringResource(R.string.forwarding_off)
                                is ForwardingStatus.On -> stringResource(R.string.forwarding_to, Contacts.format(current.number))
                                is ForwardingStatus.Said -> current.text
                                is ForwardingStatus.Failed -> stringResource(R.string.forwarding_failed, current.message)
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                )
            }
            Text(
                stringResource(R.string.forwarding_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    editing?.let { kind ->
        val current = status[kind]
        var number by remember(kind) { mutableStateOf((current as? ForwardingStatus.On)?.number.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(kind.label)) },
            text = {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.forwarding_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                val digits = number.filter { it.isDigit() || it == '+' }
                TextButton(
                    onClick = {
                        editing = null
                        run(kind, "**${kind.code}*$digits#")
                    },
                    enabled = digits.count(Char::isDigit) >= 3,
                ) { Text(stringResource(R.string.forwarding_turn_on)) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        editing = null
                        run(kind, "#${kind.code}#")
                    }) { Text(stringResource(R.string.forwarding_turn_off)) }
                    TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/** The four forwarding conditions and their service codes (3GPP TS 22.030 Annex B). */
enum class Forwarding(val code: Int, val label: Int) {
    ALWAYS(21, R.string.forwarding_always),
    BUSY(67, R.string.forwarding_busy),
    UNANSWERED(61, R.string.forwarding_unanswered),
    UNREACHABLE(62, R.string.forwarding_unreachable);

    /** Interrogation: "*#21#" asks whether calls are forwarded, and where. */
    val query: String get() = "*#$code#"
}

/** What the carrier said about one forwarding condition. */
sealed interface ForwardingStatus {
    data object Checking : ForwardingStatus
    data object Off : ForwardingStatus
    data class On(val number: String) : ForwardingStatus
    /** An answer VowiDroid could not read; shown as the carrier wrote it. */
    data class Said(val text: String) : ForwardingStatus
    data class Failed(val message: String) : ForwardingStatus

    companion object {
        private val number = Regex("""\+?\d[\d\s\-().]{5,}\d""")
        private val off = Regex("""(not\s+(active|forwarded|registered|provisioned)|deactivat|disabled|erased|\boff\b|inactive)""", RegexOption.IGNORE_CASE)

        /** Reads a carrier's free-text answer: a number means forwarding is on, words like "not active" mean off. */
        fun from(text: String): ForwardingStatus {
            val clean = text.trim()
            if (off.containsMatchIn(clean)) return Off
            number.find(clean)?.let { match -> return On(match.value.filter { it.isDigit() || it == '+' }) }
            return Said(clean)
        }
    }
}
