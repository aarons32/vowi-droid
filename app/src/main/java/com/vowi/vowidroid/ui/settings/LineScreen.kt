package com.vowi.vowidroid.ui.settings

import com.vowi.vowidroid.core.sim.Imei
import com.vowi.vowidroid.core.sim.SimCards
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PermIdentity
import com.vowi.vowidroid.EntitlementStore
import com.vowi.vowidroid.ui.EmergencyAddressActivity
import com.vowi.vowidroid.ui.stepLabel
import com.vowi.vowidroid.ui.networkLabel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.RadioButton
import com.vowi.vowidroid.data.CallSettings
import com.vowi.vowidroid.ui.LinePreferences
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.NotificationChannels
import com.vowi.vowidroid.PhoneSims
import com.vowi.vowidroid.R
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One line's WiFi Calling status and controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineScreen(lineId: String, onBack: () -> Unit, onForwarding: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    val line = lines.firstOrNull { it.id == lineId }
    var confirmRemove by remember { mutableStateOf(false) }
    var callerId by remember(lineId) { mutableStateOf(CallSettings.callerId(context, lineId)) }
    var chooseCallerId by remember { mutableStateOf(false) }
    var voicemail by remember(lineId) { mutableStateOf(LinePreferences.voicemail(context, lineId).orEmpty()) }
    var editVoicemail by remember { mutableStateOf(false) }
    var confirmEject by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var emergency by remember(lineId) { mutableStateOf(EntitlementStore.statusText(context, lineId)) }
    var imsiPrivacy by remember(lineId) { mutableStateOf(CallSettings.imsiPrivacy(context, lineId)) }
    var networkChoice by remember(lineId) { mutableStateOf(CallSettings.networkChoice(context, lineId)) }
    var chooseNetwork by remember { mutableStateOf(false) }
    var smsCenter by remember(lineId) { mutableStateOf("") }
    var editSmsCenter by remember { mutableStateOf(false) }
    var editImei by remember { mutableStateOf(false) }
    // A changed identity setting only shows on the next sign-in, so the line reconnects right away.
    val setImsiPrivacy: (Boolean) -> Unit = { enabled ->
        imsiPrivacy = enabled
        CallSettings.setImsiPrivacy(context, lineId, enabled)
        scope.launch { runCatching { VowiRepository.reconnect(lineId) } }
    }
    LaunchedEffect(lineId) {
        // Registration progresses in the background; keep the status current.
        while (true) {
            VowiRepository.refreshLines()
            emergency = EntitlementStore.statusText(context, lineId)
            delay(3000)
        }
    }
    LaunchedEffect(line?.iccid) {
        smsCenter = PhoneSims.savedSmsCenter(context, line?.iccid.orEmpty())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(line?.name ?: lineId) },
                actions = {
                    if (line != null) {
                        IconButton(onClick = { rename = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.line_rename))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (line == null) {
            Text(stringResource(R.string.line_missing), modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.line_wifi_calling)) },
                supportingContent = { LineStatus(line) },
                trailingContent = {
                    Switch(checked = line.wifiCallingEnabled, onCheckedChange = { enabled ->
                        scope.launch {
                            runCatching { VowiRepository.setWifiCalling(line.id, enabled) }
                                .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                        }
                    })
                },
            )
            ListItem(
                modifier = Modifier.clickable {
                    scope.launch {
                        runCatching { VowiRepository.reconnect(line.id) }
                            .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                    }
                },
                leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_reconnect)) },
            )
            HorizontalDivider()
            // The line's card: where it is, and taking it out safely.
            SectionHeader(stringResource(R.string.line_sim))
            ListItem(
                leadingContent = { Icon(if (line.kind == Line.Kind.USB_READER) Icons.Filled.Usb else Icons.Filled.SimCard, contentDescription = null) },
                headlineContent = {
                    Text(
                        when {
                            line.location.isNotEmpty() -> stringResource(R.string.line_sim_in, line.location)
                            line.lastSeen.isNotEmpty() -> stringResource(R.string.line_sim_last_seen, line.lastSeen)
                            else -> stringResource(R.string.line_sim_not_in)
                        },
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(
                            when {
                                line.sim == Line.SimStatus.OFF && line.simReason == SimCards.ISSUE_AIRPLANE -> R.string.line_sim_off_airplane_hint
                                line.sim == Line.SimStatus.OFF && line.simReason == SimCards.ISSUE_PIN -> R.string.add_line_pin
                                line.sim == Line.SimStatus.OFF -> R.string.line_sim_off_hint
                                else -> R.string.line_sim_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            when (line.sim) {
                Line.SimStatus.IN_USE -> ListItem(
                    modifier = Modifier.clickable { confirmEject = true },
                    leadingContent = { Icon(Icons.Filled.Eject, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.line_eject)) },
                )
                Line.SimStatus.EJECTED -> ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            runCatching { VowiRepository.resumeLine(line.id) }
                                .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                        }
                    },
                    leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.line_resume)) },
                )
                else -> Unit
            }
            HorizontalDivider()
            // Calling settings, as the stock phone keeps them per SIM.
            SectionHeader(stringResource(R.string.line_calling))
            ListItem(
                modifier = Modifier.clickable { chooseCallerId = true },
                leadingContent = { Icon(Icons.Filled.PermIdentity, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_caller_id)) },
                supportingContent = { Text(stringResource(callerIdLabel(callerId))) },
            )
            ListItem(
                modifier = Modifier.clickable { editVoicemail = true },
                leadingContent = { Icon(Icons.Filled.Voicemail, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.voicemail_number_label)) },
                supportingContent = { Text(voicemail.ifBlank { stringResource(R.string.line_voicemail_sim) }) },
            )
            ListItem(
                modifier = Modifier.clickable { onForwarding(line.id) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PhoneForwarded, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.forwarding_title)) },
                supportingContent = { Text(stringResource(R.string.line_forwarding_body)) },
            )
            // What the carrier has on file for 911 over Wi-Fi, through its entitlement server.
            ListItem(
                modifier = Modifier.clickable { context.startActivity(EmergencyAddressActivity.intent(context, line.id)) },
                leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.emergency_address_title)) },
                supportingContent = { Text(emergency) },
            )
            // Which network carries the tunnel: Android's default, or one transport the user picks.
            ListItem(
                modifier = Modifier.clickable { chooseNetwork = true },
                leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_network)) },
                supportingContent = {
                    Column {
                        Text(stringResource(networkChoiceLabel(networkChoice)))
                        if (line.network.isNotEmpty() && line.registered) {
                            Text(stringResource(R.string.line_network_now, networkLabel(line.network)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                },
            )
            // The anonymous name and encrypted IMSI towards this line's ePDG; off when its carrier cannot take them.
            ListItem(
                modifier = Modifier.clickable { setImsiPrivacy(!imsiPrivacy) },
                leadingContent = { Icon(Icons.Filled.Shield, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_imsi_privacy)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.line_imsi_privacy_body))
                        val identity = identityText(line.identity, imsiPrivacy)
                        if (identity.isNotEmpty()) {
                            Text(
                                stringResource(R.string.line_imsi_privacy_now, identity),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                },
                trailingContent = { Switch(checked = imsiPrivacy, onCheckedChange = { setImsiPrivacy(it) }) },
            )
            // Some ePDGs ask for the IMEI and admit only devices they know; the line carries whichever one the user chose.
            ListItem(
                modifier = Modifier.clickable { editImei = true },
                leadingContent = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_imei_title)) },
                supportingContent = { Text(line.imei.ifEmpty { stringResource(R.string.line_imei_not_set) }) },
            )
            HorizontalDivider()
            // Texting, as the stock Messages app keeps it per SIM: the SMS center VowiDroid sends through.
            SectionHeader(stringResource(R.string.line_messaging))
            ListItem(
                modifier = if (line.iccid.isNotEmpty()) Modifier.clickable { editSmsCenter = true } else Modifier,
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.line_smsc_title)) },
                supportingContent = { Text(smsCenterText(line, smsCenter)) },
            )
            HorizontalDivider()
            // This line's notification categories; Android's settings keep their sound, importance and lock-screen rules.
            SectionHeader(stringResource(R.string.line_notifications))
            NotificationRows(line.id, line.name)
            HorizontalDivider()
            SectionHeader(stringResource(R.string.line_details))
            Detail(R.string.line_detail_number, line.phoneNumber)
            Detail(R.string.line_detail_state, line.phase)
            Detail(R.string.line_detail_epdg, line.epdg)
            Detail(R.string.line_detail_profile, line.carrierProfile)
            Detail(R.string.line_detail_network, networkLabel(line.network))
            Detail(R.string.line_detail_identity, identityText(line.identity, imsiPrivacy))
            if (line.failedStage.isNotEmpty() && (line.phase == "failed" || line.phase == "blocked")) {
                Detail(R.string.line_detail_failed_step, stringResource(stepLabel(line.failedStage)))
            }
            if (line.errorCode.isNotEmpty()) Detail(R.string.line_detail_error_code, line.errorCode)
            if (line.lastError.isNotEmpty()) Detail(R.string.line_detail_error, line.lastError)
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { confirmRemove = true },
                leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text(stringResource(R.string.line_remove), color = MaterialTheme.colorScheme.error) },
            )
        }
    }

    if (confirmEject && line != null) {
        AlertDialog(
            onDismissRequest = { confirmEject = false },
            title = { Text(stringResource(R.string.line_eject_title, line.name)) },
            text = { Text(stringResource(R.string.line_eject_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEject = false
                    scope.launch {
                        runCatching { VowiRepository.ejectLine(line.id) }
                            .onSuccess { Toast.makeText(context, R.string.line_ejected_toast, Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                    }
                }) { Text(stringResource(R.string.line_eject)) }
            },
            dismissButton = { TextButton(onClick = { confirmEject = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (rename && line != null) {
        var entered by remember { mutableStateOf(line.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.line_rename)) },
            text = {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    label = { Text(stringResource(R.string.line_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rename = false
                    scope.launch { runCatching { VowiRepository.renameLine(line.id, entered) } }
                }, enabled = entered.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (chooseNetwork) {
        AlertDialog(
            onDismissRequest = { chooseNetwork = false },
            title = { Text(stringResource(R.string.line_network)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.line_network_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    for (choice in VowiCore.NetworkChoice.ALL) {
                        ListItem(
                            modifier = Modifier.selectable(selected = networkChoice == choice, role = Role.RadioButton) {
                                chooseNetwork = false
                                if (networkChoice != choice) {
                                    networkChoice = choice
                                    CallSettings.setNetworkChoice(context, lineId, choice)
                                    scope.launch { runCatching { VowiRepository.reconnect(lineId) } }
                                }
                            },
                            leadingContent = { RadioButton(selected = networkChoice == choice, onClick = null) },
                            headlineContent = { Text(stringResource(networkChoiceLabel(choice))) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseNetwork = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (chooseCallerId) {
        AlertDialog(
            onDismissRequest = { chooseCallerId = false },
            title = { Text(stringResource(R.string.line_caller_id)) },
            text = {
                Column {
                    CallSettings.CallerId.entries.forEach { option ->
                        ListItem(
                            modifier = Modifier.clickable {
                                CallSettings.setCallerId(context, lineId, option)
                                callerId = option
                                chooseCallerId = false
                            },
                            leadingContent = { RadioButton(selected = callerId == option, onClick = null) },
                            headlineContent = { Text(stringResource(callerIdLabel(option))) },
                        )
                    }
                    Text(
                        stringResource(R.string.line_caller_id_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { chooseCallerId = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (editVoicemail) {
        var entered by remember { mutableStateOf(voicemail) }
        AlertDialog(
            onDismissRequest = { editVoicemail = false },
            title = { Text(stringResource(R.string.voicemail_number_label)) },
            text = {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    placeholder = { Text(stringResource(R.string.line_voicemail_sim)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Empty goes back to the SIM's own voicemail number.
                    LinePreferences.setVoicemail(context, lineId, entered.trim())
                    voicemail = entered.trim()
                    editVoicemail = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editVoicemail = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (editSmsCenter && line != null) {
        var entered by remember { mutableStateOf(smsCenter) }
        AlertDialog(
            onDismissRequest = { editSmsCenter = false },
            title = { Text(stringResource(R.string.line_smsc_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.line_smsc_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = entered,
                        onValueChange = { entered = it },
                        placeholder = { Text(stringResource(R.string.line_smsc_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Empty goes back to the SIM's or the carrier's number.
                    PhoneSims.saveSmsCenter(context, line.iccid, entered)
                    smsCenter = PhoneSims.savedSmsCenter(context, line.iccid)
                    editSmsCenter = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editSmsCenter = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (editImei && line != null) {
        var entered by remember { mutableStateOf(line.imei) }
        val phoneImei = remember(line.id) { VowiCore.phoneImei(line.id) }
        val digits = Imei.normalize(entered)
        val wellFormed = digits.isEmpty() || Imei.isWellFormed(digits)
        val checkDigitOff = digits.isNotEmpty() && wellFormed && !Imei.checkDigitMatches(digits)
        AlertDialog(
            onDismissRequest = { editImei = false },
            title = { Text(stringResource(R.string.line_imei_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.line_imei_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = entered,
                        onValueChange = { entered = it },
                        placeholder = { Text(stringResource(R.string.line_imei_hint)) },
                        singleLine = true,
                        isError = !wellFormed,
                        supportingText = when {
                            !wellFormed -> ({ Text(stringResource(R.string.line_imei_invalid)) })
                            checkDigitOff -> ({ Text(stringResource(R.string.line_imei_check)) })
                            else -> null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = { entered = Imei.generate() }) { Text(stringResource(R.string.line_imei_generate)) }
                        if (phoneImei.isNotEmpty()) TextButton(onClick = { entered = phoneImei }) { Text(stringResource(R.string.line_imei_use_phone)) }
                        if (entered.isNotEmpty()) TextButton(onClick = { entered = "" }) { Text(stringResource(R.string.line_imei_clear)) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = wellFormed, onClick = {
                    scope.launch { runCatching { VowiRepository.setImei(line.id, digits) } }
                    editImei = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editImei = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmRemove && line != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.line_remove_title, line.name)) },
            text = { Text(stringResource(R.string.line_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        runCatching { VowiRepository.removeLine(line.id) }
                            .onSuccess { onBack() }
                            .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                    }
                }) { Text(stringResource(R.string.line_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun callerIdLabel(value: CallSettings.CallerId): Int = when (value) {
    CallSettings.CallerId.NETWORK -> R.string.caller_id_network
    CallSettings.CallerId.SHOW -> R.string.caller_id_show
    CallSettings.CallerId.HIDE -> R.string.caller_id_hide
}

private fun networkChoiceLabel(choice: String): Int = when (choice) {
    VowiCore.NetworkChoice.WIFI -> R.string.network_wifi
    VowiCore.NetworkChoice.CELLULAR -> R.string.network_cellular
    VowiCore.NetworkChoice.ETHERNET -> R.string.network_ethernet
    VowiCore.NetworkChoice.VPN -> R.string.network_vpn
    else -> R.string.network_auto
}

/** How the subscriber was named to the ePDG, in words. */
@Composable
/**
 * Whether the Wi-Fi network between the phone and the ePDG can see the IMSI:
 * hidden only when the name is anonymous and the IMSI goes out encrypted.
 * With the switch on and the IMSI still visible, the carrier is the reason.
 */
private fun identityText(identity: String, privacyOn: Boolean): String = when {
    identity.isEmpty() -> ""
    identity.startsWith("anonymous+encrypted") -> stringResource(R.string.identity_hidden)
    identity.contains("refused") -> stringResource(R.string.identity_refused)
    privacyOn -> stringResource(R.string.identity_unsupported)
    else -> stringResource(R.string.identity_visible)
}

@Composable
private fun Detail(label: Int, value: String) {
    if (value.isEmpty()) return
    ListItem(
        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
        supportingContent = { Text(value) },
    )
}

/** One row per category of the line, with how it stands now; each opens Android's page for it. */
@Composable
private fun NotificationRows(lineId: String, name: String) {
    val context = LocalContext.current
    // Read again whenever the page comes back, e.g. from Android's settings.
    var version by remember(lineId) { mutableIntStateOf(0) }
    LifecycleResumeEffect(lineId) {
        NotificationChannels.ensureLine(context, lineId, name)
        version++
        onPauseOrDispose { }
    }
    for (kind in NotificationChannels.Kind.entries) {
        val id = NotificationChannels.id(kind, lineId)
        val state = remember(id, version) { NotificationChannels.state(context, id) }
        ListItem(
            modifier = Modifier.clickable {
                if (!NotificationChannels.openChannel(context, id)) Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
            },
            leadingContent = { Icon(kindIcon(kind), contentDescription = null) },
            headlineContent = { Text(stringResource(kind.title)) },
            supportingContent = {
                Text(
                    stringResource(
                        when (state) {
                            NotificationChannels.State.ON -> R.string.notification_state_on
                            NotificationChannels.State.SILENT -> R.string.notification_state_silent
                            NotificationChannels.State.OFF -> R.string.notification_state_off
                        },
                    ),
                )
            },
        )
    }
}

private fun kindIcon(kind: NotificationChannels.Kind): ImageVector = when (kind) {
    NotificationChannels.Kind.INCOMING_CALLS -> Icons.Filled.RingVolume
    NotificationChannels.Kind.MISSED_CALLS -> Icons.Filled.PhoneMissed
    NotificationChannels.Kind.MESSAGES -> Icons.AutoMirrored.Filled.Message
    NotificationChannels.Kind.VOICEMAIL -> Icons.Filled.Voicemail
}

/** The SMS center in use and where it comes from: the user's entry, the SIM, the carrier profile, or nothing yet. */
@Composable
private fun smsCenterText(line: Line, saved: String): String = when {
    saved.isNotEmpty() -> stringResource(R.string.line_smsc_yours, saved)
    line.smsCenterFrom == "sim" -> stringResource(R.string.line_smsc_from_sim, line.smsCenter)
    line.smsCenterFrom == "profile" -> stringResource(R.string.line_smsc_from_profile, line.smsCenter)
    line.iccid.isEmpty() -> stringResource(R.string.line_smsc_unknown)
    else -> stringResource(R.string.line_smsc_not_set)
}
