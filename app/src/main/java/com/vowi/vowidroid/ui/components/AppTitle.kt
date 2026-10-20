package com.vowi.vowidroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.networkLabel
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.theme.CallGreen

/**
 * The title of every tab: the app's name, and under it which carriers are
 * online, that is which lines are registered for WiFi Calling right now.
 */
@Composable
fun AppTitle() {
    val lines by VowiRepository.lines.collectAsStateWithLifecycle()
    val registered = lines.filter { it.registered }
    val online = registered.map { it.name }
    val network = networkLabel(registered.firstOrNull()?.network.orEmpty())
    val connecting = lines.any { it.wifiCallingEnabled && it.present && it.sim == Line.SimStatus.IN_USE && !it.registered }
    val subtitle = when {
        online.isNotEmpty() -> stringResource(R.string.title_online, online.joinToString(stringResource(R.string.list_separator))) + (if (network.isNotEmpty()) " · $network" else "")
        connecting -> stringResource(R.string.title_connecting)
        else -> stringResource(R.string.title_offline)
    }
    Column {
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (online.isNotEmpty()) CallGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}
