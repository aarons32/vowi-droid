package com.vowi.vowidroid.ui.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.components.AppTitle
import com.vowi.vowidroid.data.Contact
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.ui.LinePreferences
import com.vowi.vowidroid.ui.Permissions
import com.vowi.vowidroid.ui.calls.rememberCallLauncher
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberPhoto
import com.vowi.vowidroid.ui.usableLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The section a contact sorts under: the Contacts app's index letter, else its first letter or #. */
private fun sectionOf(contact: Contact): String {
    if (contact.section.isNotBlank()) return contact.section
    val first = contact.name.trim().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * The Contacts tab, after AOSP's contacts list: favorites (starred in
 * Contacts) first, then A–Z sections with sticky headers and a fast scroller,
 * photos, and each number's type. A contact opens a sheet to call or text any
 * of its numbers, or to open it in Contacts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(Contacts.permitted(context)) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Contact?>(null) }
    val listState = rememberLazyListState()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = Contacts.permitted(context)
        if (permitted) Contacts.forget()
        else if (Permissions.blockedByRom(context, Manifest.permission.READ_CONTACTS)) Permissions.openAppSettings(context)
    }
    LaunchedEffect(permitted) {
        if (permitted) contacts = withContext(Dispatchers.IO) { Contacts.all(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTitle() },
                actions = {
                    IconButton(onClick = { Actions.createContact(context, "") }) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.contacts_create))
                    }
                },
            )
        },
    ) { padding ->
        if (!permitted) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.contacts_permission_body), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = {
                        if (Permissions.blockedByRom(context, Manifest.permission.READ_CONTACTS)) Permissions.openAppSettings(context)
                        else request.launch(Manifest.permission.READ_CONTACTS)
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.contacts_permission_action))
                }
            }
            return@Scaffold
        }
        val digits = query.filter(Char::isDigit)
        val visible = contacts.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                (digits.isNotEmpty() && it.numbers.any { number -> number.filter(Char::isDigit).contains(digits) })
        }
        val favorites = if (query.isBlank()) visible.filter { it.starred } else emptyList()
        // The provider already sorts by its index, so sections keep the order they come in.
        val sections = visible.groupBy(::sectionOf)
        // Where each section's header sits in the list, for the fast scroller.
        val headerIndex = HashMap<String, Int>()
        var index = 1 + (if (favorites.isNotEmpty()) 1 + favorites.size else 0) + (if (visible.isEmpty()) 1 else 0)
        for ((letter, members) in sections) {
            headerIndex[letter] = index
            index += 1 + members.size
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.contacts_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                if (favorites.isNotEmpty()) {
                    stickyHeader(key = "header-favorites") { SectionHeader(stringResource(R.string.contacts_favorites), Icons.Filled.Star) }
                    items(favorites, key = { "favorite-${it.id}" }) { contact -> ContactRow(contact) { selected = contact } }
                }
                if (visible.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.contacts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                for ((letter, members) in sections) {
                    stickyHeader(key = "header-$letter") { SectionHeader(letter, null) }
                    items(members, key = { it.id }) { contact -> ContactRow(contact) { selected = contact } }
                }
            }
            if (query.isBlank() && sections.size > 3) {
                FastScroller(
                    letters = sections.keys.toList(),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 72.dp),
                ) { letter -> headerIndex[letter]?.let { scope.launch { listState.scrollToItem(it) } } }
            }
        }
    }

    selected?.let { contact ->
        ContactSheet(contact, onDismiss = { selected = null }, onMessage = { number ->
            selected = null
            onMessage(number)
        })
    }
}

@Composable
private fun SectionHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val photo = rememberPhoto(contact.photoUri)
    val first = contact.numbers.firstOrNull().orEmpty()
    val label = contact.labels.firstOrNull()
    val more = contact.numbers.size - 1
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(contact.name, contact.numbers.firstOrNull() ?: contact.name, photo = photo) },
        headlineContent = { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = listOfNotNull(label, Contacts.format(first)).joinToString(" · ") + if (more > 0) "  +$more" else ""
            Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/** The letter index on the right edge: tap or drag to jump to a section. */
@Composable
private fun FastScroller(letters: List<String>, modifier: Modifier, onLetter: (String) -> Unit) {
    var height by remember { mutableStateOf(1) }
    fun letterAt(y: Float): String = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    Column(
        modifier = modifier
            .width(28.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) { detectTapGestures { offset -> onLetter(letterAt(offset.y)) } }
            .pointerInput(letters) { detectVerticalDragGestures { change, _ -> onLetter(letterAt(change.position.y)) } },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactSheet(contact: Contact, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val lines = usableLines()
    val call = rememberCallLauncher()
    val photo = rememberPhoto(contact.photoUri)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(contact.name, contact.numbers.firstOrNull() ?: contact.name, size = 72.dp, photo = photo)
            Text(contact.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            contact.numbers.forEachIndexed { index, number ->
                ListItem(
                    headlineContent = { Text(Contacts.format(number)) },
                    supportingContent = { contact.labels.getOrNull(index)?.let { Text(it) } },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                onDismiss()
                                call(LinePreferences.pick(lines, LinePreferences.defaultLine(context)), number)
                            }) { Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call)) }
                            IconButton(onClick = { onMessage(number) }) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.action_send_message))
                            }
                        }
                    },
                )
            }
            val lookup = contact.lookupKey
            if (lookup != null) {
                TextButton(onClick = {
                    onDismiss()
                    Actions.viewContact(context, android.provider.ContactsContract.Contacts.getLookupUri(contact.id, lookup))
                }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.contacts_open))
                }
            }
        }
    }
}
