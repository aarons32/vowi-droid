package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.AudioCodecFactory
import com.vowi.vowidroid.core.call.Call
import com.vowi.vowidroid.core.call.CallRuntime
import com.vowi.vowidroid.core.call.MergeResult
import com.vowi.vowidroid.core.call.RtpMedia
import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.ike.EpdgErrors
import com.vowi.vowidroid.core.ike.EpdgRejectedException
import com.vowi.vowidroid.core.ike.EpdgResolver
import com.vowi.vowidroid.core.ike.IkeSession
import com.vowi.vowidroid.core.ike.Tunnel
import com.vowi.vowidroid.core.ike.TunnelConnector
import com.vowi.vowidroid.core.ike.TunnelParams
import com.vowi.vowidroid.core.ims.ImsConfig
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ims.VoicemailWatcher
import com.vowi.vowidroid.core.ims.VoicemailState
import com.vowi.vowidroid.core.ims.UssdRuntime
import com.vowi.vowidroid.core.ims.UssdReply
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.sms.ReceivedSms
import com.vowi.vowidroid.core.sms.ReceivedSmsStatus
import com.vowi.vowidroid.core.sms.SmsOverIms
import com.vowi.vowidroid.core.sms.SmsSubmitResult
import com.vowi.vowidroid.core.util.CoreLog
import java.io.IOException
import java.net.DatagramSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One line's WiFi Calling state, safe to show in the UI. */
data class LineState(
    val lineId: String,
    val phase: String = "idle",
    val enabled: Boolean = false,
    val tunnelReady: Boolean = false,
    val imsReady: Boolean = false,
    val smsReady: Boolean = false,
    val carrierProfile: String = "",
    val carrierProfileFrom: String = "",
    val epdg: String = "",
    val epdgAddress: String = "",
    val phoneNumber: String = "",
    val iccid: String = "",
    val lastError: String = "",
    val lastErrorClass: String = "",
    val attempt: Int = 0,
    val security: String = "",
    val transport: String = "",
    /** When the next attempt is due while the line is failed, else 0. */
    val retryAt: Long = 0,
    /** The step under way while connecting: sim, carrier, tunnel or ims. */
    val stage: String = "",
    /** The step the last failure happened at. */
    val failedStage: String = "",
    /** A short code for the last failure, e.g. "IKE 9000", "SIP 403", "timeout". */
    val lastErrorCode: String = "",
    /** How the subscriber was named to the ePDG: "permanent", "anonymous" or "anonymous+encrypted". */
    val identity: String = "",
    /** The network the tunnel runs over: "Wi-Fi", "mobile data", "Ethernet" or "VPN". */
    val network: String = "",
    /** The SMS center read from the SIM, or the carrier profile's; and which one gave it: "sim", "profile" or "". */
    val smsCenter: String = "",
    val smsCenterFrom: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Runs WiFi Calling for one line: SIM identity → carrier profile → ePDG
 * tunnel → IMS registration → SMS and calls, with retries while enabled.
 */
class WifiCallingEngine(
    val lineId: String,
    val sim: SimSource,
    private val network: NetworkBinder,
    private val codecs: AudioCodecFactory,
    private val listener: Listener,
    private val imsConfig: ImsConfig = ImsConfig(deviceId = lineId),
) {
    /** The SIM behind the line. */
    interface SimSource {
        fun identity(): SimIdentity
        val aka: AkaProvider

        /** The SMS centre for this card, from the SIM, the user or the carrier profile. */
        fun smsCenter(identity: SimIdentity, profile: CarrierProfile): String
    }

    /** How sockets reach the Wi-Fi network; the app binds them to it. */
    interface NetworkBinder {
        fun bind(socket: DatagramSocket)
        fun resolver(): EpdgResolver
        val available: Boolean

        /** The network in use, in words: "Wi-Fi", "mobile data", "Ethernet", "VPN". */
        val description: String get() = ""

        /** Why [available] is false, for the line's status. */
        val unavailableReason: String get() = "No network is connected"
    }

    interface Listener {
        fun onState(state: LineState)

        /** The line's carrier profile was resolved for an attempt. */
        fun onProfile(lineId: String, profile: CarrierProfile) = Unit
        fun onSms(lineId: String, sms: ReceivedSms): Boolean
        fun onSmsStatus(lineId: String, status: ReceivedSmsStatus): Boolean

        /** The service centre answered a submitted text: RP-ACK ([cause] null) or RP-ERROR with its cause. */
        fun onSmsSubmitResult(lineId: String, reference: Int, cause: Int?) = Unit
        fun onIncomingCall(lineId: String, call: Call)
        fun onCallChanged(lineId: String, call: Call)

        /** True when calls from [number] should be turned away. */
        fun isBlocked(number: String): Boolean = false

        /** A blocked caller was turned away. */
        fun onBlockedCall(lineId: String, number: String) = Unit

        /** Terminal-based call waiting: false turns second calls away as busy. */
        fun callWaitingEnabled(lineId: String): Boolean = true

        /** Whether to sign in with the anonymous name and encrypted IMSI where the carrier supports it. */
        fun imsiPrivacyEnabled(lineId: String): Boolean = true

        /** The network reported the voicemail box. */
        fun onVoicemail(lineId: String, state: VoicemailState) = Unit
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "vowi-line-$lineId").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)

    @Volatile
    var state = LineState(lineId)
        private set

    private var enabled = false
    private var retry: ScheduledFuture<*>? = null
    private var attempts = 0

    private class Runtime(
        val identity: SimIdentity,
        val profile: CarrierProfile,
        val tunnel: Tunnel,
        val ims: ImsSession,
        val sms: SmsOverIms,
        val calls: CallRuntime,
        val ussd: UssdRuntime,
        val voicemail: VoicemailWatcher,
    )

    @Volatile
    private var runtime: Runtime? = null

    // --- Control --------------------------------------------------------------

    fun enable() = worker.execute {
        if (enabled) return@execute
        enabled = true
        attempts = 0
        connect()
    }

    /** Signs out and stops; the future completes once the session is down. */
    fun disable(): Future<*> = worker.submit {
        enabled = false
        retry?.cancel(false)
        retry = null
        teardown("disabled")
        update { it.copy(phase = "idle", enabled = false, lastError = "", lastErrorClass = "", tunnelReady = false, imsReady = false, smsReady = false) }
    }

    fun reconnect() = worker.execute {
        if (!enabled) return@execute
        attempts = 0
        teardown("reconnect")
        connect()
    }

    /** The Wi-Fi network changed; a running session cannot survive it. */
    fun networkChanged() = worker.execute {
        if (!enabled) return@execute
        if (runtime != null || state.phase == "failed") {
            teardown("network changed")
            attempts = 0
            scheduleRetry(2_000)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            enabled = false
            retry?.cancel(false)
            teardown("closed")
        }
        worker.shutdown()
        runCatching { worker.awaitTermination(5, TimeUnit.SECONDS) }
    }

    // --- Bring-up --------------------------------------------------------------

    private fun connect() {
        val attemptGeneration = generation.incrementAndGet()
        attempts++
        update { it.copy(enabled = true, attempt = attempts, lastError = "", lastErrorClass = "", lastErrorCode = "", failedStage = "", retryAt = 0, stage = STAGE_SIM, identity = "", tunnelReady = false, imsReady = false, smsReady = false) }
        var stage = STAGE_SIM
        try {
            if (!network.available) throw IOException(network.unavailableReason)
            update { it.copy(network = network.description) }
            CoreLog.i(TAG, "line $lineId connects over ${network.description.ifEmpty { "the default network" }}")
            val identity = sim.identity()
            identity.validate()
            val profile = CarrierProfiles.resolve(identity)
            update {
                it.copy(
                    phase = "sim_ready", iccid = identity.iccid, carrierProfile = profile.id, carrierProfileFrom = profile.matchSource,
                    smsCenter = identity.smsc.ifEmpty { profile.smsCenter },
                    smsCenterFrom = if (identity.smsc.isNotEmpty()) "sim" else if (profile.smsCenter.isNotEmpty()) "profile" else "",
                )
            }
            listener.onProfile(lineId, profile)

            stage = STAGE_CARRIER
            update { it.copy(stage = stage) }
            val epdg = CarrierProfiles.deriveEpdg(identity)
            update { it.copy(phase = "access_ready", epdg = epdg) }

            stage = STAGE_TUNNEL
            update { it.copy(stage = stage) }
            val connector = TunnelConnector(network.resolver(), addressMemory)
            val params = TunnelParams(identity, profile, epdg, sim.aka, imei = identity.imei, bindSocket = network::bind, plainIdentity = !listener.imsiPrivacyEnabled(lineId))
            val handshake = connector.connect(params) { !enabled || generation.get() != attemptGeneration }
            val tunnelSettings = profile.tunnel
            val session = IkeSession(
                handshake,
                keepaliveMs = (if (tunnelSettings.nattKeepaliveSeconds > 0) tunnelSettings.nattKeepaliveSeconds else 20) * 1000L,
                dpdEnabled = tunnelSettings.dpdEnabled ?: true,
            )
            val tunnel = Tunnel(session, mtu = if (tunnelSettings.ipsecMtu > 0) tunnelSettings.ipsecMtu else NetStack.MTU) { error -> onFailure(attemptGeneration, "tunnel", error) }
            if (!enabled || generation.get() != attemptGeneration) {
                tunnel.close()
                return
            }
            update { it.copy(phase = "tunnel_ready", tunnelReady = true, epdgAddress = handshake.evidence.epdgAddress, security = "${handshake.evidence.ikeEncryption}/${handshake.evidence.espEncryption}", identity = handshake.evidence.identity) }

            stage = STAGE_IMS
            update { it.copy(stage = stage) }
            val ims = ImsSession(tunnel, identity, profile, sim.aka, imsConfig)
            val sms = SmsOverIms(ims) { sim.smsCenter(identity, profile) }
            val calls = CallRuntime(ims, codecs)
            val ussd = UssdRuntime(ims)
            val voicemail = VoicemailWatcher(ims) { state -> listener.onVoicemail(lineId, state) }
            ims.listener = object : ImsSession.Listener {
                override fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean =
                    calls.handle(request, respond) || ussd.handle(request, respond) || voicemail.handle(request, respond) || sms.handle(request, respond)

                override fun onFailure(error: Exception) = onFailure(attemptGeneration, "ims", error)
            }
            sms.listener = object : SmsOverIms.Listener {
                override fun onSms(sms: ReceivedSms): Boolean = listener.onSms(lineId, sms)
                override fun onStatus(status: ReceivedSmsStatus): Boolean = listener.onSmsStatus(lineId, status)
                override fun onSubmitResult(reference: Int, cause: Int?) = listener.onSmsSubmitResult(lineId, reference, cause)
            }
            calls.listener = object : CallRuntime.Listener {
                override fun onIncoming(call: Call) = listener.onIncomingCall(lineId, call)
                override fun onStateChanged(call: Call) = listener.onCallChanged(lineId, call)
            }
            calls.isBlocked = { number -> listener.isBlocked(number) }
            calls.onBlocked = { number -> listener.onBlockedCall(lineId, number) }
            calls.callWaiting = { listener.callWaitingEnabled(lineId) }
            try {
                ims.connect()
            } catch (error: Exception) {
                calls.close()
                ims.close()
                tunnel.close()
                throw error
            }
            runtime = Runtime(identity, profile, tunnel, ims, sms, calls, ussd, voicemail)
            voicemail.start()
            val evidence = ims.evidence
            update {
                it.copy(
                    phase = if (ims.smsReady) "sms_ready" else "ims_ready",
                    imsReady = true,
                    smsReady = ims.smsReady,
                    phoneNumber = evidence.phoneNumber,
                    transport = evidence.transport,
                    security = "${it.security}, ${evidence.securityMode}",
                    lastError = "",
                    lastErrorClass = "",
                )
            }
            attempts = 0
            CoreLog.i(TAG, "line $lineId is ${state.phase}")
        } catch (error: Exception) {
            if (!enabled || generation.get() != attemptGeneration) return
            val message = error.message ?: error.javaClass.simpleName
            val code = ErrorCodes.of(error)
            CoreLog.w(TAG, "line $lineId failed at $stage${if (code.isNotEmpty()) " ($code)" else ""}: $message")
            teardown("failed")
            when (val decision = RetryPolicy.decide(error, attempts)) {
                // The carrier refused the subscriber: retrying the ePDG is no use until the card is put in again or the user asks.
                RetryPolicy.Decision.Stop -> update {
                    it.copy(
                        phase = "blocked", lastError = message, lastErrorClass = "carrier_rejected", lastErrorCode = code, failedStage = stage, retryAt = 0,
                        tunnelReady = false, imsReady = false, smsReady = false,
                    )
                }
                is RetryPolicy.Decision.Wait -> {
                    val carrierPause = decision.carrierAsked
                    val retryAt = System.currentTimeMillis() + decision.delayMs
                    if (carrierPause) CoreLog.i(TAG, "line $lineId pauses for ${decision.delayMs / 1000} s at the carrier's request")
                    update {
                        it.copy(
                            phase = "failed", lastError = message, lastErrorClass = if (carrierPause) "carrier_backoff" else classify(stage, error),
                            lastErrorCode = code, failedStage = stage, retryAt = retryAt, tunnelReady = false, imsReady = false, smsReady = false,
                        )
                    }
                    scheduleRetry(decision.delayMs)
                }
            }
        }
    }

    private fun onFailure(attemptGeneration: Long, where: String, error: Exception) = worker.execute {
        if (generation.get() != attemptGeneration || !enabled) return@execute
        CoreLog.w(TAG, "line $lineId lost its $where session: ${error.message}")
        teardown("$where failed")
        update {
            it.copy(
                phase = "failed", lastError = error.message ?: where, lastErrorClass = "${where}_lost", lastErrorCode = ErrorCodes.of(error).ifEmpty { "lost" },
                failedStage = if (where == "ims") STAGE_IMS else STAGE_TUNNEL, retryAt = System.currentTimeMillis() + backoff(),
                tunnelReady = false, imsReady = false, smsReady = false,
            )
        }
        scheduleRetry(backoff())
    }

    private fun teardown(reason: String) {
        generation.incrementAndGet()
        val current = runtime ?: return
        runtime = null
        CoreLog.i(TAG, "line $lineId tearing down: $reason")
        runCatching { current.voicemail.stop() }
        runCatching { current.ussd.cancel() }
        runCatching { current.calls.close() }
        runCatching { current.ims.close() }
        runCatching { current.tunnel.close() }
    }

    private fun scheduleRetry(delayMs: Long) {
        retry?.cancel(false)
        retry = worker.schedule({ if (enabled && runtime == null) connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun backoff(): Long = RetryPolicy.plainBackoffMs(attempts)

    /** What a failed attempt leads to: a plain back-off, the pause the carrier asked for, or no retry at all. */
    object RetryPolicy {
        sealed interface Decision {
            data object Stop : Decision
            data class Wait(val delayMs: Long, val carrierAsked: Boolean) : Decision
        }

        /** 10 s, then 20 s, then every 30 s for as long as it takes. */
        fun plainBackoffMs(attempts: Int): Long = when {
            attempts <= 1 -> 10_000L
            attempts == 2 -> 20_000L
            else -> 30_000L
        }

        /** The pause after NETWORK_FAILURE and the like when the network names none. */
        const val CARRIER_PAUSE_MS = 30 * 60_000L
        const val MIN_CARRIER_PAUSE_MS = 60_000L
        const val MAX_CARRIER_PAUSE_MS = 2 * 60 * 60_000L

        fun decide(error: Throwable, attempts: Int): Decision {
            val rejection = generateSequence(error) { it.cause }.filterIsInstance<EpdgRejectedException>().firstOrNull()
                ?: return Decision.Wait(plainBackoffMs(attempts), carrierAsked = false)
            return when (rejection.policy) {
                EpdgErrors.Policy.FATAL -> Decision.Stop
                EpdgErrors.Policy.BACK_OFF -> {
                    val asked = rejection.backoffSeconds?.let { it * 1000 } ?: CARRIER_PAUSE_MS
                    Decision.Wait(asked.coerceIn(MIN_CARRIER_PAUSE_MS, MAX_CARRIER_PAUSE_MS), carrierAsked = true)
                }
            }
        }
    }

    private fun classify(stage: String, error: Exception): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            message.contains("did not answer") || message.contains("timed out") -> "network_timeout"
            message.contains("eap") || message.contains("authentication") -> "authentication"
            message.contains("rejected") -> "rejected"
            else -> stage
        }
    }

    private fun update(change: (LineState) -> LineState) {
        state = change(state).copy(updatedAt = System.currentTimeMillis())
        listener.onState(state)
    }

    // --- Runtime access ----------------------------------------------------------

    val registered: Boolean get() = runtime?.ims?.evidence?.registered == true

    fun sendSms(recipient: String, text: String): SmsSubmitResult {
        val current = runtime ?: throw IOException("the line is not registered")
        return current.sms.send(recipient, text)
    }

    val calls: List<Call> get() = runtime?.calls?.active ?: emptyList()

    fun dial(number: String, hideCallerId: Boolean? = null): Call = (runtime ?: throw IOException("the line is not registered")).calls.dial(number, hideCallerId)
    fun merge(firstId: String, secondId: String): MergeResult = (runtime ?: throw IOException("the line is not registered")).calls.merge(firstId, secondId)
    fun removeParticipant(conferenceId: String, participant: String) = (runtime ?: throw IOException("the line is not registered")).calls.removeParticipant(conferenceId, participant)

    /** Sends a USSD or MMI code (e.g. *#21#) and returns the network's answer. */
    fun ussd(code: String): UssdReply = (runtime ?: throw IOException("the line is not registered")).ussd.send(code)
    fun ussdReply(text: String): UssdReply = (runtime ?: throw IOException("the line is not registered")).ussd.reply(text)
    fun ussdCancel() = runtime?.ussd?.cancel()
    fun answer(callId: String): Call = (runtime ?: throw IOException("the line is not registered")).calls.answer(callId)
    fun hangup(callId: String) = (runtime ?: throw IOException("the line is not registered")).calls.hangup(callId)
    fun hold(callId: String): Call = (runtime ?: throw IOException("the line is not registered")).calls.hold(callId)
    fun resume(callId: String): Call = (runtime ?: throw IOException("the line is not registered")).calls.resume(callId)
    fun sendDtmf(callId: String, digit: Char) = (runtime ?: throw IOException("the line is not registered")).calls.sendDtmf(callId, digit)
    fun media(callId: String): RtpMedia = (runtime ?: throw IOException("the line is not registered")).calls.media(callId)
    fun call(callId: String): Call? = runtime?.calls?.call(callId)

    companion object {
        private const val TAG = "Line"

        const val STAGE_SIM = "sim"
        const val STAGE_CARRIER = "carrier"
        const val STAGE_TUNNEL = "tunnel"
        const val STAGE_IMS = "ims"

        /** The steps in order, for "step N of M". */
        val STAGES = listOf(STAGE_SIM, STAGE_CARRIER, STAGE_TUNNEL, STAGE_IMS)
        private val addressMemory = TunnelConnector.AddressMemory.inProcess()
    }
}
