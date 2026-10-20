package com.vowi.vowidroid.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.PermissionChecker
import com.vowi.vowidroid.R
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.core.WifiCallingEngine
import com.vowi.vowidroid.core.call.Call as CoreCall
import com.vowi.vowidroid.core.call.CallState as CoreCallState
import com.vowi.vowidroid.core.call.ConferenceInfo
import com.vowi.vowidroid.core.call.MergeResult
import com.vowi.vowidroid.core.call.Participant
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.data.CallRecord
import com.vowi.vowidroid.data.CallSettings
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.VowiRepository
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs VowiDroid's calls: Telecom owns audio focus and routing, the core owns
 * SIP and RTP, and this object keeps the two and the UI in step. There can
 * be two calls: one in progress plus one waiting or on hold. Only one call is
 * live (connected and not on hold), and the microphone and speaker follow it.
 * Everything runs on the main thread except the core's requests.
 */
object CallManager {
    private const val TAG = "VowiCall"
    const val EXTRA_DEVICE = "com.vowi.vowidroid.DEVICE"
    const val EXTRA_CALL_ID = "com.vowi.vowidroid.CALL_ID"
    const val EXTRA_NUMBER = "com.vowi.vowidroid.NUMBER"
    private const val MAX_CALLS = 2

    /** How long the network has to end the calls a conference took over before VowiDroid ends them itself. */
    private const val MERGE_CLEANUP_MS = 20_000L

    enum class Phase { DIALING, RINGING, INCOMING, CONNECTING, ACTIVE, ENDED }

    /** The second call on the screen: one waiting to be answered, or one on hold. */
    data class OtherCall(val number: String, val name: String?, val waiting: Boolean, val busy: Boolean, val conference: Boolean = false)

    /** Someone in a conference call. */
    data class Member(val uri: String, val number: String?, val name: String?, val status: String)

    data class CallState(
        val deviceId: String,
        val number: String,
        val name: String?,
        val outgoing: Boolean,
        val phase: Phase,
        val connectedAt: Long? = null,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val message: String? = null,
        /** We put this call on hold. */
        val held: Boolean = false,
        /** The other side put this call on hold. */
        val remoteHeld: Boolean = false,
        /** A hold or resume request is on its way to the network. */
        val holdPending: Boolean = false,
        val other: OtherCall? = null,
        /** Where call audio can go, and where it goes now. */
        val routes: List<AudioRoute> = emptyList(),
        val route: AudioRoute? = null,
        /** Tones after a wait (;) in the dialled number, waiting for the user's go-ahead. */
        val postDialWait: String? = null,
        /** The codec in use, e.g. AMR-WB for HD voice. */
        val codec: String = "",
        val hd: Boolean = false,
        /** This call is a conference; [members] are the other people in it. */
        val conference: Boolean = false,
        val members: List<Member> = emptyList(),
        /** The calls are being merged into a conference. */
        val merging: Boolean = false,
        /** Add call and Merge, as the stock in-call screen offers them. */
        val canAddCall: Boolean = false,
        val canMerge: Boolean = false,
    )

    /** One call: its Telecom connection and what the screen shows of it. */
    private class Tracked(val connection: VowiConnection, var phase: Phase) {
        val startedAt: Instant = Instant.now()
        var name: String? = null
        var connectedAt: Instant? = null
        var connectedAtMillis: Long? = null
        var rejected = false
        var held = false
        var remoteHeld = false
        var holdPending = false
        var codec = ""
        var message: String? = null
        var watch: Job? = null
        /** What followed the first pause or wait in the dialled number. */
        var postDial = ""
        var postDialWait: String? = null
        var postDialGate: CompletableDeferred<Boolean>? = null
        /** Our leg to a conference focus; [participants] are the people in it. */
        var conference = false
        var participants: List<Participant> = emptyList()
        /** A merge is moving this call into a conference, so its own dialog may end meanwhile. */
        var merging = false
        var dialogEnded = false
        val deviceId: String get() = connection.deviceId
        val number: String get() = connection.number
        val live: Boolean get() = phase == Phase.ACTIVE && !held && !holdPending
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<CallState?>(null)
    val state: StateFlow<CallState?> = mutableState.asStateFlow()

    /** Calls in progress, at most [MAX_CALLS]; the first is the one on screen. */
    private val calls = ArrayList<Tracked>()

    /** What the screen shows while no call is tracked: a call being placed, or one that just ended. */
    private var placeholder: CallState? = null
    private var audio: CallAudioEngine? = null
    private var audioOwner: Tracked? = null
    private var audioCodec = ""
    private var muted = false
    private var speaker = false
    private var routes: List<AudioRoute> = emptyList()
    private var route: AudioRoute? = null
    private var waitingTone: ToneGenerator? = null
    private var pendingPostDial = ""
    private var pendingHideCallerId: Boolean? = null

    /** Contact names of conference members, by number; null while unknown. */
    private val memberNames = HashMap<String, String?>()

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** PermissionChecker also honors app-ops, which some ROMs use to silence the microphone. */
    fun hasMicrophone(context: Context): Boolean =
        PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED

    // --- Placing and receiving calls ------------------------------------------

    /**
     * Places a call on [deviceId]; the in-call screen opens once Telecom
     * accepts it. With one connected call this is Add call: that call goes on
     * hold, and the new call uses its line, as Telecom keeps a self-managed
     * app's calls on one account. A caller ID prefix (#31# or *67 to hide,
     * *31# or *82 to show) applies to this call only.
     */
    fun dial(deviceId: String, label: String, dialed: String): String? {
        val (prefixHide, rest) = callerIdPrefix(dialed)
        // "555 1234,,567;89": the call goes to the part before the first pause or wait.
        val number = rest.substringBefore(',').substringBefore(';')
        if (number.isEmpty()) return null
        if (EmergencyNumbers.isEmergency(app, number)) return app.getString(R.string.emergency_use_phone)
        val adding = calls.singleOrNull()?.takeIf { canAddCall() }
        if (placing() || (calls.isNotEmpty() && adding == null)) return app.getString(R.string.call_in_progress)
        val lineId = adding?.deviceId ?: deviceId
        val telecom = app.getSystemService(TelecomManager::class.java) ?: return "Telecom is unavailable"
        PhoneAccounts.ensure(app, lineId, if (adding != null) lineId else label)
        val handle = PhoneAccounts.handle(app, lineId)
        if (!telecom.isOutgoingCallPermitted(handle)) return app.getString(R.string.call_not_permitted)
        if (adding == null) muted = false
        pendingPostDial = rest.substring(number.length)
        pendingHideCallerId = prefixHide ?: when (CallSettings.callerId(app, lineId)) {
            CallSettings.CallerId.NETWORK -> null
            CallSettings.CallerId.SHOW -> false
            CallSettings.CallerId.HIDE -> true
        }
        placeholder = CallState(lineId, number, null, outgoing = true, phase = Phase.DIALING)
        render()
        lookupName(number)
        try {
            telecom.placeCall(
                Uri.fromParts("tel", number, null),
                Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) },
            )
        } catch (e: SecurityException) {
            placeholder = null
            render()
            return "VowiDroid may not place calls: ${e.message}"
        }
        openInCallScreen()
        return null
    }

    fun createOutgoing(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val deviceId = account?.id ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        if (calls.size >= MAX_CALLS) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        val number = request.address?.schemeSpecificPart.orEmpty()
        if (EmergencyNumbers.isEmergency(app, number)) {
            placeholder = null
            render()
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, app.getString(R.string.emergency_use_phone)))
        }
        val call = VowiConnection(deviceId, number, outgoing = true)
        call.describe(app, lineLabel(deviceId))
        call.setDialing()
        val tracked = Tracked(call, Phase.DIALING)
        tracked.name = placeholder?.takeIf { it.number == number }?.name
        tracked.postDial = pendingPostDial
        pendingPostDial = ""
        val hideCallerId = pendingHideCallerId
        pendingHideCallerId = null
        placeholder = null
        // Add call: the connected call goes on hold before the new one goes out.
        val live = calls.filter { it.phase == Phase.ACTIVE && !it.held }
        live.forEach { it.holdPending = true }
        calls.add(0, tracked)
        syncAudio()
        render()
        scope.launch {
            holdAll(live)?.let { error ->
                finish(tracked, DisconnectCause(DisconnectCause.ERROR), null)
                calls.firstOrNull()?.message = app.getString(R.string.incall_hold_failed, error)
                render()
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { engineFor(deviceId).dial(number, hideCallerId).id } }
            result.onSuccess { id ->
                call.callId = id
                if (tracked !in calls) {
                    // Ended from the screen while the INVITE was still going out: cancel it now.
                    scope.launch { apiHangup(deviceId, id) }
                } else {
                    watch(tracked)
                }
            }.onFailure { finish(tracked, DisconnectCause(DisconnectCause.ERROR), friendly(it)) }
        }
        return call
    }

    fun outgoingFailed() {
        placeholder = placeholder?.copy(phase = Phase.ENDED, message = "Android refused the call")
        render()
        clearSoon()
    }

    /** The core received an INVITE: hand it to Telecom, which asks VowiDroid to show its UI. */
    fun onIncoming(deviceId: String, callId: String, caller: String) {
        scope.launch {
            if (!canTakeIncoming()) {
                declineBusy(deviceId, callId, caller)
                return@launch
            }
            val telecom = app.getSystemService(TelecomManager::class.java) ?: return@launch
            PhoneAccounts.ensure(app, deviceId)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", caller, null))
                putString(EXTRA_DEVICE, deviceId)
                putString(EXTRA_CALL_ID, callId)
                putString(EXTRA_NUMBER, caller)
            }
            try {
                telecom.addNewIncomingCall(PhoneAccounts.handle(app, deviceId), extras)
            } catch (e: SecurityException) {
                Log.w(TAG, "Telecom refused the incoming call", e)
                apiHangup(deviceId, callId)
            }
        }
    }

    fun createIncoming(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras ?: Bundle()
        val deviceId = extras.getString(EXTRA_DEVICE) ?: account?.id.orEmpty()
        val number = extras.getString(EXTRA_NUMBER).orEmpty()
        val callId = extras.getString(EXTRA_CALL_ID)
        if (!canTakeIncoming()) {
            // Another call got in first.
            if (callId != null) scope.launch { declineBusy(deviceId, callId, number) }
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }
        val call = VowiConnection(deviceId, number, outgoing = false)
        call.callId = callId
        call.describe(app, lineLabel(deviceId))
        call.setRinging()
        if (calls.isEmpty()) {
            placeholder = null
            muted = false
        }
        val tracked = Tracked(call, Phase.INCOMING)
        // A call that arrives during another one waits behind it.
        calls.add(tracked)
        render()
        lookupName(number)
        watch(tracked)
        if (calls.size > 1) startWaitingTone()
        return call
    }

    fun incomingFailed(request: ConnectionRequest?) {
        val extras = request?.extras ?: return
        val deviceId = extras.getString(EXTRA_DEVICE) ?: return
        val callId = extras.getString(EXTRA_CALL_ID) ?: return
        val number = extras.getString(EXTRA_NUMBER).orEmpty()
        scope.launch {
            apiHangup(deviceId, callId)
            record(deviceId, number, outgoing = false, CallRecord.Outcome.MISSED, Instant.now(), 0)
            CallNotifications.showMissed(app, deviceId, number, null)
        }
    }

    fun showIncomingCallUi(call: VowiConnection) {
        val tracked = find(call) ?: return
        if (tracked.phase != Phase.INCOMING) return
        CallNotifications.showIncoming(app, tracked.deviceId, tracked.number, tracked.name, waiting = calls.size > 1, line = lineLabel(tracked.deviceId))
    }

    /** The line's name when there is more than one line, as AOSP names the calling account only then. */
    fun lineLabel(deviceId: String): String? =
        VowiRepository.lines.value.takeIf { it.size > 1 }?.firstOrNull { it.id == deviceId }?.name

    /**
     * Why a call ended, in words, after AOSP's disconnect messages; null when
     * it simply ended.
     */
    private fun endReason(code: Int, failed: Boolean, connected: Boolean): String? = when {
        code == 486 || code == 600 -> app.getString(R.string.call_end_busy)
        connected && !failed -> null
        code == 480 -> app.getString(R.string.call_end_unavailable)
        code == 404 || code == 604 || code == 410 -> app.getString(R.string.call_end_not_found)
        code == 408 -> app.getString(R.string.call_end_no_answer)
        code == 403 -> app.getString(R.string.call_end_not_allowed)
        code == 484 -> app.getString(R.string.call_end_incomplete)
        code == 603 -> app.getString(R.string.call_end_declined)
        failed -> app.getString(R.string.call_end_network) + if (code > 0) " ($code)" else ""
        else -> null
    }

    /** Declines the ringing call and texts the caller, as "Decline with message" does on the stock phone. */
    fun rejectWithMessage(text: String) {
        val tracked = calls.firstOrNull { it.phase == Phase.INCOMING } ?: return
        val deviceId = tracked.deviceId
        val number = tracked.number
        reject(tracked.connection)
        if (number.isBlank() || text.isBlank()) return
        scope.launch {
            runCatching { VowiRepository.sendSms(deviceId, number, text) }
                .onFailure { Log.w(TAG, "decline message not sent", it) }
        }
    }

    /** A new call rings when the line is free, and waits when every call is connected and there is room. */
    private fun canTakeIncoming(): Boolean =
        !placing() && calls.size < MAX_CALLS && calls.all { it.phase == Phase.ACTIVE }

    private fun placing(): Boolean = placeholder?.phase?.let { it != Phase.ENDED } == true

    private suspend fun declineBusy(deviceId: String, callId: String, caller: String) {
        apiHangup(deviceId, callId)
        record(deviceId, caller, outgoing = false, CallRecord.Outcome.MISSED, Instant.now(), 0)
        val name = withContext(Dispatchers.IO) { Contacts.nameFor(app, caller) }
        CallNotifications.showMissed(app, deviceId, caller, name)
    }

    // --- Actions ----------------------------------------------------------------

    /** Answers the ringing call (or [call]). A live call goes on hold first. */
    fun answer(call: VowiConnection? = null) {
        val tracked = (if (call != null) find(call) else calls.firstOrNull { it.phase == Phase.INCOMING }) ?: return
        if (tracked.phase != Phase.INCOMING) return
        if (!hasMicrophone(app)) {
            // The in-call screen asks for the microphone, then answers.
            openInCallScreen(answer = true)
            return
        }
        val live = calls.filter { it !== tracked && it.phase == Phase.ACTIVE && !it.held }
        startAnswer(tracked)
        live.forEach { it.holdPending = true }
        syncAudio()
        render()
        scope.launch {
            val holdError = holdAll(live)
            if (holdError != null) {
                // The call in progress stays as it was, and the new one keeps ringing behind it.
                if (tracked in calls) {
                    tracked.phase = Phase.INCOMING
                    calls.remove(tracked)
                    calls.add(tracked)
                    calls.firstOrNull()?.message = app.getString(R.string.incall_hold_failed, holdError)
                    startWaitingTone()
                    CallNotifications.showIncoming(app, tracked.deviceId, tracked.number, tracked.name, waiting = true, line = lineLabel(tracked.deviceId))
                }
                syncAudio()
                render()
                return@launch
            }
            completeAnswer(tracked)
        }
    }

    /** Ends the call in progress and answers the waiting one. */
    fun endAndAnswer() {
        val waiting = calls.firstOrNull { it.phase == Phase.INCOMING } ?: return
        if (!hasMicrophone(app)) {
            openInCallScreen(answer = true)
            return
        }
        val current = calls.filter { it !== waiting }
        startAnswer(waiting)
        render()
        // The call in progress ends at once; its BYE need not be answered before the waiting one is taken.
        for (other in current) {
            val id = other.connection.callId
            finish(other, DisconnectCause(DisconnectCause.LOCAL), null)
            if (id != null) scope.launch { apiHangup(other.deviceId, id) }
        }
        scope.launch { completeAnswer(waiting) }
    }

    private fun startAnswer(tracked: Tracked) {
        stopWaitingTone()
        CallNotifications.cancelIncoming(app)
        tracked.phase = Phase.CONNECTING
        tracked.message = null
        // The answered call takes the screen.
        bringToFront(tracked)
    }

    private suspend fun completeAnswer(tracked: Tracked) {
        // The caller may have given up meanwhile.
        if (tracked !in calls) return
        val id = tracked.connection.callId ?: return
        val result = withContext(Dispatchers.IO) { runCatching { engineFor(tracked.deviceId).answer(id) } }
        result.onSuccess { connect(tracked) }
            .onFailure { finish(tracked, DisconnectCause(DisconnectCause.ERROR), friendly(it)) }
    }

    /** Declines the ringing call (or [call]). */
    fun reject(call: VowiConnection? = null) {
        val tracked = (if (call != null) find(call) else calls.firstOrNull { it.phase == Phase.INCOMING }) ?: return
        tracked.rejected = true
        val id = tracked.connection.callId
        finish(tracked, DisconnectCause(DisconnectCause.REJECTED), null)
        if (id != null) scope.launch { apiHangup(tracked.deviceId, id) }
    }

    /** Ends [call], or the call on screen. A ringing call is declined. */
    fun hangup(call: VowiConnection? = null) {
        val tracked = if (call != null) find(call) ?: return else calls.firstOrNull()
        if (tracked == null) {
            // Telecom never created the connection; just close the screen.
            placeholder = placeholder?.copy(phase = Phase.ENDED)
            render()
            clearSoon()
            return
        }
        if (tracked.phase == Phase.INCOMING) {
            reject(tracked.connection)
            return
        }
        // The call is over for the user now: Telecom, the screen and the notification see it at once. The BYE
        // follows on its own; a carrier may take seconds to answer it (T-Mobile took 32 s and then said 480).
        val id = tracked.connection.callId
        finish(tracked, DisconnectCause(DisconnectCause.LOCAL), null)
        if (id != null) scope.launch { apiHangup(tracked.deviceId, id) }
    }

    /** Puts [call], or the call on screen, on hold: the Hold button, or Android making room for another app's call. */
    fun hold(call: VowiConnection? = null) {
        val tracked = (if (call != null) find(call) else calls.firstOrNull()) ?: return
        if (tracked.held) {
            // Already on hold: confirm it to Telecom, which may ask again.
            tracked.connection.setOnHold()
            return
        }
        if (tracked.phase != Phase.ACTIVE || tracked.holdPending) return
        tracked.holdPending = true
        tracked.message = null
        syncAudio()
        render()
        scope.launch {
            holdAll(listOf(tracked))?.let { error -> tracked.message = app.getString(R.string.incall_hold_failed, error) }
            syncAudio()
            render()
        }
    }

    /** Takes [call], or the call on screen, off hold. A live call goes on hold first (swap). */
    fun resume(call: VowiConnection? = null) {
        val tracked = (if (call != null) find(call) else calls.firstOrNull()) ?: return
        if (tracked.phase != Phase.ACTIVE || !tracked.held || tracked.holdPending) return
        val live = calls.filter { it !== tracked && it.phase == Phase.ACTIVE && !it.held }
        tracked.holdPending = true
        tracked.message = null
        live.forEach { it.holdPending = true }
        bringToFront(tracked)
        syncAudio()
        render()
        scope.launch {
            val holdError = holdAll(live)
            if (holdError != null) {
                tracked.holdPending = false
                live.firstOrNull()?.let(::bringToFront)
                calls.firstOrNull()?.message = app.getString(R.string.incall_hold_failed, holdError)
                syncAudio()
                render()
                return@launch
            }
            val id = tracked.connection.callId
            val result = withContext(Dispatchers.IO) { runCatching { engineFor(tracked.deviceId).resume(id ?: error("no call")) } }
            tracked.holdPending = false
            result.onSuccess { if (tracked in calls) apply(tracked, it) }
                .onFailure { tracked.message = "Couldn't resume the call: ${friendly(it)}" }
            syncAudio()
            render()
        }
    }

    /** The Hold button: holds the call on screen, or takes it off hold. */
    fun toggleHold() {
        val primary = calls.firstOrNull() ?: return
        if (primary.held) resume(primary.connection) else hold(primary.connection)
    }

    /** Brings the call on hold back and holds the one on screen. */
    fun swap() {
        val other = calls.getOrNull(1) ?: return
        if (other.phase == Phase.ACTIVE && other.held) resume(other.connection)
    }

    // --- Add call and conference calls -------------------------------------------

    /** One connected call, free to go on hold for another: the stock in-call screen's Add call. */
    private fun canAddCall(): Boolean {
        val only = calls.singleOrNull() ?: return false
        return !placing() && only.phase == Phase.ACTIVE && !only.merging && !only.holdPending && only.connection.callId != null
    }

    /** Two connected calls on one line, at most one of them a conference already. */
    private fun canMerge(): Boolean {
        if (calls.size != 2) return false
        val (first, second) = calls
        return first.deviceId == second.deviceId &&
            calls.all { it.phase == Phase.ACTIVE && !it.merging && !it.holdPending && it.connection.callId != null } &&
            !(first.conference && second.conference)
    }

    /**
     * Merges the two calls into a conference (3GPP TS 24.147), or adds the
     * other call to the conference in progress. The conference carries on in
     * the place of the call on screen; the other call ends quietly.
     */
    fun merge() {
        if (!canMerge()) return
        val parties = calls.toList()
        val firstId = parties[0].connection.callId ?: return
        val secondId = parties[1].connection.callId ?: return
        parties.forEach {
            it.merging = true
            it.dialogEnded = false
            it.message = null
        }
        render()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engineFor(parties[0].deviceId).merge(firstId, secondId) } }
            parties.forEach { it.merging = false }
            result.onSuccess { completeMerge(parties, it) }
                .onFailure { error ->
                    // A call whose dialog ended meanwhile is over; the other stays as it was.
                    parties.filter { it.dialogEnded && it in calls }.forEach { finish(it, DisconnectCause(DisconnectCause.REMOTE), null) }
                    calls.firstOrNull()?.message = app.getString(R.string.incall_merge_failed, friendly(error))
                    syncAudio()
                    render()
                }
        }
    }

    private fun completeMerge(parties: List<Tracked>, result: MergeResult) {
        val joined = parties.filter { it.connection.callId in result.joined }
        val host = parties.firstOrNull { it.conference && it in calls }
            ?: joined.firstOrNull { it === calls.firstOrNull() }
            ?: joined.firstOrNull()
            ?: return
        val oldDialogs = joined.mapNotNull { it.connection.callId }
        if (!host.conference) {
            // Our leg to the conference focus carries on in the host call's place.
            if (host !in calls) calls.add(0, host)
            host.connection.callId = result.conference.id
            host.conference = true
            host.held = result.conference.held
            host.remoteHeld = false
            host.holdPending = false
            host.dialogEnded = false
            if (result.conference.codec.isNotEmpty()) host.codec = result.conference.codec
            host.connection.setHighDefinition(hdCodec(host.codec))
            if (!host.held) host.connection.setActive()
        }
        host.participants = result.conference.participants
        bringToFront(host)
        for (party in parties) {
            if (party === host) continue
            when {
                // Taken over by the conference: it ends quietly and the conference carries on.
                party in joined -> finish(party, DisconnectCause(DisconnectCause.OTHER), null)
                party.dialogEnded -> finish(party, DisconnectCause(DisconnectCause.REMOTE), null)
            }
        }
        if (parties.any { it !== host && it !in joined && it in calls }) {
            host.message = app.getString(R.string.incall_merge_partial)
        }
        // The microphone and speaker move to the conference's own media.
        restartAudio()
        watch(host)
        render()
        // The network ends the calls the conference took over; any it leaves up, VowiDroid ends.
        val deviceId = host.deviceId
        scope.launch {
            delay(MERGE_CLEANUP_MS)
            for (id in oldDialogs) {
                if (id == host.connection.callId) continue
                val core = withContext(Dispatchers.IO) { VowiCore.engine(deviceId)?.call(id) }
                if (core != null && core.endedAt == null) apiHangup(deviceId, id)
            }
        }
    }

    /** Removes someone from the conference on screen, as Manage conference's End does. */
    fun removeParticipant(uri: String) {
        val host = calls.firstOrNull { it.conference } ?: return
        val id = host.connection.callId ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { engineFor(host.deviceId).removeParticipant(id, uri) } }
            result.onFailure {
                host.message = app.getString(R.string.incall_remove_failed, friendly(it))
                render()
            }
        }
    }

    /** "#31#" or "*67" before a number hides our caller ID for that call; "*31#" or "*82" shows it. */
    private fun callerIdPrefix(dialed: String): Pair<Boolean?, String> = when {
        dialed.startsWith("#31#") && dialed.length > 4 -> true to dialed.substring(4)
        dialed.startsWith("*31#") && dialed.length > 4 -> false to dialed.substring(4)
        dialed.startsWith("*67") && dialed.length > 5 && dialed[3].isDigit() -> true to dialed.substring(3)
        dialed.startsWith("*82") && dialed.length > 5 && dialed[3].isDigit() -> false to dialed.substring(3)
        else -> null to dialed
    }

    /** Holds each call in turn; returns why one could not be held, or null. */
    private suspend fun holdAll(targets: List<Tracked>): String? {
        var failure: String? = null
        for (target in targets) {
            if (failure != null) break
            val id = target.connection.callId ?: continue
            val result = withContext(Dispatchers.IO) { runCatching { engineFor(target.deviceId).hold(id) } }
            target.holdPending = false
            result.onSuccess { if (target in calls) apply(target, it) }
                .onFailure { failure = friendly(it) }
        }
        targets.forEach { it.holdPending = false }
        return failure
    }

    fun toggleMute() {
        if (calls.isEmpty()) return
        muted = !muted
        audio?.muted = muted
        render()
    }

    /** The Speaker button: speaker on, or back to Bluetooth, a headset or the earpiece. */
    fun toggleSpeaker() {
        val connection = calls.firstOrNull()?.connection ?: return
        val target = if (route?.type == AudioRoute.Type.SPEAKER) {
            routes.firstOrNull { it.type == AudioRoute.Type.BLUETOOTH }
                ?: routes.firstOrNull { it.type == AudioRoute.Type.WIRED_HEADSET }
                ?: routes.firstOrNull { it.type == AudioRoute.Type.EARPIECE }
        } else {
            routes.firstOrNull { it.type == AudioRoute.Type.SPEAKER }
        }
        if (target != null) {
            AudioRoutes.select(app, connection, target)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Telecom has not reported the routes yet.
            @Suppress("DEPRECATION")
            connection.setAudioRoute(if (speaker) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        }
    }

    /** Moves call audio to [target], e.g. a Bluetooth headset chosen on the in-call screen. */
    fun selectRoute(target: AudioRoute) {
        val connection = calls.firstOrNull()?.connection ?: return
        AudioRoutes.select(app, connection, target)
    }

    /** Telecom changed the routes or the route in use. */
    fun onRoutesChanged(call: VowiConnection) {
        if (find(call) == null) return
        val (available, current) = AudioRoutes.of(call)
        routes = available
        route = current
        speaker = current?.type == AudioRoute.Type.SPEAKER
        render()
    }

    /** Sends a keypad digit to the live call (or [connection]) as RFC 4733 DTMF. */
    fun sendDtmf(digit: Char, connection: VowiConnection? = null) {
        val tracked = (if (connection != null) find(connection) else calls.firstOrNull { it.live }) ?: return
        if (!tracked.live) return
        val id = tracked.connection.callId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { engineFor(tracked.deviceId).sendDtmf(id, digit) }.onFailure { Log.w(TAG, "DTMF failed", it) }
        }
    }

    /** Clears an ended call's screen state. */
    fun dismiss() {
        if (calls.isEmpty() && placeholder?.phase == Phase.ENDED) {
            placeholder = null
            render()
        }
    }

    // --- Following the core ---------------------------------------------------

    /** Follows the core's view of the call until it ends. */
    private fun watch(tracked: Tracked) {
        tracked.watch?.cancel()
        tracked.watch = scope.launch {
            val id = tracked.connection.callId ?: return@launch
            val lineId = tracked.deviceId
            VowiCore.callChanges
                .onSubscription { VowiCore.engine(lineId)?.call(id)?.let { emit(lineId to it) } }
                .filter { (line, core) -> line == lineId && core.id == id }
                .collect { (_, core) -> if (!apply(tracked, core)) cancel() }
        }
    }

    /** Applies one core call state; returns false once the call has ended. */
    private fun apply(tracked: Tracked, core: CoreCall): Boolean {
        if (tracked !in calls) return false
        if (core.id != tracked.connection.callId) return false
        if (tracked.conference && core.participants != tracked.participants) {
            tracked.participants = core.participants
            core.participants.mapNotNull { ConferenceInfo.number(it.uri) }.forEach(::lookupMemberName)
        }
        when (core.state) {
            CoreCallState.DIALING -> if (tracked.connection.outgoing) tracked.phase = Phase.DIALING
            CoreCallState.RINGING, CoreCallState.EARLY_MEDIA -> if (tracked.connection.outgoing) tracked.phase = Phase.RINGING
            CoreCallState.ACTIVE -> {
                if (tracked.connectedAt == null) connect(tracked)
                tracked.remoteHeld = core.remoteHeld
                if (core.codec.isNotEmpty() && core.codec != tracked.codec) {
                    tracked.codec = core.codec
                    tracked.connection.setHighDefinition(hdCodec(core.codec))
                }
                if (core.held != tracked.held) {
                    tracked.held = core.held
                    if (core.held) tracked.connection.setOnHold() else tracked.connection.setActive()
                }
            }
            CoreCallState.ENDED, CoreCallState.FAILED -> {
                if (tracked.merging) {
                    // The conference took this call over; the merge decides what comes next.
                    tracked.dialogEnded = true
                    return false
                }
                val code = core.sipCode
                val cause = when {
                    code == 486 || code == 600 -> DisconnectCause(DisconnectCause.BUSY)
                    core.state == CoreCallState.FAILED && tracked.connectedAt == null -> DisconnectCause(DisconnectCause.ERROR)
                    else -> DisconnectCause(DisconnectCause.REMOTE)
                }
                finish(tracked, cause, endReason(code, core.state == CoreCallState.FAILED, tracked.connectedAt != null))
                return false
            }
        }
        syncAudio()
        render()
        return true
    }

    private fun connect(tracked: Tracked) {
        if (tracked !in calls || tracked.connectedAt != null) return
        tracked.connectedAt = Instant.now()
        tracked.connectedAtMillis = System.currentTimeMillis()
        tracked.phase = Phase.ACTIVE
        tracked.connection.setActive()
        if (!hasMicrophone(app)) tracked.message = "Microphone permission is off; the other side cannot hear you"
        syncAudio()
        render()
        if (tracked.postDial.isNotEmpty()) runPostDial(tracked)
    }

    /**
     * Sends what followed the dialled number: digits as tones, a pause (,)
     * as two seconds' wait, a wait (;) as a question to the user first, as
     * AOSP's in-call screen asks "Send the following tones?".
     */
    private fun runPostDial(tracked: Tracked) {
        val sequence = tracked.postDial
        tracked.postDial = ""
        scope.launch {
            var index = 0
            while (index < sequence.length && tracked in calls) {
                when (val character = sequence[index]) {
                    ',' -> delay(2000)
                    ';' -> {
                        val next = sequence.substring(index + 1).takeWhile { it != ',' && it != ';' }
                        if (next.isNotEmpty()) {
                            val gate = CompletableDeferred<Boolean>()
                            tracked.postDialGate = gate
                            tracked.postDialWait = next
                            render()
                            val send = gate.await()
                            tracked.postDialGate = null
                            tracked.postDialWait = null
                            render()
                            if (!send) return@launch
                        }
                    }
                    else -> {
                        sendDtmf(character, tracked.connection)
                        delay(300)
                    }
                }
                index++
            }
        }
    }

    /** The answer to "Send these tones?". */
    fun continuePostDial(send: Boolean) {
        calls.firstOrNull { it.postDialGate != null }?.postDialGate?.complete(send)
    }

    /** Starts the microphone and speaker again, on whichever call is live now. */
    private fun restartAudio() {
        audio?.stop()
        audio = null
        audioOwner = null
        audioCodec = ""
        syncAudio()
    }

    /** Keeps the microphone and speaker on the live call, and restarts them when its codec changes. */
    private fun syncAudio() {
        val live = calls.firstOrNull { it.live }
        val codecChanged = live != null && live === audioOwner && audioCodec.isNotEmpty() && live.codec.isNotEmpty() && live.codec != audioCodec
        if (live === audioOwner && !codecChanged) {
            if (live != null && audioCodec.isEmpty()) audioCodec = live.codec
            return
        }
        audio?.stop()
        audio = null
        audioOwner = null
        audioCodec = ""
        if (live == null) return
        val id = live.connection.callId ?: return
        audioOwner = live
        audioCodec = live.codec
        audio = runCatching { CallAudioEngine.start(engineFor(live.deviceId).media(id)) }
            .onFailure { Log.w(TAG, "call media unavailable", it) }
            .getOrNull()
        if (audio == null) {
            live.message = "Call audio could not start"
        } else {
            audio?.muted = muted
        }
    }

    private fun finish(tracked: Tracked, cause: DisconnectCause, message: String?) {
        if (!calls.remove(tracked)) return
        tracked.watch?.cancel()
        tracked.postDialGate?.complete(false)
        tracked.watch = null
        if (audioOwner === tracked) {
            audio?.stop()
            audio = null
            audioOwner = null
            audioCodec = ""
        }
        val answered = tracked.connectedAt
        val duration = answered?.let { Duration.between(it, Instant.now()).seconds } ?: 0
        val outcome = when {
            answered != null -> CallRecord.Outcome.ANSWERED
            tracked.connection.outgoing && cause.code == DisconnectCause.LOCAL -> CallRecord.Outcome.CANCELLED
            tracked.connection.outgoing -> CallRecord.Outcome.FAILED
            tracked.rejected -> CallRecord.Outcome.REJECTED
            else -> CallRecord.Outcome.MISSED
        }
        // Telecom types the system call-history entry from the cause: an unanswered incoming call is MISSED.
        tracked.connection.setDisconnected(if (outcome == CallRecord.Outcome.MISSED) DisconnectCause(DisconnectCause.MISSED) else cause)
        tracked.connection.destroy()
        record(tracked.deviceId, tracked.number, tracked.connection.outgoing, outcome, tracked.startedAt, duration)
        if (outcome == CallRecord.Outcome.MISSED) CallNotifications.showMissed(app, tracked.deviceId, tracked.number, tracked.name)
        val ringing = calls.firstOrNull { it.phase == Phase.INCOMING }
        if (ringing == null) {
            stopWaitingTone()
            CallNotifications.cancelIncoming(app)
        } else if (calls.size == 1) {
            // The call it was waiting behind is gone: ring like any incoming call.
            stopWaitingTone()
            CallNotifications.showIncoming(app, ringing.deviceId, ringing.number, ringing.name, waiting = false, line = lineLabel(ringing.deviceId))
        }
        if (calls.isEmpty()) {
            // AOSP's timing: a call you hung up closes almost at once, one the other side ended or that
            // failed stays a moment to show why, and a declined or missed call closes straight away.
            val shownFor = when {
                outcome == CallRecord.Outcome.MISSED -> 0L
                cause.code == DisconnectCause.LOCAL -> 400L
                cause.code == DisconnectCause.REJECTED -> 0L
                else -> 2000L
            }
            placeholder = if (shownFor == 0L) {
                null
            } else {
                CallState(
                    tracked.deviceId, tracked.number, tracked.name, tracked.connection.outgoing, Phase.ENDED,
                    connectedAt = tracked.connectedAtMillis, muted = muted, speaker = speaker, message = message ?: tracked.message,
                )
            }
            render()
            if (shownFor > 0) clearSoon(shownFor)
        } else {
            syncAudio()
            render()
        }
    }

    // --- Screen state ---------------------------------------------------------

    private fun render() {
        val primary = calls.firstOrNull()
        if (primary == null) {
            mutableState.value = placeholder
            return
        }
        val other = calls.getOrNull(1)
        val members = if (primary.conference) {
            primary.participants.map { participant ->
                val number = ConferenceInfo.number(participant.uri)
                Member(participant.uri, number, number?.let { memberNames[it] } ?: participant.name, participant.status)
            }
        } else {
            emptyList()
        }
        mutableState.value = CallState(
            deviceId = primary.deviceId,
            number = primary.number,
            name = primary.name,
            outgoing = primary.connection.outgoing,
            phase = primary.phase,
            connectedAt = primary.connectedAtMillis,
            muted = muted,
            speaker = speaker,
            message = primary.message,
            held = primary.held,
            remoteHeld = primary.remoteHeld,
            holdPending = primary.holdPending,
            other = other?.let {
                OtherCall(it.number, it.name, waiting = it.phase == Phase.INCOMING, busy = it.holdPending || it.merging, conference = it.conference)
            },
            routes = routes,
            route = route,
            postDialWait = primary.postDialWait,
            codec = primary.codec,
            hd = hdCodec(primary.codec),
            conference = primary.conference,
            members = members,
            merging = calls.any { it.merging },
            canAddCall = canAddCall(),
            canMerge = canMerge(),
        )
    }

    private fun clearSoon(after: Long = 2500L) {
        scope.launch {
            delay(after)
            if (calls.isEmpty() && placeholder?.phase == Phase.ENDED) {
                placeholder = null
                render()
            }
        }
    }

    private fun find(connection: VowiConnection): Tracked? = calls.firstOrNull { it.connection === connection }

    private fun bringToFront(tracked: Tracked) {
        if (calls.remove(tracked)) calls.add(0, tracked)
    }

    /** The standard call-waiting tone in the call's audio, until the waiting call is answered or gone. */
    private fun startWaitingTone() {
        if (waitingTone != null) return
        waitingTone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }
            .onFailure { Log.w(TAG, "call-waiting tone unavailable", it) }
            .getOrNull()
            ?.also { it.startTone(ToneGenerator.TONE_SUP_CALL_WAITING) }
    }

    private fun stopWaitingTone() {
        waitingTone?.let {
            runCatching { it.stopTone() }
            it.release()
        }
        waitingTone = null
    }

    private fun record(
        deviceId: String,
        number: String,
        outgoing: Boolean,
        outcome: CallRecord.Outcome,
        started: Instant,
        seconds: Long,
    ) {
        scope.launch(Dispatchers.IO) {
            CallLogStore.add(CallRecord(0, deviceId, number, outgoing, outcome, started, seconds))
        }
    }

    private fun lookupMemberName(number: String) {
        if (memberNames.containsKey(number)) return
        memberNames[number] = null
        scope.launch {
            val name = withContext(Dispatchers.IO) { Contacts.nameFor(app, number) } ?: return@launch
            memberNames[number] = name
            render()
        }
    }

    private fun lookupName(number: String) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { Contacts.nameFor(app, number) } ?: return@launch
            calls.filter { it.number == number && it.name == null }.forEach {
                it.name = name
                // Car and watch screens show the contact's name too.
                it.connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
            }
            placeholder?.let { if (it.number == number && it.name == null) placeholder = it.copy(name = name) }
            render()
        }
    }

    /** Wideband codecs: HD voice. */
    private fun hdCodec(codec: String): Boolean = codec.startsWith("AMR-WB", ignoreCase = true) || codec.startsWith("EVS", ignoreCase = true)

    private fun engineFor(deviceId: String): WifiCallingEngine = VowiCore.engine(deviceId) ?: error("The line is not set up")

    /** Tells the core to end the call; it has already ended here, so a carrier that never answers the BYE only gets a log line. */
    private suspend fun apiHangup(deviceId: String, callId: String) {
        withContext(Dispatchers.IO) {
            runCatching { VowiCore.engine(deviceId)?.hangup(callId) }
                .onFailure { Log.w(TAG, "the carrier did not confirm the hang-up: ${it.message}") }
        }
    }

    private fun openInCallScreen(answer: Boolean = false) {
        app.startActivity(
            Intent(app, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(InCallActivity.EXTRA_ANSWER, answer),
        )
    }

    private fun friendly(error: Throwable): String = error.message?.removePrefix("ims: ") ?: "Call failed"
}
