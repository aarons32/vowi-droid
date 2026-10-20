package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One SIP event subscription (RFC 6665): SUBSCRIBE, refreshed before it
 * expires, each NOTIFY answered and handed to [onNotify], and SUBSCRIBE with
 * Expires: 0 at the end. Voicemail alerts (message-summary, RFC 3842) and
 * conference participants (conference, RFC 4575) use it.
 */
class EventSubscription(
    private val session: ImsSession,
    val event: String,
    private val target: String,
    private val accept: String,
    private val expires: Int = 3600,
    private val onNotify: (SipMessage) -> Unit,
) {
    /** Made on first use, as the session knows its own address only once it is connected. */
    val callId: String by lazy { Rng.bytes(16).toHex() + "@" + session.localHost }
    private val lock = Any()
    private var remoteTag: String? = null
    private var to = "<$target>"
    private var remoteTarget = target
    private var routes: List<String>? = null
    private var refresh: ScheduledFuture<*>? = null

    @Volatile
    var active = false
        private set

    /** Subscribes; true when the network accepted. Blocks for one transaction. */
    fun start(): Boolean = subscribe(expires)

    /** Ends the subscription. */
    fun stop() {
        synchronized(lock) { refresh?.cancel(false) }
        if (active) runCatching { subscribe(0) }
        active = false
    }

    private fun subscribe(seconds: Int): Boolean {
        val cseq = session.nextCseq()
        val (requestUri, toHeader, routeSet) = synchronized(lock) { Triple(remoteTarget, to, routes) }
        // Once in a dialog, its route set replaces the Service-Route.
        val builder = session.newRequest("SUBSCRIBE", requestUri, toHeader, callId, cseq, routes = routeSet)
        builder.header("Contact", session.mmtelContact())
            .header("Event", event)
            .header("Accept", accept)
            .header("Expires", seconds.toString())
            .header("Allow", "NOTIFY")
        val response = try {
            session.transaction(builder.build(), "$callId:$cseq:SUBSCRIBE")
        } catch (error: Exception) {
            CoreLog.w(TAG, "$event SUBSCRIBE got no answer: ${error.message}")
            return false
        }
        if (response.statusCode !in 200..299) {
            CoreLog.i(TAG, "$event SUBSCRIBE refused with SIP ${response.statusCode}")
            if (seconds > 0) active = false
            return false
        }
        synchronized(lock) {
            if (remoteTag == null) {
                remoteTag = tagOf(response.value("To"))
                to = response.value("To")
                SipMessage.firstUri(response.value("Contact")).takeIf { it.isNotEmpty() }?.let { remoteTarget = it }
                routeSetOf(response)?.let { routes = it }
            }
        }
        if (seconds > 0) {
            active = true
            val granted = response.value("Expires").trim().toIntOrNull()?.takeIf { it > 0 } ?: seconds
            scheduleRefresh(granted)
            CoreLog.i(TAG, "subscribed to $event for ${granted}s")
        }
        return true
    }

    private fun scheduleRefresh(granted: Int) {
        // Refresh well before expiry: at 80 %, and never sooner than in 30 seconds.
        val delay = maxOf(30L, granted * 8L / 10L)
        synchronized(lock) {
            refresh?.cancel(false)
            refresh = timers.schedule({ if (active) subscribe(expires) }, delay, TimeUnit.SECONDS)
        }
    }

    /** A NOTIFY for this subscription: answered, then handed on. False when it belongs elsewhere. */
    fun handle(request: SipMessage, respond: (ByteArray) -> Unit): Boolean {
        if (request.method != "NOTIFY" || request.callId != callId) return false
        respond(session.buildResponse(request, 200))
        synchronized(lock) {
            // A NOTIFY may arrive before the 2xx and set up the dialog (RFC 6665 §4.1.2.4).
            if (remoteTag == null) {
                remoteTag = tagOf(request.value("From"))
                to = request.value("From")
                SipMessage.firstUri(request.value("Contact")).takeIf { it.isNotEmpty() }?.let { remoteTarget = it }
                val recordRoutes = request.values("Record-Route")
                if (recordRoutes.isNotEmpty()) routes = SipMessage.splitHeaderValues(recordRoutes)
            }
        }
        if (request.value("Subscription-State").trim().lowercase().startsWith("terminated")) {
            active = false
            synchronized(lock) { refresh?.cancel(false) }
        }
        onNotify(request)
        return true
    }

    companion object {
        private const val TAG = "Subscribe"
        private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "vowi-subscriptions").apply { isDaemon = true } }
    }
}
