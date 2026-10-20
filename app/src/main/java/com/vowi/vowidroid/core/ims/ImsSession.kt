package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.ipsec.IpsecSaConfig
import com.vowi.vowidroid.core.ipsec.TransportSecurity
import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.sim.AkaException
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sip.Digest
import com.vowi.vowidroid.core.sip.DigestChallenge
import com.vowi.vowidroid.core.sip.ProtectedReceivers
import com.vowi.vowidroid.core.sip.SecurityAgreement
import com.vowi.vowidroid.core.sip.SecurityAgreementException
import com.vowi.vowidroid.core.sip.SecurityAgreements
import com.vowi.vowidroid.core.sip.SecurityProposal
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipLink
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.sip.TcpSipLink
import com.vowi.vowidroid.core.sip.UdpSipLink
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ImsConfig(
    /** Empty picks the carrier profile's transport, then TCP. */
    val transport: String = "",
    val autoTransportFallback: Boolean = true,
    val port: Int = 5060,
    val registrationExpirySeconds: Int = 3600,
    val transactionTimeoutMs: Long = 12_000,
    val userAgent: String = ImsSupport.DEFAULT_USER_AGENT,
    /** required, optional or disabled; the carrier profile may override it. */
    val securityMode: String = SECURITY_REQUIRED,
    val protectedClientPort: Int = 0,
    val protectedServerPort: Int = 0,
    val deviceId: String = "",
) {
    companion object {
        const val SECURITY_REQUIRED = "required"
        const val SECURITY_OPTIONAL = "optional"
        const val SECURITY_DISABLED = "disabled"
    }
}

/** What a registration proved; safe to show in the UI. */
data class ImsEvidence(
    val registered: Boolean = false,
    val registrationState: String = "registering",
    val pAssociatedUri: List<String> = emptyList(),
    val registeredContact: String = "",
    val serviceRoute: List<String> = emptyList(),
    val transport: String = "",
    val lastSipCode: Int = 0,
    val securityMode: String = "none",
    val securityVerified: Boolean = false,
    val smsContactConfirmed: Boolean = false,
    val phoneNumber: String = "",
    val phoneNumberSource: String = "",
)

open class ImsException(message: String, cause: Throwable? = null) : IOException("ims: $message", cause)
class RegistrationRejectedException(message: String) : IOException("ims: SIP registration was rejected: $message")

/**
 * One IMS registration over an established tunnel: REGISTER with AKAv1-MD5
 * and ipsec-3gpp, periodic refresh, and the dispatch of network-originated
 * requests to the SMS and call modules.
 */
/** What an IMS session needs from the tunnel: its stack, its SIP protection and the P-CSCF list. */
interface ImsNetwork {
    val stack: NetStack
    val security: TransportSecurity
    val pcscf: List<String>
}

class ImsSession(
    val tunnel: ImsNetwork,
    val identity: SimIdentity,
    val profile: CarrierProfile,
    private val aka: AkaProvider,
    val config: ImsConfig = ImsConfig(),
) : AutoCloseable {
    interface Listener {
        /** A request from the network. Return true when it was answered. */
        fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean

        /** The registration is lost; the engine reconnects. */
        fun onFailure(error: Exception)
    }

    @Volatile
    var listener: Listener? = null

    val identities: ImsIdentities = ImsIdentities.derive(identity, profile)
    val instanceId: String
    val accessNetworkInfo: String = ImsSupport.accessNetworkInfo(identity, profile)
    val userAgent: String = ImsSupport.userAgent(profile, config.userAgent)
    val fromTag: String = Rng.bytes(8).toHex()
    lateinit var callId: String
        private set

    @Volatile
    var evidence = ImsEvidence()
        private set

    /** The P-CSCF address and the port the main connection talks to. */
    lateinit var pcscfAddress: ByteArray
        private set
    var pcscfPort: Int = 0
        private set
    var transport: String = "tcp"
        private set
    private lateinit var localAddress: ByteArray
    private var link: SipLink? = null
    private var receivers: ProtectedReceivers? = null

    private val securityMode: String = profile.imsIpsecMode.ifEmpty { config.securityMode }
    private var securityDeclined = false
    private var proposal: SecurityProposal? = null
    private var agreement: SecurityAgreement? = null
    private var ipsecHandle: TransportSecurity.Handle? = null

    @Volatile
    var securityActive = false
        private set

    private class AuthState(val challenge: DigestChallenge, val response: ByteArray, val auts: String, val cnonce: String, var nc: Long = 0)

    private var auth: AuthState? = null
    private val cseq = AtomicLong(1)
    private val transactions = ConcurrentHashMap<String, LinkedBlockingQueue<SipMessage>>()
    private val requestExecutor = Executors.newSingleThreadExecutor { Thread(it, "vowi-sip-requests").apply { isDaemon = true } }
    private val registerLock = Any()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    @Volatile
    private var expiresAt = 0L
    private var refreshThread: Thread? = null

    val isAtt: Boolean get() = profile.imsRegisterProfile == CarrierProfiles.IMS_ATT

    init {
        val uuid = ImsSupport.stableInstanceUuid(identity, config.deviceId)
        instanceId = if (profile.registerOptions.contactFormat == CarrierProfiles.CONTACT_GSMA) ImsSupport.sipInstanceId(identity, uuid) else "urn:uuid:$uuid"
    }

    // --- Connection ----------------------------------------------------------

    /** Registers with the first P-CSCF that answers, over the preferred transport first. */
    fun connect() {
        val candidates = tunnel.pcscf
        if (candidates.isEmpty()) throw ImsException("tunnel did not provide a P-CSCF")
        var lastError: Exception? = null
        for (candidate in candidates) {
            val endpoint = try {
                ImsSupport.parsePcscf(candidate, config.port)
            } catch (error: IllegalArgumentException) {
                lastError = ImsException(error.message ?: "invalid P-CSCF")
                continue
            }
            val address = runCatching { InetAddress.getByName(endpoint.host).address }.getOrNull() ?: continue
            var preferred = profile.imsTransport.ifEmpty { config.transport }
            if (preferred.isEmpty()) preferred = endpoint.transportHint
            if (preferred.isEmpty()) preferred = "tcp"
            val transports = if (config.autoTransportFallback) listOf(preferred, if (preferred == "udp") "tcp" else "udp") else listOf(preferred)
            for ((attempt, candidateTransport) in transports.withIndex()) {
                try {
                    open(address, endpoint.port, candidateTransport)
                    establish()
                    if (attempt > 0) CoreLog.i(TAG, "registered after falling back to $candidateTransport")
                    return
                } catch (error: Exception) {
                    val responseSeen = evidence.lastSipCode != 0
                    abort()
                    lastError = error
                    if (responseSeen || closed.get()) throw error
                    CoreLog.w(TAG, "no SIP answer from ${endpoint.host} over $candidateTransport: ${error.message}")
                }
            }
        }
        throw lastError ?: ImsException("no P-CSCF could be reached")
    }

    private fun open(address: ByteArray, port: Int, candidateTransport: String) {
        pcscfAddress = address
        pcscfPort = port
        transport = candidateTransport
        localAddress = tunnel.stack.localAddressFor(address)
        callId = Rng.bytes(18).toHex() + "@" + IpPacket.host(localAddress)
        evidence = ImsEvidence(transport = candidateTransport)
        if (securityMode != ImsConfig.SECURITY_DISABLED) {
            var clientPort = config.protectedClientPort
            var serverPort = config.protectedServerPort
            if (isAtt && serverPort == 0) serverPort = 6000
            if (profile.imsIpsecEncryption == "null") {
                if (clientPort == 0) clientPort = 5062
                if (serverPort == 0) serverPort = 5063
            }
            if (clientPort == 0) clientPort = SecurityProposal.randomProtectedPort(0) { true }
            val reserved = ProtectedReceivers.open(tunnel.stack, localAddress, address, serverPort, clientPort)
            receivers = reserved
            require(clientPort != reserved.port) { "protected ports must differ" }
            val spiClient = SecurityProposal.randomSpi()
            proposal = SecurityProposal(
                spiClient = spiClient,
                spiServer = SecurityProposal.randomSpi(spiClient),
                portClient = clientPort,
                portServer = reserved.port,
                integrityAlgorithms = if (isAtt) listOf("hmac-sha-1-96") else listOf("hmac-md5-96", "hmac-sha-1-96"),
                encryptionAlgorithms = if (isAtt) listOf("aes-cbc") else listOf("null", "des-ede3-cbc", "aes-cbc"),
                singleMechanism = isAtt,
            )
        }
        link = dial(0, port).also { start(it) }
    }

    private fun dial(localPort: Int, remotePort: Int): SipLink = when (transport) {
        "tcp" -> TcpSipLink(tunnel.stack.connectTcp(pcscfAddress, remotePort, localPort, config.transactionTimeoutMs))
        else -> UdpSipLink(tunnel.stack.openUdp(localPort, if (pcscfAddress.size == 4) 4 else 6), pcscfAddress, remotePort)
    }

    private fun start(target: SipLink) {
        target.start(::dispatch) { error ->
            if (!closed.get() && link === target) fail(ImsException("the SIP connection ended: ${error?.message ?: "closed by the P-CSCF"}"))
        }
    }

    private fun establish() {
        val response = synchronized(registerLock) { register(config.registrationExpirySeconds) }
        if (response.statusCode != 200) {
            evidence = evidence.copy(registrationState = "rejected")
            throw rejection(response, if (auth != null) "authenticated" else "initial")
        }
        synchronized(registerLock) { applyRegistrationEvidence(response) }
        receivers?.start(::dispatch)
        refreshThread = Thread(::refreshLoop, "vowi-ims-refresh").apply { isDaemon = true; start() }
    }

    private fun abort() {
        runCatching { link?.close() }
        link = null
        runCatching { receivers?.close() }
        receivers = null
        runCatching { ipsecHandle?.close() }
        ipsecHandle = null
        securityActive = false
        agreement = null
        proposal = null
        securityDeclined = false
        auth = null
    }

    // --- REGISTER -----------------------------------------------------------

    private fun register(requestedExpires: Int): SipMessage {
        var expires = requestedExpires
        if (profile.registerOptions.expirySeconds != 0 && expires > 0) expires = profile.registerOptions.expirySeconds
        var intervalRetries = 0
        var challenges = 0
        while (challenges <= MAX_AUTHENTICATION_CHALLENGES) {
            val sequence = cseq.getAndIncrement()
            var authorization = ""
            var authorizationHeader = ""
            auth?.let { state ->
                state.nc++
                authorization = Digest.authorization(
                    state.challenge,
                    Digest.Credentials(identities.privateIdentity, state.response, state.auts, "sip:${identities.domain}", "REGISTER", state.cnonce, state.nc),
                )
                authorizationHeader = if (state.challenge.proxy) "Proxy-Authorization" else "Authorization"
            }
            val request = buildRegister(sequence, expires, authorizationHeader, authorization)
            val response = transaction(request, "$callId:$sequence:REGISTER")
            evidence = evidence.copy(lastSipCode = response.statusCode)
            if (response.statusCode == 423 && expires > 0) {
                val minimum = response.items("Min-Expires").firstNotNullOfOrNull { it.trim().toIntOrNull()?.takeIf { value -> value > 0 } }
                    ?: throw ImsException("missing or invalid Min-Expires header in SIP 423 response")
                if (minimum <= expires) throw ImsException("SIP 423 Min-Expires $minimum does not exceed requested Expires $expires")
                if (intervalRetries >= MAX_INTERVAL_RETRIES) throw ImsException("too many SIP registration interval retries")
                expires = minimum
                intervalRetries++
                continue
            }
            if (response.statusCode != 401 && response.statusCode != 407) return response
            challenges++
            if (challenges > MAX_AUTHENTICATION_CHALLENGES) break
            if (securityActive) throw ImsException("protected registration was challenged again")
            val challenge = challengeFrom(response)
            val (security, useSecurity) = securityFrom(response)
            val akaChallenge = Digest.challengeFromNonce(challenge.nonce)
            val result = try {
                aka.authenticate(identity, akaChallenge)
            } catch (error: AkaException) {
                throw ImsException("${if (isAtt) "ISIM" else "USIM"} AKA authentication failed: ${error.message}", error)
            }
            val auts = result.auts
            if (auts != null) {
                if (auts.size != 14) throw ImsException("USIM returned malformed AKA synchronization evidence")
                auth = AuthState(challenge, ByteArray(0), Base64.getEncoder().encodeToString(auts), Rng.bytes(16).toHex())
                continue
            }
            if (result.res.size !in 4..16) throw ImsException("USIM returned an invalid RES length")
            if (useSecurity && security != null) activateIpsec(security, result.ck, result.ik)
            auth = AuthState(challenge, result.res.copyOf(), "", Rng.bytes(16).toHex())
        }
        throw ImsException("too many SIP authentication challenges")
    }

    private fun challengeFrom(response: SipMessage): DigestChallenge {
        val header = if (response.statusCode == 407) "Proxy-Authenticate" else "WWW-Authenticate"
        var lastError: Exception? = null
        for (value in response.values(header)) {
            try {
                return Digest.parseChallenge(value, response.statusCode == 407)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw ImsException(lastError?.message ?: "authentication response omitted a digest challenge")
    }

    private val securityOffered: Boolean get() = !securityDeclined && securityMode != ImsConfig.SECURITY_DISABLED

    private fun securityFrom(response: SipMessage): Pair<SecurityAgreement?, Boolean> {
        if (!securityOffered) return null to false
        val values = response.values("Security-Server")
        if (SipMessage.splitHeaderValues(values).isEmpty()) {
            if (securityMode == ImsConfig.SECURITY_REQUIRED) throw ImsException("a supported ipsec-3gpp security agreement is required")
            declineSecurity()
            return null to false
        }
        return try {
            SecurityAgreements.parse(values, proposal!!) to true
        } catch (error: SecurityAgreementException) {
            throw ImsException(error.message ?: "security agreement failed", error)
        }
    }

    private fun declineSecurity() {
        securityDeclined = true
        runCatching { receivers?.close() }
        receivers = null
        proposal = null
    }

    /** Installs the negotiated SAs and reconnects from the protected client port. */
    private fun activateIpsec(security: SecurityAgreement, ck: ByteArray, ik: ByteArray) {
        if (securityActive) throw ImsException("ipsec-3gpp is already active")
        val offer = proposal ?: throw ImsException("a supported ipsec-3gpp security agreement is required")
        val selected = security.selected
        val (encryptionKey, integrityKey) = SecurityAgreements.expandKeys(ck, ik, selected.encryption, selected.algorithm)
        val handle = tunnel.security.install(
            IpsecSaConfig(
                localIp = localAddress,
                remoteIp = pcscfAddress,
                integrityAlgorithm = selected.algorithm,
                encryptionAlgorithm = selected.encryption,
                ueClientSpi = offer.spiClient,
                ueServerSpi = offer.spiServer,
                pcscfClientSpi = selected.spiClient,
                pcscfServerSpi = selected.spiServer,
                ueClientPort = offer.portClient,
                ueServerPort = offer.portServer,
                pcscfClientPort = selected.portClient,
                pcscfServerPort = selected.portServer,
                encryptionKey = encryptionKey,
                integrityKey = integrityKey,
            ),
        )
        encryptionKey.fill(0)
        integrityKey.fill(0)
        runCatching { link?.close() }
        link = null
        val protectedLink = try {
            dial(offer.portClient, selected.portServer)
        } catch (error: Exception) {
            runCatching { handle.close() }
            throw ImsException("connect protected P-CSCF: ${error.message}", error)
        }
        link = protectedLink
        start(protectedLink)
        pcscfPort = selected.portServer
        agreement = security
        ipsecHandle = handle
        securityActive = true
        CoreLog.i(TAG, "ipsec-3gpp active (${selected.algorithm}/${selected.encryption}) over $transport")
    }

    /** The address the registrar knows this UE by: the protected server port when ipsec-3gpp is offered. */
    val contactAddress: String
        get() = if (securityOffered && proposal != null) IpPacket.hostPort(localAddress, proposal!!.portServer) else link!!.localHostPort

    private fun buildRegister(sequence: Long, expires: Int, authorizationHeader: String, authorization: String): ByteArray {
        val options = profile.registerOptions
        val current = link ?: throw ImsException("no SIP connection")
        val builder = SipBuilder.request("REGISTER", "sip:${identities.domain}")
            .header("Via", "SIP/2.0/${transport.uppercase()} ${current.localHostPort};branch=z9hG4bK${Rng.bytes(12).toHex()};rport")
            .header("Max-Forwards", "70")
            .header("Route", "<sip:${IpPacket.hostPort(pcscfAddress, pcscfPort)};transport=$transport;lr>")
            .header("From", "<${identities.publicIdentity}>;tag=$fromTag")
            .header("To", "<${identities.publicIdentity}>")
            .header("Call-ID", callId)
            .header("CSeq", "$sequence REGISTER")
            .header("Contact", buildContact())
            .header("Expires", expires.toString())
            .header("Supported", options.supportedHeader ?: "path, gruu")
            .header("Allow", options.allowHeader ?: "REGISTER, INVITE, ACK, CANCEL, BYE, OPTIONS, MESSAGE, SUBSCRIBE, NOTIFY")
            .header("User-Agent", userAgent)
        if (options.pPreferredIdentity) builder.header("P-Preferred-Identity", "<${identities.publicIdentity}>")
        if (options.pVisitedNetworkId.isNotBlank()) builder.header("P-Visited-Network-ID", "\"${options.pVisitedNetworkId.trim()}\"")
        if (accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", accessNetworkInfo)
        var cellularInfo = options.cellularNetworkInfo.trim()
        if (cellularInfo.isEmpty() && isAtt) {
            val mcc = identity.homeMcc.trim()
            val mnc = identity.homeMnc.trim().padStart(3, '0')
            if (mcc.isNotEmpty()) cellularInfo = "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=$mcc${mnc}0000000;cell-info-age=0"
        }
        if (cellularInfo.isNotEmpty()) builder.header("Cellular-Network-Info", cellularInfo)
        val acceptContact = options.acceptContactTags.ifEmpty { listOf("*;+g.3gpp.smsip", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"") }
        for (tag in acceptContact) builder.header("Accept-Contact", tag)
        if (securityOffered && proposal != null) {
            builder.header("Security-Client", proposal!!.headerValue())
            builder.header("Require", "sec-agree")
            builder.header("Proxy-Require", "sec-agree")
            if (securityActive) builder.header("Security-Verify", agreement!!.verifyValue)
        }
        if (authorization.isNotEmpty()) {
            var value = authorization
            if (securityOffered) value += ", integrity-protected=" + if (securityActive) "yes" else "no"
            builder.header(authorizationHeader, value)
        } else if (auth == null && (securityActive || securityOffered || (securityMode == ImsConfig.SECURITY_OPTIONAL && securityDeclined))) {
            var value = Digest.emptyAuthorization(identities.privateIdentity, identities.domain)
            if (securityActive) value = value.replace("integrity-protected=no", "integrity-protected=yes")
            builder.header("Authorization", value)
        }
        return builder.build()
    }

    private fun buildContact(): String {
        val options = profile.registerOptions
        val base = "<sip:${identities.user}@$contactAddress;transport=$transport>"
        val extra = options.contactExtraTags.joinToString("") { ";$it" }
        return when (options.contactFormat) {
            CarrierProfiles.CONTACT_ATT -> "$base$extra;audio;+g.3gpp.smsip;+g.3gpp.icsi-ref=\"${ImsSupport.CONTACT_ICSI_REF}\";+sip.instance=\"<$instanceId>\""
            CarrierProfiles.CONTACT_GSMA -> "<sip:$contactAddress>;+g.3gpp.icsi-ref=\"${ImsSupport.CONTACT_ICSI_REF}\"$extra;+sip.instance=\"<$instanceId>\""
            else -> "$base;+sip.instance=\"<$instanceId>\";+g.3gpp.smsip;audio;+g.3gpp.icsi-ref=\"${ImsSupport.CONTACT_ICSI_REF}\"$extra"
        }
    }

    private fun rejection(response: SipMessage, phase: String): RegistrationRejectedException {
        var message = "$phase REGISTER was rejected: SIP ${response.statusCode}"
        diagnostic(response.reason).takeIf { it.isNotEmpty() }?.let { message += " $it" }
        for (header in listOf("Reason", "Warning")) {
            for (value in response.values(header)) diagnostic(value).takeIf { it.isNotEmpty() }?.let { message += "; $header: $it" }
        }
        if (response.values("P-Debug-Info").any { it.lowercase().contains("no matched security item") }) message += "; carrier detail: no matched IMS security item"
        return RegistrationRejectedException(message)
    }

    private fun diagnostic(value: String): String {
        val collapsed = value.trim().split(Regex("\\s+")).joinToString(" ")
        return if (collapsed.length > 256) collapsed.substring(0, 256) + "..." else collapsed
    }

    private fun applyRegistrationEvidence(response: SipMessage) {
        if (securityMode == ImsConfig.SECURITY_REQUIRED && !securityActive) {
            evidence = evidence.copy(registered = false, registrationState = "security_failed")
            throw ImsException("a supported ipsec-3gpp security agreement is required")
        }
        val associated = response.items("P-Associated-URI")
        val contacts = response.items("Contact")
        val serviceRoutes = response.items("Service-Route")
        var registeredContact = ""
        var smsConfirmed = false
        val instanceLower = instanceId.lowercase()
        val contactUriLower = "sip:${identities.user}@$contactAddress;transport=$transport".lowercase()
        val contactAddressLower = if (profile.registerOptions.contactFormat == CarrierProfiles.CONTACT_GSMA) "sip:$contactAddress".lowercase() else ""
        for (contact in contacts) {
            val lower = contact.lowercase()
            val mine = lower.contains(instanceLower) || lower.contains(contactUriLower) || (contactAddressLower.isNotEmpty() && lower.contains(contactAddressLower))
            if (mine) {
                registeredContact = contact
                smsConfirmed = lower.contains("+g.3gpp.smsip")
                if (lower.contains(instanceLower)) break
            }
        }
        val expiry = registrationExpiry(response, registeredContact)
        if (expiry <= 0) {
            evidence = evidence.copy(registered = false, registrationState = "rejected_zero_expiry", lastSipCode = response.statusCode)
            auth = null
            throw RegistrationRejectedException("registrar granted zero expiry")
        }
        expiresAt = System.currentTimeMillis() + expiry * 1000L
        val number = ImsSupport.extractAssociatedMsisdn(associated)
        evidence = ImsEvidence(
            registered = true,
            registrationState = "registered",
            pAssociatedUri = associated,
            registeredContact = registeredContact,
            serviceRoute = serviceRoutes,
            transport = transport,
            lastSipCode = response.statusCode,
            securityMode = if (securityActive) "ipsec-3gpp" else "none",
            securityVerified = securityActive,
            smsContactConfirmed = smsConfirmed,
            phoneNumber = number?.first ?: "",
            phoneNumberSource = number?.second ?: "",
        )
        // qop=auth credentials are kept for refreshes; their nonce count advances.
        if (auth != null && !auth!!.challenge.qop.equals("auth", ignoreCase = true)) auth = null
        CoreLog.i(TAG, "registered over $transport, ${contacts.size} binding(s), expires in ${expiry}s, security ${evidence.securityMode}")
    }

    private fun registrationExpiry(response: SipMessage, contact: String): Int {
        SipMessage.parameter(contact, "expires")?.toIntOrNull()?.let { return bounded(it) }
        response.value("Expires").trim().toIntOrNull()?.let { return bounded(it) }
        return config.registrationExpirySeconds
    }

    private fun bounded(seconds: Int): Int = if (seconds <= 0) 0 else minOf(seconds, 24 * 3600)

    val smsReady: Boolean
        get() = evidence.registered && (evidence.smsContactConfirmed || profile.allowSmsWithoutContactConfirmation)

    // --- Refresh -------------------------------------------------------------

    private fun refreshLoop() {
        while (!closed.get() && evidence.registered) {
            val remaining = expiresAt - System.currentTimeMillis()
            var delay = remaining * 4 / 5
            if (remaining > 60_000 && delay > remaining - 30_000) delay = remaining - 30_000
            if (delay < 100) delay = 100
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
            if (closed.get()) return
            try {
                synchronized(registerLock) {
                    val response = register(config.registrationExpirySeconds)
                    if (response.statusCode != 200) throw ImsException("refresh registration returned SIP ${response.statusCode}")
                    applyRegistrationEvidence(response)
                }
            } catch (error: Exception) {
                evidence = evidence.copy(registered = false, registrationState = "refresh_failed", smsContactConfirmed = false)
                auth = null
                fail(ImsException("refresh registration: ${error.message}", error))
                return
            }
        }
    }

    // --- Transactions and dispatch -------------------------------------------

    /** Sends a request on the main connection and waits for its final response. */
    fun transaction(request: ByteArray, key: String, timeoutMs: Long = config.transactionTimeoutMs): SipMessage {
        val current = link ?: throw ImsException("no SIP connection")
        val queue = LinkedBlockingQueue<SipMessage>()
        if (transactions.putIfAbsent(key, queue) != null) throw ImsException("duplicate SIP transaction")
        try {
            CoreLog.d(TAG, "sending ${key.substringAfterLast(':')} (${request.size} bytes) over $transport to ${current.remoteHostPort}")
            current.send(request)
            CoreLog.d(TAG, "sent ${key.substringAfterLast(':')}")
            val deadline = System.currentTimeMillis() + timeoutMs
            var retransmit = if (transport == "udp") RETRANSMIT_T1 else 0L
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw ImsException("SIP ${key.substringAfterLast(':')} transaction timed out")
                val wait = if (retransmit > 0) minOf(retransmit, remaining) else remaining
                val response = queue.poll(wait, TimeUnit.MILLISECONDS)
                if (response == null) {
                    if (retransmit > 0) {
                        current.send(request)
                        retransmit = minOf(retransmit * 2, RETRANSMIT_MAX)
                    }
                    continue
                }
                if (response.statusCode in 100..199) {
                    retransmit = if (retransmit > 0) RETRANSMIT_MAX else 0
                    continue
                }
                return response
            }
        } finally {
            transactions.remove(key)
        }
    }

    fun nextCseq(): Long = cseq.getAndIncrement()

    private fun dispatch(message: SipMessage, respond: (ByteArray) -> Unit) {
        CoreLog.d(TAG, "received $message")
        if (message.isResponse) {
            val (sequence, method) = message.cseq() ?: return
            transactions["${message.callId}:$sequence:$method"]?.offer(message)
            return
        }
        requestExecutor.execute {
            try {
                handleRequest(message, respond)
            } catch (error: Exception) {
                CoreLog.w(TAG, "handling an inbound ${message.method} failed: ${error.message}")
            }
        }
    }

    private fun handleRequest(request: SipMessage, respond: (ByteArray) -> Unit) {
        if (request.method == "OPTIONS") {
            respond(buildResponse(request, 200))
            return
        }
        if (listener?.onRequest(this, request, respond) == true) return
        respond(buildResponse(request, 405))
    }

    /** A response that mirrors the request's dialog headers. */
    fun buildResponse(request: SipMessage, status: Int, extraHeaders: List<Pair<String, String>> = emptyList(), contentType: String? = null, body: ByteArray = ByteArray(0)): ByteArray {
        val reason = REASONS[status] ?: "Unknown"
        val vias = request.values("Via")
        var to = request.value("To")
        if (vias.isEmpty() || request.value("From").isEmpty() || to.isEmpty() || request.callId.isEmpty() || request.value("CSeq").isEmpty()) {
            throw ImsException("request omitted a mandatory response header")
        }
        if (!to.lowercase().contains(";tag=")) to += ";tag=$fromTag"
        val builder = SipBuilder.response(status, reason)
        for (via in vias) builder.header("Via", via)
        builder.header("From", request.value("From")).header("To", to).header("Call-ID", request.callId).header("CSeq", request.value("CSeq"))
        if (status == 405) builder.header("Allow", "REGISTER, INVITE, ACK, CANCEL, BYE, OPTIONS, MESSAGE")
        for ((name, value) in extraHeaders) builder.header(name, value)
        if (contentType != null) builder.body(contentType, body)
        return builder.build()
    }

    /** The RFC 3329 headers every request after a security agreement must carry. */
    fun securityHeaders(): List<Pair<String, String>> {
        val verify = agreement?.verifyValue?.trim().orEmpty()
        if (!securityActive || verify.isEmpty()) return emptyList()
        return listOf("Security-Verify" to verify, "Require" to "sec-agree", "Proxy-Require" to "sec-agree")
    }

    /** Route headers for a new request: the Service-Route set, else the P-CSCF. */
    fun routeHeaders(): List<Pair<String, String>> {
        val routes = evidence.serviceRoute
        if (routes.isEmpty()) return listOf("Route" to "<sip:${IpPacket.hostPort(pcscfAddress, pcscfPort)};transport=$transport;lr>")
        return routes.map { "Route" to it }
    }

    /** The Via value for a request on the main connection. */
    fun viaHeader(branch: String = Rng.bytes(12).toHex()): String = "SIP/2.0/${transport.uppercase()} ${link!!.localHostPort};branch=z9hG4bK$branch;rport"

    /** Sends a request without waiting (ACK, or an INVITE whose responses stream into a queue). */
    fun send(packet: ByteArray) {
        (link ?: throw ImsException("no SIP connection")).send(packet)
    }

    /** Registers a queue for a transaction's responses; the caller ends it. */
    fun beginTransaction(key: String, queue: LinkedBlockingQueue<SipMessage>) {
        if (transactions.putIfAbsent(key, queue) != null) throw ImsException("duplicate SIP transaction")
    }

    fun endTransaction(key: String) {
        transactions.remove(key)
    }

    val localHostPort: String get() = link?.localHostPort ?: IpPacket.hostPort(localAddress, 0)

    /** The inner address as a SIP host (IPv6 in brackets). */
    val localHost: String get() = IpPacket.host(localAddress)
    val localIp: ByteArray get() = localAddress

    // --- Lifecycle -----------------------------------------------------------

    private fun fail(error: Exception) {
        if (!failed.compareAndSet(false, true)) return
        CoreLog.w(TAG, "IMS session failed: ${error.message}")
        listener?.onFailure(error)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        refreshThread?.interrupt()
        if (evidence.registered && !failed.get()) {
            try {
                synchronized(registerLock) {
                    val response = register(0)
                    if (response.statusCode != 200) CoreLog.w(TAG, "SIP deregistration returned ${response.statusCode}")
                }
            } catch (error: Exception) {
                CoreLog.w(TAG, "SIP deregistration failed: ${error.message}")
            }
        }
        evidence = evidence.copy(registered = false, registrationState = "closed", smsContactConfirmed = false)
        auth = null
        abort()
        requestExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "IMS"
        private const val MAX_AUTHENTICATION_CHALLENGES = 3
        private const val MAX_INTERVAL_RETRIES = 2
        private const val RETRANSMIT_T1 = 500L
        private const val RETRANSMIT_MAX = 4_000L
        val REASONS = mapOf(
            100 to "Trying", 180 to "Ringing", 183 to "Session Progress", 200 to "OK", 202 to "Accepted",
            400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 415 to "Unsupported Media Type",
            480 to "Temporarily Unavailable", 481 to "Call/Transaction Does Not Exist", 486 to "Busy Here", 487 to "Request Terminated",
            488 to "Not Acceptable Here", 491 to "Request Pending", 500 to "Server Internal Error", 603 to "Decline",
        )
    }
}
