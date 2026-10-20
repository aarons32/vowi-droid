package com.vowi.vowidroid.ui.calls

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.telecom.CallManager
import com.vowi.vowidroid.telecom.EmergencyNumbers
import com.vowi.vowidroid.ui.Permissions

/**
 * Returns a function that places a call on a line, asking for the microphone
 * first when needed, and reports problems as a toast. A dialled USSD or
 * supplementary-service code (*225#, *#21#) goes to the carrier instead,
 * with its answer in a dialog, as the stock dialer does.
 */
@Composable
fun rememberCallLauncher(): (Line?, String) -> Unit {
    val context = LocalContext.current
    val ussd = rememberUssdSession()
    UssdDialog(ussd)
    var pending by remember { mutableStateOf<Pair<Line, String>?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val (line, number) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (!CallManager.hasMicrophone(context)) {
            Toast.makeText(context, R.string.call_needs_microphone, Toast.LENGTH_LONG).show()
        }
        CallManager.dial(line.id, line.name, number)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    return remember {
        { line: Line?, number: String ->
            // Pauses (,) and waits (;) stay: the digits after them go as tones once the call connects.
            val cleaned = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
            val base = cleaned.substringBefore(',').substringBefore(';')
            when {
                base.isEmpty() -> Unit
                MmiCodes.isDeviceCode(base) -> EmergencyNumbers.openSystemDialer(context, base)
                MmiCodes.isCode(base) && base == cleaned ->
                    if (line == null) Toast.makeText(context, R.string.no_line_title, Toast.LENGTH_LONG).show() else ussd.start(line.id, base)
                EmergencyNumbers.isEmergency(context, base) -> {
                    // VowiDroid cannot place emergency calls; the phone's dialer can.
                    Toast.makeText(context, R.string.emergency_use_phone, Toast.LENGTH_LONG).show()
                    EmergencyNumbers.openSystemDialer(context, base)
                }
                line == null -> Toast.makeText(context, R.string.no_line_title, Toast.LENGTH_LONG).show()
                CallManager.hasMicrophone(context) ->
                    CallManager.dial(line.id, line.name, cleaned)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                Permissions.blockedByRom(context, Manifest.permission.RECORD_AUDIO) -> {
                    // No dialog can help; the call still works but is one-way.
                    Permissions.openAppSettings(context)
                    CallManager.dial(line.id, line.name, cleaned)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
                else -> {
                    pending = line to cleaned
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
