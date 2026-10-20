package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

/** What one tunnel attempt needs. */
class TunnelParams(
    val identity: SimIdentity,
    val profile: CarrierProfile,
    /** The ePDG hostname, used as the expected certificate name. */
    val epdg: String,
    val aka: AkaProvider,
    val apn: String = "ims",
    val imei: String = "",
    val forceLegacy: Boolean = false,
    val timeoutMs: Long = 12_000,
    /** The app's hook for `Network.bindSocket`. */
    val bindSocket: ((DatagramSocket) -> Unit)? = null,
    val random: RandomSource = Rng::bytes,
    /** Send the permanent identity in the clear although the profile asks for privacy, e.g. after the ePDG refused it. */
    val plainIdentity: Boolean = false,
) {
    fun withPlainIdentity(): TunnelParams = TunnelParams(identity, profile, epdg, aka, apn, imei, forceLegacy, timeoutMs, bindSocket, random, plainIdentity = true)
}

/** Which proposal set an attempt offers: the carrier bundle's exact one, or VowiDroid's own modern or legacy sets. */
enum class OfferKind(val label: String) { EXACT("exact"), MODERN("modern"), LEGACY("legacy") }

/** Facts about an established tunnel that are safe to show in the UI. */
data class TunnelEvidence(
    val epdgAddress: String,
    val localIpv4: String,
    val localIpv6: String,
    val pcscf: List<String>,
    val dns: List<String>,
    val responderAuth: ResponderAuthStatus,
    val ikeEncryption: String,
    val ikeIntegrity: String,
    val ikeDhGroup: String,
    val espEncryption: String,
    val espIntegrity: String,
    val encapsulated: Boolean,
    /** "exact" when the carrier bundle's proposal was accepted, else "modern" or "legacy". */
    val ikeOffer: String = "",
    /** How the subscriber was named: "permanent", "anonymous" or "anonymous+encrypted", with the identity the keys came from. */
    val identity: String = "",
)

/** The outcome of a successful handshake; the caller turns it into a running session. */
class HandshakeResult(
    val sa: IkeSa,
    val transport: IkeTransport,
    val child: ChildSaConfig,
    val network: NetworkConfiguration,
    val evidence: TunnelEvidence,
)

class NoProposalChosenException : IkeException("ike: the ePDG reported NO_PROPOSAL_CHOSEN")
class InvalidKePayloadException(val group: Int) : IkeException(if (group != 0) "ike: the ePDG requires DH group $group" else "ike: the ePDG reported INVALID_KE_PAYLOAD")

/**
 * The IKEv2 initiator for one ePDG address: IKE_SA_INIT, then IKE_AUTH with
 * EAP-AKA against the SIM, then the first CHILD_SA. It sends its payloads in
 * the order that worked on the carrier, and it never logs keys or identities.
 */
object IkeHandshake {
    private const val TAG = "IKE"
    private const val MAX_COOKIE_CHALLENGES = 2
    private const val MAX_EAP_ROUNDS = 10

    /**
     * The offers to try in turn: the carrier bundle's exact proposal when the
     * profile carries one, then the profile's own set, and for carriers without
     * a profile the legacy set after the modern one.
     */
    fun offerChain(params: TunnelParams): List<OfferKind> {
        if (params.forceLegacy) return listOf(OfferKind.LEGACY)
        val chain = ArrayList<OfferKind>()
        if (params.profile.hasExactProposal) chain.add(OfferKind.EXACT)
        if (params.profile.legacyIke) {
            chain.add(OfferKind.LEGACY)
        } else {
            chain.add(OfferKind.MODERN)
            if (params.profile.id == CarrierProfiles.STANDARD) chain.add(OfferKind.LEGACY)
        }
        return chain
    }

    fun run(transport: IkeTransport, params: TunnelParams, offer: OfferKind = offerChain(params).first()): HandshakeResult {
        val random = params.random
        val aka = EapAka.Client(params.identity, params.profile, params.aka, EapIdentities.forProfile(params.identity, params.profile, plainOnly = params.plainIdentity))
        val legacy = offer == OfferKind.LEGACY
        val ikeSpec = if (offer == OfferKind.EXACT) params.profile.ikeExactProposal else null
        val espSpec = if (offer == OfferKind.EXACT) params.profile.espExactProposal else null
        val group = ikeSpec?.dhId ?: if (legacy) Ike.DH_MODP1024 else Ike.DH_MODP2048
        val dh = DhExchange(group, random)
        val initiatorSpi = Rng.nonZero(8, random)
        val initiatorNonce = random(32)

        // --- IKE_SA_INIT -----------------------------------------------------
        val keBody = concat(byteArrayOf((group ushr 8).toByte(), group.toByte(), 0, 0), dh.public)
        val local = transport.localAddress
        val remote = transport.remoteAddress
        // The source hash is deliberately wrong, like strongSwan's encap=yes: the ePDG
        // then assumes a NAT and UDP-encapsulates ESP, which is the only form this
        // dataplane can carry.
        val sourceHash = random(20)
        val destinationHash = IkeCrypto.natDetectionHash(initiatorSpi, IkeHeader.ZERO_SPI, remote.address, remote.port)
        val ikeOffer = ikeSpec?.let(IkeOffers::ikeOffer) ?: IkeOffers.ikeOffer(group, legacy)
        CoreLog.i(TAG, "IKE_SA_INIT with the ${offer.label} offer" + (ikeSpec?.let { ": $it" } ?: ""))
        val initPayloads = listOf(
            Payload(Ike.PAYLOAD_SA, IkeWire.marshalProposals(listOf(ikeOffer))),
            Payload(Ike.PAYLOAD_KE, keBody),
            Payload(Ike.PAYLOAD_NONCE, initiatorNonce),
            IkeWire.makeNotify(Ike.NOTIFY_NAT_DETECTION_SOURCE_IP, sourceHash),
            IkeWire.makeNotify(Ike.NOTIFY_NAT_DETECTION_DESTINATION_IP, destinationHash),
            IkeWire.makeNotify(Ike.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED),
        )
        var initRequest = ByteArray(0)
        var initResponse = ByteArray(0)
        var responseHeader: IkeHeader? = null
        var initResponsePayloads: List<Payload> = emptyList()
        var cookie: ByteArray? = null
        for (attempt in 0 until MAX_COOKIE_CHALLENGES) {
            val requestPayloads = if (cookie != null) listOf(IkeWire.makeNotify(Ike.NOTIFY_COOKIE, cookie)) + initPayloads else initPayloads
            val (first, body) = IkeWire.marshalPayloadChain(requestPayloads)
            initRequest = IkeHeader(initiatorSpi, IkeHeader.ZERO_SPI, first, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_INITIATOR, messageId = 0).marshal(body)
            initResponse = transport.roundTrip(listOf(initRequest), params.timeoutMs) { header ->
                header.initiatorSpi.contentEquals(initiatorSpi) && header.isResponse && header.exchange == Ike.EXCHANGE_SA_INIT && header.messageId == 0L
            }.single()
            val (header, responseBody) = validateResponse(initResponse, initiatorSpi, null, Ike.EXCHANGE_SA_INIT, 0)
            responseHeader = header
            initResponsePayloads = IkeWire.parsePayloadChain(header.nextPayload, responseBody)
            rejectFatalNotifications(initResponsePayloads)
            val challenge = initCookie(initResponsePayloads)
            if (challenge != null) {
                if (attempt + 1 == MAX_COOKIE_CHALLENGES) throw IkeException("ike: the ePDG requested too many COOKIE challenges")
                cookie = challenge
                continue
            }
            if (header.responderSpi.all { it == 0.toByte() }) throw IkeException("ike: the ePDG returned a zero SPI")
            break
        }
        val initHeader = responseHeader!!
        val responderSpi = initHeader.responderSpi
        val fragmentation = IkeWire.hasNotify(initResponsePayloads, Ike.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED)
        val saPayload = IkeWire.onePayload(initResponsePayloads, Ike.PAYLOAD_SA)
        val selected = IkeWire.parseProposals(saPayload.body)
        if (selected.size != 1) throw IkeException("ike: the ePDG did not select exactly one IKE proposal")
        val ikeSuite = IkeWire.parseIkeSuite(selected[0])
        if (ikeSuite.dhId != group) throw IkeException("ike: the ePDG selected DH group ${ikeSuite.dhId} but KE used group $group")
        val responderKe = IkeWire.onePayload(initResponsePayloads, Ike.PAYLOAD_KE)
        if (responderKe.body.size < 4 || responderKe.body.u16(0) != group) throw IkeException("ike: the ePDG's KE group does not match the selected proposal")
        val sharedSecret = dh.shared(responderKe.body.copyOfRange(4, responderKe.body.size))
        val responderNonce = IkeWire.onePayload(initResponsePayloads, Ike.PAYLOAD_NONCE).body
        if (responderNonce.size < 16 || responderNonce.size > 256) throw IkeException("ike: the ePDG's nonce length is outside 16..256 bytes")
        val keys = IkeCrypto.deriveIkeKeys(ikeSuite, sharedSecret, initiatorNonce, responderNonce, initiatorSpi, responderSpi)
        val sa = IkeSa(initiatorSpi, responderSpi, ikeSuite, keys, originalInitiator = true, fragmentation = fragmentation)
        // With the forced NAT hash every path ends UDP-encapsulated.
        transport.float()
        CoreLog.i(TAG, "IKE_SA_INIT done: ${ikeSuite.encryptionName}/${ikeSuite.integrityName}/${ikeSuite.dhName}, fragmentation=$fragmentation")

        var cleanupNeeded = true
        var messageId = 1L
        try {
            // --- IKE_AUTH, first exchange -------------------------------------
            val childInboundSpi = Rng.nonZero(4, random)
            val childProposal = espSpec?.let { IkeOffers.espOffer(childInboundSpi, it) } ?: IkeOffers.espOffer(childInboundSpi, legacy)
            val childOffer = IkeWire.marshalProposals(listOf(childProposal))
            val idi = Payload(Ike.PAYLOAD_IDI, concat(byteArrayOf(Ike.ID_RFC822_ADDR.toByte(), 0, 0, 0), aka.idi))
            val requestedIdr = Payload(Ike.PAYLOAD_IDR, concat(byteArrayOf(Ike.ID_FQDN.toByte(), 0, 0, 0), params.apn.toByteArray(Charsets.US_ASCII)))
            val tsi = IkeChild.dualStackTrafficSelectors(Ike.PAYLOAD_TSI)
            val tsr = IkeChild.dualStackTrafficSelectors(Ike.PAYLOAD_TSR)
            val firstAuth = IkeOffers.initialEapAuthPayloads(idi, requestedIdr, childOffer, tsi, tsr, params.profile.advertiseEapOnly)
            var current = exchange(transport, sa, params, Ike.EXCHANGE_AUTH, messageId, firstAuth, random)
            val initial = IkeAuth.validateInitialResponderAuth(
                current, initResponse, initiatorNonce, ikeSuite, keys.skpr,
                expectedIdr = "", // Android's IWLAN accepts any remote identity.
                serverName = params.profile.epdgCertificate.hostname.ifEmpty { params.epdg },
                // Some ePDGs defer AUTH without the RFC 5998 notify; carriers whose settings validate the certificate get no such leeway.
                allowMissing = !params.profile.epdgCertificate.required,
                carrierCa = params.profile.epdgCertificate.caDer,
            )
            var responderAuth = initial.status
            var deviceIdentityPending = IkeOffers.deviceIdentityRequested(current)

            // --- EAP rounds ---------------------------------------------------
            var round = 0
            while (true) {
                rejectFatalNotifications(current)
                val eap = IkeWire.onePayload(current, Ike.PAYLOAD_EAP)
                val action = aka.handle(eap.body)
                if (action.success) break
                val response = action.response ?: throw IkeException("ike: the EAP state machine produced no response")
                if (++round > MAX_EAP_ROUNDS) throw IkeException("ike: the EAP exchange exceeded $MAX_EAP_ROUNDS IKE_AUTH rounds")
                messageId++
                val payloads = mutableListOf(Payload(Ike.PAYLOAD_EAP, response))
                if (deviceIdentityPending && responderAuth == ResponderAuthStatus.VERIFIED && params.imei.isNotEmpty()) {
                    runCatching { IkeOffers.deviceIdentityNotify(params.imei) }.getOrNull()?.let(payloads::add)
                }
                if (IkeOffers.deviceIdentityRequested(current)) deviceIdentityPending = true
                current = exchange(transport, sa, params, Ike.EXCHANGE_AUTH, messageId, payloads, random)
            }
            val akaKeys = aka.keys
            if (!aka.challengeComplete || akaKeys == null || akaKeys.msk.size != 64) throw IkeException("ike: EAP-AKA did not produce an authenticated MSK")
            if (aka.names.mode != "permanent") CoreLog.i(TAG, "EAP-AKA done with the ${aka.names.mode} identity; the keys derive from the ${aka.keyIdentity} one")

            // --- Final IKE_AUTH with the MSK-based AUTH -----------------------
            val initiatorAuth = IkeAuth.makeEapInitiatorAuth(akaKeys.msk, initRequest, responderNonce, ikeSuite, keys.skpi, idi)
            messageId++
            val finalPayloads = exchange(transport, sa, params, Ike.EXCHANGE_AUTH, messageId, listOf(initiatorAuth), random)
            rejectFatalNotifications(finalPayloads)
            val finalAuths = IkeWire.payloadsOfType(finalPayloads, Ike.PAYLOAD_AUTH)
            if (finalAuths.size != 1) throw ResponderAuthRequiredException()
            val responderId = initial.idr ?: throw IkeException("ike: the EAP exchange has no responder IDr for the AUTH transcript")
            val finalIds = IkeWire.payloadsOfType(finalPayloads, Ike.PAYLOAD_IDR)
            if (finalIds.size > 1) throw IkeException("ike: duplicate final responder IDr payload")
            finalIds.firstOrNull()?.let { IkeAuth.validateFqdnIdr(it, "", "final responder") }
            IkeAuth.verifyEapResponderAuth(finalAuths[0], akaKeys.msk, initResponse, initiatorNonce, ikeSuite, keys.skpr, responderId)
            responderAuth = ResponderAuthStatus.VERIFIED

            // --- CHILD_SA -----------------------------------------------------
            val childProposals = IkeWire.parseProposals(IkeWire.onePayload(finalPayloads, Ike.PAYLOAD_SA).body)
            if (childProposals.size != 1) throw IkeException("ike: the ePDG did not select exactly one ESP proposal")
            val childSuite = IkeChild.parseEspSuite(childProposals[0])
            val childOutboundSpi = childProposals[0].spi.u32(0)
            val initiatorSelectors = IkeChild.parseTrafficSelectors(IkeWire.onePayload(finalPayloads, Ike.PAYLOAD_TSI))
            val responderSelectors = IkeChild.parseTrafficSelectors(IkeWire.onePayload(finalPayloads, Ike.PAYLOAD_TSR))
            val network = IkeChild.parseConfiguration(IkeWire.onePayload(finalPayloads, Ike.PAYLOAD_CP))
            if (network.localIpv4 == null && network.localIpv6 == null) throw IkeException("ike: the ePDG did not assign an inner IP address")
            val pcscf = IkeChild.pcscfForAssignedFamilies(network.pcscf, network.localIpv4 != null, network.localIpv6 != null)
            if (pcscf.isEmpty()) throw IkeException("ike: the ePDG did not provide a P-CSCF matching an assigned address family")
            val childKeys = IkeChild.deriveChildSaKeys(ikeSuite, childSuite, keys.skd, initiatorNonce, responderNonce)
            val child = ChildSaConfig(
                innerLocalIpv4 = network.localIpv4,
                innerLocalIpv6 = network.localIpv6,
                innerIpv6Prefix = network.ipv6Prefix,
                pcscf = pcscf,
                dns = network.dns,
                inboundSpi = childInboundSpi.u32(0),
                outboundSpi = childOutboundSpi,
                encryption = childSuite.encryptionName,
                integrity = childSuite.integrityName,
                inboundEncKey = childKeys.inboundEncryption,
                inboundAuthKey = childKeys.inboundIntegrity,
                outboundEncKey = childKeys.outboundEncryption,
                outboundAuthKey = childKeys.outboundIntegrity,
                initiatorSelectors = initiatorSelectors,
                responderSelectors = responderSelectors,
            )
            validateRoutes(child)
            sa.nextRequestId = messageId + 1
            cleanupNeeded = false
            val evidence = TunnelEvidence(
                epdgAddress = remote.address.hostAddress ?: "",
                localIpv4 = network.localIpv4?.let(InetAddress::getByAddress)?.hostAddress ?: "",
                localIpv6 = network.localIpv6?.let(InetAddress::getByAddress)?.hostAddress ?: "",
                pcscf = pcscf.map { InetAddress.getByAddress(it).hostAddress ?: "" },
                dns = network.dns.map { InetAddress.getByAddress(it).hostAddress ?: "" },
                responderAuth = responderAuth,
                ikeEncryption = ikeSuite.encryptionName,
                ikeIntegrity = ikeSuite.integrityName,
                ikeDhGroup = ikeSuite.dhName,
                espEncryption = childSuite.encryptionName,
                espIntegrity = childSuite.integrityName,
                encapsulated = transport.encapsulated,
                ikeOffer = offer.label,
                identity = aka.names.mode + (if (aka.keyIdentity.isNotEmpty() && aka.keyIdentity != "permanent") " (keys: ${aka.keyIdentity})" else ""),
            )
            CoreLog.i(TAG, "tunnel established to ${evidence.epdgAddress}: inner ${evidence.localIpv4.ifEmpty { evidence.localIpv6 }}, P-CSCF ${evidence.pcscf}")
            CoreLog.d(TAG, "selectors: initiator ${initiatorSelectors.joinToString("; ")} | responder ${responderSelectors.joinToString("; ")}")
            return HandshakeResult(sa, transport, child, network, evidence)
        } finally {
            if (cleanupNeeded) {
                // Leave the ePDG a clean slate even when our side gave up.
                runCatching { sendDelete(transport, sa, messageId + 1, random) }
            }
        }
    }

    /** One encrypted request/response pair on the IKE SA. */
    private fun exchange(transport: IkeTransport, sa: IkeSa, params: TunnelParams, exchangeType: Int, messageId: Long, payloads: List<Payload>, random: RandomSource): List<Payload> {
        val packets = sa.encrypt(sa.header(exchangeType, messageId, response = false), payloads, random)
        val replies = transport.roundTrip(packets, params.timeoutMs) { header -> sa.isResponseTo(header, exchangeType, messageId) }
        val (header, decrypted) = sa.decrypt(replies)
        if (!sa.isResponseTo(header, exchangeType, messageId)) throw IkeUnexpectedPacketException("encrypted response header does not match the request")
        return decrypted
    }

    fun sendDelete(transport: IkeTransport, sa: IkeSa, messageId: Long, random: RandomSource = Rng::bytes) {
        val packets = sa.encrypt(sa.header(Ike.EXCHANGE_INFORMATIONAL, messageId, response = false), listOf(IkeWire.makeDelete(Ike.PROTOCOL_IKE)), random)
        for (packet in packets) transport.send(packet, true)
    }

    private fun validateResponse(packet: ByteArray, initiatorSpi: ByteArray, responderSpi: ByteArray?, exchange: Int, messageId: Long): Pair<IkeHeader, ByteArray> {
        val (header, body) = IkeHeader.parse(packet)
        if (!header.initiatorSpi.contentEquals(initiatorSpi) || (responderSpi != null && !header.responderSpi.contentEquals(responderSpi)) ||
            header.exchange != exchange || header.messageId != messageId || !header.isResponse || header.fromInitiator
        ) {
            throw IkeUnexpectedPacketException("response header does not match the request")
        }
        return header to body
    }

    private fun initCookie(payloads: List<Payload>): ByteArray? {
        var cookie: ByteArray? = null
        for (notify in IkeWire.notifies(payloads)) {
            if (notify.type != Ike.NOTIFY_COOKIE) continue
            if (notify.data.isEmpty()) throw IkeException("ike: the ePDG returned an empty COOKIE")
            if (cookie != null) throw IkeException("ike: the ePDG returned multiple COOKIE notifications")
            cookie = notify.data
        }
        return cookie
    }

    /** Error notifications end the attempt with a specific reason. */
    fun rejectFatalNotifications(payloads: List<Payload>) {
        val notifies = IkeWire.notifies(payloads)
        for (notify in notifies) {
            when (notify.type) {
                Ike.NOTIFY_NO_PROPOSAL_CHOSEN -> throw NoProposalChosenException()
                Ike.NOTIFY_INVALID_KE_PAYLOAD -> throw InvalidKePayloadException(if (notify.data.size == 2) notify.data.u16(0) else 0)
                Ike.NOTIFY_AUTHENTICATION_FAILED -> throw EapAuthenticationRejectedException(": the ePDG reported AUTHENTICATION_FAILED")
            }
            // The carrier's own reasons (TS 24.302) say whether retrying is any use.
            EpdgErrors.reason(notify.type)?.let { reason ->
                val backoff = notifies.firstOrNull { it.type == EpdgErrors.NOTIFY_BACKOFF_TIMER }?.let { EpdgErrors.backoffSeconds(it.data) }
                throw EpdgRejectedException(reason.code, reason.name, reason.policy, backoff, reason.explanation)
            }
            if (notify.type < 16384) throw IkeException("ike: the ePDG reported fatal notification ${notify.type}")
        }
    }

    /** Trying the next offer in the chain only helps for these rejections. */
    fun retryableWithAnotherOffer(error: Throwable): Boolean =
        error is NoProposalChosenException || (error is InvalidKePayloadException && (error.group == 0 || error.group == Ike.DH_MODP1024))

    private fun validateRoutes(child: ChildSaConfig) {
        fun validLocal(ip: ByteArray) = ip.any { it != 0.toByte() } && IkeChild.ipAllowedBySelectors(ip, child.initiatorSelectors)
        child.innerLocalIpv4?.let { if (!validLocal(it)) throw IkeException("ike: the assigned inner IPv4 address is outside the initiator traffic selectors") }
        child.innerLocalIpv6?.let { if (!validLocal(it)) throw IkeException("ike: the assigned inner IPv6 address is outside the initiator traffic selectors") }
        var matchingFamily = false
        for (pcscf in child.pcscf) {
            if (!IkeChild.ipAllowedBySelectors(pcscf, child.responderSelectors)) {
                throw IkeException("ike: P-CSCF ${InetAddress.getByAddress(pcscf).hostAddress} is outside the responder traffic selectors")
            }
            if ((pcscf.size == 4 && child.innerLocalIpv4 != null) || (pcscf.size == 16 && child.innerLocalIpv6 != null)) matchingFamily = true
        }
        if (!matchingFamily) throw IkeException("ike: no P-CSCF address matches an assigned inner address family")
    }

    /** Compares the ePDG's NAT detection hashes with ours (RFC 7296 2.23). */
    fun natDetected(payloads: List<Payload>, initiatorSpi: ByteArray, responderSpi: ByteArray, local: java.net.InetSocketAddress, remote: java.net.InetSocketAddress): Boolean {
        var source: ByteArray? = null
        var destination: ByteArray? = null
        for (notify in IkeWire.notifies(payloads)) {
            when (notify.type) {
                Ike.NOTIFY_NAT_DETECTION_SOURCE_IP -> source = notify.data
                Ike.NOTIFY_NAT_DETECTION_DESTINATION_IP -> destination = notify.data
            }
        }
        if (source == null && destination == null) return false
        if (source == null || destination == null || source.size != 20 || destination.size != 20) {
            throw IkeException("ike: NAT detection notification has an invalid hash length")
        }
        val expectedSource = IkeCrypto.natDetectionHash(initiatorSpi, responderSpi, remote.address, remote.port)
        val expectedDestination = IkeCrypto.natDetectionHash(initiatorSpi, responderSpi, local.address, local.port)
        return !constantTimeEquals(source, expectedSource) || !constantTimeEquals(destination, expectedDestination)
    }
}
