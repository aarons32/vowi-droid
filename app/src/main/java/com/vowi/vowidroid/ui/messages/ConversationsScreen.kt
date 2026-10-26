package com.vowi.vowidroid.ui.messages

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.vowi.vowidroid.ui.components.Speech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.components.AppTitle
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Conversation
import com.vowi.vowidroid.data.DraftStore
import com.vowi.vowidroid.data.Message
import com.vowi.vowidroid.data.PhoneNumbers
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LineStatusCard
import com.vowi.vowidroid.ui.NoLineCard
import com.vowi.vowidroid.ui.PermissionCard
import com.vowi.vowidroid.ui.StoppedCard
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.ui.TimeFormat
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.BlockDialog
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberContactPhoto
import com.vowi.vowidroid.ui.usableLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Conversation.key() = "$deviceId|$peer"

/**
 * The Messages tab, or the archived list. As in AOSP Messaging, a swipe
 * archives a conversation (with Undo) and a long-press selects conversations
 * for Archive, Delete, Notifications off and Block; pinning, mark unread and
 * searching texts follow Google Messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    archived: Boolean,
    onOpen: (Conversation) -> Unit,
    onOpenMessage: (Message) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onArchived: () -> Unit,
    onBlocked: () -> Unit,
    onBack: () -> Unit,
    /** Opens a line's settings from the status card. */
    onOpenLine: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by VowiRepository.conversations.collectAsStateWithLifecycle()
    val blocked by BlockedNumbers.numbers.collectAsStateWithLifecycle()
    val allLines by VowiRepository.lines.collectAsStateWithLifecycle()
    val lines = usableLines()
    // While the user has VowiDroid stopped, the stopped card explains the empty list; the no-line card would mislead.
    val userStopped by VowiCore.userStopped.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedKeys by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf<Conversation?>(null) }
    var matchingKeys by remember { mutableStateOf<Set<String>?>(null) }
    var messageHits by remember { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(Unit) { VowiRepository.refreshConversations() }

    val conversations = all.filter { it.archived == archived }
    val selected = conversations.filter { it.key() in selectedKeys }
    val showLine = allLines.size > 1

    // Searching matches names and numbers, and the texts themselves.
    LaunchedEffect(searching, query, all) {
        if (!searching || query.isBlank()) {
            matchingKeys = null
            messageHits = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        val text = query.trim()
        val digits = text.filter(Char::isDigit)
        matchingKeys = withContext(Dispatchers.IO) {
            all.filter { conversation ->
                Contacts.nameFor(context, conversation.peer)?.contains(text, ignoreCase = true) == true ||
                    (digits.isNotEmpty() && conversation.peer.filter(Char::isDigit).contains(digits))
            }.map { it.key() }.toSet()
        }
        messageHits = runCatching { VowiRepository.searchMessages(text) }.getOrDefault(emptyList())
    }

    fun clearSelection() {
        selectedKeys = emptyList()
    }

    fun archive(targets: List<Conversation>, value: Boolean) {
        clearSelection()
        scope.launch {
            targets.forEach { VowiRepository.setArchived(it.deviceId, it.peer, value) }
            // With an action, Material 3 keeps a snackbar up until the action is taken unless told otherwise.
            val result = snackbar.showSnackbar(
                message = context.resources.getQuantityString(if (value) R.plurals.conversations_archived else R.plurals.conversations_unarchived, targets.size, targets.size),
                actionLabel = context.getString(R.string.action_undo),
                withDismissAction = false,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) targets.forEach { VowiRepository.setArchived(it.deviceId, it.peer, !value) }
        }
    }

    BackHandler(enabled = selectedKeys.isNotEmpty() || searching) {
        if (selectedKeys.isNotEmpty()) clearSelection() else {
            searching = false
            query = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                selected.isNotEmpty() -> TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = ::clearSelection) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done)) }
                    },
                    title = { Text(selected.size.toString()) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    actions = {
                        IconButton(onClick = { archive(selected, !archived) }) {
                            Icon(
                                if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                contentDescription = stringResource(if (archived) R.string.action_unarchive else R.string.action_archive),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                        Box {
                            IconButton(onClick = { selectionMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                val anyUnread = selected.any { it.unread > 0 }
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (anyUnread) R.string.action_mark_read else R.string.action_mark_unread)) },
                                    onClick = {
                                        selectionMenu = false
                                        val targets = selected
                                        clearSelection()
                                        scope.launch {
                                            targets.forEach {
                                                if (anyUnread) VowiRepository.markRead(it.deviceId, it.peer) else VowiRepository.markUnread(it.deviceId, it.peer)
                                            }
                                        }
                                    },
                                )
                                if (!archived) {
                                    val allPinned = selected.all { it.pinned }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (allPinned) R.string.action_unpin else R.string.action_pin)) },
                                        onClick = {
                                            selectionMenu = false
                                            val targets = selected
                                            clearSelection()
                                            scope.launch { targets.forEach { VowiRepository.setPinned(it.deviceId, it.peer, !allPinned) } }
                                        },
                                    )
                                }
                                val allMuted = selected.all { it.muted }
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (allMuted) R.string.action_notifications_on else R.string.action_notifications_off)) },
                                    onClick = {
                                        selectionMenu = false
                                        val targets = selected
                                        clearSelection()
                                        scope.launch { targets.forEach { VowiRepository.setMuted(it.deviceId, it.peer, !allMuted) } }
                                    },
                                )
                                val single = selected.singleOrNull()
                                if (single != null) {
                                    val isBlocked = blocked.any { PhoneNumbers.same(it, single.peer) }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (isBlocked) R.string.action_unblock else R.string.action_block)) },
                                        onClick = {
                                            selectionMenu = false
                                            clearSelection()
                                            if (isBlocked) {
                                                BlockedNumbers.unblock(single.peer)
                                                Toast.makeText(context, R.string.unblocked, Toast.LENGTH_SHORT).show()
                                            } else {
                                                confirmBlock = single
                                            }
                                        },
                                    )
                                    // As in AOSP: only for a number that is not in Contacts.
                                    if (rememberContactName(single.peer) == null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_add_contact)) },
                                            onClick = {
                                                selectionMenu = false
                                                clearSelection()
                                                Actions.addContact(context, single.peer)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                searching -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = {
                                searching = false
                                query = ""
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                        },
                        title = {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text(stringResource(R.string.messages_search_hint)) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        },
                        actions = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear)) }
                            }
                        },
                    )
                }
                archived -> TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.messages_archived)) },
                )
                else -> TopAppBar(
                    title = { AppTitle() },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.messages_search))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.messages_archived)) },
                                    onClick = {
                                        menu = false
                                        onArchived()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.blocked_title)) },
                                    onClick = {
                                        menu = false
                                        onBlocked()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!archived && !searching && selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
                    text = { Text(stringResource(R.string.messages_start_chat)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                val keys = matchingKeys
                val hits = if (keys == null) emptyList() else all.filter { it.key() in keys }
                if (hits.isNotEmpty()) {
                    item { SearchLabel(stringResource(R.string.messages_conversations)) }
                    items(hits, key = { "hit|" + it.key() }) { conversation ->
                        ConversationRow(conversation, showLine, selected = false, onClick = { onOpen(conversation) }, onLongClick = {})
                    }
                }
                if (messageHits.isNotEmpty()) {
                    item { SearchLabel(stringResource(R.string.messages_texts)) }
                    items(messageHits, key = { "text|" + it.id }) { message -> MessageHit(message, query.trim()) { onOpenMessage(message) } }
                }
                if (query.isNotBlank() && keys != null && hits.isEmpty() && messageHits.isEmpty()) {
                    item { EmptyText(stringResource(R.string.messages_search_empty)) }
                }
                return@LazyColumn
            }
            if (!archived) {
                item { StoppedCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item { PermissionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item { LineStatusCard(onOpenLine, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (!archived && lines.isEmpty() && !userStopped) {
                item { NoLineCard(onSettings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (conversations.isEmpty()) {
                item { EmptyText(stringResource(if (archived) R.string.messages_archived_empty else R.string.messages_empty)) }
            }
            items(conversations, key = { it.key() }) { conversation ->
                val isSelected = conversation.key() in selectedKeys
                val toggle = {
                    val key = conversation.key()
                    selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                }
                val row = @Composable {
                    ConversationRow(
                        conversation = conversation,
                        showLine = showLine,
                        selected = isSelected,
                        onClick = { if (selectedKeys.isNotEmpty()) toggle() else onOpen(conversation) },
                        onLongClick = toggle,
                    )
                }
                if (archived || selectedKeys.isNotEmpty()) {
                    row()
                } else {
                    // A swipe either way archives, as in AOSP Messaging.
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) archive(listOf(conversation), true)
                        value != SwipeToDismissBoxValue.Settled
                    })
                    SwipeToDismissBox(
                        state = dismiss,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                                contentAlignment = if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
                            ) { Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.action_archive)) }
                        },
                    ) { row() }
                }
            }
        }
    }

    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.conversations_delete_title, count, count)) },
            text = { Text(stringResource(R.string.thread_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val targets = selected
                    clearSelection()
                    scope.launch {
                        targets.forEach {
                            DraftStore.set(context, it.deviceId, it.peer, "")
                            runCatching { VowiRepository.deleteThread(it.deviceId, it.peer) }
                        }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmBlock?.let { conversation ->
        val name = rememberContactName(conversation.peer)
        BlockDialog(conversation.peer, name, onDismiss = { confirmBlock = null }, onConfirm = {
            BlockedNumbers.block(conversation.peer)
            scope.launch { VowiRepository.setArchived(conversation.deviceId, conversation.peer, true) }
            Toast.makeText(context, R.string.blocked_and_archived, Toast.LENGTH_SHORT).show()
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conversation: Conversation, showLine: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val name = rememberContactName(conversation.peer)
    val photo = rememberContactPhoto(conversation.peer)
    val unread = conversation.unread > 0
    val draftVersion by DraftStore.version.collectAsStateWithLifecycle()
    val draft = remember(draftVersion, conversation.deviceId, conversation.peer) {
        DraftStore.get(context, conversation.deviceId, conversation.peer)
    }
    // One spoken line, as AOSP Messaging describes a conversation: who, unread, the last text, when.
    val description = listOfNotNull(
        name ?: Speech.number(Contacts.format(conversation.peer)),
        if (unread) pluralStringResource(R.plurals.a11y_unread, conversation.unread, conversation.unread) else null,
        when {
            draft.isNotBlank() -> stringResource(R.string.messages_draft, draft)
            conversation.outgoing && conversation.lastStatus == "failed" -> stringResource(R.string.thread_failed_retry)
            conversation.outgoing -> stringResource(R.string.a11y_you_said, conversation.lastMessage)
            else -> conversation.lastMessage
        },
        TimeFormat.short(context, conversation.lastTime),
        conversation.deviceName.takeIf { showLine && it.isNotBlank() },
        stringResource(R.string.messages_pinned).takeIf { conversation.pinned },
        stringResource(R.string.messages_muted).takeIf { conversation.muted },
    ).joinToString(". ")
    val selectLabel = stringResource(R.string.a11y_select)
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        leadingContent = {
            if (selected) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
            } else {
                Avatar(name, conversation.peer, size = 48.dp, photo = photo)
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    name ?: Contacts.format(conversation.peer),
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.muted) {
                    Icon(Icons.Outlined.NotificationsOff, contentDescription = stringResource(R.string.messages_muted), modifier = Modifier.size(16.dp))
                }
                if (conversation.pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.messages_pinned), modifier = Modifier.size(16.dp))
                }
            }
        },
        supportingContent = {
            when {
                // An unsent draft shows in italics, as AOSP Messaging shows it.
                draft.isNotBlank() -> Text(
                    stringResource(R.string.messages_draft, draft),
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                conversation.outgoing && conversation.lastStatus == "failed" -> Text(
                    stringResource(R.string.thread_failed_retry),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                else -> Text(
                    (if (conversation.outgoing) stringResource(R.string.messages_you_prefix) + " " else "") + conversation.lastMessage,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                val sending = conversation.outgoing && (conversation.lastStatus == "sending" || conversation.lastStatus == "queued")
                Text(
                    if (sending) stringResource(R.string.thread_sending) else TimeFormat.short(context, conversation.lastTime),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (showLine && conversation.deviceName.isNotBlank()) {
                    Text(conversation.deviceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (unread) {
                    Badge(modifier = Modifier.padding(top = 4.dp)) { Text(conversation.unread.toString()) }
                }
            }
        },
    )
}

/** A text found by search: who, when, and the words around the match. */
@Composable
private fun MessageHit(message: Message, query: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = rememberContactName(message.peer)
    val photo = rememberContactPhoto(message.peer)
    val index = message.body.indexOf(query, ignoreCase = true)
    val snippet = if (index > 40) "…" + message.body.substring(index - 30) else message.body
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(name, message.peer, size = 48.dp, photo = photo) },
        headlineContent = { Text(name ?: Contacts.format(message.peer), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(TimeFormat.short(context, message.time), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun SearchLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
