package com.vowi.vowidroid.ui.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Contact
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Conversation
import com.vowi.vowidroid.data.DraftStore
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LineChips
import com.vowi.vowidroid.ui.LinePreferences
import com.vowi.vowidroid.ui.NoLineCard
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberPhoto
import com.vowi.vowidroid.ui.components.rememberContactPhoto
import com.vowi.vowidroid.ui.usableLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts a conversation: a number, a contact or a recent conversation, and
 * the line to text from. Text being forwarded or shared into VowiDroid
 * ([initialBody]) becomes the chosen conversation's draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(initialNumber: String, initialBody: String, onStart: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lines = usableLines()
    var line by remember(lines) { mutableStateOf(LinePreferences.pick(lines, LinePreferences.defaultLine(context))) }
    var to by rememberSaveable { mutableStateOf(initialNumber) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val conversations by VowiRepository.conversations.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { Contacts.all(context) } }

    fun open(deviceId: String, number: String) {
        if (initialBody.isNotBlank()) DraftStore.append(context, deviceId, number, initialBody)
        onStart(deviceId, number)
    }

    fun start(number: String) {
        val chosen = line ?: return
        val cleaned = number.filter { it.isDigit() || it == '+' }
        if (cleaned.isNotEmpty()) open(chosen.id, cleaned)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(if (initialBody.isNotBlank()) R.string.messages_send_to_title else R.string.messages_new_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (lines.isEmpty()) {
                NoLineCard(onSettings = onBack, modifier = Modifier.padding(16.dp))
            }
            if (initialBody.isNotBlank()) {
                Text(
                    initialBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text(stringResource(R.string.messages_to)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = {
                    IconButton(onClick = { start(to) }, enabled = line != null && to.any(Char::isDigit)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.messages_start_chat))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LineChips(lines, line, { line = it }, Modifier.padding(horizontal = 16.dp))
            val query = to.trim()
            val digits = query.filter(Char::isDigit)
            val matches = contacts.filter { contact ->
                query.isEmpty() || contact.name.contains(query, ignoreCase = true) ||
                    contact.numbers.any { it.filter(Char::isDigit).contains(digits.ifEmpty { "\u0000" }) }
            }
            // Recent conversations keep their own line, so a forward lands where the conversation is.
            val recent = conversations.filter { conversation ->
                lines.any { it.id == conversation.deviceId } &&
                    (query.isEmpty() || conversation.peer.filter(Char::isDigit).contains(digits.ifEmpty { "\u0000" }))
            }.take(if (query.isEmpty()) 8 else 4)
            if (matches.isEmpty() && recent.isEmpty() && !Contacts.permitted(context)) {
                Text(
                    stringResource(R.string.contacts_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (recent.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.messages_recent)) }
                    items(recent, key = { "recent|" + it.deviceId + "|" + it.peer }) { conversation ->
                        RecentRow(conversation, showLine = lines.size > 1) { open(conversation.deviceId, conversation.peer) }
                    }
                    if (matches.isNotEmpty()) item { SectionLabel(stringResource(R.string.messages_contacts)) }
                }
                matches.forEach { contact ->
                    items(contact.numbers, key = { contact.id.toString() + it }) { number ->
                        val photo = rememberPhoto(contact.photoUri)
                        ListItem(
                            modifier = Modifier.clickable { start(number) },
                            leadingContent = { Avatar(contact.name, number, photo = photo) },
                            headlineContent = { Text(contact.name) },
                            supportingContent = { Text(Contacts.format(number)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecentRow(conversation: Conversation, showLine: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = rememberContactName(conversation.peer)
    val photo = rememberContactPhoto(conversation.peer)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(name, conversation.peer, photo = photo) },
        headlineContent = { Text(name ?: Contacts.format(conversation.peer), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = listOfNotNull(name?.let { Contacts.format(conversation.peer) }, conversation.deviceName.takeIf { showLine && it.isNotBlank() })
            if (details.isNotEmpty()) Text(details.joinToString(" · "), maxLines = 1)
        },
    )
}
