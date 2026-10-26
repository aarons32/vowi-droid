package com.vowi.vowidroid.ui.settings

import com.vowi.vowidroid.core.sim.SimCards
import androidx.compose.material.icons.filled.Usb
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhonePaused
import android.os.Build
import com.vowi.vowidroid.data.CallSettings
import com.vowi.vowidroid.telecom.PhoneAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.res.pluralStringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.AppLanguage
import com.vowi.vowidroid.AppPermissions
import com.vowi.vowidroid.DirectCardAccess
import com.vowi.vowidroid.DirectCardOutcome
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.BuildConfig
import com.vowi.vowidroid.NotificationChannels
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.components.AppTitle
import com.vowi.vowidroid.SetupActivity
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LinePreferences
import kotlinx.coroutines.launch

/** Native settings, grouped like the stock Phone app's settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLine: (String) -> Unit,
    onAddLine: () -> Unit,
    onLogs: () -> Unit,
    onAgreement: () -> Unit,
    onBlocked: () -> Unit,
    onQuickResponses: () -> Unit,
    /** Null when shown as a tab, which has no screen to go back to. */
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var chooseDefault by remember { mutableStateOf(false) }
    var defaultLine by remember { mutableStateOf(LinePreferences.defaultLine(context)) }
    var about by remember { mutableStateOf(false) }
    var chooseLanguage by remember { mutableStateOf(false) }
    var cardAccess by remember { mutableStateOf(false) }
    val directAccess by VowiCore.directAccess.collectAsStateWithLifecycle()
    // Read once: a change recreates the screen, from here or from Android's settings.
    val language = remember { AppLanguage.current(context) }
    var callWaiting by remember { mutableStateOf(CallSettings.callWaiting(context)) }
    var systemHistory by remember { mutableStateOf(CallSettings.logToSystem(context)) }
    LaunchedEffect(Unit) { VowiRepository.refreshLines() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                title = { AppTitle() },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(stringResource(R.string.settings_lines)) }
            items(lines, key = { it.id }) { line ->
                ListItem(
                    modifier = Modifier.clickable { onLine(line.id) },
                    leadingContent = { Icon(if (line.kind == Line.Kind.USB_READER) Icons.Filled.Usb else Icons.Filled.SimCard, contentDescription = null) },
                    headlineContent = { Text(line.name) },
                    supportingContent = { LineStatus(line) },
                    trailingContent = {
                        Switch(
                            checked = line.wifiCallingEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    runCatching { VowiRepository.setWifiCalling(line.id, enabled) }
                                        .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
                                }
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onAddLine),
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_add_line)) },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader(stringResource(R.string.settings_calls_messages)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { chooseDefault = true },
                    leadingContent = { Icon(Icons.Filled.Star, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_default_line)) },
                    supportingContent = {
                        Text(lines.firstOrNull { it.id == defaultLine }?.name ?: stringResource(R.string.settings_default_line_auto))
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable {
                        callWaiting = !callWaiting
                        CallSettings.setCallWaiting(context, callWaiting)
                    },
                    leadingContent = { Icon(Icons.Filled.PhonePaused, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_call_waiting)) },
                    supportingContent = { Text(stringResource(R.string.settings_call_waiting_body)) },
                    trailingContent = {
                        Switch(checked = callWaiting, onCheckedChange = {
                            callWaiting = it
                            CallSettings.setCallWaiting(context, it)
                        })
                    },
                )
            }
            // Telecom logs self-managed calls to the system history from Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item {
                    ListItem(
                        modifier = Modifier.clickable {
                            systemHistory = !systemHistory
                            CallSettings.setLogToSystem(context, systemHistory)
                            PhoneAccounts.refresh(context)
                        },
                        leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.settings_system_history)) },
                        supportingContent = { Text(stringResource(R.string.settings_system_history_body)) },
                        trailingContent = {
                            Switch(checked = systemHistory, onCheckedChange = {
                                systemHistory = it
                                CallSettings.setLogToSystem(context, it)
                                PhoneAccounts.refresh(context)
                            })
                        },
                    )
                }
            }
            item {
                // Counted again whenever the screen comes back, e.g. from the permissions page or adb.
                var missing by remember { mutableIntStateOf(AppPermissions.missing(context).size) }
                LifecycleResumeEffect(Unit) {
                    missing = AppPermissions.missing(context).size
                    onPauseOrDispose { }
                }
                ListItem(
                    modifier = Modifier.clickable { context.startActivity(Intent(context, SetupActivity::class.java)) },
                    leadingContent = { Icon(Icons.Filled.VerifiedUser, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_sim_access)) },
                    supportingContent = {
                        Text(
                            if (missing == 0) stringResource(R.string.settings_sim_access_ready)
                            else pluralStringResource(R.plurals.settings_permissions_missing, missing, missing),
                        )
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onBlocked),
                    leadingContent = { Icon(Icons.Filled.Block, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_blocked)) },
                    supportingContent = { Text(stringResource(R.string.settings_blocked_body)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onQuickResponses),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_quick_responses)) },
                    supportingContent = { Text(stringResource(R.string.settings_quick_responses_body)) },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            item {
                // The "<line> online" notification of the background service. Only the user can turn it off, on
                // Android's page for its category: the system raises a category an app muted back to "low" each
                // time a foreground service posts on it. So the row shows the state and opens that page.
                var statusShown by remember { mutableStateOf(NotificationChannels.statusShown(context)) }
                var canNotify by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
                LifecycleResumeEffect(Unit) {
                    statusShown = NotificationChannels.statusShown(context)
                    canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    onPauseOrDispose { }
                }
                ListItem(
                    modifier = Modifier.clickable {
                        if (canNotify) NotificationChannels.openChannel(context, NotificationChannels.STATUS) else NotificationChannels.openApp(context)
                    },
                    leadingContent = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_status_notification)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when {
                                    !canNotify -> R.string.settings_status_notification_blocked
                                    statusShown -> R.string.settings_status_notification_shown
                                    else -> R.string.settings_status_notification_hidden
                                },
                            ),
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { NotificationChannels.openApp(context) },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_notification_categories)) },
                    supportingContent = { Text(stringResource(R.string.settings_notification_categories_body)) },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader(stringResource(R.string.settings_advanced)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { chooseLanguage = true },
                    leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = { Text(AppLanguage.label(context, language)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { cardAccess = true },
                    leadingContent = { Icon(Icons.Filled.SimCard, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_card_access)) },
                    supportingContent = { Text(directAccessSummary(directAccess)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onLogs),
                    leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_logs)) },
                    supportingContent = { Text(stringResource(R.string.settings_logs_body)) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { about = true },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onAgreement),
                    leadingContent = { Icon(Icons.Filled.Gavel, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.agreement_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_agreement_body)) },
                )
            }
        }
    }

    if (cardAccess) {
        val hash = remember { DirectCardAccess.certificateHash(context) }
        AlertDialog(
            onDismissRequest = { cardAccess = false },
            title = { Text(stringResource(R.string.settings_card_access)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_card_access_body))
                    Text(hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ARA-M SHA-1", hash))
                    // Android 13+ shows its own "copied" notice.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.settings_card_access_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.settings_card_access_copy)) }
            },
            dismissButton = { TextButton(onClick = { cardAccess = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (chooseDefault) {
        val usable = lines.filter { it.usable }
        AlertDialog(
            onDismissRequest = { chooseDefault = false },
            title = { Text(stringResource(R.string.settings_default_line)) },
            text = {
                Column {
                    (listOf<Line?>(null) + usable).forEach { line ->
                        ListItem(
                            modifier = Modifier.clickable {
                                LinePreferences.setDefaultLine(context, line?.id)
                                defaultLine = line?.id
                                chooseDefault = false
                            },
                            leadingContent = { RadioButton(selected = defaultLine == line?.id, onClick = null) },
                            headlineContent = { Text(line?.name ?: stringResource(R.string.settings_default_line_auto)) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseDefault = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (chooseLanguage) {
        AlertDialog(
            onDismissRequest = { chooseLanguage = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLanguage.choices.forEach { choice ->
                        ListItem(
                            modifier = Modifier.clickable {
                                chooseLanguage = false
                                if (choice != language) AppLanguage.set(context, choice)
                            },
                            leadingContent = { RadioButton(selected = choice == language, onClick = null) },
                            headlineContent = { Text(AppLanguage.label(context, choice)) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseLanguage = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME) },
            text = { Text(stringResource(R.string.settings_about_body)) },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** A one-line status, like "Registered · +1 206…" or "SIM removed". */
@Composable
fun LineStatus(line: Line) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when {
        line.phase == VowiCore.PHASE_STOPPED -> stringResource(R.string.line_status_stopped) to quiet
        // Where the card is comes first: a line without its card cannot do anything.
        line.sim == Line.SimStatus.OFF -> stringResource(
            when (line.simReason) {
                SimCards.ISSUE_AIRPLANE -> R.string.line_status_sim_off_airplane
                SimCards.ISSUE_ACCESS -> R.string.line_status_sim_off_access
                SimCards.ISSUE_PIN -> R.string.line_status_sim_locked
                else -> R.string.line_status_sim_off
            },
        ) to quiet
        line.sim == Line.SimStatus.EJECTED -> stringResource(R.string.line_status_ejected) to quiet
        line.sim == Line.SimStatus.REMOVED -> stringResource(R.string.line_status_removed) to quiet
        line.sim == Line.SimStatus.UNAVAILABLE -> stringResource(
            when (line.simReason) {
                SimCards.ISSUE_AIRPLANE -> R.string.line_status_airplane
                SimCards.ISSUE_ACCESS -> R.string.line_status_no_access
                else -> R.string.line_status_unavailable
            },
        ) to quiet
        !line.wifiCallingEnabled -> stringResource(R.string.line_status_off) to MaterialTheme.colorScheme.onSurfaceVariant
        line.registered -> (stringResource(R.string.line_status_registered) +
            (if (line.phoneNumber.isNotEmpty()) " · " + line.phoneNumber else "")) to Color(0xFF1E8E3E)
        // The carrier refused the subscriber (TS 24.302): no retry until the card goes in again or the user asks.
        line.phase == "blocked" -> stringResource(R.string.line_status_blocked) to MaterialTheme.colorScheme.error
        line.phase == "failed" && line.retryAt > 0 && line.carrierPause ->
            stringResource(R.string.line_status_retry_at, android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(line.retryAt))) to MaterialTheme.colorScheme.error
        line.lastError.isNotEmpty() && line.phase == "failed" ->
            stringResource(R.string.line_status_retrying) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.line_status_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

/** One line on where direct card access stands: unsupported, connecting, or which slots' cards allowed or refused it. */
@Composable
private fun directAccessSummary(access: VowiCore.DirectAccess): String {
    val context = LocalContext.current
    val separator = stringResource(R.string.list_separator)
    fun names(outcomes: Set<DirectCardOutcome>) =
        access.outcomes.filterValues { it in outcomes }.keys.sorted().joinToString(separator) { context.getString(R.string.sim_slot_name, it + 1) }
    val granted = names(setOf(DirectCardOutcome.GRANTED, DirectCardOutcome.LOCKED))
    val denied = names(setOf(DirectCardOutcome.DENIED))
    return when {
        !access.supported -> stringResource(R.string.settings_card_access_unsupported)
        !access.connected -> stringResource(R.string.settings_card_access_connecting)
        granted.isNotEmpty() -> stringResource(R.string.settings_card_access_granted, granted)
        denied.isNotEmpty() -> stringResource(R.string.settings_card_access_denied, denied)
        else -> stringResource(R.string.settings_card_access_idle)
    }
}
