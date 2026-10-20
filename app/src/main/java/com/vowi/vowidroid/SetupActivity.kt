package com.vowi.vowidroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.vowi.vowidroid.AppPermissions.Need
import com.vowi.vowidroid.ui.MainActivity
import com.vowi.vowidroid.ui.Permissions
import com.vowi.vowidroid.ui.theme.CallGreen
import com.vowi.vowidroid.ui.theme.CallRed
import com.vowi.vowidroid.ui.theme.VowiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The permissions page: everything VowiDroid needs from Android as a list with
 * a green tick or a red cross on each, where a tap asks for what is missing.
 * SIM access, which only adb or root can grant, gets its instructions below the
 * list. Shown on the first launch and from Settings.
 */
class SetupActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    /** Bumped whenever a grant may have changed, so the list reads every status again. */
    private val version = mutableIntStateOf(0)

    /** Starts unknown, so the first look always has VowiDroid check the SIMs again, e.g. after a grant from adb. */
    private var lastIssue: String? = ISSUE_UNKNOWN
    private var asked: Need? = null

    private val dialog = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val need = asked
        asked = null
        // A second "deny" is final: only the app's settings page can grant it then.
        if (!granted && need?.permission != null && !ActivityCompat.shouldShowRequestPermissionRationale(this, need.permission)) {
            openSettings(need)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!UserAgreement.accepted(this)) {
            // Reached from the Permissions shortcut before the user agreement: that comes first.
            startActivity(Intent(this, com.vowi.vowidroid.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContent {
            VowiTheme {
                SetupScreen(
                    version = version.intValue,
                    onRequest = ::request,
                    onGrantWithRoot = ::grantWithRoot,
                    onBack = ::finish,
                    onDone = ::finishSetup,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The grant may have come from adb, or a settings page, while VowiDroid was in the background.
        refresh()
    }

    private fun refresh() {
        version.intValue++
        val issue = SimAccess.issue(this)
        if (issue != lastIssue) {
            lastIssue = issue
            // Phone SIM readers report their access issue; let VowiDroid see the change.
            CoreService.start(this, CoreService.ACTION_RESCAN)
        }
    }

    /** Asks for one need: the system dialog where there is one, else the settings page that grants it. */
    private fun request(need: Need) {
        val permission = need.permission
        when {
            need == Need.ROM_BACKGROUND -> if (!RomBattery.open(this)) Toast.makeText(this, R.string.setup_rom_no_page, Toast.LENGTH_LONG).show()
            permission != null && Permissions.blockedByRom(this, permission) -> Permissions.openAppSettings(this)
            permission != null -> {
                asked = need
                dialog.launch(permission)
            }
            else -> openSettings(need)
        }
    }

    private fun openSettings(need: Need) {
        val intent = AppPermissions.settingsIntent(this, need) ?: return
        runCatching { startActivity(intent) }.onFailure {
            // A ROM without the "always run in background?" dialog still has the allow-list page.
            val fallback = if (need == Need.BATTERY) runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess else false
            if (!fallback) Toast.makeText(this, R.string.permission_open_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun grantWithRoot(onFinished: () -> Unit) {
        lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) { SimAccess.grantWithRoot(this@SetupActivity) }
            Toast.makeText(
                this@SetupActivity,
                if (granted) R.string.setup_root_granted else R.string.setup_root_failed,
                Toast.LENGTH_LONG,
            ).show()
            onFinished()
            refresh()
        }
    }

    private fun finishSetup() {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        val next = if (SimAccess.issue(this) == null) MainActivity.TAB_ADD_LINE else MainActivity.TAB_SETTINGS
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, next))
        finish()
    }

    companion object {
        private const val ISSUE_UNKNOWN = "unknown"
        private const val PREFERENCES = "setup"
        private const val KEY_SEEN = "seen"

        /** Whether to open setup on launch: phone SIMs are unusable and setup was never finished. */
        fun shouldShow(context: Context): Boolean =
            SimAccess.supported() && SimAccess.issue(context) != null &&
                !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    version: Int,
    onRequest: (Need) -> Unit,
    onGrantWithRoot: (onFinished: () -> Unit) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    // Read again whenever the activity says a grant may have changed.
    val status = remember(version) { Need.entries.associateWith { AppPermissions.status(context, it) } }
    val listed = Need.entries.filter { it != Need.ROM_BACKGROUND || AppPermissions.supported(it) }
    // What the summary counts: the required needs whose state the phone will tell.
    val required = listed.filter { !it.optional && AppPermissions.supported(it) && status[it] != null }
    val inPlace = required.count { status[it] == true }
    val simAccessMissing = Need.SIM_ACCESS in required && status[Need.SIM_ACCESS] != true
    var rooting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.setup_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
            // SIM access is the one item a tap cannot grant, so its how-to comes first while it is missing.
            if (simAccessMissing) {
                SimAccessHelp(
                    rooting = rooting,
                    onGrantWithRoot = {
                        rooting = true
                        onGrantWithRoot { rooting = false }
                    },
                )
            } else if (!AppPermissions.supported(Need.SIM_ACCESS)) {
                Note(stringResource(R.string.setup_access_unsupported))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inPlace == required.size) stringResource(R.string.setup_summary_all) else stringResource(R.string.setup_summary, inPlace, required.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (inPlace == required.size) CallGreen else MaterialTheme.colorScheme.onSurface,
                )
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                listed.forEachIndexed { index, need ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NeedRow(need, granted = status[need], supported = AppPermissions.supported(need), onRequest = onRequest)
                }
            }
            Text(stringResource(R.string.setup_tap_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                        Text(stringResource(R.string.setup_conflict_title), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.setup_conflict_body), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.setup_next_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_done_action)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One need: its icon, what it is for, and a green tick or red cross (a grey
 * question mark for a ROM setting the phone will not read back); a tap asks
 * for it while it is missing or unknown.
 */
@Composable
private fun NeedRow(need: Need, granted: Boolean?, supported: Boolean, onRequest: (Need) -> Unit) {
    val rom = RomBattery.current
    val (icon, title, body) = when (need) {
        Need.PHONE -> Triple(Icons.Filled.Phone, stringResource(R.string.setup_phone_title), stringResource(R.string.setup_phone_body))
        Need.SIM_ACCESS -> Triple(Icons.Filled.Key, stringResource(R.string.setup_access_title), stringResource(R.string.setup_access_body))
        Need.NOTIFICATIONS -> Triple(Icons.Filled.Notifications, stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_body))
        Need.MICROPHONE -> Triple(Icons.Filled.Mic, stringResource(R.string.setup_microphone_title), stringResource(R.string.setup_microphone_body))
        Need.FULL_SCREEN -> Triple(Icons.Filled.PhonelinkLock, stringResource(R.string.setup_full_screen_title), stringResource(R.string.setup_full_screen_body))
        Need.BATTERY -> Triple(Icons.Filled.BatteryFull, stringResource(R.string.setup_battery_title), stringResource(R.string.setup_battery_body))
        Need.ROM_BACKGROUND -> Triple(
            Icons.Filled.PhoneAndroid,
            stringResource(R.string.setup_rom_title, rom?.label.orEmpty()),
            stringResource(
                when (rom) {
                    RomBattery.Rom.ONE_UI -> R.string.setup_rom_one_ui_body
                    RomBattery.Rom.HYPEROS -> R.string.setup_rom_hyperos_body
                    RomBattery.Rom.EMUI -> R.string.setup_rom_emui_body
                    RomBattery.Rom.COLOROS -> R.string.setup_rom_coloros_body
                    RomBattery.Rom.ORIGIN_OS, null -> R.string.setup_rom_origin_os_body
                },
            ),
        )
        Need.CONTACTS -> Triple(Icons.Filled.Contacts, stringResource(R.string.setup_contacts_title), stringResource(R.string.setup_contacts_body))
    }
    // SIM access has no dialog or settings page; its instructions are right below the list.
    val canAsk = granted != true && supported && need != Need.SIM_ACCESS
    ListItem(
        modifier = if (canAsk) Modifier.clickable { onRequest(need) } else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                when {
                    !supported -> stringResource(R.string.setup_access_unsupported_short)
                    need.optional -> stringResource(R.string.setup_optional, body)
                    granted == null -> stringResource(R.string.setup_unknown, body)
                    else -> body
                },
            )
        },
        trailingContent = { StatusIcon(granted) },
    )
}

@Composable
private fun StatusIcon(granted: Boolean?) {
    val icon: ImageVector = when (granted) {
        true -> Icons.Filled.CheckCircle
        false -> Icons.Filled.Cancel
        null -> Icons.AutoMirrored.Outlined.HelpOutline
    }
    Icon(
        icon,
        contentDescription = stringResource(
            when (granted) {
                true -> R.string.setup_status_granted
                false -> R.string.setup_status_missing
                null -> R.string.setup_status_unknown
            },
        ),
        tint = when (granted) {
            true -> CallGreen
            false -> CallRed
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(28.dp),
    )
}

/** How to grant SIM access: root here, or the adb command from a computer, with Copy. */
@Composable
private fun SimAccessHelp(rooting: Boolean, onGrantWithRoot: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_access_help_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.setup_access_help_body), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onGrantWithRoot, enabled = !rooting) { Text(stringResource(R.string.setup_root_action)) }
            Text(stringResource(R.string.setup_adb_body), style = MaterialTheme.typography.bodyMedium)
            CommandRow(SimAccess.grantCommand(context))
            Text(stringResource(R.string.setup_adb_check), style = MaterialTheme.typography.bodyMedium)
            CommandRow(SimAccess.checkCommand(context))
            Text(stringResource(R.string.setup_adb_after), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A command in a box, with a button that copies it. */
@Composable
private fun CommandRow(command: String) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", command))
                Toast.makeText(context, R.string.setup_copied, Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.setup_copy_action))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.SimCard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
