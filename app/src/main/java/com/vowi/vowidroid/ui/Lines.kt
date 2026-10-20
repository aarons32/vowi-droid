package com.vowi.vowidroid.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiCalling3
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.AppPermissions
import com.vowi.vowidroid.PermissionDenials
import com.vowi.vowidroid.R
import com.vowi.vowidroid.SetupActivity
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The user's preferred line for new calls and texts. */
object LinePreferences {
    private const val PREFERENCES = "lines"
    private const val KEY_DEFAULT = "default"

    fun defaultLine(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null)

    fun setDefaultLine(context: Context, id: String?) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, id).apply()
    }

    fun pick(lines: List<Line>, preferred: String?): Line? =
        lines.firstOrNull { it.id == preferred } ?: lines.firstOrNull()

    /** The voicemail number the user entered for a line, when the SIM gives none. */
    fun voicemail(context: Context, lineId: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("voicemail.$lineId", null)?.takeIf { it.isNotBlank() }

    fun setVoicemail(context: Context, lineId: String, number: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("voicemail.$lineId", number).apply()
    }
}

/** Lines that can place calls and send texts right now. */
@Composable
fun usableLines(): List<Line> {
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    return lines.filter { it.usable }
}

/**
 * Lets the user choose a line when more than one is registered, like a
 * dual-SIM chooser: segmented buttons, the chosen one filled in the primary
 * colour with a tick. Past four lines, scrolling chips in the same colours.
 */
@Composable
fun LineChips(lines: List<Line>, selected: Line?, onSelect: (Line) -> Unit, modifier: Modifier = Modifier) {
    if (lines.size < 2) return
    val scheme = MaterialTheme.colorScheme
    if (lines.size <= 4) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            lines.forEachIndexed { index, line ->
                val active = line.id == selected?.id
                SegmentedButton(
                    selected = active,
                    onClick = { onSelect(line) },
                    shape = SegmentedButtonDefaults.itemShape(index, lines.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = scheme.primary,
                        activeContentColor = scheme.onPrimary,
                        activeBorderColor = scheme.primary,
                    ),
                    icon = { SegmentedButtonDefaults.Icon(active) },
                    label = { Text(line.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lines.forEach { line ->
            val active = line.id == selected?.id
            FilterChip(
                selected = active,
                onClick = { onSelect(line) },
                label = { Text(line.name) },
                leadingIcon = { Icon(if (active) Icons.Filled.Check else Icons.Filled.SimCard, contentDescription = null) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = scheme.primary,
                    selectedLabelColor = scheme.onPrimary,
                    selectedLeadingIconColor = scheme.onPrimary,
                ),
            )
        }
    }
}

/** Shown where calls or texts need a registered line but none is. */
@Composable
fun NoLineCard(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Icon(Icons.Filled.WifiCalling3, contentDescription = null)
                Text(
                    stringResource(R.string.no_line_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(R.string.no_line_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onSettings, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.no_line_action))
            }
        }
    }
}

/**
 * The red card pinned where the calls and texts lists start while a read or
 * a notification was refused for lack of a permission (see
 * [PermissionDenials]): which permissions, the latest refusal in words, and
 * a button to the Permissions page. It goes once the permission is in
 * place, checked again whenever the screen comes back.
 */
@Composable
fun PermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val denials by PermissionDenials.denied.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        PermissionDenials.refresh(context)
        onPauseOrDispose { }
    }
    val latest = PermissionDenials.latest(denials) ?: return
    val names = AppPermissions.Need.entries.filter { it in denials }.joinToString(stringResource(R.string.list_separator)) { AppPermissions.title(context, it) }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    stringResource(R.string.denial_banner_title, names),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(latest.what),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = { context.startActivity(Intent(context, SetupActivity::class.java)) },
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
            ) {
                Text(stringResource(R.string.denial_action))
            }
        }
    }
}

/** A network name from the core ("Wi-Fi", "mobile data", "Ethernet", "VPN") in the app's language. */
@Composable
fun networkLabel(network: String): String = when (network) {
    "Wi-Fi" -> stringResource(R.string.network_name_wifi)
    "mobile data" -> stringResource(R.string.network_name_cellular)
    "Ethernet" -> stringResource(R.string.network_name_ethernet)
    "VPN" -> stringResource(R.string.network_name_vpn)
    else -> network
}

/** Times as the stock apps show them: clock time today, weekday this week, date otherwise. */
object TimeFormat {
    fun short(context: Context, instant: Instant?): String {
        instant ?: return ""
        val millis = instant.toEpochMilli()
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            date == today -> DateFormat.getTimeFormat(context).format(java.util.Date(millis))
            date.isAfter(today.minusDays(7)) ->
                DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
    }

    fun day(context: Context, instant: Instant): String {
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> context.getString(R.string.day_today)
            today.minusDays(1) -> context.getString(R.string.day_yesterday)
            else -> DateUtils.formatDateTime(context, instant.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE)
        }
    }

    fun duration(seconds: Long): String =
        if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
