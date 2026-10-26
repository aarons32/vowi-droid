package com.vowi.vowidroid

import com.vowi.vowidroid.telecom.PhoneAccounts
import com.vowi.vowidroid.data.LinePlanner
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaException
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.CardChangedException
import com.vowi.vowidroid.core.sim.CardMatcher
import com.vowi.vowidroid.core.sim.DownloadResult
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SavedLine
import com.vowi.vowidroid.core.sim.SimCardException
import com.vowi.vowidroid.core.sim.SimCards
import com.vowi.vowidroid.core.sim.SimLocation
import com.vowi.vowidroid.widget.ConversationsWidget
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.vowi.vowidroid.core.WifiCallingEngine
import com.vowi.vowidroid.core.LineState
import com.vowi.vowidroid.core.call.AndroidCodecFactory
import com.vowi.vowidroid.core.call.Call
import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.carrier.SmsCenters
import com.vowi.vowidroid.core.ike.EpdgResolver
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sms.ReceivedSms
import com.vowi.vowidroid.core.sms.ReceivedSmsStatus
import com.vowi.vowidroid.core.sms.SmsRejectedException
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.data.Conversation
import com.vowi.vowidroid.data.DiscoveredLine
import com.vowi.vowidroid.data.Line
import com.vowi.vowidroid.data.LineConfig
import com.vowi.vowidroid.data.LineStore
import com.vowi.vowidroid.data.Message
import com.vowi.vowidroid.data.SmsStore
import java.io.IOException
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.data.CallRecord
import java.time.Instant
import com.vowi.vowidroid.core.ims.VoicemailState
import com.vowi.vowidroid.core.ims.UssdReply
import com.vowi.vowidroid.core.sms.SmsPdu
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.data.CallSettings
import android.os.Build
import com.vowi.vowidroid.core.entitlement.EntitlementException
import com.vowi.vowidroid.core.entitlement.EntitlementRequest
import com.vowi.vowidroid.core.entitlement.EntitlementServers
import com.vowi.vowidroid.core.entitlement.Ts43Client
import com.vowi.vowidroid.core.entitlement.UrlConnectionTransport
import com.vowi.vowidroid.core.entitlement.VowifiEntitlement
import com.vowi.vowidroid.core.ike.EapAka
import java.net.HttpURLConnection

/**
 * The app's native backend: the phone's SIMs, one WiFi Calling engine per
 * line, the message store and the Wi-Fi network the tunnels run on. Screens
 * read its flows; the service keeps it alive.
 */
object VowiCore {
    private const val TAG = "VowiCore"

    data class Status(val running: Boolean, val detail: String = "")

    /** True from the notification's Stop until the next start, so the screens can offer Start instead of looking empty. */
    private val mutableUserStopped = MutableStateFlow(false)
    val userStopped: StateFlow<Boolean> = mutableUserStopped.asStateFlow()

    fun markStoppedByUser() {
        mutableUserStopped.value = true
    }

    private val mutableStatus = MutableStateFlow(Status(false))
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val mutableLines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = mutableLines.asStateFlow()

    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = mutableConversations.asStateFlow()

    private val mutableMessageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val messageChanges: SharedFlow<Unit> = mutableMessageChanges.asSharedFlow()

    private val mutableCallChanges = MutableSharedFlow<Pair<String, Call>>(extraBufferCapacity = 32)

    /** Every call state change, with its line. */
    val callChanges: SharedFlow<Pair<String, Call>> = mutableCallChanges.asSharedFlow()

    /** Lines whose registration is up, with their numbers, for the service notification. */
    private val mutableRegistered = MutableStateFlow<Map<String, String>>(emptyMap())
    val registered: StateFlow<Map<String, String>> = mutableRegistered.asStateFlow()

    /** Direct card access over OMAPI as the last scan saw it; see [OmapiSims]. */
    data class DirectAccess(val supported: Boolean = false, val connected: Boolean = false, val outcomes: Map<Int, DirectCardOutcome> = emptyMap())
    private val mutableDirectAccess = MutableStateFlow(DirectAccess())
    val directAccess: StateFlow<DirectAccess> = mutableDirectAccess.asStateFlow()

    /** Set by the call manager; receives network-originated calls. */
    @Volatile
    var incomingCallHandler: ((lineId: String, call: Call) -> Unit)? = null

    private lateinit var app: Context
    private lateinit var sims: PhoneSims
    private lateinit var lineStore: LineStore
    private lateinit var smsStore: SmsStore
    private lateinit var networks: NetworkTracker
    private val lineNetworks = ConcurrentHashMap<String, LineNetwork>()
    private lateinit var usbReaders: UsbSimReaders

    /** The SIM slots over the cards' own OMAPI channels, for cards whose access rules trust VowiDroid; null when the phone has none. */
    private var omapi: DirectSims? = null
    private val engines = ConcurrentHashMap<String, WifiCallingEngine>()

    /** Each line's card as the last scan found it; a line whose card is not in has no entry. */
    private val placed = ConcurrentHashMap<String, FoundCard>()

    /** What each phone slot last looked like, so changes are logged once. */
    private val slotLog = ConcurrentHashMap<Int, String>()

    /** Lines whose engine VowiDroid has started. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** What the last scan found: every card, the ones no line owns, and the USB readers. */
    @Volatile
    private var lastCards: List<FoundCard> = emptyList()

    @Volatile
    private var unknownCards: List<FoundCard> = emptyList()

    @Volatile
    private var readers: List<UsbSimReaders.Reader> = emptyList()

    /** What the previous scan saw, to tell a card put in from one that was there all along. */
    private class Seen(val emptySlots: Set<Int>, val readerCards: Set<String>, val airplane: Boolean)

    @Volatile
    private var previousScan: Seen? = null

    @Volatile
    private var lastScan: Seen? = null
    private val states = ConcurrentHashMap<String, LineState>()
    private const val WORKER_NAME = "vowi-core"

    /** The phase of every line while VowiDroid is stopped. */
    const val PHASE_STOPPED = "stopped"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, WORKER_NAME).apply { isDaemon = true } }
    private val assembler = SmsAssembler()

    @Volatile
    private var started = false

    // --- Lifecycle ------------------------------------------------------------

    @Synchronized
    fun start(context: Context) {
        if (started) return
        mutableUserStopped.value = false
        app = context.applicationContext
        CarrierProfiles.install { app.assets.open("carrier_profiles.json") }
        SmsCenters.install { app.assets.open("sms_centers.json") }
        ImsiCertificates.install(app)
        sims = PhoneSims(app)
        lineStore = LineStore(app)
        smsStore = SmsStore(app)
        networks = NetworkTracker(app) {
            // Only lines whose own network moved reconnect; a change elsewhere does not touch a working line.
            worker.execute { lineNetworks.forEach { (id, line) -> if (line.changedSinceBound()) { line.forget(); engines[id]?.networkChanged() } } }
        }
        usbReaders = UsbSimReaders(app) { rescan() }
        omapi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && DirectCardAccess.supported(app)) OmapiSims(app) { rescan() } else null
        started = true
        mutableStatus.value = Status(true)
        loadVoicemail()
        CoreLog.i(TAG, "core started with ${CarrierProfiles.count} carrier profiles, codecs ${AndroidCodecFactory.supported}")
        worker.execute {
            // Lines no longer belong to a slot, so names made up from one lose it.
            val saved = lineStore.all()
            val renamed = saved.map { if (it.imsi == null) it.copy(name = LineStore.withoutSlot(it.name)) else it }
            if (renamed != saved) lineStore.save(renamed)
            adoptOrphanedHistory()
            reconcileNow()
            refreshConversationsNow()
        }
        usbReaders.start()
        omapi?.start()
    }

    /**
     * Texts and calls left under an old slot-named line ("phone-sim-2") that
     * was removed before lines followed their SIMs go to the only line there
     * is, when there is only one such leftover; a removed line's ID is kept
     * from now on, so this is a one-time repair.
     */
    private fun adoptOrphanedHistory() {
        val line = lineStore.all().singleOrNull() ?: return
        val legacy = Regex("^phone-sim-\\d+$")
        val orphans = (smsStore.deviceIds() + CallLogStore.deviceIds()).distinct().filter { it != line.id && legacy.matches(it) }
        val orphan = orphans.singleOrNull() ?: return
        CoreLog.i(TAG, "moving the texts and calls of removed line $orphan to line ${line.id}")
        smsStore.reassignDevice(orphan, line.id)
        CallLogStore.reassignDevice(orphan, line.id)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        engines.values.forEach { it.close() }
        engines.clear()
        states.clear()
        running.clear()
        placed.clear()
        usbReaders.close()
        omapi?.close()
        networks.close()
        mutableStatus.value = Status(false)
        mutableDirectAccess.value = DirectAccess()
        mutableRegistered.value = emptyMap()
        refreshLinesNow()
    }

    val isStarted: Boolean get() = started

    /** Looks for the SIM cards again, e.g. after a SIM went in or out, a reader was plugged in, or access was granted. */
    fun rescan() = worker.execute { if (started) reconcileNow() }

    /**
     * Each line's engine reads its card wherever the card is now, a phone
     * slot or a USB reader, and checks it is still the line's own subscriber.
     */
    private fun engineFor(config: LineConfig): WifiCallingEngine = engines.getOrPut(config.id) {
        val lineId = config.id
        WifiCallingEngine(
            lineId = lineId,
            sim = object : WifiCallingEngine.SimSource {
                override fun identity(): SimIdentity {
                    val saved = lineStore.all().firstOrNull { it.id == lineId }
                    val card = placed[lineId] ?: throw SimCardException("the SIM card is not in")
                    val identity = when (val location = card.location) {
                        is SimLocation.PhoneSlot -> if (card.direct) direct().identity(location.slot) else sims.identity(location.slot, saved?.subscriptionId ?: -1)
                        is SimLocation.UsbReader -> usbReaders.identity(location.device)
                    }
                    val expected = saved?.imsi
                    if (expected != null && identity.imsi != expected) {
                        // Another subscriber's card took its place; the next scan sorts out where each card is.
                        rescan()
                        throw CardChangedException()
                    }
                    // The device identity is the line's own setting, whatever the card.
                    return identity.copy(imei = saved?.imei?.trim().orEmpty())
                }

                override val aka: AkaProvider = object : AkaProvider {
                    override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult {
                        val card = placed[lineId] ?: throw AkaException("the SIM card is not in")
                        return when (val location = card.location) {
                            is SimLocation.PhoneSlot ->
                                if (card.direct) direct().authenticate(location.slot, identity.iccid, challenge)
                                else sims.authenticate(location.slot, lineStore.all().firstOrNull { it.id == lineId }?.subscriptionId ?: -1, challenge)
                            is SimLocation.UsbReader -> usbReaders.authenticate(location.device, identity.iccid, challenge)
                        }
                    }
                }

                // The user's own entry first, then the SIM's number, then the carrier's usual one.
                override fun smsCenter(identity: SimIdentity, profile: CarrierProfile): String =
                    SmsCenters.choose(PhoneSims.savedSmsCenter(app, identity.iccid), identity.smsc, profile.smsCenter)

                override fun smsDataDownload(serviceCentre: ByteArray, tpdu: ByteArray, sendSms: (ByteArray, ByteArray) -> Boolean): DownloadResult? {
                    // Only a card in a USB reader is VowiDroid's to talk to; in a phone slot the modem owns the card's toolkit.
                    val card = placed[lineId] ?: return null
                    val reader = card.location as? SimLocation.UsbReader ?: return null
                    return usbReaders.smsDataDownload(reader.device, card.iccid, serviceCentre, tpdu, sendSms) { rescan() }
                }
            },
            network = lineNetworks.getOrPut(lineId) { LineNetwork(lineId) },
            codecs = AndroidCodecFactory,
            listener = engineListener,
        )
    }

    /** The OMAPI side, for a card that was read over its own channel. */
    private fun direct(): DirectSims = omapi ?: throw SimCardException("direct card access is not available on this phone")

    /**
     * Finds every card in the phone's slots and the USB readers, ties each
     * line to its card, and starts, moves or stops the engines to match: a
     * card put in anywhere brings its line up there, and a card taken out
     * stops its line. Runs on the worker.
     */
    private fun reconcileNow(scan: Boolean = true) {
        if (!started) return
        if (scan) {
            previousScan = lastScan
            lastCards = scanCards()
        }
        val configs = lineStore.all()
        val saved = configs.map { SavedLine(it.id, it.imsi, it.iccid, it.slot.takeIf { slot -> it.imsi == null && slot >= 0 }, it.lastSeen) }
        val match = CardMatcher.match(saved, lastCards)
        val updated = configs.map { LinePlanner.update(it, match.placed[it.id], match.bindings[it.id]) }
        if (updated != configs) lineStore.save(updated)
        for ((id, binding) in match.bindings) {
            val before = configs.firstOrNull { it.id == id }
            CoreLog.i(TAG, if (before?.imsi == null) "line $id is now tied to its subscriber" else "line $id is on a replacement card (${binding.iccid.length} digits)")
        }
        for (config in updated) {
            val card = match.placed[config.id]
            val previous = (if (card != null) placed.put(config.id, card) else placed.remove(config.id))?.location
            val location = card?.location
            if (location?.tag != previous?.tag || (card != null && !card.readable)) {
                val where = location?.tag?.let { "in $it" } ?: "not in"
                CoreLog.i(TAG, "line ${config.id}: its card is $where" + if (card != null && !card.readable) ", not readable (${card.issue})" else "")
            }
            val engine = engineFor(config)
            when (LinePlanner.action(config, card, previous, config.id in running)) {
                LinePlanner.Action.START -> {
                    running.add(config.id)
                    engine.enable()
                }
                LinePlanner.Action.MOVE -> {
                    CoreLog.i(TAG, "line ${config.id} moved to ${card?.location?.tag}")
                    engine.reconnect()
                }
                LinePlanner.Action.STOP -> {
                    running.remove(config.id)
                    engine.disable()
                }
                LinePlanner.Action.NONE -> Unit
            }
        }
        unknownCards = match.unknown
        if (scan) announceInserted(match.unknown)
        refreshLinesNow()
    }

    /**
     * Announces new cards that were just put in: into a slot that was empty,
     * or into a reader. A card that only became visible, e.g. when airplane
     * mode went off or VowiDroid started, is not news.
     */
    private fun announceInserted(unknown: List<FoundCard>) {
        val before = previousScan ?: return
        if (before.airplane) return
        val inserted = unknown.filter { card ->
            card.readable && when (val location = card.location) {
                is SimLocation.PhoneSlot -> location.slot in before.emptySlots
                is SimLocation.UsbReader -> card.iccid !in before.readerCards
            }
        }
        SimNotifications.announce(app, inserted) { location -> placeName(location) }
    }

    /**
     * The cards in the phone's slots and in the USB readers. A card the
     * phone lists is read; a card the phone has switched off, as this phone
     * does in airplane mode, is still asked through the subscription it had,
     * and failing that is reported as in but not readable.
     */
    private fun scanCards(): List<FoundCard> {
        val found = ArrayList<FoundCard>()
        val slots = runCatching { sims.slots() }.getOrDefault(emptyList())
        val airplane = runCatching { sims.airplaneMode() }.getOrDefault(false)
        val access = SimAccess.issue(app)
        val configs = lineStore.all()
        for (slot in slots) {
            val location = SimLocation.PhoneSlot(slot.slot)
            var description = slot.state.name.lowercase()
            if (slot.present) {
                var ids = if (access == null) sims.cardIds(slot.slot) else null
                var probed = false
                if (ids == null && access == null) {
                    // The phone no longer lists the card. It may still answer through the subscription it had, so
                    // each line's remembered subscription is tried, the line last seen in this slot first.
                    val remembered = configs.filter { it.subscriptionId >= 0 }.sortedByDescending { it.lastSeen == location.tag }.map { it.subscriptionId }.distinct()
                    for (subscriptionId in remembered) {
                        ids = sims.cardIds(slot.slot, subscriptionId)
                        if (ids != null) break
                    }
                    if (remembered.isNotEmpty()) {
                        probed = true
                        description += if (ids != null) ", answers through an old subscription" else ", silent through ${remembered.size} old subscription(s)"
                    }
                }
                // When the phone will not read the card, a card whose access rules trust VowiDroid, as a removable
                // eSIM can, still answers over its own OMAPI channel, switched off in Android's settings or not.
                val direct = if (ids == null) omapi?.takeIf { it.connected }?.readCard(slot.slot, slot.label) else null
                if (ids != null) {
                    found += FoundCard(location, ids.imsi, ids.iccid, slot.label, subscriptionId = ids.subscriptionId)
                    if (!probed) description += ", readable"
                } else if (direct != null) {
                    found += direct
                    description += if (direct.readable) ", readable over its own channel" else ", locked, over its own channel"
                } else {
                    omapi?.takeIf { it.connected }?.outcomes?.get(slot.slot)?.let { description += ", own channel: ${it.name.lowercase()}" }
                    when (access) {
                        SimAccess.ISSUE_PERMISSION -> PermissionDenials.report(AppPermissions.Need.PHONE, R.string.denial_sim_read)
                        SimAccess.ISSUE_ACCESS -> PermissionDenials.report(AppPermissions.Need.SIM_ACCESS, R.string.denial_sim_read)
                    }
                    val issue = when {
                        access != null -> SimCards.ISSUE_ACCESS
                        airplane -> SimCards.ISSUE_AIRPLANE
                        slot.state == PhoneSims.SlotState.LOCKED -> SimCards.ISSUE_PIN
                        else -> SimCards.ISSUE_OFF
                    }
                    found += FoundCard(location, null, "", slot.label, issue = issue)
                    description += ", not readable ($issue)"
                }
            }
            if (slotLog.put(slot.slot, description) != description) CoreLog.i(TAG, "SIM slot ${slot.slot + 1}: $description")
        }
        readers = runCatching { usbReaders.readers() }.onFailure { CoreLog.w(TAG, "USB readers: ${it.message}") }.getOrDefault(emptyList())
        found += readers.mapNotNull { it.card }
        lastScan = Seen(
            emptySlots = slots.filter { !it.present }.map { it.slot }.toSet(),
            readerCards = readers.mapNotNull { it.card?.iccid?.takeIf { iccid -> iccid.isNotEmpty() } }.toSet(),
            airplane = airplane,
        )
        mutableDirectAccess.value = DirectAccess(omapi != null, omapi?.connected == true, omapi?.outcomes.orEmpty())
        return found
    }

    /** A slot or reader as the screens name it. */
    private fun placeName(location: SimLocation): String = when (location) {
        is SimLocation.PhoneSlot -> app.getString(R.string.sim_slot_name, location.slot + 1)
        is SimLocation.UsbReader -> location.name
    }

    private fun placeName(tag: String?): String = when {
        tag == null -> ""
        tag.startsWith("slot:") -> app.getString(R.string.sim_slot_name, tag.removePrefix("slot:").toIntOrNull() ?: 0)
        tag.startsWith("usb:") -> tag.removePrefix("usb:")
        else -> ""
    }

    private val engineListener = object : WifiCallingEngine.Listener {
        override fun onProfile(lineId: String, profile: CarrierProfile) {
            // A fresh IMSI-encryption certificate, when the carrier offers one; off the line's own thread.
            if (profile.privacy.updateUrl.isNotEmpty()) Thread({ ImsiCertificates.refreshIfDue(app, profile) }, "vowi-imsi-keys").apply { isDaemon = true }.start()
        }

        override fun onState(state: LineState) {
            states[state.lineId] = state
            mutableRegistered.value = states.filterValues { it.imsReady }.mapValues { it.value.phoneNumber }
            refreshLinesNow()
        }

        override fun onSms(lineId: String, sms: ReceivedSms): Boolean {
            // A voicemail alert sent as an SMS updates the voicemail count; a "discard" one is not a text.
            val indication = runCatching { SmsPdu.voicemailIndication(sms.rawTpdu.hexToBytes()) }.getOrNull()
            if (indication != null) {
                updateVoicemail(lineId, VoicemailState(indication.active, if (indication.active) indication.count else 0))
                if (indication.discard) return true
            }
            val complete = assembler.add(lineId, sms) ?: return true
            storeInbound(lineId, complete)
            return true
        }

        override fun onSmsSubmitResult(lineId: String, reference: Int, cause: Int?) {
            if (cause == null) return
            // Matched by the reference the part went out with; "sent" was only the IMS core's acceptance.
            if (smsStore.failSubmission(lineId, reference, SmsStore.ERROR_RP + cause)) {
                notifyMessagesChanged()
            } else {
                CoreLog.i(TAG, "RP-ERROR $cause for reference $reference matches no sent text")
            }
        }

        override fun onSimDataUndeliverable(lineId: String) {
            SimNotifications.dataDownloadRefused(app, lineId, lineName(lineId))
        }

        override fun onSmsStatus(lineId: String, status: ReceivedSmsStatus): Boolean {
            // Matched by TP-MR, so a late report never marks the wrong text.
            if (smsStore.applyStatusReport(lineId, status.to, status.messageReference, status.deliveryStatus)) {
                notifyMessagesChanged()
            } else {
                CoreLog.i(TAG, "status report ${status.deliveryStatus} for reference ${status.messageReference} matches no sent text")
            }
            return true
        }

        override fun onIncomingCall(lineId: String, call: Call) {
            incomingCallHandler?.invoke(lineId, call) ?: run {
                CoreLog.w(TAG, "incoming call with no handler; declining")
                runCatching { engines[lineId]?.hangup(call.id) }
            }
        }

        override fun onCallChanged(lineId: String, call: Call) {
            mutableCallChanges.tryEmit(lineId to call)
        }

        override fun isBlocked(number: String): Boolean = BlockedNumbers.isBlocked(number)

        override fun callWaitingEnabled(lineId: String): Boolean = CallSettings.callWaiting(app)

        override fun imsiPrivacyEnabled(lineId: String): Boolean = CallSettings.imsiPrivacy(app, lineId)

        override fun onVoicemail(lineId: String, state: VoicemailState) = updateVoicemail(lineId, state)

        override fun onBlockedCall(lineId: String, number: String) {
            CallLogStore.add(CallRecord(0, lineId, number, outgoing = false, CallRecord.Outcome.BLOCKED, Instant.now(), 0))
        }
    }

    private fun storeInbound(lineId: String, sms: ReceivedSms) {
        val inserted = smsStore.addInbound(lineId, sms.from, sms.text, sms.timestamp, sms.messageId)
        val blocked = BlockedNumbers.isBlocked(sms.from)
        if (inserted) {
            if (blocked) {
                // As in AOSP Messaging: kept, read and archived, without a notification.
                smsStore.markRead(lineId, sms.from)
                smsStore.setArchived(lineId, sms.from, true)
            } else if (smsStore.settings(lineId, sms.from).archived) {
                // A new text brings an archived conversation back.
                smsStore.setArchived(lineId, sms.from, false)
            }
        }
        notifyMessagesChanged()
        if (!inserted || blocked) return
        // The conversation on screen shows the text itself, as AOSP Messaging does.
        if (visibleConversation == conversationKey(lineId, sms.from)) return
        if (smsStore.settings(lineId, sms.from).muted) return
        Notifications.sms(app, lineId, lineName(lineId), sms.from, smsStore.unread(lineId, sms.from))
    }

    // --- Voicemail ----------------------------------------------------------------

    private val mutableVoicemail = MutableStateFlow<Map<String, VoicemailState>>(emptyMap())

    /** Each line's voicemail box, as the network last reported it. */
    val voicemail: StateFlow<Map<String, VoicemailState>> = mutableVoicemail.asStateFlow()

    private fun updateVoicemail(lineId: String, state: VoicemailState) {
        mutableVoicemail.value = mutableVoicemail.value + (lineId to state)
        app.getSharedPreferences("voicemail", Context.MODE_PRIVATE).edit()
            .putBoolean("$lineId.waiting", state.waiting)
            .putInt("$lineId.new", state.newMessages ?: -1)
            .putString("$lineId.account", state.account)
            .apply()
        VoicemailNotifications.update(app, lineId, lineName(lineId), state)
    }

    private fun loadVoicemail() {
        val stored = app.getSharedPreferences("voicemail", Context.MODE_PRIVATE)
        mutableVoicemail.value = lineStore.all().associate { line ->
            val count = stored.getInt("${line.id}.new", 0)
            line.id to VoicemailState(stored.getBoolean("${line.id}.waiting", false), count.takeIf { it >= 0 }, 0, stored.getString("${line.id}.account", "").orEmpty())
        }
    }

    /** The voicemail number for a line: the user's, the SIM's, then the one the voicemail alerts name. */
    fun voicemailNumberOrAccount(lineId: String): String? =
        voicemailNumber(lineId) ?: mutableVoicemail.value[lineId]?.account?.let { account ->
            account.removePrefix("sip:").removePrefix("tel:").substringBefore('@').substringBefore(';').takeIf { it.any(Char::isDigit) }
        }

    // --- USSD -----------------------------------------------------------------------

    fun ussd(lineId: String, code: String): UssdReply = (engines[lineId] ?: throw IOException("the line is not set up")).ussd(code)

    fun ussdReply(lineId: String, text: String): UssdReply = (engines[lineId] ?: throw IOException("the line is not set up")).ussdReply(text)

    fun ussdCancel(lineId: String) = engines[lineId]?.ussdCancel()

    /**
     * Asks the carrier's entitlement server (GSMA TS.43) about this line:
     * whether Wi-Fi calling is allowed and whether the emergency address and
     * terms are on file. US carriers collect the E911 address there. Network
     * I/O: call it off the main thread, and not on the worker.
     */
    fun emergencyAddress(lineId: String, freshSignIn: Boolean = false): VowifiEntitlement {
        val engine = engines[lineId] ?: throw IOException("the line is not set up")
        val identity = engine.sim.identity()
        val profile = CarrierProfiles.resolve(identity)
        val server = EntitlementStore.server(app, lineId)
            ?: EntitlementServers.forPlmn(identity.homeMcc, identity.homeMnc)
            ?: throw EntitlementException(app.getString(R.string.emergency_server_unknown))
        val eapIdentity = String(EapAka.permanentIdentity(identity, profile), Charsets.US_ASCII)
        // Carriers tend to answer only from their own network: with the SIM in the phone, ask over mobile data.
        val lease = if (placed[lineId]?.location is SimLocation.PhoneSlot) CarrierNetwork.request(app, 8_000) else null
        try {
            val transport = UrlConnectionTransport(open = { url -> (lease?.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection })
            val client = Ts43Client(transport, eapIdentity, profile.effectiveSubscriberImsi(identity.imsi)) { challenge ->
                engine.sim.aka.authenticate(identity, challenge)
            }
            val request = EntitlementRequest(
                serverUrl = server,
                terminalId = EntitlementStore.terminalId(app),
                terminalVendor = Build.MANUFACTURER.orEmpty(),
                terminalModel = Build.MODEL.orEmpty(),
                terminalSoftwareVersion = Build.VERSION.RELEASE.orEmpty(),
                appVersion = BuildConfig.VERSION_NAME,
                token = if (freshSignIn) null else EntitlementStore.token(app, lineId),
            )
            CoreLog.i(TAG, "line $lineId: asking the entitlement server over ${if (lease != null) "mobile data" else "the default network"}")
            val answer = try {
                client.queryVowifi(request)
            } catch (error: Exception) {
                EntitlementStore.saveError(app, lineId, error.message ?: error.javaClass.simpleName)
                CoreLog.w(TAG, "line $lineId: the entitlement check failed", error)
                throw error
            }
            EntitlementStore.saveAnswer(app, lineId, answer, viaCellular = lease != null)
            val page = answer.serviceFlowUrl.takeIf { it.isNotEmpty() }?.let { " at " + it.substringBefore('?') }.orEmpty()
            CoreLog.i(TAG, "line $lineId: entitlement $answer$page")
            return answer
        } finally {
            lease?.close()
        }
    }

    /** The conversation the user is looking at, as "line|peer"; set by the thread screen. */
    @Volatile
    var visibleConversation: String? = null

    fun conversationKey(lineId: String, peer: String) = "$lineId|$peer"

    private fun lineName(lineId: String): String = lineStore.all().firstOrNull { it.id == lineId }?.name.orEmpty()

    // --- Lines ----------------------------------------------------------------

    fun refreshLines() = worker.execute { refreshLinesNow() }

    private fun refreshLinesNow() {
        if (!started) {
            // Stopped, not gone: the saved lines stay on the screens, marked as such, until the next start.
            mutableLines.value = if (::lineStore.isInitialized && ::app.isInitialized) lineStore.all().map(::stoppedLine) else emptyList()
            return
        }
        val airplane = runCatching { sims.airplaneMode() }.getOrDefault(false)
        val access = SimAccess.issue(app) != null
        mutableLines.value = lineStore.all().map { config ->
            val state = states[config.id]
            val card = placed[config.id]
            val location = card?.location
            val inReader = location is SimLocation.UsbReader || (location == null && config.lastSeen?.startsWith("usb:") == true)
            // A card that was not found may be out, or hidden: some phones show no card at all in airplane mode.
            val reason = when {
                card != null -> card.issue
                inReader -> ""
                airplane -> SimCards.ISSUE_AIRPLANE
                access -> SimCards.ISSUE_ACCESS
                else -> ""
            }
            // In airplane mode the card is taken to be where it was, since nothing can be seen.
            val assumedPlace = if (location == null && reason == SimCards.ISSUE_AIRPLANE && config.lastSeen?.startsWith("slot:") == true) placeName(config.lastSeen) else ""
            Line(
                id = config.id,
                name = config.name,
                kind = if (inReader) Line.Kind.USB_READER else Line.Kind.PHONE_SIM,
                wifiCallingEnabled = config.enabled,
                phase = state?.phase ?: if (config.enabled) "idle" else "idle",
                registered = state?.imsReady == true,
                smsReady = state?.smsReady == true,
                phoneNumber = state?.phoneNumber.orEmpty(),
                epdg = state?.epdg.orEmpty(),
                carrierProfile = state?.carrierProfile.orEmpty(),
                lastError = state?.lastError.orEmpty(),
                // The saved ICCID stands in until the card has been read, so its settings can be edited before the first connection.
                iccid = state?.iccid?.takeIf { it.isNotEmpty() } ?: config.iccid.orEmpty(),
                retryAt = state?.retryAt ?: 0,
                carrierPause = state?.lastErrorClass == "carrier_backoff",
                stage = state?.stage.orEmpty(),
                failedStage = state?.failedStage.orEmpty(),
                errorCode = state?.lastErrorCode.orEmpty(),
                identity = state?.identity.orEmpty(),
                network = state?.network.orEmpty(),
                smsCenter = state?.smsCenter.orEmpty(),
                smsCenterFrom = state?.smsCenterFrom.orEmpty(),
                imei = config.imei.orEmpty(),
                present = card != null,
                location = location?.let(::placeName) ?: assumedPlace,
                lastSeen = placeName(config.lastSeen),
                sim = when {
                    card == null && assumedPlace.isNotEmpty() -> Line.SimStatus.OFF
                    card == null -> if (reason.isEmpty()) Line.SimStatus.REMOVED else Line.SimStatus.UNAVAILABLE
                    !card.readable -> Line.SimStatus.OFF
                    config.ejected -> Line.SimStatus.EJECTED
                    else -> Line.SimStatus.IN_USE
                },
                simReason = reason,
            )
        }
    }

    /** A saved line as the screens show it while VowiDroid is stopped. */
    private fun stoppedLine(config: LineConfig): Line = Line(
        id = config.id,
        name = config.name,
        kind = if (config.lastSeen?.startsWith("usb:") == true) Line.Kind.USB_READER else Line.Kind.PHONE_SIM,
        wifiCallingEnabled = config.enabled,
        phase = PHASE_STOPPED,
        registered = false,
        smsReady = false,
        phoneNumber = "",
        epdg = "",
        carrierProfile = "",
        lastError = "",
        iccid = config.iccid.orEmpty(),
        imei = config.imei.orEmpty(),
        present = false,
        lastSeen = placeName(config.lastSeen),
        sim = Line.SimStatus.UNAVAILABLE,
        simReason = PHASE_STOPPED,
    )

    fun setWifiCalling(id: String, enabled: Boolean) = onWorker {
        if (lineStore.all().none { it.id == id }) throw IOException("unknown line $id")
        lineStore.setEnabled(id, enabled)
        reconcileNow(scan = false)
    }

    fun reconnect(id: String) {
        engines[id]?.reconnect() ?: throw IOException("unknown line $id")
    }

    /** Sets the line's device identity (IMEI, 15 digits, or "" for none) and reconnects a running line so the ePDG and the IMS core see it. */
    fun setImei(id: String, imei: String) = onWorker {
        if (lineStore.all().none { it.id == id }) throw IOException("unknown line $id")
        lineStore.update(id) { it.copy(imei = imei.trim()) }
        refreshLinesNow()
        if (id in running) engines[id]?.reconnect()
    }

    /** The phone's own IMEI for the slot the line's card is in (the first slot for a reader), or "" when Android will not give it. */
    fun phoneImei(id: String): String {
        if (!started) return ""
        val slot = (placed[id]?.location as? SimLocation.PhoneSlot)?.slot ?: 0
        return sims.imei(slot)
    }

    /**
     * SIM cards no line owns yet, in the phone's slots and the USB readers,
     * plus slots and readers VowiDroid cannot read yet, with the reason.
     */
    fun discovered(): List<DiscoveredLine> = onWorker {
        reconcileNow()
        val result = ArrayList<DiscoveredLine>()
        val slotIssue = SimAccess.issue(app)
        if (slotIssue != null) {
            for (slot in runCatching { sims.slots() }.getOrDefault(emptyList())) {
                if (!slot.present) continue
                val place = app.getString(R.string.sim_slot_name, slot.slot + 1)
                result += DiscoveredLine("android-sim:slot${slot.slot + 1}", slot.label.ifEmpty { place }, slotIssue, place = place)
            }
        }
        for (card in unknownCards) {
            result += DiscoveredLine(
                usbPath = when (val location = card.location) {
                    is SimLocation.PhoneSlot -> "android-sim:slot${location.slot + 1}"
                    is SimLocation.UsbReader -> "usb:${location.device}"
                },
                name = card.label.ifEmpty { placeName(card.location) },
                issue = card.issue,
                imsi = card.imsi.orEmpty(),
                iccid = card.iccid,
                kind = if (card.location is SimLocation.UsbReader) Line.Kind.USB_READER else Line.Kind.PHONE_SIM,
                place = placeName(card.location),
            )
        }
        for (reader in readers) {
            if (reader.card != null) continue
            result += DiscoveredLine("usb:${reader.device}", reader.name, reader.issue.orEmpty(), kind = Line.Kind.USB_READER, place = reader.name)
        }
        result
    }

    /**
     * Adds a found card as a new line, or, with [existingLineId], gives it to a
     * line whose own card is gone: a replacement SIM for the same number, or a
     * line saved before cards were tracked whose card was moved first.
     */
    fun addLine(line: DiscoveredLine, existingLineId: String? = null) = onWorker {
        if (line.imsi.isEmpty()) throw IOException(app.getString(R.string.add_line_unreadable))
        val configs = lineStore.all()
        val found = lastCards.firstOrNull { it.imsi == line.imsi }
        if (existingLineId != null) {
            if (configs.none { it.id == existingLineId }) throw IOException("unknown line $existingLineId")
            if (placed.containsKey(existingLineId)) throw IOException(app.getString(R.string.add_line_owner_present))
            lineStore.update(existingLineId) { it.copy(imsi = line.imsi, iccid = line.iccid, subscriptionId = found?.subscriptionId ?: -1, ejected = false) }
        } else {
            val location = found?.location
            var name = line.name.trim().ifEmpty { app.getString(R.string.sim_card_default_name) }
            if (configs.any { it.name == name }) name = (2..99).map { "$name $it" }.first { candidate -> configs.none { it.name == candidate } }
            // The same SIM added again carries on as the line it was, with its texts and calls.
            val id = lineStore.removedId(line.imsi) ?: SimCards.lineId(line.imsi)
            lineStore.add(
                LineConfig(
                    id, (location as? SimLocation.PhoneSlot)?.slot ?: -1, name, enabled = true,
                    imsi = line.imsi, iccid = line.iccid, subscriptionId = found?.subscriptionId ?: -1, lastSeen = location?.tag,
                ),
            )
        }
        SimNotifications.cancel(app, line.iccid)
        reconcileNow(scan = false)
    }

    /** Lines whose card is not in, which a found card could take over. */
    fun linesWithoutCard(): List<Line> = mutableLines.value.filter { !it.present }

    /** Asks Android to let VowiDroid use a USB reader; the answer leads to a new scan. */
    fun requestReaderAccess(usbPath: String) = usbReaders.requestPermission(usbPath.removePrefix("usb:"))

    /**
     * Eject: signs the line out so its card can be taken out, and powers a
     * reader's card down. The line stays off until its card is taken out
     * and put back, anywhere, or [resumeLine].
     */
    fun ejectLine(id: String) = onWorker {
        lineStore.update(id) { it.copy(ejected = true) }
        if (running.remove(id)) runCatching { engines[id]?.disable()?.get(10, TimeUnit.SECONDS) }
        (placed[id]?.location as? SimLocation.UsbReader)?.let { usbReaders.powerOff(it.device) }
        refreshLinesNow()
    }

    /** Uses an ejected line's card again without taking it out. */
    fun resumeLine(id: String) = onWorker {
        lineStore.update(id) { it.copy(ejected = false) }
        reconcileNow()
    }

    fun renameLine(id: String, name: String) = onWorker {
        val clean = name.trim()
        if (clean.isNotEmpty()) lineStore.update(id) { it.copy(name = clean) }
        refreshLinesNow()
        refreshConversationsNow()
    }

    fun removeLine(id: String) = onWorker {
        // Its card stays known, so it is not announced as new the moment its line is gone, and its ID is kept
        // for the day the same SIM is added again.
        lineStore.all().firstOrNull { it.id == id }?.let { removed ->
            removed.iccid?.let { SimNotifications.markKnown(app, it) }
            removed.imsi?.let { lineStore.rememberRemoved(it, id) }
        }
        PhoneAccounts.unregister(app, id)
        running.remove(id)
        placed.remove(id)
        engines.remove(id)?.close()
        lineNetworks.remove(id)
        states.remove(id)
        lineStore.remove(id)
        EntitlementStore.clear(app, id)
        mutableRegistered.value = states.filterValues { it.imsReady }.mapValues { it.value.phoneNumber }
        reconcileNow(scan = false)
    }

    /** Runs line changes on the worker, one at a time, and waits for them. */
    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread().name == WORKER_NAME) return block()
        return try {
            worker.submit<T> { block() }.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun engine(lineId: String): WifiCallingEngine? = engines[lineId]

    // --- Messages ---------------------------------------------------------------

    fun refreshConversations() = worker.execute { refreshConversationsNow() }

    private fun refreshConversationsNow() {
        if (!started) return
        val names = lineStore.all().associate { it.id to it.name }
        mutableConversations.value = smsStore.conversations().map { it.copy(deviceName = names[it.deviceId].orEmpty()) }
    }

    private fun notifyMessagesChanged() {
        refreshConversationsNow()
        mutableMessageChanges.tryEmit(Unit)
        ConversationsWidget.refresh(app)
    }

    /** The newest conversations, for the home-screen widget; it may ask before the core has started. */
    fun recentConversations(context: Context, limit: Int): List<Conversation> =
        if (started) smsStore.conversations(limit) else SmsStore(context).use { it.conversations(limit) }

    fun thread(deviceId: String, peer: String): List<Message> = smsStore.thread(deviceId, peer).also { refreshConversations() }

    /** Sends a text and records it; throws with a plain message when it fails. */
    fun sendSms(deviceId: String, peer: String, text: String) {
        // Stored first, so a text that cannot go out stays in the thread with a retry.
        val id = smsStore.addOutbound(deviceId, peer, text, "sending")
        unarchive(deviceId, peer)
        notifyMessagesChanged()
        // Texting someone ranks their conversation first in the share sheet, as Android expects.
        ConversationShortcuts.report(app, deviceId, peer)
        val engine = engines[deviceId]
        if (engine == null) {
            smsStore.setStatus(id, "failed", error = SmsStore.ERROR_LOCAL + "the line is not set up")
            notifyMessagesChanged()
            throw IOException("the line is not set up")
        }
        deliver(engine, id, peer, text)
    }

    /** Sends a failed text again as the same message, moved to now. */
    fun resendSms(id: Long) {
        val message = smsStore.message(id) ?: throw IOException("the text is gone")
        val engine = engines[message.deviceId] ?: throw IOException("the line is not set up")
        if (message.status != "failed") return
        smsStore.resetForResend(id)
        unarchive(message.deviceId, message.peer)
        notifyMessagesChanged()
        deliver(engine, id, message.peer, message.body)
    }

    /** Writing to a conversation brings it back from the archive, as a new text from it does. */
    private fun unarchive(deviceId: String, peer: String) {
        if (smsStore.settings(deviceId, peer).archived) smsStore.setArchived(deviceId, peer, false)
    }

    private fun deliver(engine: WifiCallingEngine, id: Long, peer: String, text: String) {
        try {
            val result = engine.sendSms(peer, text)
            if (!result.accepted) throw IOException("the network did not accept the text")
            smsStore.setSent(id, result.parts.map { it.reference })
        } catch (error: Exception) {
            val reason = if (error is SmsRejectedException) SmsStore.ERROR_SIP + error.sipCode else SmsStore.ERROR_LOCAL + (error.message ?: "the text could not be sent")
            smsStore.setStatus(id, "failed", error = reason)
            notifyMessagesChanged()
            throw IOException(error.message ?: "the text could not be sent", error)
        }
        notifyMessagesChanged()
    }

    fun message(id: Long): Message? = smsStore.message(id)

    fun deleteMessage(id: Long) {
        smsStore.deleteMessage(id)
        notifyMessagesChanged()
    }

    /** Marks a conversation read and takes its notification away. */
    fun markRead(deviceId: String, peer: String) {
        smsStore.markRead(deviceId, peer)
        Notifications.cancelConversation(app, deviceId, peer)
        notifyMessagesChanged()
    }

    fun deleteThread(deviceId: String, peer: String) {
        smsStore.deleteThread(deviceId, peer)
        Notifications.cancelConversation(app, deviceId, peer, keepBubble = false)
        ConversationShortcuts.remove(app, deviceId, peer)
        notifyMessagesChanged()
    }

    fun setArchived(deviceId: String, peer: String, archived: Boolean) {
        smsStore.setArchived(deviceId, peer, archived)
        if (archived) Notifications.cancelConversation(app, deviceId, peer, keepBubble = false)
        notifyMessagesChanged()
    }

    fun setPinned(deviceId: String, peer: String, pinned: Boolean) {
        smsStore.setPinned(deviceId, peer, pinned)
        notifyMessagesChanged()
    }

    /** Notifications off or on for one conversation. */
    fun setMuted(deviceId: String, peer: String, muted: Boolean) {
        smsStore.setMuted(deviceId, peer, muted)
        if (muted) Notifications.cancelConversation(app, deviceId, peer, keepBubble = false)
        notifyMessagesChanged()
    }

    fun markUnread(deviceId: String, peer: String) {
        smsStore.setMarkedUnread(deviceId, peer, true)
        notifyMessagesChanged()
    }

    /** Texts containing [text], newest first. */
    fun searchMessages(text: String): List<Message> = if (text.isBlank()) emptyList() else smsStore.search(text.trim())

    /** The voicemail number the SIM or carrier gives for a line, if any. */
    /** The SIM's voicemail number; Android knows it only for a card in one of the phone's slots. */
    fun voicemailNumber(lineId: String): String? {
        val slot = (placed[lineId]?.location as? SimLocation.PhoneSlot)?.slot ?: return null
        return sims.voicemailNumber(slot)
    }

    // --- Wi-Fi --------------------------------------------------------------------

    /** Tracks the Wi-Fi network and binds the tunnel sockets to it. */
    /** Which network a line wants: Android's default, or one kind of transport. */
    object NetworkChoice {
        const val AUTO = "auto"
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val ETHERNET = "ethernet"
        const val VPN = "vpn"
        val ALL = listOf(AUTO, WIFI, CELLULAR, ETHERNET, VPN)
    }

    /**
     * Every network with internet access the phone has, VPNs included, and
     * which one Android treats as the default. Lines bind their tunnel sockets
     * to the network their choice selects, and are told when that network
     * changes or goes away, so the line comes back over the new path.
     */
    private class NetworkTracker(context: Context, private val onChange: () -> Unit) : AutoCloseable {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
        private val requested = ConcurrentHashMap<Int, ConnectivityManager.NetworkCallback>()

        @Volatile
        var default: Network? = null
            private set

        private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                default = network
                CoreLog.i("Network", "Android's default network is now ${describe(network)}")
                onChange()
            }

            override fun onLost(network: Network) {
                if (default == network) {
                    default = null
                    CoreLog.w("Network", "Android's default network is gone")
                    onChange()
                }
            }
        }

        private val allCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()?.let { networks[network] = it }
                onChange()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                networks[network] = capabilities
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                onChange()
            }
        }

        init {
            try {
                connectivity.registerDefaultNetworkCallback(defaultCallback)
                // VPNs count too: the builder leaves them out unless asked.
                val all = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                connectivity.registerNetworkCallback(all, allCallback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "cannot watch the networks", error)
            }
        }

        private fun transportOf(choice: String): Int? = when (choice) {
            NetworkChoice.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkChoice.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkChoice.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            NetworkChoice.VPN -> NetworkCapabilities.TRANSPORT_VPN
            else -> null
        }

        /** The network for a choice: Android's default (or any, when it has none), or the first of the wanted transport. */
        fun select(choice: String): Network? {
            val transport = transportOf(choice) ?: return default ?: networks.keys.firstOrNull()
            return networks.entries.firstOrNull { (_, caps) ->
                caps.hasTransport(transport) && (transport == NetworkCapabilities.TRANSPORT_VPN || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            }?.key
        }

        /** Asks Android to bring a transport up and keep it up, e.g. mobile data while Wi-Fi is the default. */
        fun keepUp(choice: String) {
            val transport = transportOf(choice) ?: return
            if (transport == NetworkCapabilities.TRANSPORT_VPN) return
            requested.computeIfAbsent(transport) {
                val callback = object : ConnectivityManager.NetworkCallback() {}
                val request = NetworkRequest.Builder().addTransportType(transport).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                runCatching { connectivity.requestNetwork(request, callback) }.onFailure { Log.w(TAG, "cannot request network transport $transport", it) }
                callback
            }
        }

        /** The MTU Android reports for [network], e.g. a VPN's 1280; 0 when unknown or before Android 10. */
        fun mtu(network: Network?): Int {
            if (network == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
            return runCatching { connectivity.getLinkProperties(network)?.mtu ?: 0 }.getOrDefault(0)
        }

        fun describe(network: Network?): String {
            val caps = network?.let { networks[it] ?: runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() } ?: return if (network != null) "network" else ""
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "network"
            }
        }

        override fun close() {
            runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
            runCatching { connectivity.unregisterNetworkCallback(allCallback) }
            requested.values.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
            requested.clear()
        }
    }

    /** One line's view of the networks: the one its choice selects, bound to its sockets. */
    private class LineNetwork(private val lineId: String) : WifiCallingEngine.NetworkBinder {
        @Volatile
        private var bound: Network? = null

        private fun choice(): String = CallSettings.networkChoice(app, lineId)

        private fun selected(): Network? {
            val choice = choice()
            networks.keepUp(choice)
            return networks.select(choice)
        }

        override val available: Boolean get() = selected() != null

        override val description: String get() = networks.describe(selected())

        override val unavailableReason: String
            get() = when (choice()) {
                NetworkChoice.WIFI -> "No Wi-Fi network is connected"
                NetworkChoice.CELLULAR -> "No mobile data network is connected"
                NetworkChoice.ETHERNET -> "No Ethernet network is connected"
                NetworkChoice.VPN -> "No VPN is connected"
                else -> "No network is connected"
            }

        override fun bind(socket: DatagramSocket) {
            val network = selected() ?: throw IOException(unavailableReason)
            network.bindSocket(socket)
            bound = network
        }

        override val mtu: Int get() = networks.mtu(bound ?: selected())

        override fun resolver(): EpdgResolver = EpdgResolver(lookup = { host ->
            val network = selected() ?: throw IOException(unavailableReason)
            network.getAllByName(host)
        })

        /** The network the line runs on is not the one it would pick now. */
        fun changedSinceBound(): Boolean = bound != null && selected() != bound

        fun forget() {
            bound = null
        }
    }

    // --- Concatenated texts -------------------------------------------------------

    /** Joins the parts of a long text; a part set that never completes is delivered as it is. */
    private class SmsAssembler {
        private class Pending(val first: ReceivedSms, val total: Int) {
            val parts = HashMap<Int, String>()
            var timer: java.util.concurrent.ScheduledFuture<*>? = null
        }

        private val pending = HashMap<String, Pending>()
        private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "vowi-sms-assembler").apply { isDaemon = true } }

        @Volatile
        var onIncomplete: ((String, ReceivedSms) -> Unit)? = null

        /** Returns the complete message, or null while parts are still missing. */
        @Synchronized
        fun add(lineId: String, sms: ReceivedSms): ReceivedSms? {
            val concat = sms.concat ?: return sms
            if (concat.total <= 1) return sms
            val key = "$lineId|${sms.from}|${concat.reference}|${concat.total}"
            val entry = pending.getOrPut(key) {
                Pending(sms, concat.total).also { created ->
                    created.timer = timers.schedule({ flush(lineId, key) }, 90, TimeUnit.SECONDS)
                }
            }
            entry.parts[concat.sequence] = sms.text
            if (entry.parts.size < entry.total) return null
            entry.timer?.cancel(false)
            pending.remove(key)
            return joined(entry)
        }

        @Synchronized
        private fun flush(lineId: String, key: String) {
            val entry = pending.remove(key) ?: return
            onIncomplete?.invoke(lineId, joined(entry))
        }

        private fun joined(entry: Pending): ReceivedSms {
            val text = (1..entry.total).joinToString("") { entry.parts[it].orEmpty() }
            val first = entry.first
            return ReceivedSms(first.messageId, first.from, text, first.timestamp, first.serviceCenterTimestamp, first.encoding, null, first.rpReference, first.callId, first.rawTpdu, first.decodeError)
        }
    }

    init {
        assembler.onIncomplete = { lineId, sms -> storeInbound(lineId, sms) }
    }
}
