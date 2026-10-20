package com.vowi.vowidroid.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.core.util.CoreLog

/** The core's log, for diagnosing a line that will not register. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val version by CoreLog.version.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf(CoreLog.snapshot()) }
    val listState = rememberLazyListState()
    LaunchedEffect(version) {
        entries = CoreLog.snapshot()
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                title = { Text(stringResource(R.string.logs_title)) },
                actions = {
                    IconButton(onClick = { CoreLog.clear() }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.logs_clear)) }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(stringResource(R.string.logs_empty), modifier = Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            items(entries) { entry ->
                val color = when (entry.level) {
                    CoreLog.Level.ERROR, CoreLog.Level.WARN -> MaterialTheme.colorScheme.error
                    CoreLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    entry.format(),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}
