package com.vowi.vowidroid.telecom

import com.vowi.vowidroid.AppLanguage
import com.vowi.vowidroid.ui.components.Speech
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.vowi.vowidroid.ui.MainActivity
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.vowi.vowidroid.ui.calls.DialpadGrid
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.vowi.vowidroid.data.QuickResponses
import com.vowi.vowidroid.ui.components.rememberContactPhoto
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.ui.components.Avatar
import com.vowi.vowidroid.ui.theme.CallGreen
import com.vowi.vowidroid.ui.theme.CallRed
import com.vowi.vowidroid.ui.theme.VowiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The full-screen call screen, shown over the lock screen like the stock in-call UI. */
class InCallActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private var proximity: PowerManager.WakeLock? = null
    private var answerAfterPermission = false

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (answerAfterPermission) {
            answerAfterPermission = false
            CallManager.answer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        proximity = getSystemService(PowerManager::class.java)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "VowiDroid:proximity")
        handle(intent)
        setContent { VowiTheme { InCallScreen(onAnswer = ::answer, onAddCall = ::addCall) } }
        // Back does nothing while a call rings, as on the stock incoming-call screen.
        val ignoreBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = Unit
        }
        onBackPressedDispatcher.addCallback(this, ignoreBack)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.state.collect { state ->
                    ignoreBack.isEnabled = state?.phase == CallManager.Phase.INCOMING
                    if (state == null) finish() else updateProximity(state)
                }
            }
        }
    }

    /** The headset or keyboard mute key mutes the call, as on the stock in-call screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MUTE && CallManager.state.value?.phase == CallManager.Phase.ACTIVE) {
            CallManager.toggleMute()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        proximity?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) answer()
    }

    private fun answer() {
        if (CallManager.hasMicrophone(this)) {
            CallManager.answer()
        } else {
            answerAfterPermission = true
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Add call: the dialer, where any number dialled becomes a second call
     * while this one waits on hold. Over the lock screen, the user unlocks
     * first, as with the stock in-call screen.
     */
    private fun addCall() {
        val open = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_DIAL)
                    .setData(Uri.fromParts("tel", "", null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open()
                },
            )
        } else {
            open()
        }
    }

    /** Turns the screen off at the ear during a connected earpiece call. */
    private fun updateProximity(state: CallManager.CallState) {
        val lock = proximity ?: return
        val earpiece = state.route == null || state.route.type == AudioRoute.Type.EARPIECE
        val wanted = state.phase == CallManager.Phase.ACTIVE && !state.speaker && earpiece
        if (wanted && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
        if (!wanted && lock.isHeld) lock.release()
    }

    companion object {
        const val EXTRA_ANSWER = "com.vowi.vowidroid.ANSWER"

        fun intent(context: Context, answer: Boolean = false): Intent =
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ANSWER, answer)
    }
}

@Composable
private fun InCallScreen(onAnswer: () -> Unit, onAddCall: () -> Unit) {
    val state by CallManager.state.collectAsStateWithLifecycle()
    val call = state ?: return
    // A Surface, not a plain background: it also sets the text color for the scheme, light or dark.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        InCallContent(call, onAnswer, onAddCall)
    }
}

@Composable
private fun InCallContent(call: CallManager.CallState, onAnswer: () -> Unit, onAddCall: () -> Unit) {
    var keypad by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var routeSheet by remember { mutableStateOf(false) }
    var manageSheet by remember { mutableStateOf(false) }
    // Key feedback in the call's own audio, as the stock in-call keypad gives it.
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tones?.release() } }
    val showKeypad = keypad && call.phase == CallManager.Phase.ACTIVE
    var replySheet by remember { mutableStateOf(false) }
    val photo = rememberContactPhoto(call.number)
    val lineName = CallManager.lineLabel(call.deviceId)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // "via <line>" only when there is more than one line, as AOSP shows the calling account.
                if (lineName != null) stringResource(R.string.incall_via, lineName) else stringResource(R.string.incall_wifi_call),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            call.other?.let {
                Spacer(Modifier.height(16.dp))
                OtherCallCard(it, onAnswer)
            }
            Spacer(Modifier.height(if (call.other != null) 16.dp else 24.dp))
            // A second call or the keypad takes room on screen, so the photo shrinks or goes.
            val avatarSize = if (call.other != null) 72.dp else 112.dp
            if (!showKeypad) {
                if (call.conference) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(avatarSize)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(avatarSize / 2))
                        }
                    }
                } else {
                    Avatar(call.name, call.number, size = avatarSize, photo = photo)
                }
                Spacer(Modifier.height(20.dp))
            }
            val title = when {
                call.conference -> stringResource(R.string.incall_conference)
                else -> call.name ?: Contacts.format(call.number).ifEmpty { stringResource(R.string.call_unknown_caller) }
            }
            // A bare number is read digit by digit.
            val spokenTitle = if (!call.conference && call.name == null && call.number.isNotEmpty()) Speech.number(title) else title
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = spokenTitle
                },
            )
            if (call.conference) {
                Text(
                    call.members.joinToString(", ") { it.name ?: Contacts.format(it.number ?: it.uri) }
                        .ifEmpty { pluralStringResource(R.plurals.incall_conference_people, call.members.size, call.members.size) },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (call.name != null) {
                val number = Contacts.format(call.number)
                Text(number, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = Speech.number(number) })
            }
            Spacer(Modifier.height(8.dp))
            // Status changes (ringing, connected, on hold) are read out as they happen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                CallStatus(call)
                // Wideband audio is HD voice, which AOSP marks with an HD icon.
                if (call.phase == CallManager.Phase.ACTIVE && call.hd) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            stringResource(R.string.incall_hd),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            call.postDialWait?.let { tones ->
                Spacer(Modifier.height(16.dp))
                // A wait (;) in the dialled number: send the next tones only when the user says so.
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.incall_send_tones, tones), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { CallManager.continuePostDial(false) }) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = { CallManager.continuePostDial(true) }) { Text(stringResource(R.string.incall_send)) }
                        }
                    }
                }
            }
            if (showKeypad && typed.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(typed, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
            call.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (call.phase == CallManager.Phase.INCOMING) {
                // Decline with a text, as the stock incoming-call screen offers.
                TextButton(onClick = { replySheet = true }) {
                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.incall_decline_message))
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    BigCallButton(Icons.Filled.CallEnd, CallRed, stringResource(R.string.incall_decline)) { CallManager.reject() }
                    BigCallButton(Icons.Filled.Call, CallGreen, stringResource(R.string.incall_answer), onAnswer)
                }
            } else {
                val live = call.phase != CallManager.Phase.ENDED
                if (showKeypad) {
                    DialpadGrid(onKey = { digit, tone ->
                        tones?.startTone(tone, 150)
                        typed += digit
                        CallManager.sendDtmf(digit[0])
                    })
                    TextButton(onClick = { keypad = false }) { Text(stringResource(R.string.incall_hide_keypad)) }
                    Spacer(Modifier.height(8.dp))
                } else {
                    // Six buttons in two rows, as AOSP's in-call button grid lays them out.
                    val connected = live && call.phase == CallManager.Phase.ACTIVE && !call.merging
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        Cell {
                            ToggleControl(Icons.Filled.MicOff, stringResource(R.string.incall_mute), call.muted, live) {
                                CallManager.toggleMute()
                            }
                        }
                        Cell {
                            ToggleControl(
                                Icons.Filled.Dialpad,
                                stringResource(R.string.incall_keypad),
                                false,
                                connected && !call.held,
                            ) { keypad = true }
                        }
                        Cell { AudioControl(call, live) { routeSheet = true } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        Cell {
                            if (call.canMerge || call.merging) {
                                ActionControl(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.incall_merge), call.canMerge) { CallManager.merge() }
                            } else {
                                ActionControl(Icons.Filled.AddIcCall, stringResource(R.string.incall_add_call), call.canAddCall, onAddCall)
                            }
                        }
                        Cell {
                            ToggleControl(
                                Icons.Filled.Pause,
                                stringResource(R.string.incall_hold),
                                call.held,
                                connected && !call.holdPending,
                            ) { CallManager.toggleHold() }
                        }
                        Cell {
                            if (call.conference) {
                                ActionControl(Icons.Filled.ManageAccounts, stringResource(R.string.incall_manage), connected) { manageSheet = true }
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                if (live) {
                    BigCallButton(Icons.Filled.CallEnd, CallRed, stringResource(R.string.incall_end)) { CallManager.hangup() }
                }
            }
        }
    }
    if (routeSheet) RouteSheet(call) { routeSheet = false }
    if (replySheet) ReplySheet { replySheet = false }
    if (manageSheet && call.conference) ManageConferenceSheet(call) { manageSheet = false }
}

/** One of the grid's equal-width cells, so the two rows line up. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { content() }
}

/** Manage conference: who is in the call, with End for each, as AOSP's ConferenceManagerFragment shows it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageConferenceSheet(call: CallManager.CallState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.incall_conference),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
            if (call.members.isEmpty()) {
                Text(
                    pluralStringResource(R.plurals.incall_conference_people, 0, 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            call.members.forEach { member ->
                val label = member.name ?: Contacts.format(member.number ?: member.uri)
                val photo = rememberContactPhoto(member.number.orEmpty())
                ListItem(
                    leadingContent = { Avatar(member.name, member.number.orEmpty(), photo = photo) },
                    headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val status = when (member.status) {
                            "connected" -> member.name?.let { member.number?.let(Contacts::format) }
                            "on-hold" -> stringResource(R.string.incall_on_hold)
                            "dialing-out", "dialing-in", "alerting", "pending", "connecting" -> stringResource(R.string.incall_member_waiting)
                            else -> null
                        }
                        if (status != null) Text(status)
                    },
                    trailingContent = {
                        IconButton(onClick = { CallManager.removeParticipant(member.uri) }) {
                            Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.incall_remove, label), tint = CallRed)
                        }
                    },
                )
            }
        }
    }
}

/** Telecom's quick responses (editable in Settings) and "Write your own…". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val responses = remember { QuickResponses.all(context) }
    var writing by remember { mutableStateOf(false) }
    if (writing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.incall_reply_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.incall_reply_hint)) }, maxLines = 4) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    CallManager.rejectWithMessage(text.trim())
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.incall_send)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.incall_reply_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            responses.forEach { response ->
                ListItem(
                    modifier = Modifier.clickable {
                        onDismiss()
                        CallManager.rejectWithMessage(response)
                    },
                    headlineContent = { Text(response) },
                )
            }
            ListItem(modifier = Modifier.clickable { writing = true }, headlineContent = { Text(stringResource(R.string.incall_reply_custom)) })
        }
    }
}

/**
 * Speaker on and off, or, when a headset or Bluetooth device is also there,
 * the route in use with a chooser, as the stock in-call screen does.
 */
@Composable
private fun AudioControl(call: CallManager.CallState, enabled: Boolean, onChoose: () -> Unit) {
    val choice = call.routes.any { it.type == AudioRoute.Type.BLUETOOTH || it.type == AudioRoute.Type.WIRED_HEADSET }
    if (!choice) {
        ToggleControl(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.incall_speaker), call.speaker, enabled) {
            CallManager.toggleSpeaker()
        }
        return
    }
    val current = call.route
    ToggleControl(routeIcon(current?.type), routeLabel(current), current?.type == AudioRoute.Type.SPEAKER, enabled, onChoose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(call: CallManager.CallState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.incall_audio_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            call.routes.forEach { route ->
                ListItem(
                    modifier = Modifier.clickable {
                        CallManager.selectRoute(route)
                        onDismiss()
                    },
                    leadingContent = { Icon(routeIcon(route.type), contentDescription = null) },
                    headlineContent = { Text(routeLabel(route)) },
                    trailingContent = {
                        if (route.key == call.route?.key) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

private fun routeIcon(type: AudioRoute.Type?): ImageVector = when (type) {
    AudioRoute.Type.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.Type.WIRED_HEADSET -> Icons.Filled.Headset
    AudioRoute.Type.BLUETOOTH -> Icons.Filled.Bluetooth
    AudioRoute.Type.STREAMING -> Icons.Filled.Cast
    AudioRoute.Type.EARPIECE, null -> Icons.Filled.PhoneInTalk
}

@Composable
private fun routeLabel(route: AudioRoute?): String = route?.name ?: stringResource(
    when (route?.type) {
        AudioRoute.Type.SPEAKER -> R.string.incall_speaker
        AudioRoute.Type.WIRED_HEADSET -> R.string.route_headset
        AudioRoute.Type.BLUETOOTH -> R.string.route_bluetooth
        AudioRoute.Type.STREAMING -> R.string.route_streaming
        AudioRoute.Type.EARPIECE, null -> R.string.route_phone
    },
)

@Composable
private fun CallStatus(call: CallManager.CallState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallManager.Phase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = if (call.merging) stringResource(R.string.incall_merging) else when (call.phase) {
        CallManager.Phase.DIALING -> stringResource(R.string.incall_calling)
        CallManager.Phase.RINGING -> stringResource(R.string.incall_ringing)
        CallManager.Phase.INCOMING -> stringResource(R.string.call_incoming_title)
        CallManager.Phase.CONNECTING -> stringResource(R.string.incall_connecting)
        CallManager.Phase.ACTIVE -> when {
            call.holdPending -> stringResource(if (call.held) R.string.incall_resuming else R.string.incall_holding)
            call.held -> stringResource(R.string.incall_on_hold)
            call.remoteHeld -> stringResource(R.string.incall_remote_hold)
            else -> elapsed(now - (call.connectedAt ?: now))
        }
        CallManager.Phase.ENDED -> stringResource(R.string.incall_ended)
    }
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The second call: a waiting call to answer or decline, or a held call to swap with. */
@Composable
private fun OtherCallCard(other: CallManager.OtherCall, onAnswer: () -> Unit) {
    val label = when {
        other.conference -> stringResource(R.string.incall_conference)
        else -> other.name ?: Contacts.format(other.number).ifEmpty { stringResource(R.string.call_unknown_caller) }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(if (other.waiting) R.string.incall_waiting else R.string.incall_on_hold),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (other.waiting) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.incall_waiting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { CallManager.reject() }) { Text(stringResource(R.string.incall_decline), color = CallRed) }
                    TextButton(onClick = { CallManager.endAndAnswer() }) { Text(stringResource(R.string.incall_end_and_answer)) }
                    Button(
                        onClick = onAnswer,
                        colors = ButtonDefaults.buttonColors(containerColor = CallGreen, contentColor = Color.White),
                    ) { Text(stringResource(R.string.incall_answer)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { CallManager.swap() }, enabled = !other.busy) {
                        Icon(Icons.Filled.SwapCalls, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.incall_swap))
                    }
                }
            }
        }
    }
}

private fun elapsed(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Composable
private fun BigCallButton(icon: ImageVector, color: Color, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = color,
            contentColor = Color.White,
            modifier = Modifier.size(72.dp),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** A one-shot in-call action (Add call, Merge, Manage) styled like the toggles beside it. */
@Composable
private fun ActionControl(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(64.dp),
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun ToggleControl(icon: ImageVector, label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconToggleButtonColors(),
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics {})
    }
}
