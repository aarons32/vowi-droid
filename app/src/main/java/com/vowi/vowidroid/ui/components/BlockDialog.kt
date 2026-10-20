package com.vowi.vowidroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Contacts

/** "Block <number>?", worded after AOSP's block confirmation. */
@Composable
fun BlockDialog(number: String, name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_title, name ?: Contacts.format(number))) },
        text = { Text(stringResource(R.string.block_body)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.action_block)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
