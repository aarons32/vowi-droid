package com.vowi.vowidroid.ui.messages

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.vowi.vowidroid.ui.components.Speech
import android.text.SpannableString
import android.text.format.DateUtils
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import com.vowi.vowidroid.core.sms.SmsPdu
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.PhoneNumbers
import com.vowi.vowidroid.ui.components.BlockDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.Notifications
import com.vowi.vowidroid.R
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.DraftStore
import com.vowi.vowidroid.data.Message
import com.vowi.vowidroid.data.SmsStore
import com.vowi.vowidroid.core.sms.RpCauses
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.telecom.EmergencyNumbers
import com.vowi.vowidroid.ui.TimeFormat
import com.vowi.vowidroid.ui.calls.rememberCallLauncher
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.rememberContactName
import com.vowi.vowidroid.ui.components.rememberContactUri
import com.vowi.vowidroid.ui.usableLines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One conversation: bubbles, newest at the bottom, and the composer. A
 * long-press selects a message and offers what AOSP Messaging offers: copy,
 * select text, share, forward, delete, details and, for a failed text, send
 * again. Links, numbers and email addresses in texts can be tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    deviceId: String,
    peer: String,
    onBack: () -> Unit,
    onForward: (String) -> Unit,
    onOpenThread: (String, String) -> Unit,
    onSwitchLine: (String) -> Unit,
    /** Shown in a bubble: no back arrow, as the bubble collapses by itself. */
    inBubble: Boolean = false,
    /** Opens a line's settings, for the SMS centre a refused text points at; null where there is no settings screen to go to. */
    onOpenLine: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val allLines by VowiRepository.lines.collectAsStateWithLifecycle()
    val line = allLines.firstOrNull { it.id == deviceId }
    val callable = usableLines().firstOrNull { it.id == deviceId }
    val call = rememberCallLauncher()
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf(DraftStore.get(context, deviceId, peer)) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var confirmDeleteThread by remember { mutableStateOf(false) }
    var confirmDeleteMessage by remember { mutableStateOf<Message?>(null) }
    var details by remember { mutableStateOf<Message?>(null) }
    var selectText by remember { mutableStateOf<Message?>(null) }
    var tappedNumber by remember { mutableStateOf<String?>(null) }
    var emergencyText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val name = rememberContactName(peer)
    val contactUri = rememberContactUri(peer, name)
    val selected = messages.firstOrNull { it.id == selectedId }
    val conversations by VowiRepository.conversations.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.deviceId == deviceId && it.peer == peer }
    val blockedNumbers by BlockedNumbers.numbers.collectAsStateWithLifecycle()
    val isBlocked = blockedNumbers.any { PhoneNumbers.same(it, peer) }
    val usable = usableLines()
    var confirmBlock by remember { mutableStateOf(false) }
    var lineMenu by remember { mutableStateOf(false) }
    var unseen by remember { mutableStateOf(false) }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    suspend fun reload() {
        runCatching { VowiRepository.thread(deviceId, peer) }.onSuccess { messages = it }
    }

    fun send(text: String) {
        draft = ""
        scope.launch {
            runCatching { VowiRepository.sendSms(deviceId, peer, text) }
                .onFailure { Toast.makeText(context, it.message ?: "", Toast.LENGTH_LONG).show() }
            reload()
        }
    }

    fun resend(message: Message) {
        selectedId = null
        scope.launch {
            runCatching { VowiRepository.resendSms(message.id) }
                .onFailure { Toast.makeText(context, context.getString(R.string.thread_resend_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
            reload()
        }
    }

    LaunchedEffect(deviceId, peer) {
        // Delivery reports and new texts update in place; a push also reloads at once.
        launch { VowiRepository.messageChanges.collect { reload() } }
        while (true) {
            reload()
            delay(5000)
        }
    }
    // Follow new texts at the bottom; scrolled up, a "New message" chip appears instead.
    LaunchedEffect(messages.lastOrNull()?.id) {
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        if (atBottom || newest.outgoing) listState.animateScrollToItem(0) else unseen = true
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }
    // The unsent text survives leaving the conversation, as in AOSP Messaging.
    LaunchedEffect(draft) { DraftStore.set(context, deviceId, peer, draft) }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    // While the conversation is on screen, its texts do not raise notifications.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, deviceId, peer) {
        val key = VowiCore.conversationKey(deviceId, peer)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    VowiCore.visibleConversation = key
                    Notifications.cancelConversation(context, deviceId, peer)
                }
                Lifecycle.Event.ON_PAUSE -> if (VowiCore.visibleConversation == key) VowiCore.visibleConversation = null
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (VowiCore.visibleConversation == key) VowiCore.visibleConversation = null
        }
    }

    Scaffold(
        topBar = {
            if (selected != null) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedId = null }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done))
                        }
                    },
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    actions = {
                        if (selected.outgoing && selected.status == "failed") {
                            IconButton(onClick = { resend(selected) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_resend))
                            }
                        }
                        IconButton(onClick = {
                            Actions.copy(context, clipboard, selected.body)
                            selectedId = null
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy_text)) }
                        IconButton(onClick = {
                            Actions.shareText(context, selected.body)
                            selectedId = null
                        }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share)) }
                        IconButton(onClick = {
                            selectedId = null
                            onForward(selected.body)
                        }) { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.action_forward)) }
                        IconButton(onClick = { confirmDeleteMessage = selected }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                        Box {
                            IconButton(onClick = { selectionMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_select_text)) },
                                    onClick = {
                                        selectionMenu = false
                                        selectText = selected
                                        selectedId = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_details)) },
                                    onClick = {
                                        selectionMenu = false
                                        details = selected
                                        selectedId = null
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        if (!inBubble) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    title = {
                        // One line each, as AOSP Messaging's title bar: a long line name must not
                        // wrap and push the bar past its height.
                        Column {
                            Text(name ?: Contacts.format(peer), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(name?.let { Contacts.format(peer) }, line?.name).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { call(callable, peer) }, enabled = callable != null) {
                            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val uri = contactUri
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (uri != null) R.string.action_view_contact else R.string.action_add_contact)) },
                                    onClick = {
                                        menu = false
                                        if (uri != null) Actions.viewContact(context, uri) else Actions.addContact(context, peer)
                                    },
                                )
                                if (conversation != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (conversation.archived) R.string.action_unarchive else R.string.action_archive)) },
                                        onClick = {
                                            menu = false
                                            val archive = !conversation.archived
                                            scope.launch { VowiRepository.setArchived(deviceId, peer, archive) }
                                            if (archive) onBack()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (conversation.muted) R.string.action_notifications_on else R.string.action_notifications_off)) },
                                        onClick = {
                                            menu = false
                                            scope.launch { VowiRepository.setMuted(deviceId, peer, !conversation.muted) }
                                        },
                                    )
                                    // The conversation's own sound, priority and bubble settings (Android 11+).
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_notification_settings)) },
                                        onClick = {
                                            menu = false
                                            Actions.conversationNotificationSettings(context, deviceId, peer)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (isBlocked) R.string.action_unblock else R.string.action_block)) },
                                    onClick = {
                                        menu = false
                                        if (isBlocked) {
                                            BlockedNumbers.unblock(peer)
                                            Toast.makeText(context, R.string.unblocked, Toast.LENGTH_SHORT).show()
                                        } else {
                                            confirmBlock = true
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete_conversation)) },
                                    onClick = {
                                        menu = false
                                        confirmDeleteThread = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        // The keyboard's height already includes the navigation bar the scaffold padded for,
        // so that padding counts as used and the composer sits right on the keyboard.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    // Newest first, drawn from the bottom. Texts from one side within a minute form a
                    // group that shows its time once, as AOSP Messaging groups them; days get headers.
                    val reversed = messages.asReversed()
                    val newestOutgoing = reversed.firstOrNull { it.outgoing }
                    reversed.forEachIndexed { index, message ->
                        val newer = reversed.getOrNull(index - 1)
                        val older = reversed.getOrNull(index + 1)
                        val day = TimeFormat.day(context, message.time ?: java.time.Instant.EPOCH)
                        val groupEnd = newer == null || !sameGroup(message, newer) || message.status == "failed"
                        val groupStart = older == null || !sameGroup(older, message) || older.status == "failed"
                        item(key = message.id) {
                            Bubble(
                                message = message,
                                sender = name ?: Speech.number(Contacts.format(peer)),
                                newestOutgoing = message === newestOutgoing,
                                groupStart = groupStart,
                                groupEnd = groupEnd,
                                selected = message.id == selectedId,
                                onClick = {
                                    when {
                                        selectedId != null -> selectedId = if (selectedId == message.id) null else message.id
                                        // "Not sent. Tap to try again."
                                        message.outgoing && message.status == "failed" -> resend(message)
                                    }
                                },
                                onLongClick = { selectedId = message.id },
                                onNumber = { tappedNumber = it },
                                onCheckSmsCenter = onOpenLine?.let { open -> { open(deviceId) } },
                            )
                        }
                        if (older == null || TimeFormat.day(context, older.time ?: java.time.Instant.EPOCH) != day) {
                            item(key = "day-$day-${message.id}") { DayHeader(day) }
                        }
                    }
                }
                if (unseen) {
                    SmallFloatingActionButtonChip(
                        text = stringResource(R.string.thread_new_message),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    ) {
                        unseen = false
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
            }
            if (usable.size > 1) {
                // Which line the text goes out on; another line means that line's conversation.
                Box(Modifier.padding(start = 12.dp, top = 4.dp)) {
                    AssistChip(
                        onClick = { lineMenu = true },
                        label = { Text(stringResource(R.string.thread_via, line?.name.orEmpty())) },
                        leadingIcon = { Icon(Icons.Filled.SimCard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                        Text(
                            stringResource(R.string.thread_switch_line),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        usable.filter { it.id != deviceId }.forEach { other ->
                            DropdownMenuItem(
                                text = { Text(other.name) },
                                leadingIcon = { Icon(Icons.Filled.SimCard, contentDescription = null) },
                                onClick = {
                                    lineMenu = false
                                    if (draft.isNotBlank()) DraftStore.append(context, other.id, peer, draft)
                                    draft = ""
                                    onSwitchLine(other.id)
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = {
                        Text(
                            if (line?.smsReady == true) stringResource(R.string.thread_hint, line.name)
                            else stringResource(R.string.thread_line_not_ready),
                        )
                    },
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                    // AOSP's counter: characters left near the end of a text, "left / parts" once it spans several.
                    val length = remember(draft) { SmsPdu.measure(draft) }
                    if (length.parts > 1) {
                        Text(stringResource(R.string.thread_counter, length.remaining, length.parts), style = MaterialTheme.typography.labelSmall)
                    } else if (length.parts == 1 && length.remaining <= 10) {
                        Text(length.remaining.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                    FilledIconButton(
                        onClick = {
                            val text = draft.trim()
                            if (EmergencyNumbers.isEmergency(context, peer)) emergencyText = text else send(text)
                        },
                        enabled = draft.isNotBlank() && line?.smsReady == true,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.thread_send)) }
                }
            }
        }
    }

    if (confirmDeleteThread) {
        AlertDialog(
            onDismissRequest = { confirmDeleteThread = false },
            title = { Text(stringResource(R.string.thread_delete_title)) },
            text = { Text(stringResource(R.string.thread_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteThread = false
                    DraftStore.set(context, deviceId, peer, "")
                    scope.launch {
                        runCatching { VowiRepository.deleteThread(deviceId, peer) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteThread = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDeleteMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDeleteMessage = null },
            title = { Text(stringResource(R.string.thread_delete_message_title)) },
            text = { Text(stringResource(R.string.thread_delete_message_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteMessage = null
                    selectedId = null
                    scope.launch {
                        runCatching { VowiRepository.deleteMessage(message.id) }
                        reload()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMessage = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    details?.let { message ->
        MessageDetails(message, name, line?.let { listOf(it.name, it.phoneNumber).filter(String::isNotBlank).joinToString(" · ") }) { details = null }
    }

    selectText?.let { message ->
        AlertDialog(
            onDismissRequest = { selectText = null },
            text = { SelectionContainer { Text(message.body, style = MaterialTheme.typography.bodyLarge) } },
            confirmButton = { TextButton(onClick = { selectText = null }) { Text(stringResource(R.string.action_done)) } },
        )
    }

    tappedNumber?.let { number ->
        NumberSheet(
            number = number,
            onDismiss = { tappedNumber = null },
            onCall = { call(callable, number) },
            onMessage = { onOpenThread(deviceId, number) },
        )
    }

    if (confirmBlock) {
        BlockDialog(peer, name, onDismiss = { confirmBlock = false }, onConfirm = {
            BlockedNumbers.block(peer)
            scope.launch { VowiRepository.setArchived(deviceId, peer, true) }
            Toast.makeText(context, R.string.blocked_and_archived, Toast.LENGTH_SHORT).show()
            onBack()
        })
    }

    emergencyText?.let { text ->
        AlertDialog(
            onDismissRequest = { emergencyText = null },
            title = { Text(stringResource(R.string.emergency_text_title)) },
            text = { Text(stringResource(R.string.emergency_text_body)) },
            confirmButton = {
                TextButton(onClick = {
                    emergencyText = null
                    EmergencyNumbers.openSystemDialer(context, peer)
                }) { Text(stringResource(R.string.emergency_text_call)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    emergencyText = null
                    send(text)
                }) { Text(stringResource(R.string.emergency_text_send)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: Message,
    sender: String,
    newestOutgoing: Boolean,
    groupStart: Boolean,
    groupEnd: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onNumber: (String) -> Unit,
    onCheckSmsCenter: (() -> Unit)?,
) {
    val context = LocalContext.current
    val outgoing = message.outgoing
    val textColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val text = rememberLinkedText(message.body, textColor, onNumber)
    val status = statusText(message, newestOutgoing)
    // "Alex: see you at 6. 5:02 PM. Delivered", as AOSP Messaging reads a message; links stay reachable on their own.
    val description = listOfNotNull(
        if (outgoing) stringResource(R.string.a11y_you_said, message.body) else stringResource(R.string.a11y_they_said, sender, message.body),
        TimeFormat.short(context, message.time),
        status,
    ).joinToString(". ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(start = 12.dp, end = 12.dp, top = if (groupStart) 8.dp else 1.dp, bottom = 1.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    // Grouped bubbles meet with tight corners; the last one keeps the tail.
                    RoundedCornerShape(
                        topStart = if (!outgoing && !groupStart) 6.dp else 20.dp,
                        topEnd = if (outgoing && !groupStart) 6.dp else 20.dp,
                        bottomStart = if (outgoing) 20.dp else if (groupEnd) 4.dp else 6.dp,
                        bottomEnd = if (!outgoing) 20.dp else if (groupEnd) 4.dp else 6.dp,
                    ),
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_select))
                .semantics {
                    contentDescription = description
                    this.selected = selected
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = textColor, style = MaterialTheme.typography.bodyLarge)
        }
        val failed = message.status == "failed" || message.deliveryState == "failed"
        // Only the last bubble of a group shows the time; a status always shows.
        if (groupEnd || status != null) {
            Text(
                listOfNotNull(TimeFormat.short(context, message.time).takeIf { groupEnd }, status).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                // Already part of the bubble's description.
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).clearAndSetSemantics {},
            )
        }
        // A text the network refused most often means the line sends through the wrong SMS centre.
        if (outgoing && message.status == "failed" && networkRefused(message.error)) {
            Text(
                stringResource(R.string.thread_smsc_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            if (onCheckSmsCenter != null) {
                TextButton(onClick = onCheckSmsCenter, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.thread_smsc_check), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Texts from the same side within a minute of each other, on the same day, share one group. */
private fun sameGroup(older: Message, newer: Message): Boolean {
    if (older.outgoing != newer.outgoing) return false
    val a = older.time ?: return false
    val b = newer.time ?: return false
    if (java.time.Duration.between(a, b).abs().seconds > 60) return false
    val zone = java.time.ZoneId.systemDefault()
    return a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()
}

@Composable
private fun DayHeader(day: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "New message" chip shown when a text arrives while the user reads older ones. */
@Composable
private fun SmallFloatingActionButtonChip(text: String, modifier: Modifier, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
        text = { Text(text) },
        modifier = modifier,
    )
}

/**
 * The message with its web links, email addresses and phone numbers made
 * tappable, found the way AOSP Messaging finds them (Linkify). Web and email
 * links open in their apps; numbers call [onNumber].
 */
@Composable
private fun rememberLinkedText(body: String, color: Color, onNumber: (String) -> Unit): AnnotatedString {
    val currentOnNumber by rememberUpdatedState(onNumber)
    return remember(body, color) {
        val spannable = SpannableString(body)
        Linkify.addLinks(spannable, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS)
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java).sortedBy { spannable.getSpanStart(it) }
        if (spans.isEmpty()) return@remember AnnotatedString(body)
        val styles = TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                if (start < cursor || end <= start) continue
                append(body.substring(cursor, start))
                val url = span.url
                val link = if (url.startsWith("tel:")) {
                    LinkAnnotation.Clickable(url, styles) { currentOnNumber(url.removePrefix("tel:")) }
                } else {
                    LinkAnnotation.Url(url, styles)
                }
                withLink(link) { append(body.substring(start, end)) }
                cursor = end
            }
            append(body.substring(cursor))
        }
    }
}

/** Why a text failed, in words: the service centre's cause, the IMS core's SIP code, or VowiDroid's own reason. */
@Composable
private fun failureReason(error: String): String? = when {
    error.startsWith(SmsStore.ERROR_RP) ->
        error.removePrefix(SmsStore.ERROR_RP).toIntOrNull()?.let { stringResource(R.string.thread_refused_rp, it, RpCauses.name(it)) }
    error.startsWith(SmsStore.ERROR_SIP) -> error.removePrefix(SmsStore.ERROR_SIP).toIntOrNull()?.let { stringResource(R.string.thread_refused_sip, it) }
    error.startsWith(SmsStore.ERROR_LOCAL) -> error.removePrefix(SmsStore.ERROR_LOCAL).takeIf { it.isNotBlank() }
    else -> null
}

/** The network, not VowiDroid, turned the text away. */
private fun networkRefused(error: String): Boolean = error.startsWith(SmsStore.ERROR_RP) || error.startsWith(SmsStore.ERROR_SIP)

@Composable
private fun statusText(message: Message, newestOutgoing: Boolean): String? = when {
    !message.outgoing -> null
    message.status == "failed" -> failureReason(message.error)?.let { stringResource(R.string.thread_failed_reason, it) } ?: stringResource(R.string.thread_failed_retry)
    message.status == "sending" || message.status == "queued" -> stringResource(R.string.thread_sending)
    message.deliveryState.equals("failed", ignoreCase = true) -> stringResource(R.string.thread_not_delivered)
    !newestOutgoing -> null
    message.deliveryState.equals("delivered", ignoreCase = true) -> stringResource(R.string.thread_delivered)
    message.status.isNotEmpty() -> stringResource(R.string.thread_sent)
    else -> null
}

/** What AOSP's message details show for a text: type, the other number, times, line and status. */
@Composable
private fun MessageDetails(message: Message, name: String?, line: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sentAt = message.time?.toEpochMilli()?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
    }
    val other = listOfNotNull(name, Contacts.format(message.peer)).joinToString(" · ")
    val rows = buildList {
        add(stringResource(R.string.details_type) to stringResource(R.string.details_type_sms))
        add(stringResource(if (message.outgoing) R.string.details_to else R.string.details_from) to other)
        sentAt?.let { add(stringResource(if (message.outgoing) R.string.details_sent else R.string.details_received) to it) }
        line?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.details_line) to it) }
        if (message.outgoing) {
            val status = when {
                message.status == "failed" -> listOfNotNull(stringResource(R.string.thread_failed), failureReason(message.error)).joinToString(" · ")
                message.status == "sending" || message.status == "queued" -> stringResource(R.string.thread_sending)
                message.deliveryState.equals("delivered", ignoreCase = true) -> stringResource(R.string.thread_delivered)
                message.deliveryState.equals("failed", ignoreCase = true) -> stringResource(R.string.thread_not_delivered)
                else -> stringResource(R.string.thread_sent)
            }
            add(stringResource(R.string.details_status) to status)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** What to do with a phone number tapped in a text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberSheet(number: String, onDismiss: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val formatted = Contacts.format(number)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                formatted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                modifier = Modifier.clickable { onDismiss(); onCall() },
                leadingContent = { Icon(Icons.Filled.Call, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_call_number, formatted)) },
            )
            ListItem(
                modifier = Modifier.clickable { onDismiss(); onMessage() },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_send_message)) },
            )
            ListItem(
                modifier = Modifier.clickable { onDismiss(); Actions.copy(context, clipboard, number) },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_copy_number)) },
            )
            ListItem(
                modifier = Modifier.clickable { onDismiss(); Actions.addContact(context, number) },
                leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_add_contact)) },
            )
        }
    }
}
