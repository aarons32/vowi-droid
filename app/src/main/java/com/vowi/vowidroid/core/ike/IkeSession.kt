package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u32Bytes
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An established IKE SA with its CHILD_SA. One reader thread owns the
 * socket: IKE messages are answered here (DPD, deletes, rekeys) and ESP
 * packets go to the dataplane. NAT keepalives and our own dead-peer
 * detection run on the same socket, so the mapping that carried the
 * handshake carries the whole session.
 */
class IkeSession(
    handshake: HandshakeResult,
    private val keepaliveMs: Long = 20_000,
    private val dpdIdleMs: Long = 90_000,
    /** Carrier bundles that turn dead-peer detection off leave liveness to the ePDG's own probes and to SIP. */
    private val dpdEnabled: Boolean = true,
    private val random: RandomSource = Rng::bytes,
) : AutoCloseable {
    interface Listener {
        /** An ESP packet arrived; the dataplane decrypts it. */
        fun onEsp(packet: ByteArray)

        /** The ePDG rekeyed the CHILD_SA; the dataplane switches to the new keys. */
        fun onChildRekeyed(child: ChildSaConfig)

        /** The session is dead; the engine reconnects. Called once. */
        fun onFailure(error: Exception)
    }

    private val transport = handshake.transport
    val evidence: TunnelEvidence = handshake.evidence
    val network: NetworkConfiguration = handshake.network

    @Volatile
    var child: ChildSaConfig = handshake.child
        private set

    /** The current SA, replaced after an IKE SA rekey. */
    @Volatile
    private var sa: IkeSa = handshake.sa

    /** The SA being retired: still answers until the ePDG deletes it. */
    @Volatile
    private var retiringSa: IkeSa? = null

    /** Our inbound SPI of a CHILD_SA the ePDG replaced; its delete is expected. */
    @Volatile
    private var retiringChildSpi: Long = 0

    @Volatile
    var listener: Listener? = null

    private val pending = ConcurrentHashMap<Long, LinkedBlockingQueue<List<Payload>>>()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    @Volatile
    private var lastInbound = System.currentTimeMillis()

    @Volatile
    private var lastKeepalive = System.currentTimeMillis()

    @Volatile
    private var dpdOutstanding = false

    private val reader = Thread(::run, "vowi-ike").apply { isDaemon = true }

    fun start() {
        reader.start()
    }

    val isOpen: Boolean get() = !closed.get() && !failed.get()

    fun sendEsp(packet: ByteArray) {
        if (closed.get()) throw IOException("the tunnel is closed")
        transport.send(packet, false)
    }

    // --- Reader ------------------------------------------------------------

    private fun run() {
        val buffer = ByteArray(65535)
        while (!closed.get()) {
            val received = try {
                transport.receiveSession(buffer, 1000)
            } catch (_: SocketTimeoutException) {
                housekeeping()
                continue
            } catch (error: Exception) {
                if (!closed.get()) fail(IOException("the tunnel socket failed: ${error.message}", error))
                return
            }
            lastInbound = System.currentTimeMillis()
            if (received == null) continue
            val (length, isIke) = received
            val packet = buffer.copyOf(length)
            if (!isIke) {
                if (length == 1 && packet[0] == 0xff.toByte()) continue // the ePDG's keepalive
                if (length >= 8) listener?.onEsp(packet)
                continue
            }
            try {
                handleIke(packet)
            } catch (error: Exception) {
                CoreLog.d(TAG, "ignored an IKE packet: ${error.message}")
            }
            housekeeping()
        }
    }

    private fun housekeeping() {
        val now = System.currentTimeMillis()
        if (now - lastKeepalive >= keepaliveMs) {
            runCatching { transport.sendKeepalive() }
            lastKeepalive = now
        }
        if (dpdEnabled && !dpdOutstanding && now - lastInbound >= dpdIdleMs) {
            dpdOutstanding = true
            Thread({
                try {
                    request(Ike.EXCHANGE_INFORMATIONAL, emptyList(), 8_000)
                    dpdOutstanding = false
                } catch (error: Exception) {
                    fail(IOException("the ePDG stopped answering dead-peer detection", error))
                }
            }, "vowi-ike-dpd").apply { isDaemon = true }.start()
        }
    }

    private fun handleIke(packet: ByteArray) {
        val (header, _) = IkeHeader.parse(packet)
        val current = sa
        val retiring = retiringSa
        val owner = when {
            current.owns(header) -> current
            retiring != null && retiring.owns(header) -> retiring
            else -> {
                // A reconnect can reuse the NAT mapping while the ePDG still has
                // packets queued for a previous SA; they are not ours.
                return
            }
        }
        if (header.isResponse) {
            val waiter = pending[header.messageId] ?: return
            val (_, payloads) = owner.decrypt(listOf(packet))
            waiter.offer(payloads)
            return
        }
        // A request from the ePDG.
        if (header.messageId == owner.lastResponseId) {
            owner.lastResponse?.forEach { transport.send(it, true) }
            return
        }
        if (header.messageId != owner.nextPeerRequestId) {
            CoreLog.d(TAG, "unexpected peer message id ${header.messageId}, expected ${owner.nextPeerRequestId}")
            return
        }
        val (requestHeader, payloads) = owner.decrypt(listOf(packet))
        val response: List<Payload> = when (requestHeader.exchange) {
            Ike.EXCHANGE_INFORMATIONAL -> informational(owner, payloads)
            Ike.EXCHANGE_CREATE_CHILD_SA -> createChildSa(owner, payloads)
            else -> listOf(IkeWire.makeNotify(Ike.NOTIFY_INVALID_SYNTAX))
        }
        val packets = owner.encrypt(owner.header(requestHeader.exchange, requestHeader.messageId, response = true), response, random)
        owner.lastResponse = packets
        owner.lastResponseId = requestHeader.messageId
        owner.nextPeerRequestId = requestHeader.messageId + 1
        for (reply in packets) transport.send(reply, true)
        afterResponse?.invoke()
        afterResponse = null
    }

    /** Work that must happen after the reply left, such as failing on a delete. */
    private var afterResponse: (() -> Unit)? = null

    private fun informational(owner: IkeSa, payloads: List<Payload>): List<Payload> {
        if (payloads.isEmpty()) return emptyList() // dead-peer detection
        val response = ArrayList<Payload>()
        for (payload in payloads) {
            if (payload.type != Ike.PAYLOAD_DELETE) continue
            val delete = IkeWire.parseDelete(payload)
            when (delete.protocol) {
                Ike.PROTOCOL_IKE -> {
                    if (owner === retiringSa) {
                        // The old SA goes away after a rekey; the new one carries on.
                        afterResponse = { retiringSa = null; CoreLog.i(TAG, "the ePDG retired the previous IKE SA") }
                    } else {
                        afterResponse = { fail(IOException("the ePDG closed the IKE SA")) }
                    }
                }
                Ike.PROTOCOL_ESP -> {
                    val ours = ArrayList<ByteArray>()
                    for (spi in delete.spis) {
                        val value = spi.u32(0)
                        if (value == retiringChildSpi) {
                            retiringChildSpi = 0
                            ours.add(u32Bytes(value))
                        } else if (value == child.outboundSpi) {
                            ours.add(u32Bytes(child.inboundSpi))
                            afterResponse = { fail(IOException("the ePDG deleted the CHILD_SA")) }
                        }
                    }
                    if (ours.isNotEmpty()) response.add(IkeWire.makeDelete(Ike.PROTOCOL_ESP, ours))
                }
            }
        }
        return response
    }

    // --- Rekeys (we are always the responder) ------------------------------

    private fun createChildSa(owner: IkeSa, payloads: List<Payload>): List<Payload> {
        return try {
            val proposals = IkeWire.parseProposals(IkeWire.onePayload(payloads, Ike.PAYLOAD_SA).body)
            when (proposals.firstOrNull()?.protocol) {
                Ike.PROTOCOL_ESP -> rekeyChild(owner, payloads, proposals)
                Ike.PROTOCOL_IKE -> rekeyIke(owner, payloads, proposals)
                else -> listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_ADDITIONAL_SAS))
            }
        } catch (error: Exception) {
            CoreLog.w(TAG, "rekey request refused: ${error.message}")
            listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_ADDITIONAL_SAS))
        }
    }

    private fun chooseTransforms(proposal: Proposal, types: IntArray): List<Transform> {
        val chosen = ArrayList<Transform>()
        for (type in types) {
            val candidates = proposal.transforms.filter { it.type == type }
            if (candidates.isEmpty()) {
                if (type == Ike.TRANSFORM_ESN || type == Ike.TRANSFORM_DH) continue
                throw IkeException("rekey proposal lacks transform type $type")
            }
            val pick = candidates.firstOrNull { supported(it) } ?: throw IkeUnsupportedSuiteException("rekey transform type $type")
            chosen.add(pick)
        }
        return chosen
    }

    private fun supported(transform: Transform): Boolean = when (transform.type) {
        Ike.TRANSFORM_ENCRYPTION -> IkeAlgorithms.supportsEncryption(transform.id, transform.keyLength)
        Ike.TRANSFORM_PRF -> IkeAlgorithms.supportsPrf(transform.id)
        Ike.TRANSFORM_INTEGRITY -> IkeAlgorithms.supportsIntegrity(transform.id)
        Ike.TRANSFORM_DH -> IkeAlgorithms.supportsDh(transform.id)
        Ike.TRANSFORM_ESN -> transform.id == 0
        else -> false
    }

    private fun rekeyChild(owner: IkeSa, payloads: List<Payload>, proposals: List<Proposal>): List<Payload> {
        val rekeyNotify = IkeWire.notifies(payloads).firstOrNull { it.type == Ike.NOTIFY_REKEY_SA }
            ?: return listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_ADDITIONAL_SAS))
        if (rekeyNotify.spi.size != 4 || rekeyNotify.spi.u32(0) != child.inboundSpi) {
            return listOf(IkeWire.makeNotify(Ike.NOTIFY_CHILD_SA_NOT_FOUND))
        }
        val proposal = proposals.first { it.protocol == Ike.PROTOCOL_ESP && it.spi.size == 4 }
        val transforms = chooseTransforms(proposal, intArrayOf(Ike.TRANSFORM_ENCRYPTION, Ike.TRANSFORM_INTEGRITY, Ike.TRANSFORM_DH, Ike.TRANSFORM_ESN))
        val peerNonce = IkeWire.onePayload(payloads, Ike.PAYLOAD_NONCE).body
        val ourNonce = random(32)
        val ourSpi = Rng.nonZero(4, random)
        val tsi = IkeWire.onePayload(payloads, Ike.PAYLOAD_TSI)
        val tsr = IkeWire.onePayload(payloads, Ike.PAYLOAD_TSR)
        val response = ArrayList<Payload>()
        val chosen = Proposal(proposal.number, Ike.PROTOCOL_ESP, ourSpi, transforms)
        response.add(Payload(Ike.PAYLOAD_SA, IkeWire.marshalProposals(listOf(chosen))))
        response.add(Payload(Ike.PAYLOAD_NONCE, ourNonce))
        var shared: ByteArray? = null
        IkeWire.payloadsOfType(payloads, Ike.PAYLOAD_KE).firstOrNull()?.let { ke ->
            val group = transforms.firstOrNull { it.type == Ike.TRANSFORM_DH }?.id ?: throw IkeException("rekey KE without a DH transform")
            val dh = DhExchange(group, random)
            shared = dh.shared(ke.body.copyOfRange(4, ke.body.size))
            response.add(Payload(Ike.PAYLOAD_KE, concat(byteArrayOf((group ushr 8).toByte(), group.toByte(), 0, 0), dh.public)))
        }
        response.add(tsi)
        response.add(tsr)
        val suite = IkeChild.parseEspSuite(chosen)
        // The ePDG initiated this rekey, so the first keys are for its direction: our inbound.
        val keys = IkeChild.deriveChildSaKeys(owner.suite, suite, owner.keys.skd, peerNonce, ourNonce, shared)
        val previous = child
        val replacement = ChildSaConfig(
            innerLocalIpv4 = previous.innerLocalIpv4,
            innerLocalIpv6 = previous.innerLocalIpv6,
            innerIpv6Prefix = previous.innerIpv6Prefix,
            pcscf = previous.pcscf,
            dns = previous.dns,
            inboundSpi = ourSpi.u32(0),
            outboundSpi = proposal.spi.u32(0),
            encryption = suite.encryptionName,
            integrity = suite.integrityName,
            inboundEncKey = keys.outboundEncryption,
            inboundAuthKey = keys.outboundIntegrity,
            outboundEncKey = keys.inboundEncryption,
            outboundAuthKey = keys.inboundIntegrity,
            initiatorSelectors = IkeChild.parseTrafficSelectors(tsr),
            responderSelectors = IkeChild.parseTrafficSelectors(tsi),
        )
        afterResponse = {
            retiringChildSpi = previous.inboundSpi
            child = replacement
            listener?.onChildRekeyed(replacement)
            CoreLog.i(TAG, "CHILD_SA rekeyed by the ePDG")
        }
        return response
    }

    private fun rekeyIke(owner: IkeSa, payloads: List<Payload>, proposals: List<Proposal>): List<Payload> {
        val proposal = proposals.first { it.protocol == Ike.PROTOCOL_IKE && it.spi.size == 8 }
        val transforms = chooseTransforms(proposal, intArrayOf(Ike.TRANSFORM_ENCRYPTION, Ike.TRANSFORM_PRF, Ike.TRANSFORM_INTEGRITY, Ike.TRANSFORM_DH))
        val ke = IkeWire.onePayload(payloads, Ike.PAYLOAD_KE)
        val group = ke.body.copyOfRange(0, 2).let { (it[0].toInt() and 0xff shl 8) or (it[1].toInt() and 0xff) }
        if (transforms.first { it.type == Ike.TRANSFORM_DH }.id != group) throw IkeException("rekey KE group differs from the proposal")
        val peerNonce = IkeWire.onePayload(payloads, Ike.PAYLOAD_NONCE).body
        val ourNonce = random(32)
        val ourSpi = Rng.nonZero(8, random)
        val dh = DhExchange(group, random)
        val shared = dh.shared(ke.body.copyOfRange(4, ke.body.size))
        val chosen = Proposal(proposal.number, Ike.PROTOCOL_IKE, ourSpi, transforms)
        val suite = IkeWire.parseIkeSuite(Proposal(chosen.number, Ike.PROTOCOL_IKE, ByteArray(0), transforms))
        // RFC 7296 2.18: SKEYSEED = prf(SK_d_old, g^ir | Ni | Nr); the ePDG is the new SA's initiator.
        val skeyseed = IkeCrypto.prf(owner.suite, owner.keys.skd, concat(shared, peerNonce, ourNonce))
        val keys = IkeCrypto.deriveIkeKeysFromSkeyseed(suite, skeyseed, peerNonce, ourNonce, proposal.spi, ourSpi)
        val replacement = IkeSa(proposal.spi, ourSpi, suite, keys, originalInitiator = false, fragmentation = owner.fragmentation)
        afterResponse = {
            retiringSa = owner
            sa = replacement
            CoreLog.i(TAG, "IKE SA rekeyed by the ePDG (${suite.encryptionName}/${suite.integrityName}/${suite.dhName})")
        }
        return listOf(
            Payload(Ike.PAYLOAD_SA, IkeWire.marshalProposals(listOf(chosen))),
            Payload(Ike.PAYLOAD_NONCE, ourNonce),
            Payload(Ike.PAYLOAD_KE, concat(byteArrayOf((group ushr 8).toByte(), group.toByte(), 0, 0), dh.public)),
        )
    }

    // --- Our own requests ----------------------------------------------------

    /** Sends an encrypted request on the current SA and waits for its response. */
    fun request(exchange: Int, payloads: List<Payload>, timeoutMs: Long): List<Payload> {
        val owner = sa
        val messageId = synchronized(owner) { owner.nextRequestId++ }
        val packets = owner.encrypt(owner.header(exchange, messageId, response = false), payloads, random)
        val queue = LinkedBlockingQueue<List<Payload>>()
        pending[messageId] = queue
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            for (interval in IkeTransport.RETRANSMIT_MS) {
                for (packet in packets) transport.send(packet, true)
                val wait = minOf(interval, deadline - System.currentTimeMillis())
                if (wait <= 0) break
                queue.poll(wait, TimeUnit.MILLISECONDS)?.let { return it }
                if (System.currentTimeMillis() >= deadline) break
            }
            throw IOException("the ePDG did not answer an IKE request")
        } finally {
            pending.remove(messageId)
        }
    }

    private fun fail(error: Exception) {
        if (!failed.compareAndSet(false, true)) return
        CoreLog.w(TAG, "tunnel failed: ${error.message}")
        closed.set(true)
        runCatching { transport.close() }
        listener?.onFailure(error)
    }

    /** Deletes the IKE SA on the ePDG (best effort) and releases the socket. */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        if (!failed.get() && !closed.get()) {
            // The reader is still running, so the delete's response can arrive.
            runCatching {
                request(Ike.EXCHANGE_INFORMATIONAL, listOf(IkeWire.makeDelete(Ike.PROTOCOL_IKE)), 1_500)
            }
        }
        closed.set(true)
        runCatching { transport.close() }
    }

    private val closing = AtomicBoolean(false)

    companion object {
        private const val TAG = "IKE"
    }
}
