package com.vowi.vowidroid.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.VowiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dialled codes that are not phone numbers (3GPP TS 22.030). */
object MmiCodes {
    /**
     * A USSD or supplementary-service code: it starts with * or # and ends
     * with #, like *225# or **21*5551234#. "#31#5551234" is a number with a
     * caller ID prefix instead, since it ends in a digit.
     */
    fun isCode(dialed: String): Boolean =
        dialed.length >= 3 && (dialed[0] == '*' || dialed[0] == '#') && dialed.last() == '#' && dialed.all { it.isDigit() || it == '*' || it == '#' || it == '+' }

    /** Codes the phone itself answers, such as *#06# for the IMEI or *#*#…#*#* test codes: VowiDroid hands these to the phone's dialer. */
    fun isDeviceCode(dialed: String): Boolean = dialed == "*#06#" || dialed == "*#07#" || (dialed.startsWith("*#*#") && dialed.endsWith("#*#*"))
}

/** One USSD conversation with the carrier, as the stock phone shows it: a message, and a reply box while the network waits for one. */
@Stable
class UssdSession(private val scope: CoroutineScope) {
    sealed interface State {
        data class Running(val code: String) : State
        data class Answer(val text: String, val final: Boolean) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State?>(null)
        private set
    private var lineId: String? = null
    private var job: Job? = null

    fun start(lineId: String, code: String) {
        job?.cancel()
        this.lineId = lineId
        state = State.Running(code)
        job = scope.launch {
            state = runCatching { VowiRepository.ussd(lineId, code) }
                .fold({ State.Answer(it.text, it.final) }, { State.Failed(it.message.orEmpty().removePrefix("ims: ")) })
        }
    }

    fun reply(text: String) {
        val line = lineId ?: return
        state = State.Running(text)
        job = scope.launch {
            state = runCatching { VowiRepository.ussdReply(line, text) }
                .fold({ State.Answer(it.text, it.final) }, { State.Failed(it.message.orEmpty().removePrefix("ims: ")) })
        }
    }

    /** Ends the conversation; the carrier is told when it was still waiting for a reply. */
    fun close() {
        job?.cancel()
        val open = state.let { it is State.Running || (it is State.Answer && !it.final) }
        state = null
        val line = lineId ?: return
        if (open) scope.launch { runCatching { VowiRepository.ussdCancel(line) } }
    }
}

@Composable
fun rememberUssdSession(): UssdSession {
    val scope = rememberCoroutineScope()
    return remember { UssdSession(scope) }
}

@Composable
fun UssdDialog(session: UssdSession) {
    val state = session.state ?: return
    var reply by remember(state) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (state !is UssdSession.State.Running) session.close() },
        text = {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when (state) {
                    is UssdSession.State.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.ussd_running))
                    }
                    is UssdSession.State.Failed -> {
                        Text(stringResource(R.string.ussd_failed), style = MaterialTheme.typography.titleMedium)
                        if (state.message.isNotBlank()) Text(state.message, modifier = Modifier.padding(top = 8.dp))
                    }
                    is UssdSession.State.Answer -> {
                        // Carriers put balances and codes here, so the text can be selected and copied.
                        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                            Text(state.text.ifBlank { stringResource(R.string.ussd_done) })
                        }
                        if (!state.final) {
                            OutlinedTextField(
                                value = reply,
                                onValueChange = { reply = it },
                                placeholder = { Text(stringResource(R.string.ussd_reply_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state is UssdSession.State.Answer && !state.final ->
                    TextButton(onClick = { session.reply(reply.trim()) }, enabled = reply.isNotBlank()) { Text(stringResource(R.string.incall_send)) }
                state is UssdSession.State.Running -> Unit
                else -> TextButton(onClick = { session.close() }) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            if (state is UssdSession.State.Running || (state is UssdSession.State.Answer && !state.final)) {
                TextButton(onClick = { session.close() }) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
