package com.vowi.vowidroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.QuickResponses

/** The texts offered when declining a call, editable like Telecom's quick responses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickResponsesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var responses by remember { mutableStateOf(QuickResponses.all(context)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_quick_responses)) },
                actions = {
                    TextButton(onClick = {
                        QuickResponses.reset(context)
                        responses = QuickResponses.all(context)
                    }) { Text(stringResource(R.string.action_reset)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.settings_quick_responses_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(responses) { index, response ->
                ListItem(modifier = Modifier.clickable { editing = index }, headlineContent = { Text(response) })
            }
        }
    }
    editing?.let { index ->
        var text by remember(index) { mutableStateOf(responses[index]) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.quick_responses_edit)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 4) },
            confirmButton = {
                TextButton(
                    onClick = {
                        responses = responses.toMutableList().also { it[index] = text.trim() }
                        QuickResponses.save(context, responses)
                        editing = null
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
