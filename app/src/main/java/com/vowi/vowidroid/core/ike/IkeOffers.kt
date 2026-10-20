package com.vowi.vowidroid.core.ike

/**
 * One proposal with exactly one transform per type, as the iPhone carrier
 * bundles list them. `prfId` is 0 for ESP, which has no PRF; `dhId` is 0 when
 * an ESP proposal names no PFS group. The lifetime is the bundle's figure,
 * kept for reference: the ePDG drives rekeys, so this stack never enforces it.
 */
data class ProposalSpec(
    val encryptionBits: Int,
    val integrityId: Int,
    val prfId: Int = 0,
    val dhId: Int = 0,
    val lifetimeSeconds: Int = 0,
) {
    val encryptionName: String get() = IkeAlgorithms.encryptionName(encryptionBits)
    val prfName: String get() = IkeAlgorithms.prfName(prfId)
    val integrityName: String get() = IkeAlgorithms.integrityName(integrityId)
    val dhName: String get() = IkeAlgorithms.dhName(dhId)

    /** True when every algorithm named is one this stack implements. */
    val supported: Boolean
        get() = encryptionBits in IkeAlgorithms.encryptionBits && IkeAlgorithms.supportsIntegrity(integrityId) &&
            (prfId == 0 || IkeAlgorithms.supportsPrf(prfId)) && (dhId == 0 || IkeAlgorithms.supportsDh(dhId))

    override fun toString(): String = listOf(encryptionName, prfName, integrityName, dhName).filter { it.isNotEmpty() }.joinToString("/")
}

/** The proposals and fixed payloads VowiDroid offers an ePDG, matching Android's IKE library ordering. */
object IkeOffers {
    /** The carrier bundle's own IKE proposal, one transform per type, so the ePDG has to match it exactly. */
    fun ikeOffer(spec: ProposalSpec): Proposal {
        require(spec.prfId != 0 && spec.dhId != 0) { "an IKE proposal needs a PRF and a DH group" }
        val transforms = listOf(
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, spec.encryptionBits),
            Transform(Ike.TRANSFORM_PRF, spec.prfId),
            Transform(Ike.TRANSFORM_INTEGRITY, spec.integrityId),
            Transform(Ike.TRANSFORM_DH, spec.dhId),
        )
        return Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), transforms)
    }

    /**
     * The bundle's ESP proposal. The first CHILD_SA is made inside IKE_AUTH,
     * which carries no KE payload (RFC 7296 1.2), so the PFS group stays out
     * of this proposal; it only matters when the ePDG rekeys the CHILD_SA.
     */
    fun espOffer(spi: ByteArray, spec: ProposalSpec): Proposal {
        val transforms = listOf(
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, spec.encryptionBits),
            Transform(Ike.TRANSFORM_INTEGRITY, spec.integrityId),
            Transform(Ike.TRANSFORM_ESN, 0),
        )
        return Proposal(1, Ike.PROTOCOL_ESP, spi.copyOf(), transforms)
    }

    fun ikeOffer(group: Int, legacyFirst: Boolean): Proposal {
        val transforms = mutableListOf(
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 128),
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256),
            Transform(Ike.TRANSFORM_PRF, Ike.PRF_HMAC_SHA1),
            Transform(Ike.TRANSFORM_PRF, Ike.PRF_HMAC_SHA256),
            Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA1_96),
            Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA256_128),
            Transform(Ike.TRANSFORM_DH, group),
        )
        if (!legacyFirst) {
            transforms.swap(2, 3)
            transforms.swap(4, 5)
        }
        return Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), transforms)
    }

    fun espOffer(spi: ByteArray, legacyFirst: Boolean): Proposal {
        val transforms = mutableListOf(
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 128),
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256),
            Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA1_96),
            Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA256_128),
            Transform(Ike.TRANSFORM_ESN, 0),
        )
        if (!legacyFirst) transforms.swap(2, 3)
        return Proposal(1, Ike.PROTOCOL_ESP, spi.copyOf(), transforms)
    }

    private fun MutableList<Transform>.swap(first: Int, second: Int) {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }

    /** The first IKE_AUTH request's payloads, in the order Android's IKE library sends them. */
    fun initialEapAuthPayloads(idi: Payload, requestedIdr: Payload, childOffer: ByteArray, tsi: Payload, tsr: Payload, eapOnly: Boolean): List<Payload> {
        val payloads = mutableListOf(idi, requestedIdr)
        if (eapOnly) payloads.add(IkeWire.makeNotify(Ike.NOTIFY_EAP_ONLY_AUTHENTICATION))
        payloads.add(IkeWire.makeNotify(Ike.NOTIFY_MOBIKE_SUPPORTED))
        payloads.add(IkeWire.makeNotify(Ike.NOTIFY_INITIAL_CONTACT))
        payloads.add(Payload(Ike.PAYLOAD_SA, childOffer.copyOf()))
        payloads.add(tsi)
        payloads.add(tsr)
        payloads.add(IkeChild.configurationRequest())
        return payloads
    }

    /** The 3GPP TS 24.302 DEVICE_IDENTITY notify carrying an IMEI or IMEISV. */
    fun deviceIdentityNotify(identity: String): Payload {
        val digits = identity.trim()
        require((digits.length == 15 || digits.length == 16) && digits.all { it in '0'..'9' }) {
            "device identity must contain 15 or 16 digits"
        }
        val data = ByteArray(11)
        // The inner length excludes its own two octets: 9 for an IMEI/IMEISV value.
        data[0] = 0
        data[1] = 9
        data[2] = if (digits.length == 16) 2 else 1
        for (index in 0 until 8) {
            val low = digits[index * 2] - '0'
            val high = if (index * 2 + 1 < digits.length) digits[index * 2 + 1] - '0' else 0x0f
            data[index + 3] = ((high shl 4) or low).toByte()
        }
        return IkeWire.makeNotify(Ike.NOTIFY_DEVICE_IDENTITY, data)
    }

    /** The ePDG asked for DEVICE_IDENTITY; Android answers only after authenticating the ePDG. */
    fun deviceIdentityRequested(payloads: List<Payload>): Boolean = IkeWire.hasNotify(payloads, Ike.NOTIFY_DEVICE_IDENTITY)
}
