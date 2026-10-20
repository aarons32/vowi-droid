package com.vowi.vowidroid.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberContactPhoto

/** The numbers the user blocked, with Unblock and Add a number, like AOSP's blocked-numbers screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit) {
    val numbers by BlockedNumbers.numbers.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.blocked_title)) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.blocked_add)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.blocked_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (numbers.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.blocked_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(numbers, key = { it }) { number ->
                val name = rememberContactName(number)
                val photo = rememberContactPhoto(number)
                ListItem(
                    leadingContent = { Avatar(name, number, photo = photo) },
                    headlineContent = { Text(name ?: Contacts.format(number)) },
                    supportingContent = { if (name != null) Text(Contacts.format(number)) },
                    trailingContent = {
                        TextButton(onClick = { BlockedNumbers.unblock(number) }) { Text(stringResource(R.string.action_unblock)) }
                    },
                )
            }
        }
    }
    if (adding) {
        var number by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.blocked_add)) },
            text = {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.blocked_number_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        adding = false
                        BlockedNumbers.block(number.trim())
                    },
                    enabled = number.any(Char::isDigit),
                ) { Text(stringResource(R.string.action_block)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
