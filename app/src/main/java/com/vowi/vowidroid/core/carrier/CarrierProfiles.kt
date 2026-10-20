package com.vowi.vowidroid.core.carrier

import com.vowi.vowidroid.core.ike.IkeAlgorithms
import com.vowi.vowidroid.core.ike.ProposalSpec
import com.vowi.vowidroid.core.sim.SimIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Interoperability choices that cannot be discovered from the SIM or
 * negotiated with the network. Every protocol layer reads this one result so
 * carrier handling never drifts into scattered MCC/MNC checks.
 */
data class CarrierProfile(
    val id: String = CarrierProfiles.STANDARD,
    val matchSource: String = "standard",
    val imsiRewriteFrom: String = "",
    val imsiRewriteTo: String = "",
    val routeMcc: String = "",
    val routeMnc: String = "",
    val epdg: String = "",
    val ikeProposal: String = CarrierProfiles.IKE_MODERN,
    /** The IKE proposal the carrier's iPhone bundle sends; offered on its own, with [ikeProposal] as the fallback. */
    val ikeExactProposal: ProposalSpec? = null,
    /** The bundle's ESP proposal for the CHILD_SA, likewise. */
    val espExactProposal: ProposalSpec? = null,
    val tunnel: TunnelSettings = TunnelSettings(),
    /** How the subscriber is named towards the ePDG: in the clear, anonymous, or encrypted with the carrier's key. */
    val privacy: IdentityPrivacy = IdentityPrivacy(),
    /** Whether and how the ePDG proves itself with a certificate. */
    val epdgCertificate: EpdgCertificate = EpdgCertificate(),
    val advertiseEapOnly: Boolean = true,
    val allowSmsWithoutContactConfirmation: Boolean = false,
    val registerOptions: RegisterOptions = RegisterOptions(),
    val imsTransport: String = "",
    val imsIdentityProfile: String = CarrierProfiles.IMS_STANDARD,
    val imsRegisterProfile: String = CarrierProfiles.IMS_STANDARD,
    val imsIpsecMode: String = "",
    val imsIpsecEncryption: String = "aes-cbc",
    val smsCenter: String = "",
    val paniEnabled: Boolean? = null,
    val paniCountry: String = "",
    val paniNode: String = "",
    val imsUserAgent: String = "",
    val imsDialUriScheme: String = "tel",
    val imsUserEqPhone: Boolean = false,
    /** Codecs the carrier profile allows; empty means VowiDroid's own preference order. */
    val imsVoiceCodecs: List<String> = emptyList(),
) {
    /** The IMSI presented to EAP-AKA and IMS; the SIM's own IMSI is never changed. */
    fun effectiveSubscriberImsi(imsi: String): String {
        val trimmed = imsi.trim()
        return if (imsiRewriteFrom.isNotEmpty() && imsiRewriteTo.isNotEmpty() && trimmed.startsWith(imsiRewriteFrom)) {
            imsiRewriteTo + trimmed.removePrefix(imsiRewriteFrom)
        } else {
            trimmed
        }
    }

    val legacyIke: Boolean get() = ikeProposal == CarrierProfiles.IKE_LEGACY

    val hasExactProposal: Boolean get() = ikeExactProposal != null || espExactProposal != null
}

/**
 * IMSI privacy on the ePDG path, as the carrier's phone settings ask for it.
 * [anonymousIdi] sends `anonymous@realm` as the IKE identity; [encryptedEap]
 * answers the EAP identity request with the permanent username encrypted
 * under [certificateDer] (RSA-OAEP, SHA-256), the scheme Apple's patent
 * US10833876B2 and the WBA IMSI Privacy Protection describe.
 */
data class IdentityPrivacy(
    val anonymousIdi: Boolean = false,
    val encryptedEap: Boolean = false,
    val anonymousUsername: String = "anonymous",
    /** The NAI realm when the carrier names one; empty means the 3GPP one from the SIM's PLMN. */
    val realm: String = "",
    /** The carrier's X.509 certificate (DER) whose RSA key encrypts the identity. */
    val certificateDer: ByteArray? = null,
    /** Goes after the ciphertext, e.g. `CertificateSerialNumber=17197757648267303731`. */
    val keyIdentifier: String = "",
    /** Where a fresh certificate can be fetched. */
    val updateUrl: String = "",
) {
    /** The carrier asked for encryption and gave the key for it. */
    val canEncrypt: Boolean get() = encryptedEap && certificateDer != null && certificateDer.isNotEmpty()

    override fun equals(other: Any?): Boolean = other is IdentityPrivacy && other.anonymousIdi == anonymousIdi && other.encryptedEap == encryptedEap &&
        other.anonymousUsername == anonymousUsername && other.realm == realm && other.keyIdentifier == keyIdentifier && other.updateUrl == updateUrl &&
        (other.certificateDer?.contentEquals(certificateDer ?: ByteArray(0)) ?: (certificateDer == null))

    override fun hashCode(): Int = listOf(anonymousIdi, encryptedEap, anonymousUsername, realm, keyIdentifier, updateUrl, certificateDer?.contentHashCode() ?: 0).hashCode()
}

/** The ePDG's certificate, for carriers whose phone settings validate it. */
data class EpdgCertificate(
    /** The ePDG must prove itself with a certificate in the first IKE_AUTH answer. */
    val required: Boolean = false,
    /** The name the certificate must carry; empty means the ePDG hostname. */
    val hostname: String = "",
    /** The carrier's CA certificate (DER) that signs it, when it is not a public one. */
    val caDer: ByteArray? = null,
    val authorityName: String = "",
) {
    override fun equals(other: Any?): Boolean = other is EpdgCertificate && other.required == required && other.hostname == hostname &&
        other.authorityName == authorityName && (other.caDer?.contentEquals(caDer ?: ByteArray(0)) ?: (caDer == null))

    override fun hashCode(): Int = listOf(required, hostname, authorityName, caDer?.contentHashCode() ?: 0).hashCode()
}

/** Tunnel settings from the carrier bundle; 0 or null means VowiDroid's own default. */
data class TunnelSettings(
    /** The inner MTU the bundle sets (`IPSecMTU`). */
    val ipsecMtu: Int = 0,
    /** The NAT-T keepalive interval (`NATTKeepAliveInterval`). */
    val nattKeepaliveSeconds: Int = 0,
    /** Whether the phone runs its own dead-peer detection (`DeadPeerDetectionEnabled`). */
    val dpdEnabled: Boolean? = null,
)

/** Carrier-specific SIP REGISTER header values. Null means "use the default"; "" means "omit". */
data class RegisterOptions(
    val contactFormat: String = "",
    val expirySeconds: Int = 0,
    val contactExtraTags: List<String> = emptyList(),
    val supportedHeader: String? = null,
    val allowHeader: String? = null,
    val pPreferredIdentity: Boolean = false,
    val pVisitedNetworkId: String = "",
    val pAccessNetworkInfo: String? = null,
    val cellularNetworkInfo: String = "",
    val acceptContactTags: List<String> = emptyList(),
)

object CarrierProfiles {
    const val SCHEMA_VERSION = 1
    const val STANDARD = "standard-3gpp"
    const val IKE_MODERN = "modern"
    const val IKE_LEGACY = "legacy-sha1-modp1024"
    const val IMS_STANDARD = "standard"
    const val IMS_O2_GERMANY = "o2-germany"
    const val IMS_ATT = "att"
    const val CONTACT_STANDARD = "standard"
    const val CONTACT_ATT = "att"
    const val CONTACT_GSMA = "gsma"

    private class Match(
        val homePlmns: List<String>,
        val imsiPrefixes: List<String>,
        val iccidPrefixes: List<String>,
        val spns: List<String>,
        val gid1Prefixes: List<String>,
        val gid2Prefixes: List<String>,
    ) {
        val empty: Boolean
            get() = homePlmns.isEmpty() && imsiPrefixes.isEmpty() && iccidPrefixes.isEmpty() &&
                spns.isEmpty() && gid1Prefixes.isEmpty() && gid2Prefixes.isEmpty()
    }

    private class Rule(
        val id: String,
        val matches: List<Match>,
        val rewriteFrom: String,
        val rewriteTo: String,
        val routeMcc: String,
        val routeMnc: String,
        val epdgHostname: String,
        val dnsHosts: List<String>,
        val dnsClientSubnet: String,
        val ikeProposal: String,
        val ikeExact: ProposalSpec?,
        val espExact: ProposalSpec?,
        val tunnel: TunnelSettings,
        val privacy: IdentityPrivacy,
        val epdgCertificate: EpdgCertificate,
        val advertiseEapOnly: Boolean?,
        val ims: JsonObject,
    )

    /** A refreshed IMSI-encryption certificate for a profile, when one was fetched after the bundle's. */
    @Volatile
    var identityCertificateOverride: (String) -> ByteArray? = { null }

    @Volatile
    private var rules: List<Rule> = emptyList()

    @Volatile
    private var loader: (() -> InputStream)? = null

    /** Points the profile set at its JSON source; it is parsed on first use. */
    fun install(source: () -> InputStream) {
        loader = source
        rules = emptyList()
        loaded = false
    }

    @Volatile
    private var loaded = false

    private fun snapshot(): List<Rule> {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    rules = loader?.invoke()?.use { parse(it) } ?: emptyList()
                    loaded = true
                }
            }
        }
        return rules
    }

    val count: Int get() = snapshot().size

    private fun parse(stream: InputStream): List<Rule> {
        val document = JsonParser.parseReader(InputStreamReader(stream, Charsets.UTF_8)).asJsonObject
        val version = document.get("version")?.asInt ?: 0
        require(version == SCHEMA_VERSION) { "unsupported carrier profile version $version" }
        val seen = HashSet<String>()
        val result = ArrayList<Rule>()
        for (element in document.getAsJsonArray("profiles") ?: JsonArray()) {
            val item = element.asJsonObject
            val id = item.string("id")
            if (id.isEmpty() || !seen.add(id)) continue
            val matches = ArrayList<Match>()
            item.obj("match")?.let { parseMatch(it) }?.takeIf { !it.empty }?.let(matches::add)
            item.getAsJsonArray("match_any")?.forEach { candidate -> matches.add(parseMatch(candidate.asJsonObject)) }
            if (matches.isEmpty() || matches.any { it.empty || !validMatch(it) }) continue
            val identity = item.obj("identity")?.obj("subscriber_imsi_rewrite")
            val route = item.obj("route")
            val epdg = item.obj("epdg")
            val ike = item.obj("ike")
            val rule = Rule(
                id = id,
                matches = matches,
                rewriteFrom = identity?.string("from_prefix").orEmpty(),
                rewriteTo = identity?.string("to_prefix").orEmpty(),
                routeMcc = route?.string("mcc").orEmpty(),
                routeMnc = route?.string("mnc").orEmpty(),
                epdgHostname = epdg?.string("hostname").orEmpty().lowercase(),
                dnsHosts = epdg?.strings("dns_hosts").orEmpty(),
                dnsClientSubnet = epdg?.string("dns_client_subnet").orEmpty(),
                ikeProposal = ike?.string("proposal").orEmpty(),
                ikeExact = parseProposal(ike?.obj("exact_proposal"), ike = true),
                espExact = parseProposal(item.obj("esp")?.obj("exact_proposal"), ike = false),
                tunnel = parseTunnel(item.obj("tunnel")),
                privacy = parsePrivacy(item.obj("privacy")),
                epdgCertificate = parseEpdgCertificate(epdg?.obj("certificate")),
                advertiseEapOnly = ike?.get("advertise_eap_only")?.takeIf { it.isJsonPrimitive }?.asBoolean,
                ims = item.obj("ims") ?: JsonObject(),
            )
            if (validRule(rule)) result.add(rule)
        }
        return result
    }

    private fun parseMatch(item: JsonObject) = Match(
        homePlmns = item.strings("home_plmns"),
        imsiPrefixes = item.strings("imsi_prefixes"),
        iccidPrefixes = item.strings("iccid_prefixes"),
        spns = item.strings("spns"),
        gid1Prefixes = item.strings("gid1_prefixes"),
        gid2Prefixes = item.strings("gid2_prefixes"),
    )

    /**
     * An exact proposal from the importer. Anything this stack cannot send
     * reads as "no exact proposal", so the carrier keeps today's offers rather
     * than losing its whole profile.
     */
    private fun parseProposal(item: JsonObject?, ike: Boolean): ProposalSpec? {
        if (item == null) return null
        val encryption = item.string("encryption").lowercase()
        val bits = item.int("key_bits")
        val integrity = IkeAlgorithms.integrityId(item.string("integrity"))
        val prf = if (ike) IkeAlgorithms.prfId(item.string("prf")) else 0
        val dh = item.int("dh_group")
        val lifetime = item.int("lifetime_seconds")
        if (encryption != IkeAlgorithms.ENCRYPTION_NAME || bits !in IkeAlgorithms.encryptionBits || integrity == 0 || lifetime < 0) return null
        if (ike && (prf == 0 || !IkeAlgorithms.supportsDh(dh))) return null
        if (!ike && dh != 0 && !IkeAlgorithms.supportsDh(dh)) return null
        return ProposalSpec(encryptionBits = bits, integrityId = integrity, prfId = prf, dhId = dh, lifetimeSeconds = lifetime)
    }

    private fun parsePrivacy(item: JsonObject?): IdentityPrivacy {
        if (item == null) return IdentityPrivacy()
        val certificate = item.string("encryption_certificate").takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        return IdentityPrivacy(
            anonymousIdi = item.string("idi").equals("anonymous", ignoreCase = true),
            encryptedEap = item.string("eap").equals("encrypted", ignoreCase = true),
            anonymousUsername = item.string("anonymous_username").ifEmpty { "anonymous" },
            realm = item.string("realm").trimStart('@').lowercase(),
            certificateDer = certificate,
            keyIdentifier = item.string("key_identifier"),
            updateUrl = item.string("update_url"),
        )
    }

    private fun parseEpdgCertificate(item: JsonObject?): EpdgCertificate {
        if (item == null) return EpdgCertificate()
        val ca = item.string("ca").takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        return EpdgCertificate(
            required = item.get("required")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false,
            hostname = item.string("hostname").lowercase(),
            caDer = ca,
            authorityName = item.string("authority"),
        )
    }

    private fun parseTunnel(item: JsonObject?): TunnelSettings {
        if (item == null) return TunnelSettings()
        return TunnelSettings(
            ipsecMtu = item.int("ipsec_mtu").takeIf { it in 576..1500 } ?: 0,
            nattKeepaliveSeconds = item.int("natt_keepalive_seconds").takeIf { it in 1..600 } ?: 0,
            dpdEnabled = item.get("dpd_enabled")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean,
        )
    }

    private fun validMatch(match: Match): Boolean {
        if (match.homePlmns.any { canonicalPlmnValue(it).isEmpty() }) return false
        if (match.imsiPrefixes.any { it.length !in 5..18 || !decimal(it) }) return false
        if (match.iccidPrefixes.any { it.length !in 5..22 || !decimal(it) }) return false
        if ((match.gid1Prefixes + match.gid2Prefixes).any { it.isEmpty() || it.length > 64 || !hex(it) }) return false
        if (match.spns.any { it.isBlank() || it.length > 128 }) return false
        return true
    }

    private fun validRule(rule: Rule): Boolean {
        if (rule.routeMcc.isEmpty() != rule.routeMnc.isEmpty()) return false
        if (rule.routeMcc.isNotEmpty() && canonicalPlmn(rule.routeMcc, rule.routeMnc).isEmpty()) return false
        if (rule.rewriteFrom.isEmpty() != rule.rewriteTo.isEmpty()) return false
        if (rule.rewriteFrom.isNotEmpty() &&
            (rule.rewriteFrom.length !in 5..15 || rule.rewriteFrom.length != rule.rewriteTo.length ||
                !decimal(rule.rewriteFrom) || !decimal(rule.rewriteTo))
        ) return false
        if (rule.ikeProposal.isNotEmpty() && rule.ikeProposal != IKE_MODERN && rule.ikeProposal != IKE_LEGACY) return false
        val transport = rule.ims.string("transport").lowercase()
        if (transport.isNotEmpty() && transport != "tcp" && transport != "udp") return false
        val encryption = rule.ims.string("ipsec_encryption").lowercase()
        if (encryption.isNotEmpty() && encryption != "aes-cbc" && encryption != "null") return false
        val mode = rule.ims.string("ipsec_mode").lowercase()
        if (mode.isNotEmpty() && mode != "required" && mode != "optional" && mode != "disabled") return false
        val scheme = rule.ims.string("dial_uri_scheme").lowercase()
        if (scheme.isNotEmpty() && scheme != "tel" && scheme != "sip") return false
        if (rule.ims.strings("voice_codecs").any { it.uppercase() !in setOf("PCMA", "PCMU", "AMR", "AMR-WB") }) return false
        return true
    }

    /** The most specific matching profile; exact SIM attributes outrank broad PLMN rules. */
    fun resolve(identity: SimIdentity): CarrierProfile {
        var resolved = CarrierProfile()
        var bestScore = -1
        for (rule in snapshot()) {
            var ruleScore = -1
            var ruleSource = ""
            for (match in rule.matches) {
                val (score, source) = matchProfile(match, identity) ?: continue
                if (score > ruleScore) {
                    ruleScore = score
                    ruleSource = source
                }
            }
            if (ruleScore < 0 || ruleScore < bestScore) continue
            bestScore = ruleScore
            resolved = apply(rule, ruleSource, identity)
        }
        if (resolved.smsCenter.isEmpty()) {
            // The bundles carry no SMS centre; the hand-kept table stands in, by the profile first (an MVNO's own centre), then by home network.
            val known = SmsCenters.forProfile(resolved.id, identity.homeMcc, identity.homeMnc)
            if (known.isNotEmpty()) resolved = resolved.copy(smsCenter = known)
        }
        return resolved
    }

    private fun matchProfile(match: Match, identity: SimIdentity): Pair<Int, String>? {
        var score = 0
        val sources = ArrayList<String>()
        var homeMatch = false
        if (match.homePlmns.isNotEmpty()) {
            val wanted = canonicalPlmn(identity.homeMcc, identity.homeMnc)
            if (wanted.isNotEmpty() && match.homePlmns.any { canonicalPlmnValue(it) == wanted }) {
                score += 100
                sources.add("hplmn")
                homeMatch = true
            } else if (identity.homeMcc.isNotEmpty() && identity.homeMnc.isNotEmpty()) {
                return null
            }
        }
        var selectorMatch = false
        val selectors = listOf(
            Selector("imsi", 80, match.imsiPrefixes, identity.imsi, false),
            Selector("iccid", 70, match.iccidPrefixes, identity.iccid, false),
            // GID identifies an MVNO within a host network, so it outranks a broad ICCID prefix.
            Selector("gid1", 90, match.gid1Prefixes, identity.gid1, true),
            Selector("gid2", 85, match.gid2Prefixes, identity.gid2, true),
        )
        for (selector in selectors) {
            if (selector.values.isEmpty()) continue
            val actual = selector.actual.trim()
            val hit = actual.isNotEmpty() && selector.values.any { prefix ->
                val trimmed = prefix.trim()
                if (selector.foldCase) actual.lowercase().startsWith(trimmed.lowercase()) else actual.startsWith(trimmed)
            }
            if (hit) {
                score += selector.weight
                sources.add(selector.name)
                selectorMatch = true
            } else if (!homeMatch || selector.name == "gid1" || selector.name == "gid2") {
                return null
            }
        }
        if (match.spns.isNotEmpty()) {
            val spn = identity.spn.trim()
            if (spn.isNotEmpty() && match.spns.any { it.trim().equals(spn, ignoreCase = true) }) {
                score += 20
                sources.add("spn")
                selectorMatch = true
            } else {
                return null
            }
        }
        if (!homeMatch && !selectorMatch) return null
        return if (score > 0) score to sources.joinToString("+") else null
    }

    private class Selector(val name: String, val weight: Int, val values: List<String>, val actual: String, val foldCase: Boolean)

    private fun apply(rule: Rule, source: String, identity: SimIdentity): CarrierProfile {
        var routeMcc = rule.routeMcc
        var routeMnc = rule.routeMnc
        if (routeMcc.isEmpty()) {
            val current = canonicalPlmn(identity.homeMcc, identity.homeMnc)
            if (current.isNotEmpty() && rule.matches.any { match -> match.homePlmns.any { canonicalPlmnValue(it) == current } }) {
                routeMcc = identity.homeMcc.trim()
                routeMnc = identity.homeMnc.trim()
            }
            if (routeMcc.isEmpty()) {
                val first = rule.matches.flatMap { it.homePlmns }.map(::canonicalPlmnValue).firstOrNull { it.length >= 5 }
                if (first != null) {
                    routeMcc = first.substring(0, 3)
                    routeMnc = first.substring(3)
                }
            }
        }
        val ims = rule.ims
        val registerJson = ims.obj("register_options") ?: JsonObject()
        var register = RegisterOptions(
            contactFormat = registerJson.string("contact_format").lowercase(),
            expirySeconds = registerJson.get("expiry_seconds")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            contactExtraTags = registerJson.strings("contact_extra_tags"),
            supportedHeader = registerJson.nullableString("supported_header"),
            allowHeader = registerJson.nullableString("allow_header"),
            pPreferredIdentity = registerJson.get("p_preferred_identity")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            pVisitedNetworkId = registerJson.string("p_visited_network_id"),
            pAccessNetworkInfo = registerJson.nullableString("p_access_network_info"),
            cellularNetworkInfo = registerJson.string("cellular_network_info"),
            acceptContactTags = registerJson.strings("accept_contact_tags"),
        )
        val epdg = rule.epdgHostname
        var identityProfile = ims.string("identity_profile").ifEmpty { IMS_STANDARD }
        var registerProfile = ims.string("register_profile").ifEmpty { IMS_STANDARD }
        if (identityProfile == IMS_STANDARD && isAttEpdg(epdg)) identityProfile = IMS_ATT
        if (registerProfile == IMS_STANDARD && isAttEpdg(epdg)) registerProfile = IMS_ATT
        var paniNode = ims.string("pani_node")
        if (registerProfile == IMS_ATT) {
            register = register.copy(
                contactFormat = register.contactFormat.ifEmpty { CONTACT_ATT },
                expirySeconds = if (register.expirySeconds == 0) 18400 else register.expirySeconds,
                contactExtraTags = register.contactExtraTags.ifEmpty { listOf("+g.3gpp.accesstype=\"wlan1\"") },
                supportedHeader = register.supportedHeader ?: "path,sec-agree,gruu",
                pVisitedNetworkId = register.pVisitedNetworkId.ifEmpty { "one.att.net" },
                pPreferredIdentity = true,
                acceptContactTags = register.acceptContactTags.ifEmpty {
                    listOf("*;+g.3gpp.smsip", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"")
                },
            )
            if (paniNode.isEmpty()) paniNode = "000000000000"
        }
        return CarrierProfile(
            id = rule.id,
            matchSource = source,
            imsiRewriteFrom = rule.rewriteFrom,
            imsiRewriteTo = rule.rewriteTo,
            routeMcc = routeMcc,
            routeMnc = routeMnc,
            epdg = epdg,
            ikeProposal = rule.ikeProposal.ifEmpty { IKE_MODERN },
            ikeExactProposal = rule.ikeExact,
            espExactProposal = rule.espExact,
            tunnel = rule.tunnel,
            privacy = identityCertificateOverride(rule.id)?.let { rule.privacy.copy(certificateDer = it) } ?: rule.privacy,
            epdgCertificate = rule.epdgCertificate,
            advertiseEapOnly = rule.advertiseEapOnly ?: true,
            allowSmsWithoutContactConfirmation = ims.get("allow_sms_without_contact_confirmation")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            registerOptions = register,
            imsTransport = ims.string("transport").lowercase(),
            imsIdentityProfile = identityProfile,
            imsRegisterProfile = registerProfile,
            imsIpsecMode = ims.string("ipsec_mode").lowercase(),
            imsIpsecEncryption = ims.string("ipsec_encryption").lowercase().ifEmpty { "aes-cbc" },
            smsCenter = ims.string("sms_center"),
            paniEnabled = ims.get("pani_enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            paniCountry = ims.string("pani_country").uppercase(),
            paniNode = paniNode,
            imsUserAgent = ims.string("user_agent"),
            imsDialUriScheme = ims.string("dial_uri_scheme").lowercase().ifEmpty { "tel" },
            imsUserEqPhone = ims.get("user_eq_phone")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            imsVoiceCodecs = ims.strings("voice_codecs").map { it.uppercase() }.distinct(),
        )
    }

    private fun isAttEpdg(epdg: String): Boolean {
        val value = epdg.trim().lowercase()
        return value == "epdg.epc.att.net" || value.endsWith(".att.net")
    }

    /** An explicitly configured ePDG, the carrier profile's, or the 3GPP home-PLMN form. */
    fun deriveEpdg(identity: SimIdentity): String {
        val configured = identity.epdg.trim()
        if (configured.isNotEmpty()) {
            require(configured.length <= 253 && configured.none { it.isWhitespace() || it == '/' || it == ':' }) {
                "configured ePDG must be a hostname"
            }
            return configured.lowercase()
        }
        val profile = resolve(identity)
        if (profile.epdg.isNotEmpty()) return profile.epdg
        if (profile.routeMcc.isNotEmpty()) return standardEpdgHostname(profile.routeMcc, profile.routeMnc)
        identity.validate()
        return standardEpdgHostname(identity.homeMcc, identity.homeMnc)
    }

    fun standardEpdgHostname(mcc: String, mnc: String): String = "epdg.epc.mnc${mnc.trim().padStart(3, '0')}.mcc${mcc.trim()}.pub.3gppnetwork.org"

    /** Applies a carrier's assigned route PLMN, or an ICCID-derived home PLMN, to a SIM identity. */
    fun applyAssignedRoute(identity: SimIdentity): SimIdentity {
        val profile = resolve(identity)
        if (profile.id != STANDARD && profile.routeMcc.isNotEmpty()) {
            return identity.copy(
                homeMcc = profile.routeMcc,
                homeMnc = profile.routeMnc,
                epdg = profile.epdg.ifEmpty { standardEpdgHostname(profile.routeMcc, profile.routeMnc) },
            )
        }
        var result = identity
        homePlmnFromIccid(identity.iccid)?.let { (mcc, mnc) ->
            val imsiCountry = countryCodeForMcc(identity.homeMcc)
            val iccidCountry = countryCodeForMcc(mcc)
            if (identity.homeMcc.isEmpty() || (imsiCountry.isNotEmpty() && iccidCountry.isNotEmpty() && imsiCountry != iccidCountry)) {
                result = result.copy(homeMcc = mcc, homeMnc = mnc)
            }
        }
        if (result.epdg.isBlank() && result.homeMcc.isNotEmpty() && result.homeMnc.isNotEmpty()) {
            result = result.copy(epdg = standardEpdgHostname(result.homeMcc, result.homeMnc))
        }
        return result
    }

    /** The EDNS client subnet for ePDGs whose DNS only answers home-country resolvers. */
    fun epdgDnsClientSubnet(host: String): String {
        val normalized = host.trim().trimEnd('.').lowercase()
        for (rule in snapshot()) {
            if (rule.dnsHosts.any { it.trim().trimEnd('.').lowercase() == normalized }) return rule.dnsClientSubnet.trim()
        }
        val index = normalized.indexOf(".mcc")
        if (index >= 0 && normalized.length >= index + 7) {
            val mcc = normalized.substring(index + 4, index + 7)
            if (decimal(mcc)) return mccDefaultClientSubnet(mcc)
        }
        return ""
    }

    fun mccDefaultClientSubnet(mcc: String): String = when (mcc.trim()) {
        "262" -> "139.7.0.0/16"
        "204" -> "109.39.0.0/16"
        "234", "235" -> "212.183.0.0/16"
        "515" -> "112.198.0.0/16"
        "454" -> "203.0.0.0/16"
        "466", "467" -> "210.0.0.0/16"
        "525" -> "202.166.0.0/16"
        "440", "441" -> "126.0.0.0/16"
        "450" -> "211.0.0.0/16"
        "310", "311", "312", "313", "314", "315", "316" -> "198.228.0.0/16"
        "302" -> "142.0.0.0/16"
        "505" -> "1.120.0.0/16"
        "520" -> "171.96.0.0/16"
        "510" -> "182.0.0.0/16"
        "502" -> "115.132.0.0/16"
        "208" -> "194.51.0.0/16"
        "214" -> "212.166.0.0/16"
        "222" -> "83.224.0.0/16"
        "228" -> "178.192.0.0/16"
        "232" -> "194.138.0.0/16"
        "206" -> "193.190.0.0/16"
        "260" -> "83.0.0.0/16"
        "268" -> "194.65.0.0/16"
        "272" -> "193.1.0.0/16"
        "238" -> "193.162.0.0/16"
        "240" -> "194.236.0.0/16"
        "242" -> "193.69.0.0/16"
        "244" -> "193.64.0.0/16"
        "202" -> "194.219.0.0/16"
        "216" -> "195.199.0.0/16"
        "230" -> "195.113.0.0/16"
        "286" -> "195.175.0.0/16"
        "425" -> "192.114.0.0/16"
        "404", "405" -> "103.0.0.0/16"
        "655" -> "196.0.0.0/16"
        "724" -> "177.0.0.0/16"
        "334" -> "187.188.0.0/16"
        "452" -> "118.69.0.0/16"
        "455" -> "202.175.0.0/16"
        "530" -> "202.27.0.0/16"
        "460" -> "223.5.5.0/24"
        else -> ""
    }

    fun countryCodeForMcc(mcc: String): String = when (mcc.trim()) {
        "515" -> "PH"
        "262" -> "DE"
        "204" -> "NL"
        "234", "235" -> "GB"
        "460" -> "CN"
        "454" -> "HK"
        "466", "467" -> "TW"
        "525" -> "SG"
        "440", "441" -> "JP"
        "450" -> "KR"
        "310", "311", "312", "313", "314", "315", "316" -> "US"
        "302" -> "CA"
        "505" -> "AU"
        "208" -> "FR"
        "214" -> "ES"
        "222" -> "IT"
        "228" -> "CH"
        "232" -> "AT"
        "206" -> "BE"
        "260" -> "PL"
        "520" -> "TH"
        "510" -> "ID"
        "502" -> "MY"
        else -> ""
    }

    private val ICCID_PREFIXES = listOf(
        Triple("896366", "515", "66"), Triple("896302", "515", "02"), Triple("896303", "515", "03"),
        Triple("894920", "262", "02"), Triple("894901", "262", "01"), Triple("894902", "262", "03"),
        Triple("894903", "262", "03"), Triple("894907", "262", "07"),
        Triple("894410", "234", "15"), Triple("894415", "234", "15"), Triple("894411", "234", "30"),
        Triple("894430", "234", "30"), Triple("894420", "234", "20"), Triple("894421", "234", "10"),
        Triple("8937204", "204", "04"), Triple("893104", "204", "04"), Triple("893108", "204", "08"), Triple("893116", "204", "16"),
        Triple("8985201", "454", "00"), Triple("8985203", "454", "03"), Triple("898523", "454", "03"),
        Triple("8985204", "454", "12"), Triple("8985206", "454", "06"),
        Triple("898600", "460", "00"), Triple("898602", "460", "00"), Triple("898604", "460", "00"), Triple("898607", "460", "00"),
        Triple("898601", "460", "01"), Triple("898606", "460", "01"), Triple("898609", "460", "01"),
        Triple("898603", "460", "03"), Triple("898605", "460", "03"), Triple("898611", "460", "03"),
        Triple("8988601", "466", "92"), Triple("8988602", "466", "97"), Triple("8988603", "466", "01"),
        Triple("896501", "525", "01"), Triple("896502", "525", "05"), Triple("896503", "525", "03"), Triple("896504", "525", "10"),
    )

    fun homePlmnFromIccid(iccid: String): Pair<String, String>? {
        val value = iccid.trim()
        if (value.length < 6 || !value.startsWith("89")) return null
        return ICCID_PREFIXES.firstOrNull { value.startsWith(it.first) }?.let { it.second to it.third }
    }

    fun canonicalPlmn(mcc: String, mnc: String): String {
        val trimmedMcc = mcc.trim()
        val trimmedMnc = mnc.trim()
        if (!SimIdentity.isDigits(trimmedMcc, 3, 3) || !SimIdentity.isDigits(trimmedMnc, 2, 3)) return ""
        return trimmedMcc + trimmedMnc.padStart(3, '0')
    }

    fun canonicalPlmnValue(value: String): String {
        val clean = value.trim().replace("/", "")
        if (clean.length != 5 && clean.length != 6) return ""
        return canonicalPlmn(clean.substring(0, 3), clean.substring(3))
    }

    private fun decimal(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
    private fun hex(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    private fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
    private fun JsonObject.strings(name: String): List<String> =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
}
