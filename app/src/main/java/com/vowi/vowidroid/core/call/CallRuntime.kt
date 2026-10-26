package com.vowi.vowidroid.core.call

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.ims.ImsException
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ims.ImsSupport
import com.vowi.vowidroid.core.ims.tagOf
import com.vowi.vowidroid.core.ims.sipUriFor
import com.vowi.vowidroid.core.ims.EventSubscription
import com.vowi.vowidroid.core.ims.originatingIdentities
import com.vowi.vowidroid.core.ims.mmtelContact
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.sip.SipTrace
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

enum class CallState(val wire: String) { DIALING("dialing"), RINGING("ringing"), EARLY_MEDIA("early_media"), ACTIVE("active"), ENDED("ended"), FAILED("failed") }

/** What the UI sees of one call. */
data class Call(
    val id: String,
    val number: String,
    val outgoing: Boolean,
    val state: CallState,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val sipCode: Int = 0,
    val reason: String = "",
    val codec: String = "",
    val mediaReady: Boolean = false,
    /** We put the call on hold. */
    val held: Boolean = false,
    /** The far end put the call on hold. */
    val remoteHeld: Boolean = false,
    /** Our leg to a conference focus (TS 24.147); [participants] are the people in it. */
    val conference: Boolean = false,
    val participants: List<Participant> = emptyList(),
)

/** A merge's outcome: our leg to the conference, and the calls it took over. */
data class MergeResult(val conference: Call, val joined: List<String>)

class CallNotFoundException : ImsException("call not found")
class CallStateException : ImsException("call is not in the required state")

/**
 * Voice calls over IMS (3GPP TS 24.229 / GSMA IR.92): INVITE with SDP,
 * reliable provisional responses, session timers, ACK/BYE/CANCEL, hold and
 * resume by re-INVITE (TS 24.610), call waiting (TS 24.615) and the RTP
 * media for each call. At most two calls exist at once and only one is live:
 * answering or resuming a call first holds the other.
 */
class CallRuntime(private val session: ImsSession, private val codecs: AudioCodecFactory) {
    interface Listener {
        fun onIncoming(call: Call)
        fun onStateChanged(call: Call)
    }

    @Volatile
    var listener: Listener? = null

    /**
     * Terminal-based call waiting (TS 24.615). When it is off, a second call
     * gets 486 Busy Here instead of ringing, so the network can forward it.
     */
    @Volatile
    var callWaiting: () -> Boolean = { true }

    /** Callers the user blocked: they get 486 Busy Here before anything rings. */
    @Volatile
    var isBlocked: (String) -> Boolean = { false }

    /** Told about each blocked caller, so the call log can show it. */
    @Volatile
    var onBlocked: ((String) -> Unit)? = null

    /** The wait before offering again after a 491 (RFC 3261 §14.1); tests shorten it. */
    internal var glareDelayMs: (ownsCallId: Boolean) -> Long = { owns ->
        val random = ThreadLocalRandom.current()
        if (owns) 2100L + random.nextLong(191) * 10 else random.nextLong(201) * 10
    }

    private inner class Dialog(
        var public: Call,
        val callId: String,
        var target: String,
        val inviteTarget: String,
        val from: String,
        var to: String,
        val branch: String,
        val inviteCseq: Long,
        val invite: SipMessage?,
        val respond: ((ByteArray) -> Unit)?,
        var routes: List<String>,
        val media: RtpMedia,
    ) {
        val responses = LinkedBlockingQueue<SipMessage>()
        var terminated = false
        val pracked = HashSet<String>()
        var sessionExpires = 0
        var sessionTimer: ScheduledFuture<*>? = null

        /** Serializes our own hold and resume offers. */
        val renegotiation = Any()

        /** Our re-INVITE is out; an offer from the far end meanwhile is glare (491). */
        @Volatile
        var offerPending = false

        /** We offered in a 2xx; the ACK brings the answer. */
        @Volatile
        var awaitingAnswer = false
    }

    private val calls = ConcurrentHashMap<String, Dialog>()
    private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "vowi-call-timer").apply { isDaemon = true } }

    val active: List<Call>
        get() {
            val now = System.currentTimeMillis()
            val result = ArrayList<Call>()
            for ((id, dialog) in calls) {
                val ended = dialog.public.endedAt
                if (ended != null && now - ended > TERMINAL_RETENTION_MS) {
                    calls.remove(id)
                    continue
                }
                result.add(dialog.public)
            }
            return result.sortedBy { it.startedAt }
        }

    fun call(id: String): Call? = calls[id]?.public

    /** The media direction in force for a call, from our side. */
    fun mediaDirection(id: String): MediaDirection? = calls[id]?.media?.direction

    fun media(id: String): RtpMedia {
        val dialog = calls[id] ?: throw CallNotFoundException()
        if (dialog.public.state != CallState.ACTIVE || !dialog.media.ready) throw CallStateException()
        return dialog.media
    }

    // --- Outgoing ------------------------------------------------------------

    /**
     * Calls [number]. [hideCallerId] true withholds our number (TS 24.607:
     * Privacy: id and an anonymous From), false presents it, null leaves it
     * to the network's default. A live call goes on hold first (Add call).
     */
    fun dial(number: String, hideCallerId: Boolean? = null): Call {
        val digits = number.trim()
        require(validNumber(digits)) { "invalid dial number" }
        holdOthers(null)
        return startCall(targetUri(digits), digits, conference = false, hideCallerId = hideCallerId)
    }

    /** Sends an INVITE with our media offer to [target] and follows it in the background. */
    private fun startCall(target: String, number: String, conference: Boolean, hideCallerId: Boolean?): Call {
        val digits = number
        val callId = Rng.bytes(18).toHex() + "@" + session.localHost
        val branch = Rng.bytes(12).toHex()
        val cseq = session.nextCseq()
        val (fromIdentity, preferredIdentity) = originatingIdentities()
        val media = RtpMedia(session.tunnel.stack, session.localIp, codecs)
        val from = if (hideCallerId == true) "\"Anonymous\" <sip:anonymous@anonymous.invalid>;tag=${session.fromTag}" else "<$fromIdentity>;tag=${session.fromTag}"
        val to = "<$target>"
        val builder = SipBuilder.request("INVITE", target)
            .header("Via", session.viaHeader(branch))
            .header("Max-Forwards", "70")
        for ((name, value) in session.securityHeaders()) builder.header(name, value)
        for ((name, value) in session.routeHeaders()) builder.header(name, value)
        builder.header("From", from).header("To", to).header("Call-ID", callId).header("CSeq", "$cseq INVITE")
            .header("Contact", dialogContact())
            .header("P-Preferred-Identity", "<$preferredIdentity>")
            .header("P-Preferred-Service", MMTEL_SERVICE_URN)
            .header("Accept-Contact", "*;+g.3gpp.icsi-ref=\"$MMTEL_FEATURE_TAG\"")
        when (hideCallerId) {
            true -> builder.header("Privacy", "id")
            false -> builder.header("Privacy", "none")
            null -> Unit
        }
        if (session.accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", session.accessNetworkInfo)
        builder.header("User-Agent", session.userAgent)
            .header("Allow", ALLOW)
            .header("Supported", "100rel, timer, replaces")
            .header("Session-Expires", "1800;refresher=uac")
            .header("Min-SE", "90")
            .header("Accept", "application/sdp")
            .body("application/sdp", media.offerSdp())
        val dialog = Dialog(
            public = Call(callId, digits, outgoing = true, state = CallState.DIALING, startedAt = System.currentTimeMillis(), conference = conference),
            callId = callId, target = target, inviteTarget = target, from = from, to = to, branch = branch, inviteCseq = cseq,
            invite = null, respond = null, routes = session.evidence.serviceRoute, media = media,
        )
        calls[callId] = dialog
        val key = "$callId:$cseq:INVITE"
        try {
            session.beginTransaction(key, dialog.responses)
            val packet = builder.build()
            CoreLog.d(TAG, "INVITE headers: ${SipTrace.headerLine(packet)}")
            session.send(packet)
        } catch (error: Exception) {
            session.endTransaction(key)
            media.close()
            calls.remove(callId)
            throw ImsException("send SIP INVITE: ${error.message}", error)
        }
        CoreLog.i(TAG, if (conference) "conference call started" else "outgoing call started")
        Thread({ watchOutgoing(dialog, key) }, "vowi-call-invite").apply { isDaemon = true }.start()
        return dialog.public
    }

    private fun watchOutgoing(dialog: Dialog, key: String) {
        val deadline = System.currentTimeMillis() + INVITE_TIMEOUT_MS
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    finish(dialog.callId, if (dialog.terminated) CallState.ENDED else CallState.FAILED, 0, if (dialog.terminated) "" else "SIP INVITE transaction timed out")
                    return
                }
                val response = dialog.responses.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
                val diagnostic = diagnostic(response)
                CoreLog.i(TAG, "call response SIP ${response.statusCode}${if (diagnostic.isNotEmpty()) " ($diagnostic)" else ""}")
                if (response.statusCode < 200) {
                    update(dialog.callId) { it.copy(sipCode = response.statusCode, reason = diagnostic) }
                    updateDialogFromResponse(dialog, response)
                    if (response.body.isNotEmpty()) {
                        val configured = runCatching { dialog.media.applyAnswer(response.body) }.isSuccess
                        if (configured) {
                            refreshMedia(dialog)
                            setState(dialog.callId, CallState.EARLY_MEDIA)
                        }
                    } else if (response.statusCode >= 180) {
                        setState(dialog.callId, CallState.RINGING)
                    }
                    if (reliableProvisional(response)) Thread({ sendPrack(dialog, response) }, "vowi-call-prack").apply { isDaemon = true }.start()
                    continue
                }
                if (response.statusCode in 200..299) {
                    updateDialogFromResponse(dialog, response)
                    val mediaError = acceptFinalAnswer(dialog, response)
                    runCatching { sendAck(dialog) }
                    if (mediaError != null) {
                        finish(dialog.callId, CallState.FAILED, response.statusCode, mediaError.message ?: "media negotiation failed")
                        Thread({ runCatching { sendDialogRequest(dialog, "BYE") } }, "vowi-call-bye").apply { isDaemon = true }.start()
                        return
                    }
                    refreshMedia(dialog)
                    setState(dialog.callId, CallState.ACTIVE)
                    startSessionTimer(dialog, response.value("Session-Expires"))
                } else {
                    if (!dialog.terminated) CoreLog.d(TAG, "SIP ${response.statusCode} headers: ${SipTrace.headerLine(response)}")
                    runCatching { sendFailureAck(dialog, response, dialog.branch, dialog.inviteCseq, dialog.inviteTarget.ifEmpty { dialog.target }) }
                        .exceptionOrNull()?.let { CoreLog.w(TAG, "rejected INVITE ACK failed: ${it.message}") }
                    // CANCEL ends with 487; that is our own hang-up, not a network rejection.
                    finish(dialog.callId, if (dialog.terminated) CallState.ENDED else CallState.FAILED, response.statusCode, diagnostic)
                }
                return
            }
        } finally {
            session.endTransaction(key)
        }
    }

    /**
     * The 2xx to our INVITE. When the answer already came in a reliable
     * provisional response (RFC 3262 §5, the usual IMS flow), the 2xx may
     * carry no SDP, or repeat it; the media from the 183 then stands. Returns
     * why the call cannot go on, or null.
     */
    private fun acceptFinalAnswer(dialog: Dialog, response: SipMessage): Exception? {
        if (response.body.isEmpty()) {
            if (dialog.media.ready) {
                CoreLog.d(TAG, "SIP ${response.statusCode} without SDP; the answer came in a provisional response")
                return null
            }
            return ImsException("the call was answered without SDP and no earlier answer arrived")
        }
        return try {
            dialog.media.applyAnswer(response.body)
            null
        } catch (error: Exception) {
            if (!dialog.media.ready) return error
            CoreLog.w(TAG, "the SDP in SIP ${response.statusCode} was unusable; keeping the media from the provisional answer: ${error.message}")
            null
        }
    }

    // --- Incoming ------------------------------------------------------------

    fun answer(id: String): Call {
        val dialog = calls[id] ?: throw CallNotFoundException()
        val invite = dialog.invite
        val respond = dialog.respond
        if (dialog.public.outgoing || dialog.public.state != CallState.RINGING || invite == null || respond == null) throw CallStateException()
        // One live call at a time: a call in progress goes on hold before this one is answered (TS 24.615).
        holdOthers(id)
        // The caller may have given up while the other call was being held.
        if (dialog.public.state != CallState.RINGING) throw CallStateException()
        // An INVITE without SDP gets our offer in the 200; its ACK carries the answer.
        if (!dialog.media.ready) dialog.awaitingAnswer = true
        respond(session.buildResponse(invite, 200, dialogResponseHeaders(invite, "INVITE"), "application/sdp", dialog.media.answerSdp()))
        setState(id, CallState.ACTIVE)
        startSessionTimer(dialog, invite.value("Session-Expires"))
        if (dialog.media.ready) refreshMedia(dialog)
        return dialog.public
    }

    fun hangup(id: String) {
        val dialog = calls[id] ?: throw CallNotFoundException()
        synchronized(dialog) {
            // A second hang-up while the first BYE still waits for its answer sends nothing more.
            if (dialog.terminated) return
            dialog.terminated = true
        }
        val state = dialog.public.state
        val invite = dialog.invite
        val respond = dialog.respond
        if (!dialog.public.outgoing && state == CallState.RINGING && invite != null && respond != null) {
            respond(session.buildResponse(invite, 486))
            finish(id, CallState.ENDED, 0, "")
            return
        }
        if (dialog.public.outgoing && (state == CallState.DIALING || state == CallState.RINGING || state == CallState.EARLY_MEDIA)) {
            try {
                sendDialogRequest(dialog, "CANCEL")
            } finally {
                // The INVITE's own thread ends the call on the 487; this covers a CANCEL that got no answer.
                finish(id, CallState.ENDED, 0, "")
            }
            return
        }
        // A connected call is over for the user the moment they hang up: its media stops and its state is ENDED
        // before the BYE goes out, since a carrier may take seconds to answer that (T-Mobile: 32 s, then a 480).
        finish(id, CallState.ENDED, 0, "")
        sendDialogRequest(dialog, "BYE")
    }

    fun sendDtmf(id: String, digit: Char) {
        val dialog = calls[id] ?: throw CallNotFoundException()
        if (dialog.public.state != CallState.ACTIVE) throw CallStateException()
        dialog.media.sendDtmf(digit)
    }

    // --- Hold, resume, call waiting ----------------------------------------

    /** Puts a connected call on hold: a re-INVITE whose offer only sends (TS 24.610). */
    fun hold(id: String): Call {
        val dialog = calls[id] ?: throw CallNotFoundException()
        synchronized(dialog.renegotiation) {
            if (dialog.public.state != CallState.ACTIVE) throw CallStateException()
            if (dialog.public.held) return dialog.public
            dialog.media.localHold = true
            try {
                reinvite(dialog)
            } catch (error: Exception) {
                dialog.media.localHold = false
                throw error
            }
            update(id) { it.copy(held = true) }
        }
        CoreLog.i(TAG, "call on hold")
        return dialog.public
    }

    /** Takes a call off hold. A live call goes on hold first, so this also swaps two calls. */
    fun resume(id: String): Call {
        val dialog = calls[id] ?: throw CallNotFoundException()
        if (dialog.public.state != CallState.ACTIVE) throw CallStateException()
        holdOthers(id)
        synchronized(dialog.renegotiation) {
            if (dialog.public.state != CallState.ACTIVE) throw CallStateException()
            if (!dialog.public.held) return dialog.public
            dialog.media.localHold = false
            try {
                reinvite(dialog)
            } catch (error: Exception) {
                if (dialog.public.endedAt == null) dialog.media.localHold = true
                throw error
            }
            update(id) { it.copy(held = false) }
        }
        CoreLog.i(TAG, "call resumed")
        return dialog.public
    }

    private fun holdOthers(except: String?) {
        for (other in calls.values) {
            if (other.callId == except || other.public.state != CallState.ACTIVE || other.public.held) continue
            hold(other.callId)
        }
    }

    // --- Conference calls (3GPP TS 24.147) -----------------------------------

    private val conferenceWatch = ConcurrentHashMap<String, EventSubscription>()

    /** The conference factory URI of 3GPP TS 23.003 §13.10. */
    private fun conferenceFactory(): String = "sip:mmtel@conf-factory.${session.identities.domain}"

    /**
     * Merges two calls into a conference (TS 24.147 §5.3.1.5.3). Both calls go
     * on hold, an INVITE to the conference factory sets up our own leg to the
     * conference focus, and a REFER with Replaces asks the focus to take over
     * each call. The network then ends the old dialogs itself. When one of
     * the two is already a conference, the other call just joins it.
     */
    fun merge(firstId: String, secondId: String): MergeResult {
        val first = calls[firstId] ?: throw CallNotFoundException()
        val second = calls[secondId] ?: throw CallNotFoundException()
        if (first.public.state != CallState.ACTIVE || second.public.state != CallState.ACTIVE) throw CallStateException()
        val running = listOf(first, second).firstOrNull { it.public.conference }
        if (running != null) return join(running, if (running === first) second else first)
        if (!first.public.held) hold(firstId)
        if (!second.public.held) hold(secondId)
        val started = startCall(conferenceFactory(), "", conference = true, hideCallerId = null)
        val deadline = System.currentTimeMillis() + CONFERENCE_SETUP_MS
        while (true) {
            val state = calls[started.id]?.public?.state
            if (state == CallState.ACTIVE) break
            if (state == null || state == CallState.ENDED || state == CallState.FAILED) throw ImsException("the network could not start the conference")
            if (System.currentTimeMillis() > deadline) {
                runCatching { hangup(started.id) }
                throw ImsException("the conference did not start in time")
            }
            Thread.sleep(50)
        }
        val focus = calls[started.id] ?: throw CallNotFoundException()
        CoreLog.i(TAG, "conference focus answered; adding the calls")
        val added = listOf(first, second).filter { party -> runCatching { refer(focus, party) }.getOrDefault(false) }
        if (added.isEmpty()) {
            runCatching { hangup(started.id) }
            throw ImsException("the network would not move the calls into the conference")
        }
        update(started.id) { call ->
            call.copy(participants = added.map { Participant(session.sipUriFor(it.public.number), null, "connecting") })
        }
        watchConference(focus)
        return MergeResult(calls[started.id]?.public ?: focus.public, added.map { it.callId })
    }

    /** Adds [party] to the conference [focus] leads (TS 24.147 §5.3.1.5.3), our leg to it staying live. */
    private fun join(focus: Dialog, party: Dialog): MergeResult {
        if (party.public.conference) throw CallStateException()
        if (!party.public.held) hold(party.callId)
        if (focus.public.held) resume(focus.callId)
        if (!refer(focus, party)) throw ImsException("the network would not move the call into the conference")
        update(focus.callId) { call ->
            call.copy(participants = call.participants + Participant(session.sipUriFor(party.public.number), null, "connecting"))
        }
        return MergeResult(calls[focus.callId]?.public ?: focus.public, listOf(party.callId))
    }

    /** Removes someone from a conference (TS 24.147 §5.3.1.5.4): a REFER whose target says method=BYE. */
    fun removeParticipant(conferenceId: String, participant: String) {
        val focus = calls[conferenceId] ?: throw CallNotFoundException()
        if (!focus.public.conference) throw CallStateException()
        val uri = participant.substringBefore('?')
        val cseq = session.nextCseq()
        val request = dialogRequest(focus, "REFER", cseq, extra = listOf("Refer-To" to "<$uri;method=BYE>", "Referred-By" to "<${originatingIdentities().first}>"))
        val response = session.transaction(request, "${focus.callId}:$cseq:REFER", 10_000)
        if (response.statusCode !in 200..299) throw ImsException("the conference refused to remove the participant (SIP ${response.statusCode})")
    }

    /** Asks the focus to replace our call with [party] by its own (RFC 3891 Replaces in a REFER, RFC 3515). */
    private fun refer(focus: Dialog, party: Dialog): Boolean {
        // In Replaces, to-tag is the other party's own tag and from-tag ours (RFC 3891 §3).
        val theirs = tagOf(party.to) ?: return false
        val ours = tagOf(party.from) ?: session.fromTag
        val replaces = "${party.callId};to-tag=$theirs;from-tag=$ours"
        val referTo = "<${session.sipUriFor(party.public.number)}?Replaces=${uriEscape(replaces)}>"
        val cseq = session.nextCseq()
        val request = dialogRequest(focus, "REFER", cseq, extra = listOf("Refer-To" to referTo, "Referred-By" to "<${originatingIdentities().first}>"))
        val response = session.transaction(request, "${focus.callId}:$cseq:REFER", 10_000)
        CoreLog.i(TAG, "conference REFER answered with SIP ${response.statusCode}")
        return response.statusCode in 200..299
    }

    /** Follows the conference's participant list (RFC 4575). */
    private fun watchConference(focus: Dialog) {
        val watch = EventSubscription(session, "conference", focus.target, CONFERENCE_INFO) { notify -> applyConferenceInfo(focus.callId, notify) }
        conferenceWatch[focus.callId] = watch
        Thread({ runCatching { watch.start() } }, "vowi-conference").apply { isDaemon = true }.start()
    }

    private fun applyConferenceInfo(conferenceId: String, notify: SipMessage) {
        val info = ConferenceInfo.parse(String(notify.body, Charsets.UTF_8)) ?: return
        val own = originatingIdentities().second.filter(Char::isDigit).takeLast(10)
        update(conferenceId) { call ->
            val people = ConferenceInfo.apply(call.participants, info)
                .filter { own.isEmpty() || !it.uri.filter(Char::isDigit).endsWith(own) }
            call.copy(participants = people)
        }
    }

    private fun liveCalls(): Int = calls.values.count { it.public.endedAt == null }

    /** Offers the media's current wishes in a re-INVITE (RFC 3261 §14, RFC 3264 §8); throws when the far end does not take it. */
    private fun reinvite(dialog: Dialog) {
        var retried = false
        while (true) {
            val response = sendReinvite(dialog)
            val code = response?.statusCode ?: 408
            when {
                response != null && code in 200..299 -> {
                    if (response.body.isNotEmpty()) {
                        try {
                            dialog.media.applyAnswer(response.body)
                        } catch (error: Exception) {
                            dropDialog(dialog, 488, error.message ?: "media negotiation failed")
                            throw ImsException("the answer to our offer was unusable: ${error.message}", error)
                        }
                    }
                    refreshMedia(dialog)
                    startSessionTimer(dialog, response.value("Session-Expires"))
                    return
                }
                code == 491 && !retried -> {
                    // Glare: both ends offered at once. Wait, then offer again (RFC 3261 §14.1).
                    retried = true
                    Thread.sleep(glareDelayMs(dialog.public.outgoing))
                    if (dialog.public.endedAt != null) throw CallStateException()
                }
                code == 481 || code == 408 -> {
                    // The far end lost the dialog or never answered: the call is gone (RFC 3261 §12.2.1.2).
                    dropDialog(dialog, code, if (code == 481) "the network no longer knows the call" else "the network did not answer")
                    throw ImsException("the call was lost (SIP $code)")
                }
                else -> {
                    val detail = response?.let { diagnostic(it) }.orEmpty()
                    throw ImsException("the network refused (SIP $code${if (detail.isNotEmpty()) ": $detail" else ""})")
                }
            }
        }
    }

    /** One re-INVITE with a fresh offer. ACKs the final response and returns it, or null when none came. */
    private fun sendReinvite(dialog: Dialog): SipMessage? {
        val cseq = session.nextCseq()
        val branch = Rng.bytes(12).toHex()
        val target = dialog.target
        val request = dialogRequest(dialog, "INVITE", cseq, branch, dialog.media.reofferSdp())
        dialog.offerPending = true
        val response = try {
            session.transaction(request, "${dialog.callId}:$cseq:INVITE", REINVITE_TIMEOUT_MS)
        } catch (error: Exception) {
            CoreLog.w(TAG, "re-INVITE got no final response: ${error.message}")
            null
        } finally {
            dialog.offerPending = false
        }
        if (response == null) return null
        CoreLog.i(TAG, "re-INVITE answered with SIP ${response.statusCode}")
        try {
            if (response.statusCode in 200..299) {
                // A 2xx refreshes the remote target (RFC 3261 §12.2.1.2); its ACK is a new transaction.
                SipMessage.firstUri(response.value("Contact")).takeIf { it.isNotEmpty() }?.let { synchronized(dialog) { dialog.target = it } }
                session.send(dialogRequest(dialog, "ACK", cseq))
            } else {
                sendFailureAck(dialog, response, branch, cseq, target)
            }
        } catch (error: Exception) {
            CoreLog.w(TAG, "re-INVITE ACK failed: ${error.message}")
        }
        return response
    }

    /** Ends a call whose dialog is broken; BYE unless the far end already forgot it. */
    private fun dropDialog(dialog: Dialog, code: Int, reason: String) {
        dialog.terminated = true
        if (code != 481) Thread({ runCatching { sendDialogRequest(dialog, "BYE") } }, "vowi-call-bye").apply { isDaemon = true }.start()
        finish(dialog.callId, CallState.FAILED, code, reason)
    }

    /** Handles a network request for a call; returns false when it is not one. */
    fun handle(request: SipMessage, respond: (ByteArray) -> Unit): Boolean {
        when (request.method) {
            "INVITE" -> {
                val callId = request.callId
                if (callId.isEmpty()) return true
                val existing = calls[callId]
                if (existing != null) {
                    when {
                        existing.public.state == CallState.ACTIVE -> return dialogOffer(request, respond, existing)
                        existing.public.endedAt != null -> respond(session.buildResponse(request, 481))
                        // The INVITE we are ringing for, sent again.
                        existing.invite != null && request.cseq()?.first == existing.inviteCseq -> Unit
                        // RFC 3261 §14.2: a second INVITE before the first has its final response.
                        else -> respond(session.buildResponse(request, 500, listOf("Retry-After" to "${ThreadLocalRandom.current().nextInt(11)}")))
                    }
                    return true
                }
                val live = liveCalls()
                if (live > 0 && !callWaiting()) {
                    CoreLog.i(TAG, "second call refused: call waiting is off")
                    respond(session.buildResponse(request, 486))
                    return true
                }
                if (live >= MAX_CALLS) {
                    CoreLog.i(TAG, "incoming call refused: $live calls already")
                    respond(session.buildResponse(request, 486))
                    return true
                }
                val number = identityNumber(request.value("From"))
                if (number.isNotEmpty() && isBlocked(number)) {
                    CoreLog.i(TAG, "incoming call from a blocked number refused")
                    respond(session.buildResponse(request, 486))
                    onBlocked?.invoke(number)
                    return true
                }
                val target = SipMessage.firstUri(request.value("Contact")).ifEmpty { request.uri }
                val media = try {
                    RtpMedia(session.tunnel.stack, session.localIp, codecs)
                } catch (_: Exception) {
                    respond(session.buildResponse(request, 488))
                    return true
                }
                if (request.body.isNotEmpty()) {
                    try {
                        media.applyOffer(request.body)
                    } catch (error: Exception) {
                        media.close()
                        CoreLog.w(TAG, "incoming call refused: ${error.message}")
                        respond(session.buildResponse(request, 488))
                        return true
                    }
                }
                val dialog = Dialog(
                    public = Call(callId, number, outgoing = false, state = CallState.RINGING, startedAt = System.currentTimeMillis(), codec = media.codecName),
                    callId = callId, target = target, inviteTarget = request.uri, from = request.value("To") + ";tag=${session.fromTag}", to = request.value("From"),
                    branch = "", inviteCseq = request.cseq()?.first ?: 0, invite = request, respond = respond, routes = request.values("Record-Route"), media = media,
                )
                calls[callId] = dialog
                // A 180 that opens a dialog carries a Contact (RFC 3261 §12.1.1); a second call is announced as waiting (TS 24.615).
                val ringing = mutableListOf("Contact" to dialogContact())
                if (live > 0) ringing.add("Alert-Info" to CALL_WAITING_ALERT)
                CoreLog.i(TAG, if (live > 0) "incoming call waiting" else "incoming call ringing")
                respond(session.buildResponse(request, 180, ringing))
                listener?.onIncoming(dialog.public)
                return true
            }
            "NOTIFY" -> {
                for (watch in conferenceWatch.values) if (watch.handle(request, respond)) return true
                val dialog = calls[request.callId] ?: return false
                respond(session.buildResponse(request, 200))
                val event = request.value("Event").trim().lowercase()
                when {
                    // The progress of a REFER (RFC 3515), as a sipfrag.
                    event.startsWith("refer") -> CoreLog.i(TAG, "conference transfer: ${String(request.body, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()}")
                    // Some networks send the participant list in the call's own dialog.
                    event.startsWith("conference") -> applyConferenceInfo(dialog.callId, request)
                }
                return true
            }
            "PRACK" -> {
                respond(session.buildResponse(request, 200))
                return true
            }
            "UPDATE" -> {
                val dialog = calls[request.callId] ?: return false
                if (dialog.public.endedAt != null) {
                    respond(session.buildResponse(request, 481))
                    return true
                }
                return dialogOffer(request, respond, dialog)
            }
            "ACK" -> {
                val dialog = calls[request.callId] ?: return true
                if (request.body.isNotEmpty() && (dialog.awaitingAnswer || !dialog.media.ready)) {
                    dialog.awaitingAnswer = false
                    try {
                        dialog.media.applyAnswer(request.body)
                        refreshMedia(dialog)
                    } catch (error: Exception) {
                        finish(dialog.callId, CallState.FAILED, 0, error.message ?: "media negotiation failed")
                    }
                }
                return true
            }
            "CANCEL", "BYE" -> {
                val dialog = calls[request.callId] ?: return false
                respond(session.buildResponse(request, 200))
                if (request.method == "CANCEL") {
                    val invite = dialog.invite
                    val reply = dialog.respond
                    if (invite != null && reply != null) runCatching { reply(session.buildResponse(invite, 487)) }
                }
                finish(dialog.callId, CallState.ENDED, 0, "")
                return true
            }
            else -> return false
        }
    }

    /** A re-INVITE or UPDATE from the far end: hold, resume, a codec or address change, or a refresh. */
    private fun dialogOffer(request: SipMessage, respond: (ByteArray) -> Unit, dialog: Dialog): Boolean {
        if (request.body.isNotEmpty() && dialog.offerPending) {
            // Glare with our own re-INVITE (RFC 3261 §14.2, RFC 3311 §5.2).
            respond(session.buildResponse(request, 491))
            return true
        }
        var body: ByteArray? = null
        if (request.body.isNotEmpty()) {
            try {
                dialog.media.applyOffer(request.body)
            } catch (_: Exception) {
                respond(session.buildResponse(request, 488))
                return true
            }
            body = dialog.media.answerSdp()
            val remoteHeld = !dialog.media.remoteOfferDirection.receives
            if (remoteHeld != dialog.public.remoteHeld) {
                CoreLog.i(TAG, if (remoteHeld) "the other side put the call on hold" else "the other side took the call off hold")
            }
            update(dialog.callId) { it.copy(remoteHeld = remoteHeld, mediaReady = dialog.media.ready, codec = dialog.media.codecName) }
        } else if (request.method == "INVITE") {
            // An INVITE without SDP: the 200 carries our offer and the ACK the answer (RFC 3264 §8).
            body = dialog.media.reofferSdp()
            dialog.awaitingAnswer = true
        }
        val headers = dialogResponseHeaders(request, request.method)
        respond(if (body != null) session.buildResponse(request, 200, headers, "application/sdp", body) else session.buildResponse(request, 200, headers))
        startSessionTimer(dialog, request.value("Session-Expires"))
        return true
    }

    private fun dialogResponseHeaders(request: SipMessage, method: String): List<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        if (method == "INVITE") headers.add("Contact" to dialogContact())
        val expires = request.value("Session-Expires").trim()
        if (expires.isNotEmpty() && (method == "INVITE" || method == "UPDATE")) {
            headers.add("Supported" to "timer")
            headers.add("Session-Expires" to expires)
        }
        return headers
    }

    // --- Dialog requests ---------------------------------------------------

    private fun sendAck(dialog: Dialog) = session.send(dialogRequest(dialog, "ACK", dialog.inviteCseq))

    /** A non-2xx ACK belongs to the INVITE transaction: same branch, same request URI (RFC 3261 §17.1.1.3). */
    private fun sendFailureAck(dialog: Dialog, response: SipMessage, branch: String, cseq: Long, requestUri: String) {
        val to = response.value("To").ifEmpty { dialog.to }
        val builder = SipBuilder.request("ACK", requestUri)
            .header("Via", session.viaHeader(branch))
            .header("Max-Forwards", "70")
        for ((name, value) in session.securityHeaders()) builder.header(name, value)
        for (route in dialog.routes) builder.header("Route", route)
        builder.header("From", dialog.from).header("To", to).header("Call-ID", dialog.callId).header("CSeq", "$cseq ACK")
        if (session.accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", session.accessNetworkInfo)
        builder.header("User-Agent", session.userAgent)
        session.send(builder.build())
    }

    private fun sendPrack(dialog: Dialog, response: SipMessage) {
        val rseq = response.value("RSeq").trim()
        val inviteCseq = response.value("CSeq").trim()
        if (rseq.isEmpty() || inviteCseq.isEmpty()) return
        val key = "$rseq|$inviteCseq"
        synchronized(dialog) {
            if (!dialog.pracked.add(key) || dialog.public.endedAt != null) return
        }
        val cseq = session.nextCseq()
        val builder = SipBuilder.request("PRACK", dialog.target)
            .header("Via", session.viaHeader())
            .header("Max-Forwards", "70")
        for ((name, value) in session.securityHeaders()) builder.header(name, value)
        for (route in dialog.routes) builder.header("Route", route)
        builder.header("From", dialog.from).header("To", dialog.to).header("Call-ID", dialog.callId).header("CSeq", "$cseq PRACK").header("RAck", "$rseq $inviteCseq")
        if (session.accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", session.accessNetworkInfo)
        builder.header("User-Agent", session.userAgent)
        try {
            val result = session.transaction(builder.build(), "${dialog.callId}:$cseq:PRACK", 10_000)
            if (result.statusCode !in 200..299) finish(dialog.callId, CallState.FAILED, result.statusCode, "reliable provisional response could not be acknowledged")
        } catch (error: Exception) {
            finish(dialog.callId, CallState.FAILED, 0, error.message ?: "PRACK failed")
        }
    }

    private fun sendDialogRequest(dialog: Dialog, method: String) {
        val cseq = if (method == "BYE" || method == "UPDATE") session.nextCseq() else dialog.inviteCseq
        val request = dialogRequest(dialog, method, cseq)
        if (method == "ACK") {
            session.send(request)
            return
        }
        val response = session.transaction(request, "${dialog.callId}:$cseq:$method", 10_000)
        if (response.statusCode !in 200..299) throw ImsException("SIP $method rejected with ${response.statusCode}")
    }

    private fun dialogRequest(
        dialog: Dialog,
        method: String,
        cseq: Long,
        branch: String = if (method == "CANCEL") dialog.branch else Rng.bytes(12).toHex(),
        sdp: ByteArray? = null,
        extra: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val target = if (method == "CANCEL" && dialog.inviteTarget.isNotEmpty()) dialog.inviteTarget else dialog.target
        val builder = SipBuilder.request(method, target)
            .header("Via", session.viaHeader(branch))
            .header("Max-Forwards", "70")
        for ((name, value) in session.securityHeaders()) builder.header(name, value)
        for (route in dialog.routes) builder.header("Route", route)
        builder.header("From", dialog.from).header("To", dialog.to.ifEmpty { "<${dialog.target}>" }).header("Call-ID", dialog.callId).header("CSeq", "$cseq $method")
            .header("Supported", "100rel, timer").header("User-Agent", session.userAgent)
        if (method != "CANCEL" && session.accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", session.accessNetworkInfo)
        if (method == "UPDATE" || method == "INVITE" || method == "REFER") {
            builder.header("Contact", dialogContact())
            if (dialog.sessionExpires > 0) builder.header("Session-Expires", "${dialog.sessionExpires};refresher=uac")
        }
        if (method == "INVITE") builder.header("Allow", ALLOW).header("Accept", "application/sdp")
        for ((name, value) in extra) builder.header(name, value)
        if (sdp != null) builder.body("application/sdp", sdp)
        return builder.build()
    }

    private fun updateDialogFromResponse(dialog: Dialog, response: SipMessage) {
        synchronized(dialog) {
            dialog.to = response.value("To")
            SipMessage.firstUri(response.value("Contact")).takeIf { it.isNotEmpty() }?.let { dialog.target = it }
            val recordRoutes = response.values("Record-Route")
            if (recordRoutes.isNotEmpty()) dialog.routes = SipMessage.splitHeaderValues(recordRoutes).reversed()
        }
    }

    private fun startSessionTimer(dialog: Dialog, header: String) {
        val seconds = header.trim().substringBefore(';').trim().toIntOrNull() ?: return
        if (seconds < 90 || seconds > 86400) return
        synchronized(dialog) {
            dialog.sessionTimer?.cancel(false)
            dialog.sessionExpires = seconds
            val interval = seconds * 1000L / 2
            dialog.sessionTimer = timers.scheduleAtFixedRate({
                try {
                    sendDialogRequest(dialog, "UPDATE")
                } catch (error: Exception) {
                    finish(dialog.callId, CallState.FAILED, 0, "SIP session refresh failed")
                }
            }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    private fun dialogContact(): String = session.mmtelContact()

    private fun targetUri(number: String): String {
        val profile = session.profile
        val domain = session.identities.domain
        if (profile.imsDialUriScheme == "sip") {
            var target = "sip:$number@$domain"
            if (profile.imsUserEqPhone) target += ";user=phone"
            return target
        }
        return if (number.startsWith("+")) "tel:$number" else "tel:$number;phone-context=$domain"
    }

    private fun originatingIdentities(): Pair<String, String> = session.originatingIdentities()

    // --- State ---------------------------------------------------------------

    private fun update(id: String, change: (Call) -> Call) {
        val dialog = calls[id] ?: return
        val updated: Call
        synchronized(dialog) {
            updated = change(dialog.public)
            dialog.public = updated
        }
        listener?.onStateChanged(updated)
    }

    private fun setState(id: String, state: CallState) = update(id) { current ->
        current.copy(
            state = state,
            answeredAt = if (state == CallState.ACTIVE && current.answeredAt == null) System.currentTimeMillis() else current.answeredAt,
            endedAt = if (state != CallState.ENDED && state != CallState.FAILED) null else current.endedAt,
        )
    }

    private fun refreshMedia(dialog: Dialog) = update(dialog.callId) { it.copy(mediaReady = dialog.media.ready, codec = dialog.media.codecName) }

    private fun finish(id: String, state: CallState, code: Int, reason: String) {
        val dialog = calls[id] ?: return
        synchronized(dialog) {
            if (dialog.public.endedAt != null) return
            dialog.sessionTimer?.cancel(false)
            dialog.sessionTimer = null
        }
        update(id) { it.copy(state = state, sipCode = if (code != 0) code else it.sipCode, reason = reason.ifEmpty { it.reason }, endedAt = System.currentTimeMillis()) }
        dialog.media.close()
        conferenceWatch.remove(id)?.let { watch -> Thread({ watch.stop() }, "vowi-conference-end").apply { isDaemon = true }.start() }
        CoreLog.i(TAG, "call ${state.wire}${if (code != 0) " (SIP $code)" else ""}${if (reason.isNotEmpty()) ": $reason" else ""}")
    }

    fun close() {
        for (dialog in calls.values) {
            dialog.sessionTimer?.cancel(false)
            dialog.media.close()
        }
        timers.shutdownNow()
    }

    private fun reliableProvisional(response: SipMessage): Boolean =
        response.value("RSeq").isNotBlank() && response.value("Require").lowercase().split(',').any { it.trim() == "100rel" }

    private fun diagnostic(response: SipMessage): String {
        val parts = ArrayList<String>()
        response.reason.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        for (name in listOf("Reason", "Warning")) for (value in response.values(name)) value.trim().takeIf { it.isNotEmpty() }?.let { parts.add("$name: $it") }
        val joined = parts.joinToString("; ").split(Regex("\\s+")).joinToString(" ")
        return if (joined.length > 256) joined.substring(0, 256) + "..." else joined
    }

    companion object {
        private const val TAG = "Call"
        private const val TERMINAL_RETENTION_MS = 30_000L
        private const val INVITE_TIMEOUT_MS = 120_000L

        /** Timer B (64 × T1): how long a re-INVITE may wait for its final response. */
        private const val REINVITE_TIMEOUT_MS = 32_000L

        /** One call in progress, plus one waiting or on hold. */
        const val MAX_CALLS = 2

        private const val CONFERENCE_SETUP_MS = 32_000L
        const val CONFERENCE_INFO = "application/conference-info+xml"

        /** Escapes a value for a URI header, e.g. the Replaces in a Refer-To (RFC 3261 §19.1.1). */
        fun uriEscape(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val character = byte.toInt().toChar()
                if (character.isLetterOrDigit() && character.code < 128 || character in "-_.!~*'()") append(character)
                else append('%').append("%02X".format(byte.toInt() and 0xff))
            }
        }
        const val CALL_WAITING_ALERT = "<urn:alert:service:call-waiting>"
        private const val ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, MESSAGE, PRACK, UPDATE, INFO"
        const val MMTEL_SERVICE_URN = com.vowi.vowidroid.core.ims.MMTEL_SERVICE_URN
        const val MMTEL_FEATURE_TAG = com.vowi.vowidroid.core.ims.MMTEL_FEATURE_TAG

        fun validNumber(value: String): Boolean {
            if (value.length < 2 || value.length > 32) return false
            return value.withIndex().all { (index, character) -> character in '0'..'9' || (index == 0 && character == '+') || character == '*' || character == '#' }
        }

        fun identityNumber(value: String): String {
            var uri = value.trim()
            val start = uri.indexOf('<')
            if (start >= 0) {
                val end = uri.indexOf('>', start)
                if (end > start) uri = uri.substring(start + 1, end)
            }
            uri = uri.removePrefix("sip:").removePrefix("tel:")
            val at = uri.indexOf('@')
            if (at >= 0) uri = uri.substring(0, at)
            return uri.substringBefore(';').trim()
        }
    }
}
