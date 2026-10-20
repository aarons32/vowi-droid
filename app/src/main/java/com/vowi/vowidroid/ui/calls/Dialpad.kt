package com.vowi.vowidroid.ui.calls

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.data.Contact
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.PhoneNumbers
import com.vowi.vowidroid.data.SmartDial
import com.vowi.vowidroid.data.VowiRepository
import com.vowi.vowidroid.ui.LineChips
import com.vowi.vowidroid.ui.LinePreferences
import com.vowi.vowidroid.ui.components.Actions
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.components.rememberPhoto
import com.vowi.vowidroid.ui.theme.CallGreen
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Digit, letters and DTMF tone of each key, in the stock dialpad's layout.
private val keys = listOf(
    listOf(Triple("1", "", ToneGenerator.TONE_DTMF_1), Triple("2", "ABC", ToneGenerator.TONE_DTMF_2), Triple("3", "DEF", ToneGenerator.TONE_DTMF_3)),
    listOf(Triple("4", "GHI", ToneGenerator.TONE_DTMF_4), Triple("5", "JKL", ToneGenerator.TONE_DTMF_5), Triple("6", "MNO", ToneGenerator.TONE_DTMF_6)),
    listOf(Triple("7", "PQRS", ToneGenerator.TONE_DTMF_7), Triple("8", "TUV", ToneGenerator.TONE_DTMF_8), Triple("9", "WXYZ", ToneGenerator.TONE_DTMF_9)),
    listOf(Triple("*", "", ToneGenerator.TONE_DTMF_S), Triple("0", "+", ToneGenerator.TONE_DTMF_0), Triple("#", "", ToneGenerator.TONE_DTMF_P)),
)

/** A contact number or a recent number matching what is typed. */
private class Suggestion(val name: String?, val number: String, val label: String?, val photoUri: String?)

/**
 * The dialpad, after AOSP's: the number can be edited at a cursor and pasted
 * into (letters become digits), contacts and recent numbers are suggested by
 * keypad letters, 2-second pauses and waits can be added, a long-press on 1
 * calls voicemail, and the call button with nothing typed recalls the last
 * number dialled. Key tones follow the system "Dial pad tones" setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadSheet(
    initialNumber: String,
    lines: List<Line>,
    onDismiss: () -> Unit,
    onCall: (Line?, String) -> Unit,
    onMessage: (Line?, String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val state = rememberTextFieldState(initialNumber, TextRange(initialNumber.length))
    val number = state.text.toString()
    var line by remember(lines) { mutableStateOf(LinePreferences.pick(lines, LinePreferences.defaultLine(context))) }
    val tones = remember { if (keyTonesOn(context)) runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 80) }.getOrNull() else null }
    DisposableEffect(Unit) { onDispose { tones?.release() } }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { Contacts.all(context) } }
    val calls by CallLogStore.calls.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var menu by remember { mutableStateOf(false) }

    fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    fun backspace() {
        state.edit {
            if (!selection.collapsed) {
                val start = selection.min
                replace(start, selection.max, "")
                selection = TextRange(start)
            } else if (selection.start > 0) {
                val start = selection.start - 1
                replace(start, start + 1, "")
                selection = TextRange(start)
            }
        }
    }

    fun press() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    val voicemailCaller = rememberVoicemailCaller()

    fun callVoicemail() {
        val chosen = line ?: return
        onDismiss()
        voicemailCaller(chosen)
    }

    val query = number.filter(Char::isDigit)
    val suggestions = remember(query, contacts, calls) {
        if (query.isEmpty()) emptyList() else {
            val fromContacts = contacts.flatMap { contact ->
                contact.numbers.mapIndexedNotNull { index, value ->
                    if (SmartDial.matchesName(contact.name, query) || SmartDial.matchesNumber(value, query)) {
                        Suggestion(contact.name, value, contact.labels.getOrNull(index), contact.photoUri)
                    } else {
                        null
                    }
                }
            }
            // Recent numbers that are not contacts, as AOSP's smart dial includes the call log.
            val recent = calls.asSequence().map { it.number }.distinct()
                .filter { recentNumber -> fromContacts.none { PhoneNumbers.same(it.number, recentNumber) } && SmartDial.matchesNumber(recentNumber, query) }
                .take(3).map { Suggestion(null, it, null, null) }.toList()
            (fromContacts + recent).distinctBy { it.number.filter(Char::isDigit) }.take(20)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (suggestions.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 168.dp)) {
                    items(suggestions, key = { it.number + (it.name ?: "") }) { suggestion ->
                        val photo = rememberPhoto(suggestion.photoUri)
                        ListItem(
                            modifier = Modifier.clickable { onCall(line, suggestion.number) },
                            leadingContent = { Avatar(suggestion.name, suggestion.number, size = 36.dp, photo = photo) },
                            headlineContent = { Text(suggestion.name ?: Contacts.format(suggestion.number), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(suggestion.label, Contacts.format(suggestion.number).takeIf { suggestion.name != null }).joinToString(" · "), maxLines = 1)
                            },
                        )
                    }
                }
            } else if (number.length >= 2) {
                // AOSP's actions for a number that is not a contact.
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { Actions.createContact(context, number) }) { Text(stringResource(R.string.dialpad_create_contact)) }
                    TextButton(onClick = { Actions.addContact(context, number) }) { Text(stringResource(R.string.dialpad_add_to_contact)) }
                    TextButton(onClick = { onMessage(line, number) }) { Text(stringResource(R.string.dialpad_send_sms)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No soft keyboard, on focus or on a tap: the dialpad types. The field asks the keyboard controller
                // it finds in the composition to show the IME, so it gets one that shows nothing; the long-press
                // toolbar with Copy, Cut, Paste and Select all is the text toolbar, not the keyboard, and stays.
                val noKeyboard = remember {
                    object : SoftwareKeyboardController {
                        override fun show() = Unit
                        override fun hide() = Unit
                    }
                }
                CompositionLocalProvider(LocalSoftwareKeyboardController provides noKeyboard) {
                    BasicTextField(
                        state = state,
                        // Pasted text keeps only what can be dialled.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
                        inputTransformation = InputTransformation.byValue { _, proposed -> SmartDial.dialable(proposed.toString()) },
                        outputTransformation = PhoneFormatting,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle = MaterialTheme.typography.headlineLarge.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                        // The dialpad types, so no blinking caret; a tap still moves where digits go.
                        cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                if (number.isNotEmpty()) {
                    @OptIn(ExperimentalFoundationApi::class)
                    Box(
                        modifier = Modifier.size(48.dp).combinedClickable(
                            onClick = {
                                press()
                                backspace()
                            },
                            onLongClick = { state.clearText() },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.dialpad_delete))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = number.isNotEmpty()) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dialpad_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val cursor = state.selection.min
                        val before = number.getOrNull(cursor - 1)
                        val after = number.getOrNull(cursor)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dialpad_pause)) },
                            enabled = cursor > 0,
                            onClick = {
                                menu = false
                                insert(",")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dialpad_wait)) },
                            // AOSP: never first, and never two waits side by side.
                            enabled = cursor > 0 && before != ';' && after != ';',
                            onClick = {
                                menu = false
                                insert(";")
                            },
                        )
                    }
                }
            }
            LineChips(lines, line, { line = it }, Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Spacer(Modifier.height(4.dp))
            DialpadGrid(
                onKey = { digit, tone ->
                    press()
                    tones?.startTone(tone, 120)
                    insert(digit)
                },
                onLongPress = { digit ->
                    when {
                        digit == "0" -> ({ insert("+") })
                        digit == "1" && (number.isEmpty() || number == "1") -> ({
                            press()
                            state.clearText()
                            callVoicemail()
                        })
                        else -> null
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            FloatingActionButton(
                onClick = {
                    press()
                    if (number.isNotEmpty()) {
                        onCall(line, number)
                    } else {
                        // As in AOSP: the call button on an empty dialpad brings back the last number dialled.
                        calls.firstOrNull { it.outgoing }?.number?.let { last ->
                            state.edit {
                                replace(0, length, last)
                                selection = TextRange(last.length)
                            }
                        }
                    }
                },
                shape = CircleShape,
                containerColor = CallGreen,
                contentColor = Color.White,
                modifier = Modifier.size(64.dp),
            ) { Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call)) }
        }
    }

}

/** The number grouped for the phone's country as it is typed, like AOSP's formatting text watcher. */
private val PhoneFormatting = OutputTransformation {
    val original = asCharSequence().toString()
    if (original.length < 4 || original.any { it == ',' || it == ';' || it == '*' || it == '#' }) return@OutputTransformation
    val formatted = PhoneNumberUtils.formatNumber(original, Locale.getDefault().country.ifEmpty { "US" }) ?: return@OutputTransformation
    // Only add separators; never change what was typed.
    if (formatted.filter { it.isDigit() || it == '+' } != original) return@OutputTransformation
    var typed = 0
    var position = 0
    for (character in formatted) {
        if (typed < original.length && character == original[typed]) {
            typed++
        } else {
            insert(position, character.toString())
        }
        position++
    }
}

/** AOSP plays key tones only when "Dial pad tones" is on and the ringer is not silent or vibrate-only. */
private fun keyTonesOn(context: Context): Boolean {
    val setting = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) }.getOrDefault(1) == 1
    val ringer = context.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    return setting && ringer == AudioManager.RINGER_MODE_NORMAL
}

/** The twelve keys in the stock dialpad's layout; [onKey] gets the digit and its DTMF tone. */
@Composable
internal fun DialpadGrid(
    onKey: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (String) -> (() -> Unit)? = { null },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                row.forEach { (digit, letters, tone) ->
                    DialKey(digit = digit, letters = letters, onPress = { onKey(digit, tone) }, onLongPress = onLongPress(digit))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(digit: String, letters: String, onPress: () -> Unit, onLongPress: (() -> Unit)?) {
    // Read as AOSP's dialpad reads its keys: "2, A B C", "star", "pound".
    val description = when (digit) {
        "*" -> stringResource(R.string.a11y_key_star)
        "#" -> stringResource(R.string.a11y_key_pound)
        else -> digit + if (letters.isNotBlank()) ", " + letters.toList().joinToString(" ") else ""
    }
    val longPressLabel = when {
        onLongPress == null -> null
        digit == "1" -> stringResource(R.string.dialpad_voicemail)
        digit == "0" -> stringResource(R.string.a11y_key_plus)
        else -> null
    }
    Column(
        modifier = Modifier
            .size(width = 96.dp, height = 76.dp)
            .clip(RoundedCornerShape(28.dp))
            .combinedClickable(onClick = onPress, onLongClick = onLongPress, onLongClickLabel = longPressLabel)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onPress(); true }
                if (onLongPress != null) onLongClick(longPressLabel) { onLongPress(); true }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(digit, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface)
        if (digit == "1") {
            // Long-press 1 for voicemail, marked as on the stock dialpad.
            Icon(
                Icons.Filled.Voicemail,
                contentDescription = stringResource(R.string.dialpad_voicemail),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text(
                letters.ifEmpty { " " },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
