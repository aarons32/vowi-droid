package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.CoreLog
import kotlin.concurrent.thread

/** A voicemail box as the network reports it. */
data class VoicemailState(
    val waiting: Boolean,
    /** New voice messages, or null when the network only says some are waiting. */
    val newMessages: Int?,
    val oldMessages: Int = 0,
    /** The voicemail account, e.g. sip:+18056377243@…, when the network names it. */
    val account: String = "",
)

/** The message-summary body (RFC 3842, application/simple-message-summary). */
object MessageSummary {
    private val counts = Regex("""(\d+)\s*/\s*(\d+)""")

    fun parse(body: String): VoicemailState? {
        var waiting: Boolean? = null
        var account = ""
        var newVoice = -1
        var oldVoice = 0
        for (raw in body.lines()) {
            val line = raw.trim()
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (name) {
                "messages-waiting" -> waiting = value.equals("yes", ignoreCase = true)
                "message-account" -> account = value
                "voice-message" -> counts.find(value)?.let {
                    newVoice = it.groupValues[1].toInt()
                    oldVoice = it.groupValues[2].toInt()
                }
            }
        }
        val isWaiting = waiting ?: return null
        // The voice counts decide when present; otherwise "yes" means some, number unknown.
        return if (newVoice >= 0) {
            VoicemailState(newVoice > 0, newVoice, oldVoice, account)
        } else {
            VoicemailState(isWaiting, if (isWaiting) null else 0, 0, account)
        }
    }
}

/**
 * Voicemail alerts over IMS (3GPP TS 24.606): a message-summary subscription
 * to the user's own identity, and NOTIFYs from the voicemail system, also
 * unsolicited ones that some networks send without a subscription.
 */
class VoicemailWatcher(private val session: ImsSession, private val onChange: (VoicemailState) -> Unit) {
    private val subscription = EventSubscription(
        session = session,
        event = EVENT,
        target = session.identities.publicIdentity,
        accept = CONTENT_TYPE,
        onNotify = ::deliver,
    )

    /** Subscribes in the background; refusal is fine, as some networks only send unsolicited NOTIFYs. */
    fun start() {
        thread(name = "vowi-mwi", isDaemon = true) {
            if (!runCatching { subscription.start() }.getOrDefault(false)) CoreLog.i(TAG, "no message-summary subscription; waiting for the network's own alerts")
        }
    }

    fun stop() = runCatching { subscription.stop() }

    fun handle(request: SipMessage, respond: (ByteArray) -> Unit): Boolean {
        if (subscription.handle(request, respond)) return true
        if (request.method != "NOTIFY" || !request.value("Event").trim().lowercase().startsWith(EVENT)) return false
        respond(session.buildResponse(request, 200))
        deliver(request)
        return true
    }

    private fun deliver(notify: SipMessage) {
        val state = MessageSummary.parse(String(notify.body, Charsets.UTF_8)) ?: return
        CoreLog.i(TAG, "voicemail: ${state.newMessages} new, ${state.oldMessages} old")
        onChange(state)
    }

    companion object {
        private const val TAG = "Voicemail"
        const val EVENT = "message-summary"
        const val CONTENT_TYPE = "application/simple-message-summary"
    }
}
