package com.vowi.vowidroid.ui.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LinePreferences
import kotlinx.coroutines.launch

/**
 * Returns a function that calls a line's voicemail: the number the user set,
 * else the SIM's, else the account the voicemail alerts name. When there is
 * none, it asks once and remembers the answer.
 */
@Composable
fun rememberVoicemailCaller(): (Line) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val call = rememberCallLauncher()
    var asking by remember { mutableStateOf<Line?>(null) }
    asking?.let { line ->
        var entered by remember(line.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.voicemail_unknown_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.voicemail_unknown_body, line.name))
                    OutlinedTextField(
                        value = entered,
                        onValueChange = { entered = it },
                        label = { Text(stringResource(R.string.voicemail_number_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        LinePreferences.setVoicemail(context, line.id, entered.trim())
                        call(line, entered.trim())
                    },
                    enabled = entered.any(Char::isDigit),
                ) { Text(stringResource(R.string.dialpad_call)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    return remember(call) {
        { line: Line ->
            scope.launch {
                val number = LinePreferences.voicemail(context, line.id)
                    ?: runCatching { VowiRepository.voicemailNumberOrAccount(line.id) }.getOrNull()
                if (number == null) asking = line else call(line, number)
            }
        }
    }
}
